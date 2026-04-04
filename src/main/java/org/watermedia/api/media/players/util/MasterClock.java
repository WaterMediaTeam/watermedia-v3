package org.watermedia.api.media.players.util;

import org.watermedia.api.media.players.MediaPlayer.Status;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SINGLE SOURCE OF TRUTH FOR TIME, STATUS, SEEK REQUESTS, AND INTER-THREAD
 * COORDINATION. USES A REENTRANT LOCK + CONDITION FOR THREAD SYNCHRONIZATION.
 *
 * CLOCK IS PTS-DRIFT BASED (LIKE FFPLAY):
 *   TIME = PTS_DRIFT + NOW    (WHEN PLAYING)
 *   TIME = PTS                (WHEN NOT PLAYING — FROZEN)
 *
 * STATUS IS VOLATILE FOR LOCK-FREE READS; ALL MUTATIONS HAPPEN UNDER LOCK.
 */
public final class MasterClock {

    // STATE MACHINE — VALID TRANSITIONS
    private static final Map<Status, Set<Status>> TRANSITIONS = new EnumMap<>(Status.class);
    static {
        TRANSITIONS.put(Status.WAITING,   EnumSet.of(Status.LOADING));
        TRANSITIONS.put(Status.LOADING,   EnumSet.of(Status.PLAYING, Status.PAUSED, Status.ERROR));
        TRANSITIONS.put(Status.PLAYING,   EnumSet.of(Status.PAUSED, Status.BUFFERING, Status.ENDED, Status.STOPPED, Status.ERROR));
        TRANSITIONS.put(Status.PAUSED,    EnumSet.of(Status.PLAYING, Status.BUFFERING, Status.STOPPED, Status.ERROR));
        TRANSITIONS.put(Status.BUFFERING, EnumSet.of(Status.PLAYING, Status.PAUSED, Status.LOADING, Status.ENDED, Status.STOPPED, Status.ERROR));
        TRANSITIONS.put(Status.ENDED,     EnumSet.of(Status.BUFFERING, Status.STOPPED));
        TRANSITIONS.put(Status.STOPPED,   EnumSet.noneOf(Status.class)); // TERMINAL
        TRANSITIONS.put(Status.ERROR,     EnumSet.noneOf(Status.class)); // TERMINAL
    }

    // LOCK + CONDITION
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition condition = this.lock.newCondition();

    // CLOCK STATE — VOLATILE FOR LOCK-FREE READS
    private volatile double pts;
    private volatile double ptsDrift;
    private volatile double lastUpdated;
    private volatile double speed = 1.0;
    private volatile Status status = Status.WAITING;
    private volatile int serial;

    // SEEK STATE — ACCESSED UNDER LOCK
    private volatile SeekRequest pendingSeek;
    private volatile boolean pauseIntent;

    // PIPELINE STATE
    private volatile boolean demuxFinished;

    // CLOCK RECALIBRATION THRESHOLD (SECONDS) — ONLY RECALIBRATE ptsDrift
    // WHEN AUDIO PTS DRIFTS MORE THAN THIS FROM WALLCLOCK. PREVENTS VIDEO
    // PROCESSING DELAY FROM PULLING THE CLOCK BACKWARD EVERY AUDIO UPDATE.
    private static final double DRIFT_RECALIBRATION_THRESHOLD = 0.050;

    // FRAME TIMING
    private volatile double frameDurationSec = 1.0 / 30.0;
    private volatile float fpsValue = 30.0f;
    private volatile long skipThresholdMs = 165;

    // INNER TYPES
    /** IMMUTABLE SEEK REQUEST — STORED ATOMICALLY UNDER LOCK */
    public record SeekRequest(long targetMs, boolean precise) {}

    // WALLCLOCK
    // WALLCLOCK IN SECONDS WITH NANOSECOND PRECISION
    private static double wallclock() {
        return System.nanoTime() / 1_000_000_000.0;
    }

