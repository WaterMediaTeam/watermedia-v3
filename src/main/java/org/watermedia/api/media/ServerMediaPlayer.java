package org.watermedia.api.media;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.util.MathUtil;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * A headless media player that acts as a wall clock for time synchronization.
 * <p>
 * This player does not produce any audio or video output. It calculates time progression
 * purely from the system clock, allowing multiple clients to synchronize their playback
 * position against a single authoritative instance. Every successful mutation bumps
 * {@link #revision()}.
 * <p>
 * Built with a bridge through {@link MediaAPI#serverPlayer(Bridge, Config.Capability...)}, it runs
 * the whole server side of the sync protocol by itself: it answers {@link Watch} hellos
 * with its {@link Config}, tracks the follower pool from {@link Report}s (with a TTL for
 * vanished clients), applies {@link Control} requests under the
 * {@link Config.Capability#CONTROLS} capability, re-broadcasts {@link #snapshot()} when
 * the revision moves plus a periodic heartbeat, and under
 * {@link Config.Capability#LOCKSTEP} gates the whole pool while a pooled follower is
 * still loading or buffering — presenting {@code BUFFERING} with a frozen clock. Feed
 * received upstream payloads through {@link #sync(ByteBuffer)}; everything else is
 * automatic.
 * <p>
 * One instance represents one media session: {@link #syncDuration(long)} is first-wins
 * for the whole lifetime, so switching media warrants a fresh instance.
 */
public final class ServerMediaPlayer extends MediaPlayer {
    private static final Marker IT = MarkerManager.getMarker(ServerMediaPlayer.class.getSimpleName());
    // CLIENT DURATION REPORTS DIVERGING BEYOND THIS GAP GET LOGGED — LIKELY A BROKEN SOURCE VARIANT
    private static final long DURATION_DIVERGENCE_MS = 500;
    private static final long HEARTBEAT_NS = 5_000_000_000L;     // PERIODIC SNAPSHOT RESEND
    private static final long FOLLOWER_TTL_NS = 15_000_000_000L;  // 3 MISSED FOLLOWER KEEPALIVES
    private static final long DEPARTED_TTL_NS = 10_000_000_000L;  // 2 KEEPALIVES: A REPORT IN FLIGHT BEHIND AN Unwatch LANDS WITHIN THIS
    private static final int MAX_FOLLOWERS = 4_096;                // BOUNDS THE PER-TICK SWEEP AGAINST A FLOOD OF FORGED HELLOS

    // TIME TRACKING — MUTATORS ARE synchronized (CALLER THREAD vs SHARED TICKER), READS STAY VOLATILE
    // LOCK-FREE. time() MAY PAIR accumulatedMs/segmentStartNanos ACROSS A CONCURRENT REBASE: THE GLITCH
    // IS SUB-TICK (<50ms), TRANSIENT AND ACCEPTED.
    private volatile long duration;
    private volatile boolean live;
    private volatile long accumulatedMs;      // TIME ACCUMULATED BEFORE THE CURRENT PLAY SEGMENT
    private volatile long segmentStartNanos;  // System.nanoTime() WHEN THE CURRENT SEGMENT BEGAN
    private volatile Status status = Status.WAITING;
    private volatile boolean released;
    // MUTATION COUNTER (AtomicInteger: ++ ON volatile ISN'T ATOMIC AND repeat() BUMPS UNLOCKED), SEEDED FROM
    // THE CLOCK SO A REPLACEMENT AUTHORITY OUTRANKS ITS PREDECESSOR ON FOLLOWERS (SIGNED-DISTANCE COMPARE)
    private final AtomicInteger revision = new AtomicInteger((int) System.currentTimeMillis());

    // registry HOLDS THE FOLLOWERS, departed WHO JUST LEFT (A REPORT BEHIND ITS Unwatch CANNOT RE-ENROLL IT);
    // gated IS THE LOCKSTEP OVERLAY: THE STATUS STAYS PLAYING UNDERNEATH, THE CLOCK FREEZES, BUFFERING SHOWS
    private final int capabilities;
    private final Map<Long, Follower> registry = new ConcurrentHashMap<>();
    private final Map<Long, Long> departed = new ConcurrentHashMap<>();
    private volatile boolean gated;
    private volatile long followerTtlNanos = FOLLOWER_TTL_NS;
    // ANOMALY LATCHES: A FULL REGISTRY AND A DIVERGENT DURATION ARE LOGGED ONCE PER PLAYER
    private volatile boolean fullWarned;
    private boolean divergentWarned; // GUARDED BY THIS
    // BROADCAST STATE — TOUCHED ONLY FROM THE SHARED TICKER THREAD
    private int lastCastRevision = this.revision.get() - 1;
    private long lastCastNanos;

    // A PLAIN CLOCK WITH NO BRIDGE; BROADCASTING, IF ANY, IS MANUAL
    ServerMediaPlayer() {
        super(null, Role.SOLO);
        this.capabilities = 0;
        this.arm();
    }

    // A BROADCASTING AUTHORITY: THE WHOLE SERVER SIDE OF THE SYNC PROTOCOL RUNS OVER THE CARRIER (SEE THE CLASS DOCS)
    ServerMediaPlayer(final Bridge bridge, final Config.Capability... capabilities) {
        super(bridge, Role.AUTHORITY);
        this.capabilities = Config.Capability.mask(capabilities);
        this.arm();
    }

    // FOLLOWER ROLE — PRIVATE SO new ServerMediaPlayer(bridge) INSIDE THE PACKAGE STILL RESOLVES TO THE AUTHORITY
    private ServerMediaPlayer(final Bridge bridge) {
        super(bridge, Role.FOLLOWER);
        this.capabilities = 0;
        this.arm();
    }

    // A HEADLESS FOLLOWER: A WALL CLOCK DRIVEN BY A REMOTE AUTHORITY THROUGH THE CARRIER, FED THROUGH sync(ByteBuffer)
    static ServerMediaPlayer follower(final Bridge bridge) {
        return new ServerMediaPlayer(bridge);
    }

    // --- CLIENT SYNC ---

    /**
     * Synchronizes the total media duration reported by a client.
     * The first non-zero report wins for the player lifetime; later divergent reports are
     * ignored (the first one is logged), keeping the loop modulo stable across mixed-quality clients.
     * @param durationMs total duration in milliseconds (values {@code <= 0} are ignored)
     */
    public synchronized void syncDuration(final long durationMs) {
        if (durationMs <= 0) return;
        if (this.duration > 0) {
            if (!this.divergentWarned && Math.abs(durationMs - this.duration) > DURATION_DIVERGENCE_MS) {
                this.divergentWarned = true;
                LOGGER.warn(IT, "Ignored divergent duration report: {}ms vs synced {}ms", durationMs, this.duration);
            }
            return;
        }
        this.duration = durationMs;
        // THE TIMELINE JUST BECAME KNOWN — A RUNNING SESSION STARTS COUNTING FROM THIS INSTANT,
        // SINCE THE CLOCK WAS HELD WHILE IT HAD NOTHING TO MEASURE AGAINST (SEE computeTime)
        if (this.status == Status.PLAYING && !this.gated && !this.live) this.segmentStartNanos = System.nanoTime();
        this.revision.incrementAndGet();
    }

    /**
     * Synchronizes the current playback position reported by a client.
     * If the player is currently playing, time continues advancing from this position.
     * @param timeMs the corrected playback position in milliseconds (clamped to >= 0)
     */
    public synchronized void syncTime(final long timeMs) {
        this.accumulatedMs = Math.max(0, timeMs);
        // A GATED CLOCK STAYS FROZEN — THE UNGATE REBASES THE SEGMENT
        if (this.status == Status.PLAYING && !this.gated) {
            this.segmentStartNanos = System.nanoTime();
        }
        this.revision.incrementAndGet();
    }

    /**
     * Synchronizes the live flag reported by a client.
     * A live source locks speed changes and tells followers to never correct time.
     * @param live whether the media source is a live stream
     */
    public synchronized void live(final boolean live) {
        if (this.live == live) return;
        if (this.duration <= 0 && this.status == Status.PLAYING && !this.gated) {
            // SAME TIMELINE-BECAME-KNOWN REBASE AS syncDuration: A HELD CLOCK STARTS COUNTING NOW, AND A CLOCK THAT
            // RAN LIVE KEEPS WHAT IT COUNTED BEFORE IT HOLDS AGAIN
            if (!live) this.accumulatedMs = this.computeTime(this.speed());
            this.segmentStartNanos = System.nanoTime();
        }
        this.live = live;
        this.revision.incrementAndGet();
    }

    // --- SNAPSHOT SYNC ---

    /**
     * Monotonic mutation counter. Bumped by every successful state change, including the
     * internal repeat-loop wrap and lockstep gate flips, so a broadcaster can poll it and
     * resend {@link #snapshot()} only when something actually changed. A bridged authority
     * already does this automatically.
     * @return the current revision
     */
    public int revision() {
        return this.revision.get();
    }

    /**
     * Captures the current state as an immutable snapshot, consistent under concurrent mutations.
     * @return a snapshot of revision, status, time, duration, speed, volume, mute, repeat and live
     */
    public synchronized Sync snapshot() {
        return new Sync(this.revision.get(), this.status(), this.time(), this.duration, this.speed(),
                this.volume(), this.mute(), this.repeat(), this.live);
    }

    // --- AUTHORITY PROTOCOL ---

    @Override
    void upstream(final Packet packet) {
        if (packet instanceof final Watch watch) {
            // A HELLO IS AN EXPLICIT (RE)JOIN — IT OVERRIDES ANY EARLIER DEPARTURE
            this.departed.remove(watch.watcherId());
            final Follower f = this.registry.computeIfAbsent(watch.watcherId(), id -> this.enroll());
            if (f == null) return;
            f.lastSeenNanos = System.nanoTime();
            // ANSWER WITH THE SESSION CONFIG (ECHOING THE ID FOR RTT) AND A FRESH SNAPSHOT,
            // SO LATE JOINERS SYNC IMMEDIATELY INSTEAD OF WAITING FOR THE NEXT HEARTBEAT
            this.send(new Config(watch.watcherId(), this.capabilities));
            this.send(this.snapshot());
        } else if (packet instanceof final Report report) {
            // A REPORT FROM AN UNSEEN ID REGISTERS IMPLICITLY — Watch MAY BE LOST ON UNORDERED TRANSPORTS —
            // UNLESS THE ID JUST LEFT: THEN IT IS THE KEEPALIVE THAT OVERTOOK ITS OWN Unwatch
            final Long left = this.departed.get(report.watcherId());
            if (left != null && System.nanoTime() - left < DEPARTED_TTL_NS) {
                LOGGER.debug(IT, "Ignored a late report from departed follower {}", report.watcherId());
                return;
            }
            final Follower f = this.registry.computeIfAbsent(report.watcherId(), id -> this.enroll());
            if (f == null) return;
            f.lastSeenNanos = System.nanoTime();
            f.status = report.status();
            // FIRST READY REPORT POOLS A MID-PLAYBACK JOINER INTO THE LOCKSTEP GATE
            if (report.status() == Status.PAUSED || report.status() == Status.PLAYING) f.pooled = true;
            // MEDIA FACTS COUNT ONLY ONCE A CLIENT OPENED THE SOURCE: AN UNOPENED PLAYER KNOWS NO DURATION, AND A WRONG LIVE
            // LATCH WOULD KILL SPEED CONTROL, SEEKING AND EVERY TIME CORRECTION FOR THE WHOLE SESSION
            if (report.status() != Status.WAITING && report.status() != Status.LOADING) {
                this.syncDuration(report.duration());
                // LIVE IS A ONE-WAY LATCH FROM REPORTS — MIXED CLIENTS MUST NOT FLAP IT
                if (report.live()) this.live(true);
            }
        } else if (packet instanceof final Control control) {
            final Follower f = this.registry.get(control.watcherId());
            if (f != null) f.lastSeenNanos = System.nanoTime();
            if ((this.capabilities & Config.Capability.CONTROLS.bit) == 0) {
                LOGGER.debug(IT, "Dropped control request {} — CONTROLS capability is off", control.op());
                return;
            }
            switch (control.op()) {
                case START -> this.start();
                case START_PAUSED -> this.startPaused();
                case PAUSE -> this.pause(control.value() != 0);
                case STOP -> this.stop();
                case TOGGLE -> this.togglePlay();
                case SEEK -> this.seek(control.value());
                case SEEK_QUICK -> this.seekQuick(control.value());
                case SPEED -> this.speed(control.floatValue());
                case REPEAT -> this.repeat(control.value() != 0);
            }
        } else if (packet instanceof final Unwatch unwatch) {
            // ONLY A KNOWN FOLLOWER LEAVES A TOMBSTONE — STRANGERS MUST NOT GROW THE MAP
            if (this.registry.remove(unwatch.watcherId()) != null) this.departed.put(unwatch.watcherId(), System.nanoTime());
        } else {
            LOGGER.debug(IT, "Ignored downstream packet on the authority: {}", packet);
        }
    }

    // A FOLLOWER JOINING A RUNNING SESSION NEVER GATES UNTIL ITS FIRST READY REPORT; BEFORE THE SESSION
    // RUNS, EVERY FOLLOWER GATES THE INITIAL LOCKSTEP START. A FULL REGISTRY ENROLLS NOBODY (NULL)
    private Follower enroll() {
        if (this.registry.size() >= MAX_FOLLOWERS) {
            if (!this.fullWarned) LOGGER.warn(IT, "Follower registry is full ({}); ignoring new followers", MAX_FOLLOWERS);
            this.fullWarned = true;
            return null;
        }
        final Follower f = new Follower();
        if (this.status != Status.PLAYING) f.pooled = true;
        return f;
    }

    /** Number of followers currently registered on this authority. */
    public int followers() {
        return this.registry.size();
    }

    /**
     * Sets how long a follower may go silent before the authority drops it. Followers send a
     * keepalive every 5 seconds, so the default (15s) tolerates three losses; raise it for slow
     * or bursty transports. A dropped follower stops holding the lockstep gate — this is what
     * keeps one vanished client from freezing the whole pool forever.
     * @param ms silence tolerance in milliseconds, from {@code 1} through
     *           {@code Long.MAX_VALUE / 1_000_000} to fit the nanosecond clock
     */
    public void followerTimeout(final long ms) {
        if (ms <= 0 || ms > Long.MAX_VALUE / 1_000_000L)
            throw new IllegalArgumentException("Follower timeout must fit a positive nanosecond duration");
        this.followerTtlNanos = ms * 1_000_000L;
    }

    /** Returns the follower silence tolerance in milliseconds. */
    public long followerTimeout() {
        return this.followerTtlNanos / 1_000_000L;
    }

    @Override
    void tick() {
        // A HEADLESS FOLLOWER HAS NO MEDIA TO LEARN THE TIMELINE FROM — ADOPT THE SESSION'S
        // DURATION AND LIVE FLAG SO ITS OWN CLOCK RUNS, WRAPS AND ENDS ON THE REAL TIMELINE
        if (this.role() == Role.FOLLOWER && this.duration <= 0 && !this.live) {
            final Sync session = this.authority(); // NULL UNTIL THE FIRST SNAPSHOT LANDS
            if (session != null) {
                this.syncDuration(session.duration());
                if (session.live()) this.live(true);
            }
        }
        super.tick();
        this.update();
        // TTL SWEEP, LOCKSTEP GATE AND REVISION/HEARTBEAT BROADCAST
        if (this.role() == Role.AUTHORITY) this.broadcast();
    }

    // SWEEPS THE REGISTRY AND BROADCASTS THE SNAPSHOT WHEN THE REVISION MOVED OR THE HEARTBEAT IS DUE
    private void broadcast() {
        final long now = System.nanoTime();
        boolean hold = false;
        for (final Map.Entry<Long, Follower> e: this.registry.entrySet()) {
            final Follower f = e.getValue();
            if (now - f.lastSeenNanos >= this.followerTtlNanos) {
                // A VANISHED FOLLOWER IS TREATED LIKE A FAILED ONE — IT NEVER HOLDS THE GATE
                this.registry.remove(e.getKey());
                continue;
            }
            // ONLY POOLED FOLLOWERS STILL WAITING/LOADING/BUFFERING HOLD THE GATE; ERROR NEVER DOES
            hold |= f.pooled && (f.status == Status.WAITING || f.status == Status.LOADING || f.status == Status.BUFFERING);
        }
        this.departed.values().removeIf(left -> now - left >= DEPARTED_TTL_NS);
        if ((this.capabilities & Config.Capability.LOCKSTEP.bit) != 0) this.gate(hold);
        // THE GATE FLIP BUMPS THE REVISION, SO IT TRAVELS IN THIS VERY BROADCAST
        final int rev = this.revision.get();
        if (rev != this.lastCastRevision || now - this.lastCastNanos >= HEARTBEAT_NS) {
            this.lastCastRevision = rev;
            this.lastCastNanos = now;
            this.send(this.snapshot());
        }
    }

    // FLIPS THE LOCKSTEP GATE: ON FREEZES THE CLOCK WHERE IT STANDS, OFF RESUMES FROM THE SAME SPOT.
    // THE PRESENTED STATUS FLIPS PLAYING<->BUFFERING, WHICH IS A TRANSITION LISTENERS HEAR ABOUT
    private void gate(final boolean on) {
        synchronized (this) {
            if (this.gated == on || this.status != Status.PLAYING) return;
            if (on) this.accumulatedMs = this.computeTime(this.speed());
            else this.segmentStartNanos = System.nanoTime();
            this.gated = on;
            this.revision.incrementAndGet();
            this.queueStatus(on ? Status.PLAYING : Status.BUFFERING, on ? Status.BUFFERING : Status.PLAYING);
        }
        this.deliverStatus();
    }

    // --- PLAYBACK CONTROLS ---

    // THE BASE CALLS THIS ONCE A START IS THIS CLOCK'S OWN; THE MONITOR RELEASE() TAKES KEEPS A RELEASED CLOCK STOPPED
    @Override
    boolean launch(final boolean paused, final long seekMs) {
        synchronized (this) {
            if (this.released) return false;
            this.exception = null;
            final Status prev = this.status();
            this.accumulatedMs = Math.max(0L, seekMs);
            this.segmentStartNanos = System.nanoTime();
            this.gated = false;
            this.status = paused ? Status.PAUSED : Status.PLAYING;
            this.revision.incrementAndGet();
            this.ticking(true);
            this.queueStatus(prev, this.status);
        }
        this.deliverStatus();
        return true;
    }

    // EVERY CONTROL DELEGATES TO super FIRST (A FOLLOWER'S CALL IS HANDED TO ITS AUTHORITY THERE); THE LOCK COMES AFTER IT AND
    // QUEUES THE TRANSITION IN WRITE ORDER, AND THE LISTENER HEARS IT AFTER THE LOCK, SO NEITHER THE BRIDGE NOR A CALLBACK RUNS UNDER IT
    @Override
    public boolean pause(final boolean paused) {
        if (!super.pause(paused)) return false;
        synchronized (this) {
            final Status prev = this.status();
            if (paused) {
                if (this.status != Status.PLAYING) return false;
                // A GATED CLOCK IS ALREADY FROZEN — DON'T RE-DERIVE TIME FROM A STALE SEGMENT START
                if (!this.gated) this.accumulatedMs = this.computeTime(this.speed());
                this.gated = false;
                this.status = Status.PAUSED;
            } else {
                if (this.status != Status.PAUSED) return false;
                this.segmentStartNanos = System.nanoTime();
                this.status = Status.PLAYING;
                // A SOLO CLOCK DEREGISTERS ON ENDED/STOP; RESUMING AFTER A SEEK MUST TICK AGAIN
                // OR IT NEVER WRAPS NOR ENDS FOR THE REST OF ITS LIFE
                this.ticking(true);
            }
            this.revision.incrementAndGet();
            this.queueStatus(prev, paused ? Status.PAUSED : Status.PLAYING);
        }
        this.deliverStatus();
        return true;
    }

    @Override
    public boolean stop() {
        if (!super.stop()) return false;
        synchronized (this) {
            if (this.status == Status.WAITING || this.status == Status.STOPPED) return false;
            final Status prev = this.status();
            this.accumulatedMs = 0;
            this.gated = false;
            this.status = Status.STOPPED;
            this.revision.incrementAndGet();
            this.ticking(false);
            this.queueStatus(prev, Status.STOPPED);
        }
        this.deliverStatus();
        return true;
    }

    @Override
    public boolean togglePlay() {
        if (!super.togglePlay()) return false;
        // THE NESTED CALLS RESOLVE LOCALLY: super ALREADY CLEARED THE FORWARDING DECISION.
        // READ THE RAW STATUS, NOT status() — A GATED CLOCK IS STILL PLAYING UNDERNEATH.
        return switch (this.status) {
            case PLAYING -> this.pause(true);
            case PAUSED  -> this.pause(false);
            case STOPPED, ENDED -> this.start();
            default -> false;
        };
    }

    @Override
    public boolean seek(final long time) {
        if (!super.seek(time)) return false;
        synchronized (this) {
            if (this.status == Status.WAITING || !this.canSeek()) return false;
            final Status prev = this.status();
            // SCRUBBING A FINISHED/STOPPED CLOCK LANDS PAUSED AT THE POSITION INSTEAD OF STAYING DEAD
            if (this.status == Status.ENDED || this.status == Status.STOPPED) this.status = Status.PAUSED;
            this.syncTime(MathUtil.clamp(time, 0L, this.duration));
            this.queueStatus(prev, this.status());
        }
        this.deliverStatus();
        return true;
    }

    @Override
    public boolean seekQuick(final long time) {
        return this.seek(time);
    }

    @Override
    public long time() {
        // GATED OR NON-PLAYING CLOCKS REPORT THE FROZEN/ACCUMULATED POSITION (0 UNTIL FIRST USE)
        if (this.gated || this.status != Status.PLAYING) return this.accumulatedMs;
        final long t = this.computeTime(this.speed());
        final long d = this.duration;
        // CLAMP/MODULO AGAINST DURATION SO time() NEVER OVERRUNS BETWEEN 50ms TICKS; A LIVE
        // TIMELINE IS OPEN-ENDED AND RUNS FREE OF ANY MIXED-VARIANT DURATION LATCH
        return d > 0 && !this.live ? (this.repeat() ? t % d : Math.min(t, d)) : t;
    }

    @Override
    public boolean speed(final float speed) {
        final float old = this.speed();
        synchronized (this) {
            // THE ELAPSED SEGMENT RAN AT THE OUTGOING RATE: CLOSE IT BEFORE THE BASE PUBLISHES THE NEW ONE, SO NEITHER
            // THE TICKER NOR A SNAPSHOT RESCALES TIME THAT ALREADY PASSED (A REFUSED CHANGE JUST KEEPS THE OLD RATE)
            if (this.status == Status.PLAYING && !this.gated) {
                this.accumulatedMs = this.computeTime(old);
                this.segmentStartNanos = System.nanoTime();
            }
        }
        if (!super.speed(speed)) return false;
        if (old != speed) this.revision.incrementAndGet();
        return true;
    }

    @Override
    public boolean repeat(final boolean repeat) {
        final boolean old = this.repeat();
        if (!super.repeat(repeat)) return false;
        if (old != repeat) this.revision.incrementAndGet();
        return true;
    }

    @Override
    public synchronized void volume(final int volume) {
        final int old = this.volume();
        super.volume(volume);
        if (this.volume() != old) this.revision.incrementAndGet();
    }

    @Override
    public synchronized void mute(final boolean mute) {
        if (this.mute() != mute) this.revision.incrementAndGet();
        super.mute(mute);
    }

    @Override
    public float fps() {
        return 0;
    }

    @Override
    public long duration() {
        return this.duration;
    }

    @Override
    public Status status() {
        // THE LOCKSTEP GATE PRESENTS BUFFERING WHILE THE UNDERLYING SESSION STAYS PLAYING
        return this.gated ? Status.BUFFERING : this.status;
    }

    @Override
    public boolean live() {
        // ONLY A CLIENT REPORT OR THE HOST MARKS THE SESSION LIVE: AN UNKNOWN DURATION IS A SOURCE NOT OPEN YET
        return this.live;
    }

    @Override
    public boolean canSeek() {
        // NO TIMELINE TO MOVE UNTIL A CLIENT REPORTS THE DURATION, AND NONE ON A LIVE SESSION
        return this.duration > 0 && !this.live;
    }

    @Override
    public boolean canPlay() {
        return !this.released && this.status != Status.ERROR;
    }

    @Override
    public boolean previousFrame() {
        return false;
    }

    @Override
    public boolean nextFrame() {
        return false;
    }

    @Override
    public void release() {
        synchronized (this) {
            final Status prev = this.status();
            this.released = true;
            this.gated = false;
            this.status = Status.STOPPED;
            this.registry.clear();
            this.departed.clear();
            this.queueStatus(prev, Status.STOPPED);
        }
        // OUTSIDE THE LOCK — A FOLLOWER'S super SENDS Unwatch THROUGH THE DEV'S BRIDGE
        this.disarm();
        this.deliverStatus();
        super.release();
    }

    // --- INTERNAL ---

    // POSITION OF THE RUNNING SEGMENT AT THE GIVEN RATE. THE RATE IS EXPLICIT BECAUSE a speed change
    // MUST CLOSE THE SEGMENT AT THE OUTGOING VALUE BEFORE THE NEW ONE APPLIES.
    private long computeTime(final float rate) {
        // NO TIMELINE, NO PROGRESSION: WITHOUT A DURATION (AND NOT LIVE) THE CLOCK CANNOT WRAP OR
        // END, SO RUNNING IT ACCUMULATES HOURS OF NONSENSE ON SESSIONS PLAYING UNWATCHED AND THE
        // EVENTUAL DURATION LATCH WOULD SNAP EVERY FOLLOWER TO A MEANINGLESS POSITION.
        if (this.duration <= 0 && !this.live) return this.accumulatedMs;
        final long elapsedNanos = System.nanoTime() - this.segmentStartNanos;
        final long run = (long) (TimeUnit.NANOSECONDS.toMillis(elapsedNanos) * rate);
        // SATURATE: A POSITION SEEKED NEAR Long.MAX_VALUE MUST NOT WRAP NEGATIVE AND BREAK THE SNAPSHOT
        return run > Long.MAX_VALUE - this.accumulatedMs ? Long.MAX_VALUE : this.accumulatedMs + run;
    }

    // 50ms CLOCK MAINTENANCE: LOOP WRAP AND THE ENDED TRANSITION, QUEUED UNDER THE MONITOR AND HEARD OUTSIDE IT
    private void update() {
        synchronized (this) {
            if (this.status != Status.PLAYING || this.gated) return;

            // A LIVE TIMELINE HAS NO END — NEVER WRAP NOR END IT, EVEN WITH A DURATION LATCHED
            final long d = this.duration;
            if (d <= 0 || this.live) return;
            final long now = this.computeTime(this.speed());
            if (now < d) return;

            if (!this.repeat()) {
                this.accumulatedMs = d;
                this.status = Status.ENDED;
                this.revision.incrementAndGet();
                this.ticking(false);
                this.queueStatus(Status.PLAYING, Status.ENDED);
            } else {
                // CARRY THE OVERSHOOT ACROSS THE LOOP BOUNDARY — DISCARDING IT SLIPPED THE AUTHORITATIVE
                // CLOCK BY UP TO ONE TICK (50ms) PER LOOP, DESYNCING EVERY CLIENT THAT TRUSTS IT.
                this.accumulatedMs = now % d;
                this.segmentStartNanos = System.nanoTime();
                // THE WRAP REBASES THE TIMELINE — BUMP SO THE BROADCASTER RESENDS WHERE CLIENTS DRIFT MOST
                this.revision.incrementAndGet();
            }
        }
        this.deliverStatus();
    }

    // PER-FOLLOWER REGISTRY ENTRY. pooled MARKS LOCKSTEP GATE MEMBERSHIP — IMMEDIATE WHEN THE
    // SESSION IS NOT RUNNING, DEFERRED TO THE FIRST READY REPORT FOR MID-PLAYBACK JOINERS.
    private static final class Follower {
        volatile Status status = Status.LOADING;
        // STAMPED AT BIRTH — THE TTL SWEEP RUNS CONCURRENTLY WITH REGISTRATION AND WOULD READ
        // A ZERO STAMP AS A FOLLOWER SILENT SINCE FOREVER, DROPPING IT ON THE SPOT
        volatile long lastSeenNanos = System.nanoTime();
        volatile boolean pooled;
    }
}
