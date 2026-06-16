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
 * Single source of truth for time, status, seek requests, and inter-thread coordination.
 * Uses a reentrant lock plus condition for thread synchronization.
 * <p>
 * The clock is PTS-drift based (like ffplay):
 * <ul>
 *   <li>{@code time = ptsDrift + now} when playing</li>
 *   <li>{@code time = pts} when not playing (frozen)</li>
 * </ul>
 * Status is volatile for lock-free reads; all mutations happen under the lock.
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
    /** Immutable seek request — stored atomically under the lock. */
    public record SeekRequest(long targetMs, boolean precise) {}

    // WALLCLOCK
    // WALLCLOCK IN SECONDS WITH NANOSECOND PRECISION
    private static double wallclock() {
        return System.nanoTime() / 1_000_000_000.0;
    }

    // CLOCK READ (LOCK-FREE VIA VOLATILE)
    /**
     * Returns the current clock time in seconds.
     * When playing: {@code ptsDrift + now}, adjusted for speed.
     * When not playing: frozen at the last PTS value.
     */
    public double time() {
        final Status s = this.status;
        if (s != Status.PLAYING) return this.pts; // FROZEN IN ALL NON-PLAYING STATES
        final double now = wallclock();
        final double elapsed = now - this.lastUpdated;
        return this.ptsDrift + now - elapsed * (1.0 - this.speed);
    }

    /** Convenience: current clock time in milliseconds. */
    public long timeMs() {
        return (long) (this.time() * 1000.0);
    }

    // CLOCK WRITE (UNDER LOCK)
    /**
     * Audio-driven clock update. Called each time the audio decoder processes a frame.
     * If buffering and the serial matches, transitions to playing or paused based on the
     * pause intent.
     * <p>
     * No backward guard — the serial system handles stale frames.
     *
     * @param ptsSec PTS of the frame in seconds
     * @param serial serial of the packet queue that originated this frame
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

    /** Convenience: update with milliseconds. */
    public void updateMs(final long ptsMs, final int serial) {
        this.update(ptsMs / 1000.0, serial);
    }

    // STATE (STATUS IS VOLATILE FOR LOCK-FREE READS)
    /** Current status — lock-free read via volatile. */
    public Status status() {
        return this.status;
    }

    /**
     * Validated state transition. Checks the state machine for legality, updates the status,
     * adjusts the clock when entering or leaving the playing state, and signals all waiting
     * threads.
     *
     * @param newStatus the desired status to transition to
     * @return true if the transition was valid and applied
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
     * Starts the pipeline — transition from loading to playing or paused.
     * Calibrates the clock from time 0.
     *
     * @param paused if true, start in the paused state instead of playing
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
     * Requests a seek. Stores the request atomically, freezes the clock at the target
     * position, transitions to buffering, and wakes all waiting threads. Rejected if the
     * status is terminal.
     *
     * @param targetMs target position in milliseconds
     * @param precise  if true, request a precise (frame-exact) seek
     * @return true if the seek was accepted
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
     * Consumes the pending seek request, returning and clearing it.
     *
     * @return the pending seek request, or null if none
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

    /** Checks if a seek request is pending — lock-free via volatile. */
    public boolean hasSeekPending() {
        return this.pendingSeek != null;
    }

    // PAUSE (CONVENIENCE — DELEGATES TO transition())

    /**
     * Sets the paused state. Convenience method that delegates to {@link #transition(Status)}.
     * Also stores the pause intent for buffering resolution.
     *
     * @param paused true to pause, false to resume
     * @return true if the state changed
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

    /** Stored pause intent for buffering to playing/paused resolution. */
    public boolean pauseRequested() {
        return this.pauseIntent;
    }

    // THREAD COORDINATION (UNDER LOCK)
    /**
     * Blocks until the status changes or a new seek request arrives.
     * Polls every 50ms to bound latency.
     *
     * @param timeoutMs maximum time to wait (0 = indefinite)
     * @return the status at the time of return
     * @throws InterruptedException if the thread is interrupted
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
     * Blocks until the status is no longer the given value.
     *
     * @param current   the status to wait to leave
     * @param timeoutMs maximum time to wait (0 = indefinite)
     * @return the status at the time of return
     * @throws InterruptedException if the thread is interrupted
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
     * Pacing for video-only streams (no audio clock driver).
     * Blocks until the clock reaches the given frame PTS. Uses the lock condition instead of
     * {@link Thread#sleep(long)} to allow early wake-up on status changes.
     *
     * @param framePtsSec PTS of the frame in seconds
     * @return true if the frame should be displayed, false if interrupted or the status changed
     * @throws InterruptedException if the thread is interrupted
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
    /** Resets all state to its initial values. */
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

    /** Signals that the demuxer has reached end-of-stream. */
    public void signalDemuxFinished() {
        this.lock.lock();
        try {
            this.demuxFinished = true;
            this.condition.signalAll();
        } finally {
            this.lock.unlock();
        }
    }

    /** Checks if the demuxer has finished — lock-free via volatile. */
    public boolean isDemuxFinished() {
        return this.demuxFinished;
    }

    /**
     * Increments and returns the serial number.
     * Used to invalidate stale packets and frames after a seek or restart.
     *
     * @return the new serial value
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
    /** Current serial number — lock-free via volatile. */
    public int serial() {
        return this.serial;
    }

    /**
     * Sets the serial to match an external source (e.g. the {@link PacketQueue} serial after a
     * flush). Called by the seek handler after flushing queues so that {@link #update(double, int)}
     * accepts post-seek frames (a serial match is required for the buffering to playing transition).
     *
     * @param serial the serial value to set
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
     * Signals that the demux has resumed after EOF (e.g. after a seek).
     * Clears the {@code demuxFinished} flag and signals all waiting threads.
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
     * Sets the FPS of the stream. Called when opening the video stream.
     * Also updates the frame duration and skip threshold.
     *
     * @param fps frames per second (clamped to a minimum of 1.0)
     */
    public void fps(final float fps) {
        this.fpsValue = Math.max(fps, 1.0f);
        this.frameDurationSec = 1.0 / this.fpsValue;
        // SKIP THRESHOLD: ~5 FRAMES WORTH
        this.skipThresholdMs = (long) (this.frameDurationSec * 5.0 * 1000.0);
    }

    /** Current FPS value. */
    public float fps() {
        return this.fpsValue;
    }

    /** Frame duration in milliseconds. */
    public long frameDurationMs() {
        return (long) (this.frameDurationSec * 1000.0);
    }

    /** Frame duration in seconds. */
    public double frameDurationSec() {
        return this.frameDurationSec;
    }

    /** Skip threshold in milliseconds (~5 frames). */
    public long skipThresholdMs() {
        return this.skipThresholdMs;
    }

    /** Current playback speed multiplier. */
    public double speed() {
        return this.speed;
    }

    /**
     * Sets the playback speed. Recalibrates the clock to maintain continuity at the current
     * time position.
     *
     * @param speed playback speed multiplier (1.0 = normal)
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