    // CLOCK READ (LOCK-FREE VIA VOLATILE)
    /**
     * CURRENT CLOCK TIME IN SECONDS.
     * WHEN PLAYING: PTS_DRIFT + NOW, ADJUSTED FOR SPEED.
     * WHEN NOT PLAYING: FROZEN AT LAST PTS VALUE.
     */
    public double time() {
        final Status s = this.status;
        if (s != Status.PLAYING) return this.pts; // FROZEN IN ALL NON-PLAYING STATES
        final double now = wallclock();
        final double elapsed = now - this.lastUpdated;
        return this.ptsDrift + now - elapsed * (1.0 - this.speed);
    }

    /** CONVENIENCE: CURRENT CLOCK TIME IN MILLISECONDS */
    public long timeMs() {
        return (long) (this.time() * 1000.0);
    }

    // CLOCK WRITE (UNDER LOCK)
    /**
     * AUDIO-DRIVEN CLOCK UPDATE. CALLED EACH TIME THE AUDIO DECODER
     * PROCESSES A FRAME. IF BUFFERING AND SERIAL MATCHES, TRANSITIONS
     * TO PLAYING OR PAUSED BASED ON PAUSE INTENT.
     *
     * NO BACKWARD GUARD — THE SERIAL SYSTEM HANDLES STALE FRAMES.
     *
     * @param ptsSec PTS OF THE FRAME IN SECONDS
     * @param serial SERIAL OF THE PACKET QUEUE THAT ORIGINATED THIS FRAME
     */
    public void update(final double ptsSec, final int serial) {
        this.lock.lock();
        try {
            if (serial != this.serial) return; // STALE FRAME — REJECT
            final double now = wallclock();

            // ALWAYS UPDATE PTS (FOR DISPLAY AND BUFFERING TRANSITION)
            this.pts = ptsSec;

            // ONLY RECALIBRATE DRIFT IF AUDIO IS SIGNIFICANTLY OUT OF SYNC (>50MS).
            // WITHOUT THIS, EVERY update() PULLS THE CLOCK BACKWARD WHEN VIDEO
            // PROCESSING (sws_scale ~30MS) DELAYS AUDIO CONSUMPTION BEYOND THE
            // AUDIO FRAME PERIOD (~20MS). THE CLOCK EFFECTIVELY RUNS AT
            // audio_duration/video_duration SPEED (e.g., 20/30 = 0.67x).
            // WITH THE THRESHOLD, THE CLOCK RUNS AT WALLCLOCK RATE AND ONLY
            // CORRECTS FOR REAL DESYNC (SEEK, AUDIO DISCONTINUITY).
            final double currentTime = this.ptsDrift + now;
            final double drift = ptsSec - currentTime;
            if (this.status == Status.BUFFERING || Math.abs(drift) > DRIFT_RECALIBRATION_THRESHOLD) {
                this.ptsDrift = ptsSec - now;
                this.lastUpdated = now;
            }

            // IF BUFFERING → FIRST VALID UPDATE → TRANSITION TO PLAYING/PAUSED
            if (this.status == Status.BUFFERING) {
                this.status = this.pauseIntent ? Status.PAUSED : Status.PLAYING;
                this.condition.signalAll();
            }
        } finally {
            this.lock.unlock();
        }
    }

    /** CONVENIENCE: UPDATE WITH MILLISECONDS */
    public void updateMs(final long ptsMs, final int serial) {
        this.update(ptsMs / 1000.0, serial);
    }

    // STATE (STATUS IS VOLATILE FOR LOCK-FREE READS)
    /** CURRENT STATUS — LOCK-FREE READ VIA VOLATILE */
    public Status status() {
        return this.status;
    }

