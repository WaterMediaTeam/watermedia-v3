package org.watermedia.api.media.players;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.media.players.sync.Bridge;
import org.watermedia.api.media.players.sync.Config;
import org.watermedia.api.media.players.sync.Control;
import org.watermedia.api.media.players.sync.Packet;
import org.watermedia.api.media.players.sync.Report;
import org.watermedia.api.media.players.sync.Sync;
import org.watermedia.api.media.players.sync.Unwatch;
import org.watermedia.api.media.players.sync.Watch;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * A headless media player that acts as a wall clock for time synchronization.
 * <p>
 * This player does not produce any audio or video output. It calculates time progression
 * purely from the system clock, allowing multiple clients to synchronize their playback
 * position against a single authoritative instance. Every successful mutation bumps
 * {@link #revision()}.
 * <p>
 * Built with a bridge — {@code new ServerMediaPlayer(bridge, capabilities...)} — it runs
 * the whole server side of the sync protocol by itself: it answers {@link Watch} hellos
 * with its {@link Config}, tracks the spectator pool from {@link Report}s (with a TTL for
 * vanished clients), applies {@link Control} requests under the
 * {@link Config.Capability#CONTROLS} capability, re-broadcasts {@link #snapshot()} when
 * the revision moves plus a periodic heartbeat, and under
 * {@link Config.Capability#LOCKSTEP} gates the whole audience while a pooled spectator is
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
    private static final long HEARTBEAT_NANOS = 5_000_000_000L;     // PERIODIC SNAPSHOT RESEND
    private static final long WATCHER_TTL_NANOS = 15_000_000_000L;  // 3 MISSED FOLLOWER KEEPALIVES

    // TIME TRACKING — MUTATORS ARE synchronized (CALLER THREAD vs SHARED TICKER), READS STAY VOLATILE
    // LOCK-FREE. time() MAY PAIR accumulatedMs/segmentStartNanos ACROSS A CONCURRENT REBASE: THE GLITCH
    // IS SUB-TICK (<50ms), TRANSIENT AND ACCEPTED.
    private volatile long duration;
    private volatile boolean live;
    private volatile long accumulatedMs;      // TIME ACCUMULATED BEFORE THE CURRENT PLAY SEGMENT
    private volatile long segmentStartNanos;  // System.nanoTime() WHEN THE CURRENT SEGMENT BEGAN
    private volatile Status status = Status.WAITING;
    // MUTATION COUNTER — EVERY SUCCESSFUL STATE CHANGE BUMPS IT SO THE BROADCASTER KNOWS TO RESEND
    private volatile int revision;

    // AUTHORITY BRIDGE STATE — watchers IS THE SPECTATOR REGISTRY; gated IS THE LOCKSTEP OVERLAY:
    // WHILE SET, THE UNDERLYING STATUS STAYS PLAYING BUT THE CLOCK FREEZES AND BUFFERING IS PRESENTED.
    private final int capabilities;
    private final Map<Long, Watcher> watchers = new ConcurrentHashMap<>();
    private volatile boolean gated;
    private volatile long watcherTtlNanos = WATCHER_TTL_NANOS;
    // BROADCAST STATE — TOUCHED ONLY FROM THE SHARED TICKER THREAD
    private int lastCastRevision = -1;
    private long lastCastNanos;

    /** Creates a plain clock with no bridge — broadcasting, if any, is manual. */
    public ServerMediaPlayer() {
        super(null, Role.SOLO);
        this.capabilities = 0;
        this.arm();
    }

    /**
     * Creates a broadcasting authority: the whole server side of the sync protocol runs
     * automatically over the given byte carrier (see the class docs).
     * @param bridge byte carrier that reaches every follower
     * @param capabilities capabilities granted to the followers
     */
    public ServerMediaPlayer(final Bridge bridge, final Config.Capability... capabilities) {
        super(Objects.requireNonNull(bridge, "Bridge cannot be null"), Role.AUTHORITY);
        this.capabilities = Config.Capability.mask(capabilities);
        this.arm();
    }

    // FOLLOWER ROLE — SEE follower(...)
    private ServerMediaPlayer(final Bridge bridge) {
        super(bridge, Role.FOLLOWER);
        this.capabilities = 0;
        this.arm();
    }

    /**
     * Creates a headless follower: a wall clock driven by a remote authority through the
     * given carrier, useful as a media-less mirror (HUDs, logic-side replicas) and for
     * exercising the protocol without a media backend. Feed received payloads through
     * {@link #sync(ByteBuffer)}.
     * @param bridge byte carrier towards the authority
     * @return the follower player
     */
    public static ServerMediaPlayer follower(final Bridge bridge) {
        return new ServerMediaPlayer(Objects.requireNonNull(bridge, "Bridge cannot be null"));
    }

    // --- CLIENT SYNC ---

    /**
     * Synchronizes the total media duration reported by a client.
     * The first non-zero report wins for the player lifetime; later divergent reports are
     * ignored (and logged), keeping the loop modulo stable across mixed-quality clients.
     * @param durationMs total duration in milliseconds (values {@code <= 0} are ignored)
     */
    public synchronized void syncDuration(final long durationMs) {
        if (durationMs <= 0) return;
        if (this.duration > 0) {
            if (Math.abs(durationMs - this.duration) > DURATION_DIVERGENCE_MS)
                LOGGER.warn(IT, "Ignored divergent duration report: {}ms vs synced {}ms", durationMs, this.duration);
            return;
        }
        this.duration = durationMs;
        this.revision++;
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
        this.revision++;
    }

    /**
     * Synchronizes the live flag reported by a client.
     * A live source locks speed changes and tells followers to never correct time.
     * @param live whether the media source is a live stream
     */
    public synchronized void syncLive(final boolean live) {
        if (this.live == live) return;
        this.live = live;
        this.revision++;
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
        return this.revision;
    }

    /**
     * Captures the current state as an immutable snapshot, consistent under concurrent mutations.
     * @return a snapshot of revision, status, time, duration, speed, volume, mute, repeat and live
     */
    public synchronized Sync snapshot() {
        return new Sync(this.revision, this.status(), this.time(), this.duration, this.speed(),
                this.volume(), this.mute(), this.repeat(), this.live);
    }

    // --- AUTHORITY PROTOCOL ---

    @Override
    protected void upstream(final Packet packet) {
        if (packet instanceof final Watch watch) {
            final Watcher w = this.watchers.computeIfAbsent(watch.watcherId(), id -> this.watcher());
            w.lastSeenNanos = System.nanoTime();
            // ANSWER WITH THE SESSION CONFIG (ECHOING THE ID FOR RTT) AND A FRESH SNAPSHOT,
            // SO LATE JOINERS SYNC IMMEDIATELY INSTEAD OF WAITING FOR THE NEXT HEARTBEAT
            this.send(new Config(watch.watcherId(), this.capabilities));
            this.send(this.snapshot());
        } else if (packet instanceof final Report report) {
            // A REPORT FROM AN UNSEEN ID REGISTERS IMPLICITLY — Watch MAY BE LOST ON UNORDERED TRANSPORTS
            final Watcher w = this.watchers.computeIfAbsent(report.watcherId(), id -> this.watcher());
            w.lastSeenNanos = System.nanoTime();
            w.status = report.status();
            // FIRST READY REPORT POOLS A MID-PLAYBACK JOINER INTO THE LOCKSTEP GATE
            if (report.status() == Status.PAUSED || report.status() == Status.PLAYING) w.pooled = true;
            // MEDIA FACTS COUNT ONLY ONCE A CLIENT ACTUALLY OPENED THE SOURCE: A PLAYER THAT HASN'T
            // REPORTS AN UNKNOWN DURATION, WHICH READS AS "LIVE" AND WOULD LATCH THE WHOLE SESSION
            // INTO A LIVE ONE — KILLING SPEED CONTROL AND EVERY TIME CORRECTION.
            if (report.status() != Status.WAITING && report.status() != Status.LOADING) {
                this.syncDuration(report.duration());
                // LIVE IS A ONE-WAY LATCH FROM REPORTS — MIXED CLIENTS MUST NOT FLAP IT
                if (report.live()) this.syncLive(true);
            }
        } else if (packet instanceof final Control control) {
            final Watcher w = this.watchers.get(control.watcherId());
            if (w != null) w.lastSeenNanos = System.nanoTime();
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
            this.watchers.remove(unwatch.watcherId());
        } else {
            LOGGER.debug(IT, "Ignored downstream packet on the authority: {}", packet);
        }
    }

    // A SPECTATOR JOINING A RUNNING SESSION NEVER GATES UNTIL ITS FIRST READY REPORT;
    // BEFORE THE SESSION RUNS, EVERY WATCHER GATES THE INITIAL LOCKSTEP START.
    private Watcher watcher() {
        final Watcher w = new Watcher();
        if (this.status != Status.PLAYING) w.pooled = true;
        return w;
    }

    /** Number of spectators currently registered on this authority. */
    public int watchers() {
        return this.watchers.size();
    }

    /**
     * Sets how long a spectator may go silent before the authority drops it. Followers send a
     * keepalive every 5 seconds, so the default (15s) tolerates three losses; raise it for slow
     * or bursty transports. A dropped spectator stops holding the lockstep gate — this is what
     * keeps one vanished client from freezing the whole audience forever.
     * @param ms silence tolerance in milliseconds, {@code > 0}
     */
    public void watcherTimeout(final long ms) {
        if (ms <= 0) throw new IllegalArgumentException("Watcher timeout must be positive");
        this.watcherTtlNanos = ms * 1_000_000L;
    }

    /** Returns the spectator silence tolerance in milliseconds. */
    public long watcherTimeout() {
        return this.watcherTtlNanos / 1_000_000L;
    }

    @Override
    protected void tick50() {
        // A HEADLESS FOLLOWER HAS NO MEDIA TO LEARN THE DURATION FROM — ADOPT THE SESSION'S
        // SO ITS OWN CLOCK WRAPS AND ENDS ON THE REAL TIMELINE
        if (this.role() == Role.FOLLOWER && this.duration <= 0) {
            final Sync session = this.authority(); // NULL UNTIL THE FIRST SNAPSHOT LANDS
            if (session != null) this.syncDuration(session.duration());
        }
        super.tick50();
        this.update();
        // TTL SWEEP, LOCKSTEP GATE AND REVISION/HEARTBEAT BROADCAST
        if (this.role() == Role.AUTHORITY) this.broadcast();
    }

    // SWEEPS THE REGISTRY AND BROADCASTS THE SNAPSHOT WHEN THE REVISION MOVED OR THE HEARTBEAT IS DUE
    private void broadcast() {
        final long now = System.nanoTime();
        boolean hold = false;
        for (final Map.Entry<Long, Watcher> e: this.watchers.entrySet()) {
            final Watcher w = e.getValue();
            if (now - w.lastSeenNanos >= this.watcherTtlNanos) {
                // A VANISHED SPECTATOR IS TREATED LIKE A FAILED ONE — IT NEVER HOLDS THE GATE
                this.watchers.remove(e.getKey());
                continue;
            }
            // ONLY POOLED SPECTATORS STILL WAITING/LOADING/BUFFERING HOLD THE GATE; ERROR NEVER DOES
            hold |= w.pooled && (w.status == Status.WAITING || w.status == Status.LOADING || w.status == Status.BUFFERING);
        }
        if ((this.capabilities & Config.Capability.LOCKSTEP.bit) != 0) this.gate(hold);
        // THE GATE FLIP BUMPS THE REVISION, SO IT TRAVELS IN THIS VERY BROADCAST
        final int rev = this.revision;
        if (rev != this.lastCastRevision || now - this.lastCastNanos >= HEARTBEAT_NANOS) {
            this.lastCastRevision = rev;
            this.lastCastNanos = now;
            this.send(this.snapshot());
        }
    }

    // FLIPS THE LOCKSTEP GATE: ON FREEZES THE CLOCK WHERE IT STANDS, OFF RESUMES FROM THE SAME SPOT
    private synchronized void gate(final boolean on) {
        if (this.gated == on || this.status != Status.PLAYING) return;
        if (on) {
            this.accumulatedMs = this.computeTime(this.speed());
        } else {
            this.segmentStartNanos = System.nanoTime();
        }
        this.gated = on;
        this.revision++;
    }

    // --- PLAYBACK CONTROLS ---

    // EVERY CONTROL DELEGATES TO super FIRST: THAT IS WHERE A FOLLOWER'S CALL IS HANDED TO ITS
    // AUTHORITY, AND THE CLOCK MUTATION BELOW ONLY RUNS WHEN THE CALL IS THIS PLAYER'S OWN.
    // THE LOCK IS TAKEN AFTER super SO THE DEV'S BRIDGE IS NEVER INVOKED WHILE HOLDING IT.
    @Override
    public boolean start() {
        if (!super.start()) return false;
        synchronized (this) {
            this.accumulatedMs = 0;
            this.segmentStartNanos = System.nanoTime();
            this.gated = false;
            this.status = Status.PLAYING;
            this.revision++;
            ticking(this, true);
        }
        return true;
    }

    @Override
    public boolean startPaused() {
        if (!super.startPaused()) return false;
        synchronized (this) {
            this.accumulatedMs = 0;
            this.gated = false;
            this.status = Status.PAUSED;
            this.revision++;
            ticking(this, true);
        }
        return true;
    }

    @Override
    public boolean pause(final boolean paused) {
        if (!super.pause(paused)) return false;
        synchronized (this) {
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
            }
            this.revision++;
        }
        return true;
    }

    @Override
    public boolean stop() {
        if (!super.stop()) return false;
        synchronized (this) {
            if (this.status == Status.WAITING || this.status == Status.STOPPED) return false;
            this.accumulatedMs = 0;
            this.gated = false;
            this.status = Status.STOPPED;
            this.revision++;
            ticking(this, false);
        }
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
            if (this.status == Status.WAITING) return false;
            // SCRUBBING A FINISHED/STOPPED CLOCK LANDS PAUSED AT THE POSITION INSTEAD OF STAYING DEAD
            if (this.status == Status.ENDED || this.status == Status.STOPPED) this.status = Status.PAUSED;
            final long d = this.duration;
            this.syncTime(d > 0 ? Math.min(time, d) : time);
        }
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
        // CLAMP/MODULO AGAINST DURATION SO time() NEVER OVERRUNS BETWEEN 50ms TICKS
        return d > 0 ? (this.repeat() ? t % d : Math.min(t, d)) : t;
    }

    @Override
    public boolean speed(final float speed) {
        final float old = this.speed();
        if (!super.speed(speed)) return false;
        synchronized (this) {
            if (this.status == Status.PLAYING && !this.gated) {
                // THE ELAPSED SEGMENT RAN AT THE OUTGOING RATE — REBASING WITH THE NEW ONE WOULD
                // RETROACTIVELY RESCALE TIME THAT ALREADY PASSED AND JUMP THE CLOCK
                this.accumulatedMs = this.computeTime(old);
                this.segmentStartNanos = System.nanoTime();
            }
            if (old != speed) this.revision++;
        }
        return true;
    }

    @Override
    public boolean repeat(final boolean repeat) {
        final boolean old = this.repeat();
        final boolean now = super.repeat(repeat);
        if (old != now) this.revision++;
        return now;
    }

    @Override
    public synchronized void volume(final int volume) {
        final int old = this.volume();
        super.volume(volume);
        if (this.volume() != old) this.revision++;
    }

    @Override
    public synchronized void mute(final boolean mute) {
        if (this.mute() != mute) this.revision++;
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
    public boolean liveSource() {
        // EXPLICIT CLIENT-REPORTED FLAG, PLUS THE ORIGINAL INFERENCE: UNKNOWN DURATION BEHAVES AS LIVE
        return this.live || this.duration <= 0;
    }

    @Override
    public boolean canSeek() {
        return this.duration > 0;
    }

    @Override
    public boolean canPlay() {
        return this.status != Status.ERROR;
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
    public synchronized void release() {
        this.gated = false;
        this.status = Status.STOPPED;
        this.watchers.clear();
        super.release();
    }

    // --- INTERNAL ---

    // POSITION OF THE RUNNING SEGMENT AT THE GIVEN RATE. THE RATE IS EXPLICIT BECAUSE a speed change
    // MUST CLOSE THE SEGMENT AT THE OUTGOING VALUE BEFORE THE NEW ONE APPLIES.
    private long computeTime(final float rate) {
        final long elapsedNanos = System.nanoTime() - this.segmentStartNanos;
        return this.accumulatedMs + (long) (TimeUnit.NANOSECONDS.toMillis(elapsedNanos) * rate);
    }

    // 50ms CLOCK MAINTENANCE: LOOP WRAP AND THE ENDED TRANSITION
    private synchronized void update() {
        if (this.status != Status.PLAYING || this.gated) return;

        final long d = this.duration;
        if (d <= 0) return;
        final long now = this.computeTime(this.speed());
        if (now < d) return;

        if (this.repeat()) {
            // CARRY THE OVERSHOOT ACROSS THE LOOP BOUNDARY — DISCARDING IT SLIPPED THE AUTHORITATIVE
            // CLOCK BY UP TO ONE TICK (50ms) PER LOOP, DESYNCING EVERY CLIENT THAT TRUSTS IT.
            this.accumulatedMs = now % d;
            this.segmentStartNanos = System.nanoTime();
            // THE WRAP REBASES THE TIMELINE — BUMP SO THE BROADCASTER RESENDS WHERE CLIENTS DRIFT MOST
            this.revision++;
        } else {
            this.accumulatedMs = d;
            this.status = Status.ENDED;
            this.revision++;
            ticking(this, false);
            this.invokeStatus(Status.PLAYING, Status.ENDED);
        }
    }

    // PER-SPECTATOR REGISTRY ENTRY. pooled MARKS LOCKSTEP GATE MEMBERSHIP — IMMEDIATE WHEN THE
    // SESSION IS NOT RUNNING, DEFERRED TO THE FIRST READY REPORT FOR MID-PLAYBACK JOINERS.
    private static final class Watcher {
        volatile Status status = Status.LOADING;
        volatile long lastSeenNanos;
        volatile boolean pooled;
    }
}
