package org.watermedia.api.media;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.util.MathUtil;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.bootstrap.WaterMediaModule;
import org.watermedia.tools.ThreadTool;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Base of every playback unit, carrying the shared control surface (volume, mute, speed,
 * repeat, LOD/scaling) and the bridge sync protocol.
 * <p>
 * A player built with a {@link Bridge} joins a synchronized session as a {@link Role#FOLLOWER}:
 * it tracks where the session says playback should be — see {@link #authority()} — and keeps
 * its own playback aligned with it. Received payloads go in through {@link #sync(ByteBuffer)};
 * control calls go out as {@link Control} requests when {@link Config.Capability#CONTROLS} is
 * granted, and are dropped otherwise. The session's truth lives in a {@link ServerMediaPlayer}
 * built with a bridge of its own, the {@link Role#AUTHORITY}.
 */
public abstract sealed class MediaPlayer permits ServerMediaPlayer, FFMediaPlayer, TxMediaPlayer {
    private static final Marker IT = MarkerManager.getMarker(MediaPlayer.class.getSimpleName());
    /** Sentinel for unknown or unset video dimensions. */
    public static final int NO_SIZE = 0;
    /** Sentinel for a missing GPU texture handle. */
    public static final long NO_TEXTURE = 0L;
    /** Sentinel for a missing audio source handle. */
    public static final int NO_SOURCE = 0;
    /** Sentinel for an unknown media duration. */
    public static final long NO_DURATION = 0L;

    // === SHARED 50ms TICKER — DRIVES SERVER CLOCKS, FOLLOWER CORRECTIONS AND AUTHORITY BROADCASTS ===
    // A SINGLE DAEMON THREAD TICKS EVERY REGISTERED PLAYER, SO ALL CORRECTION STATE IS
    // EFFECTIVELY SINGLE-THREADED AND THE BRIDGE FLOW NEVER TOUCHES THE GAME THREAD.
    private static final long TICK_MS = 50;
    private static final Set<MediaPlayer> TICKING = ConcurrentHashMap.newKeySet();
    private static final ScheduledExecutorService TICKER = Executors.newSingleThreadScheduledExecutor(
            ThreadTool.workerFactory("MediaSync", Thread.NORM_PRIORITY - 2)
    );

    // MONOTONIC MILLISECONDS FOR PACING, DEADLINES AND PASSIVE CLOCKS — A WALL-CLOCK ADJUSTMENT MUST
    // NEVER MOVE A PLAYBACK POSITION. ANCHORED AT CLASS LOAD SO VALUES START NEAR ZERO AND GROW
    private static final long EPOCH_NS = System.nanoTime();

    static {
        TICKER.scheduleAtFixedRate(MediaPlayer::tickAll, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    // THE TICKER THREAD, SO A FOLLOWER CAN TELL ITS OWN follow() PASS FROM A USER CALL RACING IT
    private static volatile Thread tickerThread;

    private static void tickAll() {
        tickerThread = Thread.currentThread();
        for (final MediaPlayer player: TICKING) {
            try {
                if (player.armed) player.tick();
            } catch (final Throwable t) {
                if (player.tickWarned) continue;
                player.tickWarned = true;
                // SERVER PLAYERS AND FOLLOWER CLOCKS CARRY NO MRL
                final Object subject = player.mrl != null ? player.mrl.uri() : player.getClass().getSimpleName();
                LOGGER.error(IT, "Sync tick failed for {}", subject, t);
            }
        }
    }

    // FOLLOWER CORRECTION CONSTANTS
    private static final long SEEK_COOLDOWN_NS = 1_000_000_000L; // 1s BETWEEN SEEKS WHILE THE PIPELINE RESETTLES
    private static final long REPORT_NS = 5_000_000_000L;        // UPSTREAM KEEPALIVE SO THE AUTHORITY TTL HOLDS
    private static final long SKIP_STEP_MS = 5_000L;             // forward()/rewind() DISTANCE FOR VIDEO AND MUSIC

    // BASIC PROPERTIES
    final MRL mrl;
    final int sourceIndex;
    final MRL.Source source;
    final GFXEngine gfx;
    final SFXEngine sfx;
    // WRITTEN BY THE CALLER AND BY THE PIPELINE THREADS WHEN THEY RESOLVE THE REAL RENDITION
    volatile MediaQuality quality = WaterMediaConfig.media.defaultQuality;

    // AUDIO PROPERTIES
    private volatile boolean repeat;
    private volatile float volume = 1f;
    private volatile float speed = 1.0f;
    private volatile boolean muted = false;

    // BRIDGE SYNC — bridge IS THE DEV'S BYTE CARRIER (NULL WHEN UNBRIDGED); target IS THE LAST
    // SNAPSHOT A FOLLOWER HEARD, AGED INTO A LIVE POSITION BY authorityTime(). armed GATES THE
    // SHARED TICKER OFF A HALF-CONSTRUCTED INSTANCE — EVERY CONCRETE CTOR CALLS arm() LAST.
    private final Bridge bridge;
    private final Role role;
    private final long watcherId;
    private volatile boolean armed;
    private final Object bridgeLock = new Object();
    private int sending;
    private boolean pendingUnwatch;
    private boolean unwatched;
    private volatile int caps;
    private volatile boolean configured;
    private volatile long aheadMs;    // HALF THE MEASURED ROUND TRIP — SNAPSHOT AGE IN FLIGHT
    private volatile long watchNanos; // NON-ZERO WHILE THE Watch HELLO AWAITS ITS Config ECHO
    private volatile Sync target;     // LAST SNAPSHOT HEARD FROM THE SESSION, NULL UNTIL THE FIRST ONE
    private volatile long targetNanos;// WHEN IT ARRIVED, SO IT CAN BE AGED INTO A CURRENT POSITION
    private volatile boolean sendWarned; // A FAILING BRIDGE IS LOGGED ONCE PER PLAYER

    // FOLLOWER TUNABLE — HOW FAR PLAYBACK MAY DRIFT BEFORE IT IS PULLED BACK
    private volatile long toleranceMs = 1_000;

    // FOLLOWER CORRECTION STATE — TOUCHED ONLY FROM THE SINGLE TICKER THREAD
    private boolean tickWarned; // A FAILING TICK IS LOGGED ONCE PER PLAYER
    private boolean startIssued;
    private long lastSeekNanos;
    private Status lastReport;
    private long lastReportNanos;
    // SET WHILE follow() IMPOSES AUTHORITY STATE, SO THOSE CALLS LAND LOCALLY INSTEAD OF BEING SENT
    // BACK UPSTREAM. ONLY HONOURED ON THE TICKER THREAD: A USER CALL RACING THE PASS STILL FORWARDS
    private volatile boolean applying;

    // STATUS LISTENERS, CONSUMED BY A TERMINAL TRANSITION; TRANSITIONS QUEUE UP AND ONE THREAD DRAINS THEM IN ORDER, SO
    // A CONTROL ISSUED FROM A CALLBACK NEVER DELIVERS ITS TRANSITION AHEAD OF THE REPORTED ONE. GUARDED BY statusEvents
    private final List<StatusListener> statusListeners = new ArrayList<>();
    private final ArrayDeque<Transition> statusEvents = new ArrayDeque<>();
    private boolean statusDraining;

    // FAILURE BEHIND THE CURRENT ERROR STATE: SET THROUGH exception(Throwable), CLEARED BY EVERY launch() WHERE IT RETIRES THE
    // PREVIOUS RUN, SO A LATE FAILURE OF THAT RUN NEVER LANDS ON THE NEW ONE
    volatile Throwable exception;

    // VIDEO UPLOAD SCALING, WRITTEN BY THE CALLER AND READ BY THE PLAYBACK THREADS THROUGH scaled(native, scale, lod.percent()):
    // THE SCALE CAPS EACH AXIS (NO_SIZE = NO CAP, NEVER UPSCALES) AND lod SHRINKS IT FURTHER BY A PERCENTAGE
    volatile int scaleWidth = NO_SIZE;
    volatile int scaleHeight = NO_SIZE;
    volatile LodLevel lod = LodLevel.MAX;

    // NATIVE SOURCE FRAME SIZE BEFORE ANY SCALING — UPDATED BY THE SUBCLASS WHEN IT LEARNS
    // THE DECODED DIMENSIONS, READ BY CALLERS THAT NEED THE UNCAPPED RESOLUTION (e.g. A UI
    // CLAMPING A CUSTOM maxSize TO THE SOURCE).
    volatile int sourceWidth = NO_SIZE;
    volatile int sourceHeight = NO_SIZE;

    // A NON-NULL bridge MAKES THIS PLAYER A FOLLOWER OF A SERVER-SIDE ServerMediaPlayer AUTHORITY (SEE THE CLASS DOCS).
    // MEDIA PLAYERS DECODE AND RENDER, SO THEY ARE CLIENT-ONLY; THE HEADLESS CLOCK BELOW IS THE SERVER-SIDE PLAYER
    MediaPlayer(final MRL mrl, final int sourceIndex, final GFXEngine gfx, final SFXEngine sfx, final Bridge bridge) {
        WaterMedia.checkIsClientSideOrThrow(this.getClass());
        if (mrl == null) throw new IllegalArgumentException("MediaPlayer must have a valid MRL");
        final MRL.Source resolved = mrl.source(sourceIndex);
        if (resolved == null) throw new IllegalArgumentException("Source at index " + sourceIndex + " is not available");
        if (gfx == null && sfx == null) throw new IllegalArgumentException("MediaPlayer must have a valid GFX or SFX resource");
        // A MISSING VIDEO SINK IS WORTH A WARNING; MISSING AUDIO IS THE NORMAL SHAPE OF EVERY IMAGE PLAYER
        if (gfx == null)
            LOGGER.warn(IT, "GFXEngine is null — there will be no video output");
        if (sfx == null)
            LOGGER.debug(IT, "SFXEngine is null — there will be no audio output");

        // INIT PROPERTIES
        this.mrl = mrl;
        this.sourceIndex = sourceIndex;
        this.source = resolved;
        this.gfx = gfx;
        this.sfx = sfx;
        this.bridge = bridge;
        // A PLAYER WITH REAL MEDIA CAN ONLY EVER FOLLOW — THE AUTHORITY ROLE BELONGS TO THE CLOCK
        this.role = bridge != null ? Role.FOLLOWER : Role.SOLO;

        this.watcherId = bridge != null ? ThreadLocalRandom.current().nextLong() : 0L;
    }

    // HEADLESS CONSTRUCTOR FOR ServerMediaPlayer, WHICH ONLY TRACKS TIME PROGRESSION; bridge IS NULL WHEN UNBRIDGED
    MediaPlayer(final Bridge bridge, final Role role) {
        if (role != Role.SOLO && bridge == null) throw new IllegalArgumentException(role + " requires a bridge");
        this.mrl = null;
        this.sourceIndex = -1;
        this.source = null;
        this.gfx = null;
        this.sfx = null;
        this.bridge = bridge;
        this.role = role;

        this.watcherId = role == Role.FOLLOWER ? ThreadLocalRandom.current().nextLong() : 0L;
    }

    // ==========================================================================
    // BRIDGE SYNC
    // ==========================================================================

    // PUBLISHES THIS FULLY-CONSTRUCTED PLAYER TO THE BRIDGE MACHINERY: REGISTERS THE 50ms TICK
    // AND SENDS THE Watch HELLO. MUST BE THE LAST STATEMENT OF EVERY CONCRETE CONSTRUCTOR —
    // NEVER CALLED FROM THIS BASE CLASS, WHERE SUBCLASS FIELDS WOULD STILL BE UNINITIALIZED
    // WHEN THE TICKER FIRES.
    final void arm() {
        synchronized (this.bridgeLock) {
            if (this.armed) return;
            // A SERVER PLAYER NEEDS NO SESSION; A MEDIA PLAYER NEEDS THE LOADED MEDIA MODULE
            if (this.mrl != null && !WaterMedia.loaded()) throw new IllegalStateException("Cannot create a media player before WaterMedia loads");
            this.armed = true;
        }
        if (this.role == Role.FOLLOWER) {
            // BACKDATE THE COOLDOWN SO THE FIRST CORRECTION IS NEVER BLOCKED (nanoTime ORIGIN IS ARBITRARY)
            this.lastSeekNanos = System.nanoTime() - SEEK_COOLDOWN_NS;
            this.watchNanos = System.nanoTime();
            TICKING.add(this);
            try {
                this.send(new Watch(this.watcherId));
            } catch (final RuntimeException | Error failure) {
                TICKING.remove(this);
                throw failure;
            }
        } else if (this.role == Role.AUTHORITY) {
            // TICKS PERMANENTLY FOR AUTO-BROADCAST, HEARTBEAT AND GATE SWEEPS
            TICKING.add(this);
        }
    }

    // REGISTERS/DEREGISTERS A PLAYER ON THE SHARED TICKER. BRIDGED PLAYERS IGNORE DEREGISTRATION —
    // THEY TICK UNTIL release() — SO CLOCK STOP/START CYCLES NEVER SILENCE THE BRIDGE.
    void ticking(final boolean on) {
        synchronized (this.bridgeLock) {
            if (on && this.armed) TICKING.add(this);
            else if (this.role == Role.SOLO) TICKING.remove(this);
        }
    }

    // SHARED-TICKER CALLBACK. THE BASE RUNS THE FOLLOWER ENGINE; ServerMediaPlayer LAYERS THE
    // AUTHORITY CLOCK AND BROADCAST ON TOP.
    void tick() {
        if (!this.armed || this.role != Role.FOLLOWER) return;
        this.applying = true;
        try {
            this.follow();
        } finally {
            // NEVER LEAVE IT LATCHED: A STUCK FLAG WOULD TURN EVERY LATER USER CALL INTO A LOCAL
            // MUTATION AND DESYNC THIS PLAYER FROM THE AUTHORITY FOR GOOD.
            this.applying = false;
        }
    }

    /**
     * Feeds a received bridge payload into this player — the single inbound entry of the
     * sync protocol. Safe to call from any thread (a packet handler, a socket reader): the
     * payload is decoded and validated on the calling thread. Authorities handle it and may
     * reply immediately; followers reconcile playback on their synchronization tick.
     * <p>
     * Followers accept {@link Sync} and {@link Config}; an authority accepts {@link Watch},
     * {@link Report}, {@link Control} and {@link Unwatch}. Packets for the opposite role
     * are ignored.
     * @param payload the packet bytes, consumed
     * @throws IllegalArgumentException if the payload is malformed (see {@link Packet#read(ByteBuffer)})
     */
    public final void sync(final ByteBuffer payload) {
        this.sync(Packet.read(payload));
    }

    /**
     * Feeds an already decoded packet into this player. Use it to inspect traffic before it
     * takes effect — decode with {@link Packet#read(ByteBuffer)}, apply your own rules (say,
     * dropping a {@link Control} from a player without permission) and forward what survives.
     * Safe to call from any thread.
     * @param packet the decoded packet
     */
    public final void sync(final Packet packet) {
        if (packet == null) throw new IllegalArgumentException("Packet cannot be null");
        if (!this.armed) return;
        if (this.role == Role.AUTHORITY) {
            this.upstream(packet);
            return;
        }
        if (this.role == Role.SOLO) {
            // A SOLO PLAYER HAS NO SESSION: ACCEPTING FOLLOWER TRAFFIC WOULD ENROLL CLIENTS NOBODY SWEEPS
            LOGGER.debug(IT, "Ignored {} on a solo player", packet);
            return;
        }
        if (packet instanceof final Sync snapshot) {
            // A SNAPSHOT SUPERSEDES EVERY EARLIER ONE — THERE IS NOTHING TO QUEUE, ONLY THE LATEST
            // MATTERS. AN OLDER REVISION IS REORDERED TRANSPORT NOISE; AN EQUAL ONE IS A HEARTBEAT
            // CARRYING A FRESHER TIMESTAMP, SO IT MUST LAND. THE ARRIVAL STAMP GOES FIRST: A READER
            // SEEING THE NEW SNAPSHOT MUST NEVER PAIR IT WITH THE PREVIOUS ONE'S AGE.
            final Sync known = this.target;
            // SIGNED DISTANCE: CLOCK-SEEDED REVISIONS MAY WRAP, AND A REPLACEMENT AUTHORITY STILL READS AS NEWER
            if (known == null || snapshot.revision() - known.revision() >= 0) {
                this.targetNanos = System.nanoTime();
                this.target = snapshot;
            }
        } else if (packet instanceof final Config config) {
            this.caps = config.capabilities();
            this.configured = true;
            final long sent = this.watchNanos;
            if (sent != 0 && config.watcherId() == this.watcherId) {
                // SNAPSHOTS AGE HALF THE ROUND TRIP IN FLIGHT. STICKY: ONLY A FRESH MEASUREMENT
                // (A NEW Watch/Config EXCHANGE) EVER CHANGES IT.
                this.watchNanos = 0;
                this.aheadMs = (System.nanoTime() - sent) / 2_000_000L;
            }
        } else {
            LOGGER.debug(IT, "Ignored upstream packet on a follower: {}", packet);
        }
    }

    // AUTHORITY-SIDE PACKET HANDLING — OVERRIDDEN BY ServerMediaPlayer; EVERY OTHER PLAYER
    // HAS NO FOLLOWERS TO SERVE AND JUST DROPS IT.
    void upstream(final Packet packet) {
        LOGGER.debug(IT, "Ignored packet on a non-authority player: {}", packet);
    }

    // SENDS A PACKET THROUGH THE DEV'S CARRIER. A BROKEN CARRIER NEVER TEARS DOWN PLAYBACK.
    final void send(final Packet packet) {
        final Bridge bridge = this.bridge;
        if (bridge == null) return;
        final boolean unwatch = packet instanceof Unwatch;
        synchronized (this.bridgeLock) {
            if (unwatch) {
                if (this.unwatched) return;
                if (this.sending != 0) {
                    this.pendingUnwatch = true;
                    return;
                }
                this.pendingUnwatch = false;
                this.unwatched = true;
            } else {
                if (!this.armed) return;
                this.sending++;
            }
        }
        // HOST CALLBACKS RUN WITHOUT OUR LOCKS; THE LAST ADMITTED SEND EMITS THE FINAL UNWATCH.
        try {
            bridge.send(packet.toBytes());
        } catch (final Throwable t) {
            if (!this.sendWarned) LOGGER.error(IT, "Bridge send failed", t);
            this.sendWarned = true;
        } finally {
            if (!unwatch) {
                final boolean finish;
                synchronized (this.bridgeLock) {
                    finish = --this.sending == 0 && this.pendingUnwatch;
                }
                if (finish) this.send(new Unwatch(this.watcherId));
            }
        }
    }

    /** What this player is within its sync session. */
    public final Role role() { return this.role; }

    /**
     * The last state this follower heard from the session. It is a point in the past — use
     * {@link #authorityTime()} for where playback should be right now.
     * @return the latest snapshot, or {@code null} before the first one arrives
     */
    public final Sync authority() { return this.target; }

    /**
     * Where the session says playback should be right now. A running session is extrapolated
     * from the last snapshot at its own rate and folded into the media timeline, so a loop
     * wraps instead of overrunning; a stopped one reports its frozen position. A live session
     * is never folded — its timeline is open-ended.
     * @return the authoritative position in milliseconds, or 0 on a non-follower
     */
    public final long authorityTime() {
        final Sync target = this.target;
        if (target == null) return 0;
        if (target.status() != Status.PLAYING) return target.time();
        // THE SNAPSHOT AGED HALF THE ROUND TRIP IN FLIGHT, AND KEPT AGEING SINCE IT LANDED
        final long elapsed = (System.nanoTime() - this.targetNanos) / 1_000_000L + this.aheadMs;
        final long run = (long) (elapsed * target.speed());
        // SATURATE LIKE THE AUTHORITY: A LIVE POSITION NEAR Long.MAX_VALUE MUST NOT WRAP NEGATIVE
        final long t = run > Long.MAX_VALUE - target.time() ? Long.MAX_VALUE : target.time() + run;
        final long d = target.duration();
        if (d <= 0 || target.live()) return t;
        return target.repeat() ? t % d : Math.min(t, d);
    }

    /**
     * Signed drift between the session and this player in milliseconds; positive means the
     * session is ahead. Always zero on non-followers. On repeating media the drift is computed
     * on the circular timeline, taking the short way around the wrap.
     * @return the current drift in milliseconds
     */
    public final long drift() {
        final Sync target = this.target;
        if (target == null) return 0;
        long drift = this.authorityTime() - this.time();
        final long d = target.duration();
        if (target.repeat() && d > 0 && !target.live()) {
            // LOOPING TIMELINES ARE CIRCULAR — MAP INTO [-d/2, d/2) SO THE WRAP NEVER FAKES A HUGE DRIFT
            drift = Math.floorMod(drift + d / 2, d) - d / 2;
        }
        return drift;
    }

    /**
     * Sets how far a follower may drift from the session before it is pulled back with a
     * {@link #seekQuick(long)}. Correction is deliberately not millimetric: the alternative,
     * trimming the playback rate to converge smoothly, is perceived as the video randomly
     * running slow, which is a worse experience than an occasional jump. No effect on
     * non-followers.
     * @param ms drift tolerance in milliseconds, {@code > 0}
     */
    public final void tolerance(final long ms) {
        if (ms <= 0) throw new IllegalArgumentException("Tolerance must be positive");
        this.toleranceMs = ms;
    }

    /** Returns the follower drift tolerance in milliseconds. */
    public final long tolerance() { return this.toleranceMs; }

    // FOLLOWER ENGINE, RUNS ON THE SHARED TICKER: REPORTS LOCAL TRANSITIONS UPSTREAM, MIRRORS THE
    // SESSION ONTO THIS PLAYER AND PULLS PLAYBACK BACK WHEN IT DRIFTS PAST THE TOLERANCE.
    // `applying` MARKS THE WHOLE PASS AS AUTHORITY STATE SO THE ORDINARY CONTROL METHODS ACT
    // LOCALLY INSTEAD OF SENDING THE CORRECTION BACK UPSTREAM.
    private void follow() {
        final Sync authority = this.target;
        if (authority == null) return; // NOTHING HEARD FROM THE SESSION YET
        final Status target = authority.status();
        Status current = this.status();
        final long now = System.nanoTime();

        // UPSTREAM REPORT: EVERY LOCAL TRANSITION, PLUS A KEEPALIVE SO THE AUTHORITY TTL HOLDS.
        // 50ms POLLING NATURALLY DEBOUNCES SUB-TICK STATUS BLIPS THAT WOULD FLAP A LOCKSTEP GATE.
        if (current != this.lastReport || now - this.lastReportNanos >= REPORT_NS) {
            this.lastReport = current;
            this.lastReportNanos = now;
            this.send(new Report(this.watcherId, current, this.duration(), this.live()));
        }

        // RE-SEND THE HELLO UNTIL A CONFIG ANSWERS IT — LOSSY OR LATE-WIRED TRANSPORTS MAY DROP THE FIRST
        final long watch = this.watchNanos;
        if (watch != 0 && now - watch >= REPORT_NS) {
            this.watchNanos = now;
            this.send(new Watch(this.watcherId));
        }
        if (!this.armed) return;

        // VOLUME/MUTE MIRROR ONLY UNDER THE VOLUME CAPABILITY — OTHERWISE CLIENT-LOCAL, LIKE LOD
        if ((this.caps & Config.Capability.VOLUME.bit) != 0) {
            if (this.volume() != authority.volume()) this.volume(authority.volume());
            if (this.mute() != authority.mute()) this.mute(authority.mute());
        }

        final boolean dormant = current == Status.WAITING || current == Status.STOPPED || current == Status.ENDED;
        if (!dormant) this.startIssued = false;

        // DISCRETE STATE MIRROR — THE SESSION DICTATES START/PAUSE/STOP TRANSITIONS. ERROR PLAYERS
        // ARE LEFT ALONE: RETRY POLICY BELONGS TO THE OWNER. A LOCKSTEP GATE REACHES US AS
        // BUFFERING AND HOLDS THIS PLAYER LIKE A PAUSE UNTIL THE AUTHORITY RELEASES IT.
        switch (target) {
            case PLAYING -> {
                if (dormant) {
                    // START ONCE PER DORMANCY — FF PIPELINES PUBLISH STATUS ASYNC, RE-CALLING START
                    // EVERY TICK WOULD RESTART THE LIFECYCLE FOREVER. SKIP THE TAIL: WITH LESS THAN
                    // THE TOLERANCE OF NON-REPEAT MEDIA LEFT, THE SESSION ENDS BEFORE WE FINISH LOADING.
                    // A PLAYER THAT ALREADY ENDED RESTARTS ONLY WHEN THE SESSION SITS MEANINGFULLY
                    // BEFORE ITS OWN MEDIA'S END — GATING ON THE SESSION DURATION REPLAYED IT FOREVER
                    // WHENEVER THE TWO DIVERGED (BAD LATCH, MIXED VARIANTS).
                    final boolean go;
                    if (current == Status.ENDED) {
                        final long end = this.duration() > 0 ? this.duration() : authority.duration();
                        go = end > 0 && this.authorityTime() < Math.max(end - this.toleranceMs, this.toleranceMs);
                    } else {
                        go = authority.repeat() || authority.duration() <= 0
                                || authority.duration() - this.authorityTime() > this.toleranceMs;
                    }
                    if (!this.startIssued && go) {
                        this.start();
                        this.startIssued = true;
                    }
                    return;
                }
                if (current == Status.PAUSED) this.resume();
            }
            case PAUSED, BUFFERING -> {
                if (dormant) {
                    if (!this.startIssued) {
                        this.startPaused();
                        this.startIssued = true;
                    }
                    return;
                }
                if (current == Status.PLAYING) this.pause();
            }
            case STOPPED -> {
                // ENDED IS ALREADY DORMANT AND ITS PIPELINE IS GONE — stop() THERE CANNOT TRANSITION
                // ANYTHING AND WOULD RE-FIRE EVERY TICK FOREVER
                if (current != Status.STOPPED && current != Status.WAITING && current != Status.ENDED) this.stop();
                return;
            }
            // ENDED: LET THE PLAYER REACH ITS OWN END NATURALLY; WAITING/OTHERS: NOTHING TO FOLLOW YET
            default -> { return; }
        }

        // REPEAT AND RATE ARE SESSION STATE; NO TIME AUTHORITY ON LIVE SOURCES, AND NO CORRECTION
        // WHILE THE LOCAL PIPELINE IS STILL CATCHING UP
        if (this.repeat() != authority.repeat()) this.repeat(authority.repeat());
        if (this.speed() != authority.speed()) this.speed(authority.speed());
        if (authority.live() || authority.duration() <= 0) return;
        current = this.status();
        if (current == Status.LOADING || current == Status.BUFFERING) return;
        if (current != Status.PLAYING && current != Status.PAUSED) return;

        // ONE CORRECTION, ONE THRESHOLD: PAST THE TOLERANCE, JUMP TO WHERE THE SESSION IS. TRIMMING
        // THE PLAYBACK RATE TO CONVERGE SMOOTHLY WAS TRIED AND DROPPED — IT READS AS THE VIDEO
        // RANDOMLY RUNNING SLOW, AND THE CLIENT ONLY EVER FALLS BEHIND ANYWAY (THE AUTHORITY IS A
        // BARE CLOCK: NO DECODING, NO BUFFERS), SO SMOOTHING THE OTHER DIRECTION BOUGHT NOTHING.
        // RATE-LIMITED SO A RESETTLING PIPELINE ISN'T SEEKED AGAIN BEFORE IT REPORTS ITS POSITION.
        if (Math.abs(this.drift()) > this.toleranceMs && now - this.lastSeekNanos >= SEEK_COOLDOWN_NS) {
            this.seekQuick(this.authorityTime());
            this.lastSeekNanos = now;
        }
    }

    // HANDS A CONTROL CALL TO THE AUTHORITY WHEN THIS PLAYER FOLLOWS ONE, AND ANSWERS WHETHER THE
    // CALL WAS TAKEN OVER — true MEANS THE CALLER MUST NOT TOUCH LOCAL STATE. A CALL RACING THE
    // HANDSHAKE STILL GOES OUT: THE AUTHORITY IS THE REAL ENFORCEMENT POINT, AND SWALLOWING THE
    // USER'S FIRST CLICK BECAUSE THE CONFIG HASN'T LANDED IS WORSE THAN ASKING FOR TOO MUCH.
    private boolean request(final Control.Op op, final long value) {
        if (!this.armed) return true;
        if (this.role != Role.FOLLOWER || (this.applying && Thread.currentThread() == tickerThread)) return false;
        if (this.configured && (this.caps & Config.Capability.CONTROLS.bit) == 0) {
            LOGGER.debug(IT, "Dropped {} — controls are locked on this bridged player", op);
            return true;
        }
        this.send(new Control(this.watcherId, op, value));
        return true;
    }

    /**
     * Whether the sync authority granted the given capability to this follower. Always false
     * on non-followers and until the authority answers the initial hello — useful to reflect
     * the session rules in a UI (e.g. disabling controls followers are not allowed to drive).
     * @param capability the capability to check
     * @return true when granted
     */
    public final boolean granted(final Config.Capability capability) {
        return this.configured && (this.caps & capability.bit) != 0;
    }

    // ==========================================================================
    // GENERAL CONTROL SURFACE
    // ==========================================================================

    /**
     * Selects the quality to use for this player; if that quality is unavailable, the source
     * resolves it to the closest available one. A playing video or music backend switches its
     * source on the playback loop while preserving the current timestamp; a picture player keeps
     * showing the frames it decoded and applies the selection on its next {@link #start()}.
     * @param quality the new quality to use
     * @throws IllegalArgumentException if {@code quality} is {@code null}
     */
    public void quality(final MediaQuality quality) {
        if (quality == null) throw new IllegalArgumentException("Quality cannot be null.");
        this.quality = quality;
    }

    /**
     * Returns the currently selected quality. It may be {@link MediaQuality#UNKNOWN} until source dimensions are known.
     * @return the selected quality
     */
    public MediaQuality quality() { return this.quality; }

    // RESOLVES A STILL-UNKNOWN QUALITY ONCE THE DECODED SIZE IS KNOWN, RELABELING THE URI IN THE SHARED MRL (OR FINDING IT ALREADY
    // RELABELED BY AN EARLIER RUN); WHEN ANOTHER URI HOLDS THAT QUALITY OR THE MRL RELOADED, IT STAYS UNKNOWN
    final void resolveQuality(final int width, final int height) {
        if (this.quality != MediaQuality.UNKNOWN) return;
        final MediaQuality real = MediaQuality.of(width, height);
        final URI uri = this.source.uri(MediaQuality.UNKNOWN);
        if (!this.mrl.moveQuality(this.sourceIndex, uri, real)) return;
        LOGGER.info(IT, "Moved URI {} from quality {} to {}", uri, MediaQuality.UNKNOWN, real);
        this.quality = real;
    }

    /** The media reference this player renders. */
    public MRL mrl() { return this.mrl; }

    /** Index of the {@link MRL.Source} within {@link #mrl()} this player renders. */
    public int sourceIndex() { return this.sourceIndex; }

    /** The resolved source within {@link #mrl()} this player renders. */
    public MRL.Source source() { return this.source; }

    /**
     * Indicates if the media player has video support enabled.
     * @return true if video support is enabled, false otherwise.
     */
    public boolean video() { return this.gfx != null; }

    /**
     * Indicates if the media player has audio support enabled.
     * @return true if audio support is enabled, false otherwise.
     */
    public boolean audio() { return this.sfx != null; }

    /**
     * Returns the width of the video in pixels, as uploaded to the GPU
     * (after any {@link #scale(int, int)} / {@link #lod(LodLevel)} downscale).
     * @return the width of the video, or {@link MediaPlayer#NO_SIZE NO_SIZE} if not available.
     */
    public final int width() { return this.gfx == null ? NO_SIZE : this.gfx.width(); }

    /**
     * Returns the height of the video in pixels, as uploaded to the GPU
     * (after any {@link #scale(int, int)} / {@link #lod(LodLevel)} downscale).
     * @return the height of the video, or {@link MediaPlayer#NO_SIZE NO_SIZE} if not available.
     */
    public final int height() { return this.gfx == null ? NO_SIZE : this.gfx.height(); }

    /**
     * Returns the native width of the source video in pixels, before any
     * {@link #scale(int, int)} / {@link #lod(LodLevel)} downscale is applied.
     * @return the source width, or {@link MediaPlayer#NO_SIZE NO_SIZE} if not yet known.
     */
    public final int sourceWidth() { return this.sourceWidth; }

    /**
     * Returns the native height of the source video in pixels, before any
     * {@link #scale(int, int)} / {@link #lod(LodLevel)} downscale is applied.
     * @return the source height, or {@link MediaPlayer#NO_SIZE NO_SIZE} if not yet known.
     */
    public final int sourceHeight() { return this.sourceHeight; }

    /**
     * Caps the dimensions of the video frames uploaded to the GPU.
     * Each axis is capped independently to the requested maximum: a frame larger than the
     * cap on either axis is downscaled by the player before upload, and frames are never
     * upscaled. Pass dimensions matching the source aspect ratio to avoid distortion.
     * This trades a small CPU scaling cost for less data uploaded to the GPU and smaller
     * textures.
     * <p>
     * The cap is further reduced by the current {@link LodLevel}. Changes apply on the fly
     * to the next uploaded frame without interrupting playback; already uploaded frames
     * (static images, preloaded animations) keep their size until the next {@link #start()}.
     * @param width maximum upload width in pixels, or {@link MediaPlayer#NO_SIZE NO_SIZE} for no limit
     * @param height maximum upload height in pixels, or {@link MediaPlayer#NO_SIZE NO_SIZE} for no limit
     * @throws IllegalArgumentException if width or height is negative
     */
    public void scale(final int width, final int height) {
        if (width < 0 || height < 0) throw new IllegalArgumentException("Dimensions cannot be negative.");
        this.scaleWidth = width;
        this.scaleHeight = height;
    }

    /**
     * Returns the maximum upload width set by {@link #scale(int, int)}.
     * @return the maximum width in pixels, or {@link MediaPlayer#NO_SIZE NO_SIZE} when unlimited.
     */
    public int scaleWidth() { return this.scaleWidth; }

    /**
     * Returns the maximum upload height set by {@link #scale(int, int)}.
     * @return the maximum height in pixels, or {@link MediaPlayer#NO_SIZE NO_SIZE} when unlimited.
     */
    public int scaleHeight() { return this.scaleHeight; }

    /**
     * Sets the level of detail for video uploads.
     * Each level shrinks the effective maximum video dimensions to a percentage of their
     * value (see {@link LodLevel}), so distant or less important media decodes, scales and
     * uploads fewer pixels. The change is applied on the fly to the next uploaded frame
     * without interrupting playback.
     * @param lod the new level of detail
     * @throws IllegalArgumentException if lod is null
     * @see #scale(int, int)
     */
    public void lod(final LodLevel lod) {
        if (lod == null) throw new IllegalArgumentException("LOD level cannot be null.");
        this.lod = lod;
    }

    /**
     * Returns the current level of detail. Always starts at {@link LodLevel#MAX MAX}.
     * @return the current level of detail
     */
    public LodLevel lod() { return this.lod; }

    /**
     * Returns the GPU handle of the final RGBA frame texture.
     * <p>
     * The handle always refers to a resolved RGBA texture. Frames decoded in planar or packed
     * layouts (YUV, YUVA, NV12/NV21, YUYV/UYVY, GRAY) are converted to RGBA on the GPU by the
     * backing engine before this handle is valid; direct formats (BGRA, RGBA, RGB) are exposed as
     * is. The concrete handle type depends on the engine — an OpenGL texture name for the GL engine,
     * or a {@code VkImageView} handle for the Vulkan engine.
     * <p>
     * <b>Pipeline caution.</b> Read this fresh on every draw and sample it within (or right after)
     * the engine's render cycle on the render thread. The handle may be reallocated on a resolution
     * or LOD change, and using it earlier, from an unrelated render stage, or caching it across
     * frames can sample a stale, half-converted, or freshly allocated (undefined contents) texture.
     * @return the RGBA texture handle, or {@link MediaPlayer#NO_TEXTURE NO_TEXTURE} if no frame has been produced yet
     * @see GFXEngine#texture()
     */
    public long texture() { return this.gfx == null ? NO_TEXTURE : this.gfx.texture(); }

    /**
     * Returns the audio source handle exposed by the backing {@link SFXEngine}.
     * The concrete handle type depends on the engine (an OpenAL source ID for OpenAL,
     * zero for Java Sound).
     * @return the audio source handle, or {@link MediaPlayer#NO_SOURCE NO_SOURCE} if audio is not supported.
     */
    public int audioSource() { return this.sfx != null ? this.sfx.source() : NO_SOURCE; }

    /** Whether the audio engine was created for mono spatial playback. */
    public boolean spatial() { return this.sfx != null && this.sfx.spatial(); }

    /** Last applied spatial state, or null when no spatial environment is active. */
    public SFXEngine.SpatialAudio spatialAudio() { return this.sfx != null ? this.sfx.spatialAudio() : null; }

    /**
     * Applies position and effects on the host's sound executor with the source's OpenAL context current.
     * Passing null restores dry listener-relative playback. Spatial controls are local to this listener.
     * @return false if the engine does not support spatial audio or has been released
     */
    public boolean spatialAudio(final SFXEngine.SpatialAudio audio) { return this.sfx != null && this.sfx.spatialAudio(audio); }

    /**
     * Moves to the previous frame of the video. Refused on a follower — stepping frames
     * against a synced timeline is meaningless.
     * @return true if the operation was successful, false otherwise.
     */
    public boolean previousFrame() { return this.role != Role.FOLLOWER; }

    /**
     * Moves to the next frame of the video. Refused on a follower — stepping frames
     * against a synced timeline is meaningless.
     * @return true if the operation was successful, false otherwise.
     */
    public boolean nextFrame() { return this.role != Role.FOLLOWER; }

    /**
     * Sets the volume of the audio playback, clamped between 0 and 100.
     * Volume and {@link #mute(boolean)} are independent controls: changing the volume
     * never alters the mute state, and audio stays silent while muted.
     * On a follower with the {@link Config.Capability#VOLUME} capability the authority
     * re-imposes its own value on the next tick.
     * @param volume the desired volume level (0-100)
     */
    public void volume(final int volume) {
        this.volume = MathUtil.clamp(volume, 0, 100) / 100f;
        // MUTE IS AN INDEPENDENT CONTROL — APPLY THE GAIN THROUGH THE MUTE GATE, DON'T FLIP IT HERE
        if (this.sfx != null) this.sfx.volume(this.muted ? 0.0f : this.volume);
    }

    /**
     * Returns the current volume level as a percentage (0-100).
     * @return the current volume level (0-100), mute state doesn't affect the volume level
     */
    public int volume() { return Math.round(this.volume * 100); }

    /**
     * Mutes or unmutes the audio playback.
     * When muted, the volume is internally set to 0.0f,
     * and when unmuted, the volume is restored to the previous level.
     * @param mute true to mute the audio, false to unmute
     */
    public void mute(final boolean mute) {
        this.muted = mute;
        if (this.sfx != null) this.sfx.volume(mute ? 0.0f : this.volume);
    }

    /**
     * Returns the current mute status of the audio playback.
     * @see MediaPlayer#mute(boolean)
     * @return true if the audio is muted, false otherwise.
     */
    public boolean mute() { return this.muted; }

    // ==========================================================================
    // SESSION CONTROLS
    // ==========================================================================
    // EVERY BOOLEAN CONTROL ANSWERS ONE QUESTION: DID THIS CALL CHANGE THIS PLAYER'S OWN STATE? false
    // COVERS A CALL FORWARDED TO THE AUTHORITY, DROPPED BY A LOCKED SESSION, REFUSED OR MADE AFTER release()

    /**
     * Starts media playback from the beginning, recovering from any previous state
     * (stopped, ended or error). An already-playing player restarts. Non-blocking.
     * @return true if a local run was launched, false when the call was forwarded to the
     *         sync authority (see {@link #sync(ByteBuffer)}), dropped or refused
     */
    public final boolean start() {
        return !this.request(Control.Op.START, 0L) && this.launch(false, -1L);
    }

    /**
     * Starts media playback like {@link #start()} but landing in a paused state, also clearing a
     * previous failure. Not equivalent to {@code start()} followed by {@link #pause()}: those two
     * calls race the pipeline setup, so the pause intent is latched before playback begins. Non-blocking.
     * @return true if a local run was launched, false when the call was forwarded, dropped or refused
     * @see MediaPlayer#start()
     */
    public final boolean startPaused() {
        return !this.request(Control.Op.START_PAUSED, 0L) && this.launch(true, -1L);
    }

    // LAUNCHES A LOCAL RUN, PAUSED OR PLAYING, FROM THE BEGINNING (seekMs < 0) OR AT seekMs, AND CLEARS exception WHERE IT
    // RETIRES THE PREVIOUS RUN; START CONTROLS AND RESTARTS FROM ENDED/STOPPED CALL IT. FALSE WHEN RELEASED
    abstract boolean launch(final boolean paused, final long seekMs);

    /**
     * Resumes media playback from the current position.
     * @see #pause(boolean)
     * @return true if the player left the paused state, false otherwise
     */
    public boolean resume() { return this.pause(false); }

    /**
     * Pauses media playback at the current position.
     * @see #pause(boolean)
     * @return true if the player entered the paused state, false otherwise
     */
    public boolean pause() { return this.pause(true); }

    /**
     * Pauses or resumes media playback. A pause while loading is latched and applied when the
     * pipeline opens; an ended, stopped, waiting or failed player has nothing to pause or resume
     * and answers false (use {@link #start()} or {@link #togglePlay()} to play it again).
     * @param paused true to pause the media playback, false to resume
     * @return true if the local state changed, false when forwarded, dropped, already there or impossible
     */
    public boolean pause(final boolean paused) { return !this.request(Control.Op.PAUSE, paused ? 1L : 0L); }

    /**
     * Stops media playback, tearing the pipeline down and resetting the position to the beginning.
     * Every player, a failed one included, lands in {@link Status#STOPPED} with {@link #time()} at 0.
     * Non-blocking.
     * @return true if a transition to stopped happened (or was scheduled on the playback thread),
     *         false when the player was already stopped, never started, forwarded or dropped
     */
    public boolean stop() { return !this.request(Control.Op.STOP, 0L); }

    /**
     * Toggles playback: a playing (or buffering) player pauses, a paused one resumes, and an ended
     * or stopped one starts again from the beginning like {@link #start()}.
     * @return true if the local state changed, false when forwarded, dropped or there is nothing to toggle
     */
    public boolean togglePlay() { return !this.request(Control.Op.TOGGLE, 0L); }

    /**
     * Seeks to a specific time in the media, clamped to the known duration. Seeking an ended or
     * stopped player restarts it paused at the target; a waiting, failed or non-seekable one
     * answers false.
     * @param time the time to seek to, in milliseconds.
     * @return true if the seek was taken locally, false when forwarded, dropped or impossible
     */
    public boolean seek(final long time) { return !this.request(Control.Op.SEEK, time); }

    /**
     * Returns the current playback time in milliseconds: 0 before the first frame and after
     * {@link #stop()}, the duration after the media ended.
     * @return the current playback time in milliseconds
     */
    public abstract long time();

    /**
     * Skips forward or backward by a specific time in the media. A positive value skips forward,
     * a negative one backward; the sum saturates instead of overflowing.
     * @param time the time to skip, in milliseconds.
     * @return true if the seek was taken locally, false otherwise (see {@link #seek(long)})
     */
    public boolean skipTime(final long time) {
        final long now = this.time();
        final long target = (time > 0 && now > Long.MAX_VALUE - time) ? Long.MAX_VALUE : Math.max(0L, now + time);
        return this.seek(target);
    }

    /**
     * Quickly seeks to a specific time in the media without precise accuracy.
     * This method is useful for fast seeking operations where exact frame accuracy is not required.
     * @param time the time to seek to, in milliseconds.
     * @return true if the seek was taken locally, false otherwise (see {@link #seek(long)})
     */
    public boolean seekQuick(final long time) { return !this.request(Control.Op.SEEK_QUICK, time); }

    /**
     * Skips forward 5 seconds in the media. Players with a finer natural step
     * (e.g. animated images) may override the distance.
     * @return true if the seek was taken locally, false otherwise (see {@link #seek(long)})
     */
    public boolean forward() { return this.skipTime(SKIP_STEP_MS); }

    /**
     * Skips backward 5 seconds in the media. Players with a finer natural step
     * (e.g. animated images) may override the distance.
     * @return true if the seek was taken locally, false otherwise (see {@link #seek(long)})
     */
    public boolean rewind() { return this.skipTime(-SKIP_STEP_MS); }

    /**
     * Frame rate of the video stream being played, a float because 29.97 exists.
     * @return the frames per second, or 0 until the rate is known or when the media has no video
     */
    public abstract float fps();

    /**
     * Returns the current playback speed.
     * A speed of 1.0f indicates normal playback speed.
     * A speed greater than 1.0f indicates faster playback,
     * while a speed less than 1.0f indicates slower playback.
     * @return the current playback speed.
     */
    public float speed() { return this.speed; }

    /**
     * Sets the playback speed.
     * A speed of 1.0f indicates normal playback speed.
     * A speed greater than 1.0f indicates faster playback,
     * while a speed less than 1.0f indicates slower playback.
     * The speed must be within the range (0.0f, 4.0f].
     * @param speed the desired playback speed.
     * @return true if the speed applies locally, false when the value is out of range, the call was
     *         forwarded or dropped, the player cannot change speed ({@link #canSpeed()}) or the
     *         audio engine refused the new rate.
     */
    public boolean speed(final float speed) {
        // NaN FAILS BOTH COMPARISONS, WHICH IS THE POINT — IT MUST NEVER REACH THE CLOCKS
        if (!(speed > 0f && speed <= 4.0f)) return false;
        if (this.request(Control.Op.SPEED, Float.floatToIntBits(speed) & 0xFFFFFFFFL)) return false;
        // RE-REQUESTING THE CURRENT SPEED IS A SUCCESSFUL NO-OP EVEN WHEN LOCKED (e.g. 1.0x ON SETUP)
        if (speed == this.speed) return true;
        // THE AUDIO ENGINE APPLIES THE RATE FIRST; THE CLOCKS FOLLOW ONLY A SPEED IT ACCEPTED
        if (!this.canSpeed() || (this.sfx != null && !this.sfx.speed(speed))) return false;
        this.speed = speed;
        return true;
    }

    /**
     * Returns the total duration of the media in milliseconds.
     * @return the total duration in milliseconds, or {@link MediaPlayer#NO_DURATION NO_DURATION}
     *         when unknown or not applicable (e.g. a live stream).
     */
    public abstract long duration();

    /**
     * Indicates whether the media should repeat playback when it reaches the end.
     */
    public boolean repeat() { return this.repeat; }

    /**
     * Sets whether the media should repeat playback when it reaches the end. A timed still image
     * (see {@link #displayTime(long)}) never loops.
     * @param repeat true to enable repeat playback, false to disable
     * @return true if the setting applied locally, false when forwarded to the authority or dropped
     */
    public boolean repeat(final boolean repeat) {
        if (this.request(Control.Op.REPEAT, repeat ? 1L : 0L)) return false;
        this.repeat = repeat;
        return true;
    }

    /**
     * Returns the current status of the media player.
     * @return the current status of the media player.
     */
    public abstract Status status();

    /**
     * Adds a status listener; adding one twice keeps it once. Listeners hear every real transition,
     * including the ones a restart, {@link #stop()} and {@link #release()} cause, so playlist
     * consumers advance without polling {@link #status()}; the transition into
     * {@link Status#STOPPED}, {@link Status#ENDED} or {@link Status#ERROR} consumes them, so a
     * consumer registers once per playback and, when it restarts the player from that terminal
     * callback, registers again from the callback itself. Transitions are delivered one at a time
     * and in the order the statuses changed, on whichever thread drove the first of them: a playback
     * thread, the shared sync ticker, or the caller of {@link #status()}, {@link #time()} or
     * {@link #texture()} on image players whose clock resolves the end lazily. A listener never runs
     * under a lock of the player; {@link #release()} delivers its transitions once it finishes and
     * the engines are freed. Keep it short, never block, and never call {@link #release()} from it.
     * @param listener the listener to add
     * @throws IllegalArgumentException when {@code listener} is null
     */
    public void onStatus(final StatusListener listener) {
        if (listener == null) throw new IllegalArgumentException("Listener cannot be null");
        synchronized (this.statusEvents) {
            if (!this.statusListeners.contains(listener)) this.statusListeners.add(listener);
        }
    }

    /**
     * The failure behind the current {@link Status#ERROR ERROR} state, useful to surface the real
     * cause (bad URL, unsupported codec, network failure) instead of a bare error flag.
     * @return the last playback failure, or {@code null} when none occurred since the current run started (through
     *         {@link #start()}, {@link #startPaused()}, or a {@link #togglePlay()} or {@link #seek(long)} restart)
     */
    public final Throwable exception() { return this.exception; }

    // RECORDS THE FAILURE BEHIND AN ERROR TRANSITION. FIRST FAILURE OF A RUN WINS — LATER ONES
    // ARE USUALLY CASCADE NOISE OF THE ROOT CAUSE. EVERY launch() CLEARS IT FOR THE NEXT RUN.
    final void exception(final Throwable t) {
        if (this.exception == null) this.exception = t;
    }

    // QUEUES A REAL TRANSITION WITHOUT DELIVERING IT. CALL IT UNDER THE LOCK THAT WROTE THE STATUS, SO TWO WRITERS QUEUE IN THE
    // ORDER THEY WROTE, THEN deliverStatus() ONCE NO PLAYER LOCK IS HELD
    final void queueStatus(final Status prev, final Status next) {
        if (prev == next) return;
        synchronized (this.statusEvents) {
            this.statusEvents.add(new Transition(prev, next));
        }
    }

    // DELIVERS THE QUEUE IN ORDER UNLESS ANOTHER THREAD ALREADY DRAINS IT. A TERMINAL TRANSITION CONSUMES THE LISTENERS
    // BEFORE FIRING, SO ONE ADDED FROM ITS CALLBACK SURVIVES; FAILURES ARE SWALLOWED
    final void deliverStatus() {
        synchronized (this.statusEvents) {
            if (this.statusDraining || this.statusEvents.isEmpty() || this.holdStatus()) return;
            this.statusDraining = true;
        }
        while (true) {
            final Transition transition;
            final StatusListener[] listeners;
            synchronized (this.statusEvents) {
                transition = this.holdStatus() ? null : this.statusEvents.poll();
                if (transition == null) {
                    this.statusDraining = false;
                    return;
                }
                listeners = this.statusListeners.toArray(new StatusListener[0]);
                if (transition.terminal()) this.statusListeners.clear();
            }
            for (final StatusListener listener: listeners) {
                try {
                    listener.onStatus(this, transition.previous(), transition.current());
                } catch (final Throwable t) {
                    LOGGER.error(IT, "Status listener failed", t);
                }
            }
        }
    }

    // TRUE WHILE A RELEASE JOINS THE PIPELINE: NO THREAD IT WAITS FOR MAY RUN A LISTENER (ONE BLOCKED ON THE RELEASING THREAD WOULD
    // STALL THE JOIN), SO TRANSITIONS STAY QUEUED AND THE RELEASE DELIVERS THEM WHEN IT FINISHES
    boolean holdStatus() { return false; }

    // LEAVES THE BRIDGE BEFORE A RELEASE PUBLISHES STOPPED, SO A CONTROL A LISTENER ISSUES NEVER REACHES THE AUTHORITY
    final void disarm() {
        synchronized (this.bridgeLock) {
            this.armed = false;
            TICKING.remove(this);
        }
    }

    // A STATUS TRANSITION WAITING FOR DELIVERY; THE TERMINAL ONES CONSUME THE LISTENERS
    private record Transition(Status previous, Status current) {
        boolean terminal() { return this.current == Status.STOPPED || this.current == Status.ENDED || this.current == Status.ERROR; }
    }

    /** Hears a real status transition of a player; see {@link #onStatus(StatusListener)} for the threading and lifetime rules. */
    @FunctionalInterface
    public interface StatusListener {
        void onStatus(MediaPlayer player, Status previous, Status current);
    }

    /**
     * Sets how long a still image should remain displayed before the player transitions to
     * {@link Status#ENDED}; a timed still ignores {@link #repeat()}. Applies to image players and
     * to single-frame sources that fall into the video backend; a server clock has no media and
     * ignores it. A value {@code <= 0} shows the still until stopped.
     * @param ms display duration in milliseconds
     */
    public void displayTime(final long ms) {}

    /**
     * Returns the display duration configured via {@link #displayTime(long)}.
     * @return the display duration in milliseconds, or 0 when unlimited or not applicable
     */
    public long displayTime() { return 0; }

    /**
     * Check if the media player equals to {@link Status#WAITING WAITING}
     * @return true if the media player is in WAITING status, false otherwise.
     */
    public boolean waiting() { return this.status() == Status.WAITING; }

    /**
     * Check if the media player equals to {@link Status#LOADING LOADING}
     * @return true if the media player is in LOADING status, false otherwise.
     */
    public boolean loading() { return this.status() == Status.LOADING; }

    /**
     * Check if the media player equals to {@link Status#BUFFERING BUFFERING}
     * @return true if the media player is in BUFFERING status, false otherwise.
     */
    public boolean buffering() { return this.status() == Status.BUFFERING; }

    /**
     * Best-effort fill of the playback read-ahead buffers as a percentage (0-100).
     * Complements {@link #buffering()}: while the player loads or buffers this reports how full
     * its pipeline is, so a UI can show real progress instead of an indeterminate spinner; a
     * pipeline that is playing or paused is keeping up and reports 100. Players without a
     * streaming pipeline (images, server clocks) report 100 whenever they {@link #canPlay()}.
     * @return the buffered percentage (0-100)
     */
    public int buffered() { return this.canPlay() ? 100 : 0; }

    /**
     * Check if the media player equals to {@link Status#PAUSED PAUSED}
     * @return true if the media player is in PAUSED status, false otherwise.
     */
    public boolean paused() { return this.status() == Status.PAUSED; }

    /**
     * Check if the media player equals to {@link Status#PLAYING PLAYING}
     * @return true if the media player is in PLAYING status, false otherwise.
     */
    public boolean playing() { return this.status() == Status.PLAYING; }

    /**
     * Check if the media player equals to {@link Status#STOPPED STOPPED}
     * @return true if the media player is in STOPPED status, false otherwise.
     */
    public boolean stopped() { return this.status() == Status.STOPPED; }

    /**
     * Check if the media player equals to {@link Status#ENDED ENDED}
     * @return true if the media player is in ENDED status, false otherwise.
     */
    public boolean ended() { return this.status() == Status.ENDED; }

    /**
     * Check if the media player equals to {@link Status#ERROR ERROR}
     * @return true if the media player is in ERROR status, false otherwise.
     */
    public boolean error() { return this.status() == Status.ERROR; }

    /**
     * Indicates if the media uri is a live stream. Live streams do not support seeking and have
     * an indefinite duration. False until the source is open and says otherwise.
     * @return true if the media uri is a live stream, false otherwise.
     */
    public abstract boolean live();

    /**
     * Indicates if the media player supports seeking operations: false until the source is open,
     * on live streams and on still images.
     * @return true if seeking is supported, false otherwise.
     */
    public abstract boolean canSeek();

    /**
     * Indicates if the media is loadable and playable: true from the first successful load of the
     * source, through ended and stopped states (where {@link #start()} plays it again), until the
     * player fails or is released.
     * @return true if the media can be played, false otherwise.
     */
    public abstract boolean canPlay();

    /**
     * Indicates if the playback speed can be changed. A pure query without native calls, safe on
     * any thread and every frame. Live streams cannot change speed, and neither can media whose
     * audio engine reports {@link SFXEngine#canSpeed()} false — scaling the timeline against
     * audio stuck at 1.0× would desync the playback clock.
     * @return true if {@link #speed(float)} can take effect, false otherwise.
     */
    public boolean canSpeed() {
        return !this.live() && (this.sfx == null || this.sfx.canSpeed());
    }


    /**
     * Releases all resources associated with the media player, including the GPU and audio
     * resources held by the backing engines. A follower deregisters from its authority and every
     * player lands in {@link Status#STOPPED}. Idempotent: a second call is a no-op.
     * <p>Implementations may block the calling thread while their internal decode/IO threads
     * finish, so never call it from a status listener or a playback callback. After calling this
     * method, the media player should not be used again.
     */
    public void release() {
        this.disarm();
        Throwable failure = null;
        // ATTEMPT EVERY OWNED RESOURCE EVEN WHEN A HOST CALLBACK OR ANOTHER ENGINE FAILS.
        final Runnable[] cleanup = {
                () -> { if (this.role == Role.FOLLOWER) this.send(new Unwatch(this.watcherId)); },
                () -> { if (this.gfx != null) this.gfx.release(); },
                () -> { if (this.sfx != null) this.sfx.release(); }
        };
        for (final Runnable step: cleanup) {
            try {
                step.run();
            } catch (final RuntimeException | Error problem) {
                failure = WaterMediaModule.mergeFailure(failure, problem);
            }
        }
        if (failure instanceof final Error error) throw error;
        if (failure instanceof final RuntimeException error) throw error;
    }

    // MONOTONIC MILLISECONDS SINCE CLASS LOAD (SEE EPOCH_NS)
    static long nowMs() { return (System.nanoTime() - EPOCH_NS) / 1_000_000L; }

    // LOD SIZING: CAPS nativeSize AT max (0 = NO CAP, NEVER UPSCALING), THEN KEEPS percent OF IT. AN UNREDUCED SIZE STAYS
    // EXACT (ODD SOURCES INCLUDED); A REDUCED ONE IS FORCED EVEN AND AT LEAST 2 SO CHROMA-SUBSAMPLED PLANES STAY ALIGNED
    static int scaled(final int nativeSize, final int max, final int percent) {
        if (nativeSize <= 0) return nativeSize;
        int size = max > 0 ? Math.min(nativeSize, max) : nativeSize;
        if (percent < 100) size = (int) Math.round(size * (percent / 100.0));
        if (size >= nativeSize) return nativeSize;
        return Math.max(2, size & ~1);
    }

    /**
     * What a player is within a synchronized session. Not a client/server split: the axis is who
     * owns the truth. A {@link ServerMediaPlayer} can take any role — it is the authority on a
     * server, and a headless mirror on a client — while players backed by real media can only
     * ever follow.
     * @see MediaPlayer#role()
     */
    public enum Role {
        /** No bridge: the player owns its playback and answers to nobody. */
        SOLO,
        /** Owns the session's truth and broadcasts it to every follower. */
        AUTHORITY,
        /** Tracks an authority and keeps its own playback aligned with it. */
        FOLLOWER
    }

    /**
     * Represents the various states of the media player during its lifecycle.
     * Each state indicates a specific phase of media playback or an error condition.
     * @see MediaPlayer#status()
     */
    public enum Status {
        /**
         * The media player is waiting for resources or conditions to start loading the media.
         */
        WAITING,
        /**
         * The media player is in the process of loading the media.
         */
        LOADING,
        /**
         * The media player is buffering data to ensure smooth playback.
         */
        BUFFERING,
        /**
         * The media player is currently playing the media.
         */
        PLAYING,
        /**
         * The media player is paused and can be resumed.
         */
        PAUSED,
        /**
         * The media player is stopped and can be started from the beginning.
         */
        STOPPED,
        /**
         * The media player has reached the end of the media.
         */
        ENDED,
        /**
         * The media player has encountered an error and cannot continue playback.
         */
        ERROR;

        private static final MediaPlayer.Status[] VALUES = values();

        /**
         * Returns the Status corresponding to the given ordinal value (0 to {@code values().length - 1}).
         * @param value the ordinal of the status (0-7)
         * @return the corresponding Status enum value
         * @throws IllegalArgumentException if the value is out of range
         */
        public static Status of(final int value) {
            if (value < 0 || value >= VALUES.length) throw new IllegalArgumentException("Invalid status ordinal: " + value);
            return VALUES[value];
        }
    }

    /**
     * Level of detail applied to video uploads.
     * Each level keeps a percentage of the effective maximum video dimensions
     * ({@link #scale(int, int)} when set, otherwise the source size), reducing the
     * amount of pixel data scaled and uploaded to the GPU. Useful to tie media
     * resolution to the viewer distance. Playback always starts at {@link #MAX}.
     * @see MediaPlayer#lod(LodLevel)
     */
    public enum LodLevel {
        /** Full resolution — no LOD reduction. */
        MAX(100),
        /** Close range — 75% of the maximum dimensions. */
        CLOSE(75),
        /** Near range — 50% of the maximum dimensions. */
        NEAR(50),
        /** Far range — 25% of the maximum dimensions. */
        FAR(25),
        /** Barely visible — 10% of the maximum dimensions. */
        FAR_AWAY(10);

        private final int percent;

        LodLevel(final int percent) { this.percent = percent; }

        /**
         * Returns the percentage of the maximum video dimensions this level keeps.
         * @return the percentage (1-100)
         */
        public int percent() { return this.percent; }
    }
}