    /**
     * VALIDATED STATE TRANSITION. CHECKS THE STATE MACHINE FOR LEGALITY,
     * UPDATES STATUS, ADJUSTS CLOCK WHEN ENTERING/LEAVING PLAYING STATE,
     * AND SIGNALS ALL WAITING THREADS.
     *
     * @param newStatus THE DESIRED STATUS TO TRANSITION TO
     * @return TRUE IF THE TRANSITION WAS VALID AND APPLIED
     */
    public boolean transition(final Status newStatus) {
        this.lock.lock();
        try {
            final Status current = this.status;
            if (current == newStatus) return false; // NO-OP — ALREADY IN THAT STATE

            // VALIDATE AGAINST STATE MACHINE
            final Set<Status> allowed = TRANSITIONS.get(current);
            if (allowed == null || !allowed.contains(newStatus)) return false;

            // ADJUST CLOCK WHEN LEAVING PLAYING — FREEZE PTS
            if (current == Status.PLAYING) {
                this.pts = this.time();
            }

            // ADJUST CLOCK WHEN ENTERING PLAYING — RECALIBRATE DRIFT
            if (newStatus == Status.PLAYING) {
                final double now = wallclock();
                this.ptsDrift = this.pts - now;
                this.lastUpdated = now;
            }

            this.status = newStatus;
            this.condition.signalAll();
            return true;
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * START THE PIPELINE — TRANSITION FROM LOADING TO PLAYING OR PAUSED.
     * CALIBRATES THE CLOCK FROM TIME 0.
     *
     * @param paused IF TRUE, START IN PAUSED STATE INSTEAD OF PLAYING
     */
    public void start(final boolean paused) {
        this.lock.lock();
        try {
            final double now = wallclock();
            this.pts = 0;
            this.ptsDrift = -now;
            this.lastUpdated = now;
            this.status = paused ? Status.PAUSED : Status.PLAYING;
            this.pauseIntent = paused;
            this.condition.signalAll();
        } finally {
            this.lock.unlock();
        }
    }

    // SEEK (UNDER LOCK, SIGNALS CONDITION)
    /**
     * REQUEST A SEEK. STORES THE REQUEST ATOMICALLY, FREEZES THE CLOCK
     * AT THE TARGET POSITION, TRANSITIONS TO BUFFERING, AND WAKES ALL
     * WAITING THREADS. REJECTED IF STATUS IS TERMINAL.
     *
     * @param targetMs TARGET POSITION IN MILLISECONDS
     * @param precise  IF TRUE, REQUEST PRECISE (FRAME-EXACT) SEEK
     * @return TRUE IF THE SEEK WAS ACCEPTED
     */
    public boolean requestSeek(final long targetMs, final boolean precise) {
        this.lock.lock();
        try {
            if (this.status == Status.STOPPED || this.status == Status.ERROR) return false;
            this.pendingSeek = new SeekRequest(targetMs, precise);
            this.pts = targetMs / 1000.0; // FREEZE CLOCK AT TARGET
            this.serial++; // INVALIDATE ALL CURRENT FRAMES — PREVENTS update() FROM
                           // AUTO-TRANSITIONING BUFFERING→PLAYING WITH STALE AUDIO
            this.status = Status.BUFFERING;
            this.demuxFinished = false; // CLEAR EOF FLAG FOR SEEK AFTER EOF
            this.condition.signalAll(); // WAKE ALL THREADS
            return true;
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * CONSUME THE PENDING SEEK REQUEST. RETURNS AND CLEARS IT.
     *
     * @return THE PENDING SEEK REQUEST, OR NULL IF NONE
     */
    public SeekRequest consumeSeek() {
        this.lock.lock();
        try {
            final SeekRequest req = this.pendingSeek;
            this.pendingSeek = null;
            return req;
        } finally {
            this.lock.unlock();
        }
    }

    /** CHECK IF A SEEK REQUEST IS PENDING — LOCK-FREE VIA VOLATILE */
    public boolean hasSeekPending() {
        return this.pendingSeek != null;
    }

    // PAUSE (CONVENIENCE — DELEGATES TO transition())

    /**
     * SET PAUSED STATE. CONVENIENCE METHOD THAT DELEGATES TO transition().
     * ALSO STORES THE PAUSE INTENT FOR BUFFERING RESOLUTION.
     *
     * @param paused TRUE TO PAUSE, FALSE TO RESUME
     * @return TRUE IF THE STATE CHANGED
     */
    public boolean setPaused(final boolean paused) {
        this.lock.lock();
        try {
            this.pauseIntent = paused;
            if (paused) {
                // PAUSE IS ALWAYS ALLOWED (BUFFERING→PAUSED, PLAYING→PAUSED)
                return this.transition(Status.PAUSED);
            } else {
                // RESUME: IF WE WERE PAUSED DURING BUFFERING, GO BACK TO BUFFERING
                // (NOT PLAYING) — THE SEEK HASN'T RESOLVED YET.
                // update() WILL TRANSITION BUFFERING→PLAYING WHEN DATA ARRIVES.
                if (this.status == Status.PAUSED && this.pendingSeek != null) {
                    return this.transition(Status.BUFFERING);
                }
                return this.transition(Status.PLAYING);
            }
        } finally {
            this.lock.unlock();
        }
    }

    /** STORED PAUSE INTENT FOR BUFFERING → PLAYING/PAUSED RESOLUTION */
    public boolean pauseRequested() {
        return this.pauseIntent;
    }

    // THREAD COORDINATION (UNDER LOCK)
    /**
     * BLOCK UNTIL THE STATUS CHANGES OR A NEW SEEK REQUEST ARRIVES.
     * POLLS EVERY 50MS TO BOUND LATENCY.
     *
     * @param timeoutMs MAXIMUM TIME TO WAIT (0 = INDEFINITE)
     * @return THE STATUS AT THE TIME OF RETURN
     * @throws InterruptedException IF THE THREAD IS INTERRUPTED
     */
    public Status awaitChange(final long timeoutMs) throws InterruptedException {
        this.lock.lock();
        try {
            final Status initial = this.status;
            final SeekRequest initialSeek = this.pendingSeek;
            final long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;
            while (this.status == initial && this.pendingSeek == initialSeek) {
                final long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                this.condition.await(Math.min(remaining, 50), TimeUnit.MILLISECONDS);
            }
            return this.status;
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * BLOCK UNTIL THE STATUS IS NO LONGER THE GIVEN VALUE.
     *
     * @param current   THE STATUS TO WAIT TO LEAVE
     * @param timeoutMs MAXIMUM TIME TO WAIT (0 = INDEFINITE)
     * @return THE STATUS AT THE TIME OF RETURN
     * @throws InterruptedException IF THE THREAD IS INTERRUPTED
     */
    public Status awaitNotStatus(final Status current, final long timeoutMs) throws InterruptedException {
        this.lock.lock();
        try {
            final long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;
            while (this.status == current) {
                final long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                this.condition.await(Math.min(remaining, 50), TimeUnit.MILLISECONDS);
            }
            return this.status;
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * PACING FOR VIDEO-ONLY STREAMS (NO AUDIO CLOCK DRIVER).
     * BLOCKS UNTIL THE CLOCK REACHES THE GIVEN FRAME PTS.
     * USES THE LOCK CONDITION INSTEAD OF Thread.sleep TO ALLOW
     * EARLY WAKE-UP ON STATUS CHANGES.
     *
     * @param framePtsSec PTS OF THE FRAME IN SECONDS
     * @return TRUE IF THE FRAME SHOULD BE DISPLAYED, FALSE IF INTERRUPTED OR STATUS CHANGED
     * @throws InterruptedException IF THE THREAD IS INTERRUPTED
     */
    public boolean waitUntil(final double framePtsSec) throws InterruptedException {
        this.lock.lock();
        try {
            while (this.status == Status.PLAYING) {
                final double now = this.time();
                final double diff = framePtsSec - now;

                if (diff <= 0.002) return true; // 2MS TOLERANCE — TIME TO SHOW

                final long sleepMs = Math.min((long) (diff * 1000.0), 10);
                this.condition.await(sleepMs, TimeUnit.MILLISECONDS);
            }
            // STATUS CHANGED WHILE WAITING — CALLER SHOULD RE-EVALUATE
            return false;
        } finally {
            this.lock.unlock();
        }
    }

    // PIPELINE LIFECYCLE
    /** RESET ALL STATE TO INITIAL VALUES */
    public void reset() {
        this.lock.lock();
        try {
            final double now = wallclock();
            this.pts = 0;
            this.ptsDrift = -now;
            this.lastUpdated = now;
            this.status = Status.WAITING;
            this.serial = 0;
            this.speed = 1.0;
            this.pendingSeek = null;
            this.pauseIntent = false;
            this.demuxFinished = false;
            this.condition.signalAll();
        } finally {
            this.lock.unlock();
        }
    }

    /** SIGNAL THAT THE DEMUXER HAS REACHED END-OF-STREAM */
    public void signalDemuxFinished() {
        this.lock.lock();
        try {
            this.demuxFinished = true;
            this.condition.signalAll();
        } finally {
            this.lock.unlock();
        }
    }

    /** CHECK IF THE DEMUXER HAS FINISHED — LOCK-FREE VIA VOLATILE */
    public boolean isDemuxFinished() {
        return this.demuxFinished;
    }

    /**
     * INCREMENT AND RETURN THE SERIAL NUMBER.
     * USED TO INVALIDATE STALE PACKETS/FRAMES AFTER A SEEK OR RESTART.
     *
     * @return THE NEW SERIAL VALUE
     */
    public int nextSerial() {
        this.lock.lock();
        try {
            return ++this.serial;
        } finally {
            this.lock.unlock();
        }
    }

    // FRAME TIMING
    /** CURRENT SERIAL NUMBER — LOCK-FREE VIA VOLATILE */
    public int serial() {
        return this.serial;
    }

    /**
     * SET SERIAL TO MATCH AN EXTERNAL SOURCE (E.G., PacketQueue SERIAL AFTER FLUSH).
     * CALLED BY THE SEEK HANDLER AFTER FLUSHING QUEUES SO THAT clock.update()
     * ACCEPTS POST-SEEK FRAMES (SERIAL MATCH REQUIRED FOR BUFFERING → PLAYING).
     *
     * @param serial THE SERIAL VALUE TO SET
     */
    public void setSerial(final int serial) {
        this.lock.lock();
        try {
            this.serial = serial;
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * SIGNAL THAT THE DEMUX HAS RESUMED AFTER EOF (E.G., AFTER A SEEK).
     * CLEARS THE demuxFinished FLAG AND SIGNALS ALL WAITING THREADS.
     */
    public void signalDemuxResumed() {
        this.lock.lock();
        try {
            this.demuxFinished = false;
            this.condition.signalAll();
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * SET FPS OF THE STREAM. CALLED WHEN OPENING THE VIDEO STREAM.
     * ALSO UPDATES FRAME DURATION AND SKIP THRESHOLD.
     *
     * @param fps FRAMES PER SECOND (CLAMPED TO MINIMUM 1.0)
     */
    public void fps(final float fps) {
        this.fpsValue = Math.max(fps, 1.0f);
        this.frameDurationSec = 1.0 / this.fpsValue;
        // SKIP THRESHOLD: ~5 FRAMES WORTH
        this.skipThresholdMs = (long) (this.frameDurationSec * 5.0 * 1000.0);
    }

    /** CURRENT FPS VALUE */
    public float fps() {
        return this.fpsValue;
    }

    /** FRAME DURATION IN MILLISECONDS */
    public long frameDurationMs() {
        return (long) (this.frameDurationSec * 1000.0);
    }

    /** FRAME DURATION IN SECONDS */
    public double frameDurationSec() {
        return this.frameDurationSec;
    }

    /** SKIP THRESHOLD IN MILLISECONDS (~5 FRAMES) */
    public long skipThresholdMs() {
        return this.skipThresholdMs;
    }

    /** CURRENT PLAYBACK SPEED MULTIPLIER */
    public double speed() {
        return this.speed;
    }

    /**
     * SET PLAYBACK SPEED. RECALIBRATES THE CLOCK TO MAINTAIN
     * CONTINUITY AT THE CURRENT TIME POSITION.
     *
     * @param speed PLAYBACK SPEED MULTIPLIER (1.0 = NORMAL)
     */
    public void speed(final double speed) {
        this.lock.lock();
        try {
            final double current = this.time();
            this.speed = speed;
            final double now = wallclock();
            this.pts = current;
            this.ptsDrift = current - now;
            this.lastUpdated = now;
        } finally {
            this.lock.unlock();
        }
    }
}
