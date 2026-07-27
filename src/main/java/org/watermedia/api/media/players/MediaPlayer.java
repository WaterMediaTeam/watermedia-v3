package org.watermedia.api.media.players;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.players.sync.Bridge;
import org.watermedia.api.media.players.sync.Config;
import org.watermedia.api.media.players.sync.Control;
import org.watermedia.api.media.players.sync.Packet;
import org.watermedia.api.media.players.sync.Report;
import org.watermedia.api.media.players.sync.Sync;
import org.watermedia.api.media.players.sync.Unwatch;
import org.watermedia.api.media.players.sync.Watch;
import org.watermedia.api.util.MathUtil;
import org.watermedia.api.media.engines.GFXEngine;
import org.watermedia.api.media.engines.SFXEngine;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.tools.ThreadTool;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

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
    public static final int NO_TEXTURE = 0;
    /** Sentinel for a missing audio source handle. */
    public static final int NO_SOURCE = 0;
    /** Sentinel for an unknown media duration. */
    public static final int NO_DURATION = 0;

    // === SHARED 50ms TICKER — DRIVES SERVER CLOCKS, FOLLOWER CORRECTIONS AND AUTHORITY BROADCASTS ===
    // A SINGLE DAEMON THREAD TICKS EVERY REGISTERED PLAYER, SO ALL CORRECTION STATE IS
    // EFFECTIVELY SINGLE-THREADED AND THE BRIDGE FLOW NEVER TOUCHES THE GAME THREAD.
    private static final long TICK_MS = 50;
    private static final Set<MediaPlayer> TICKING = ConcurrentHashMap.newKeySet();
    private static final ScheduledExecutorService TICKER = Executors.newSingleThreadScheduledExecutor(
            ThreadTool.createFactory("MediaSync", Thread.NORM_PRIORITY - 2)
    );

    static {
        TICKER.scheduleAtFixedRate(MediaPlayer::tickAll, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    private static void tickAll() {
        for (final MediaPlayer player: TICKING) {
            try {
                player.tick50();
            } catch (final Throwable t) {
                LOGGER.error(IT, "Sync tick failed", t);
            }
        }
    }

    // FOLLOWER CORRECTION CONSTANTS
    private static final long SEEK_COOLDOWN_NANOS = 1_000_000_000L; // 1s BETWEEN SEEKS WHILE THE PIPELINE RESETTLES
    private static final long REPORT_NANOS = 5_000_000_000L;        // UPSTREAM KEEPALIVE SO THE AUTHORITY TTL HOLDS

    // BASIC PROPERTIES
    protected final MRL mrl;
    protected final int sourceIndex;
    protected final MRL.Source source;
    protected final GFXEngine gfx;
    protected final SFXEngine sfx;
    protected MediaQuality quality = WaterMediaConfig.media.defaultQuality;

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
    private volatile int caps;
    private volatile boolean configured;
    private volatile long aheadMs;    // HALF THE MEASURED ROUND TRIP — SNAPSHOT AGE IN FLIGHT
    private volatile long watchNanos; // NON-ZERO WHILE THE Watch HELLO AWAITS ITS Config ECHO
    private volatile Sync target;     // LAST SNAPSHOT HEARD FROM THE SESSION, NULL UNTIL THE FIRST ONE
    private volatile long targetNanos;// WHEN IT ARRIVED, SO IT CAN BE AGED INTO A CURRENT POSITION

    // FOLLOWER TUNABLE — HOW FAR PLAYBACK MAY DRIFT BEFORE IT IS PULLED BACK
    private volatile long toleranceMs = 1000;

    // FOLLOWER CORRECTION STATE — TOUCHED ONLY FROM THE SINGLE TICKER THREAD
    private boolean startIssued;
    private long lastSeekNanos;
    private Status lastReport;
    private long lastReportNanos;
    // SET WHILE follow() IMPOSES AUTHORITY STATE, SO THOSE CALLS LAND LOCALLY INSTEAD OF BEING SENT
    // BACK UPSTREAM. IT NEEDS NO CROSS-THREAD VISIBILITY: A CONCURRENT USER CALL READING A STALE
    // false SIMPLY FORWARDS, WHICH IS EXACTLY WHAT A USER CALL SHOULD DO.
    private volatile boolean applying;

    // OPTIONAL STATUS-TRANSITION LISTENER — LETS CONSUMERS REACT TO TERMINAL STATES
    // (ENDED/ERROR) WITHOUT POLLING status() EVERY TICK. INVOKED FROM INTERNAL THREADS.
    private volatile BiConsumer<Status, Status> statusListener;

    // VIDEO UPLOAD SCALING — WRITTEN BY THE CALLER (OR A SUBCLASS), READ BY THE PLAYBACK
    // THREADS. EACH SUBCLASS RESOLVES ITS UPLOAD SIZE FROM THESE VIA MathUtil.scaled(native,
    // scale, lod.percent()): THE SCALE IS THE PER-AXIS CEILING (NO_SIZE = NO CAP, NEVER
    // UPSCALES) AND lod SHRINKS IT FURTHER BY A PERCENTAGE. PROTECTED SO THE EXTENDER OWNS THE
    // VALUES AND MediaPlayer ONLY CENTRALIZES THE SETTER/GETTER PLUMBING.
    protected volatile int scaleWidth = NO_SIZE;
    protected volatile int scaleHeight = NO_SIZE;
    protected volatile LodLevel lod = LodLevel.MAX;

    // NATIVE SOURCE FRAME SIZE BEFORE ANY SCALING — UPDATED BY THE SUBCLASS WHEN IT LEARNS
    // THE DECODED DIMENSIONS, READ BY CALLERS THAT NEED THE UNCAPPED RESOLUTION (e.g. A UI
    // CLAMPING A CUSTOM maxSize TO THE SOURCE).
    protected volatile int sourceWidth = NO_SIZE;
    protected volatile int sourceHeight = NO_SIZE;

    public MediaPlayer(final MRL mrl, final int sourceIndex, final GFXEngine gfx, final SFXEngine sfx) {
        this(mrl, sourceIndex, gfx, sfx, null);
    }

    /**
     * Bridged constructor: a non-null {@code bridge} makes this player a {@link Role#FOLLOWER}
     * of a server-side {@link ServerMediaPlayer} authority (see the class docs).
     * @param bridge byte carrier towards the authority, or {@code null} for a plain player
     */
    public MediaPlayer(final MRL mrl, final int sourceIndex, final GFXEngine gfx, final SFXEngine sfx,
                       final Bridge bridge) {
        Objects.requireNonNull(mrl, "MediaPlayer must have a valid MRL");
        final MRL.Source resolved = mrl.source(sourceIndex);
        Objects.requireNonNull(resolved, "Source at index " + sourceIndex + " is not available");
        if (gfx == null && sfx == null && !(this instanceof ServerMediaPlayer))
            throw new IllegalStateException("MediaPlayer must have a valid GFX or SFX resource.");
        if (gfx == null)
            LOGGER.warn(IT, "GFXEngine is null — there will be no video output");
        if (sfx == null)
            LOGGER.warn(IT, "SFXEngine is null — there will be no audio output");

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

    /**
     * Headless constructor for players that don't require audio or video output.
     * Used by {@link ServerMediaPlayer} which only tracks time progression.
     * @param bridge byte carrier, or {@code null} when unbridged
     * @param role what this player is within the session
     */
    protected MediaPlayer(final Bridge bridge, final Role role) {
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
    protected final void arm() {
        if (this.armed) return;
        this.armed = true;
        if (this.role == Role.FOLLOWER) {
            // BACKDATE THE COOLDOWN SO THE FIRST CORRECTION IS NEVER BLOCKED (nanoTime ORIGIN IS ARBITRARY)
            this.lastSeekNanos = System.nanoTime() - SEEK_COOLDOWN_NANOS;
            this.watchNanos = System.nanoTime();
            TICKING.add(this);
            this.send(new Watch(this.watcherId));
        } else if (this.role == Role.AUTHORITY) {
            // TICKS PERMANENTLY FOR AUTO-BROADCAST, HEARTBEAT AND GATE SWEEPS
            TICKING.add(this);
        }
    }

    // REGISTERS/DEREGISTERS A PLAYER ON THE SHARED TICKER. BRIDGED PLAYERS IGNORE DEREGISTRATION —
    // THEY TICK UNTIL release() — SO CLOCK STOP/START CYCLES NEVER SILENCE THE BRIDGE.
    protected static void ticking(final MediaPlayer player, final boolean on) {
        if (on) TICKING.add(player);
        else if (player.role == Role.SOLO) TICKING.remove(player);
    }

    // SHARED-TICKER CALLBACK. THE BASE RUNS THE FOLLOWER ENGINE; ServerMediaPlayer LAYERS THE
    // AUTHORITY CLOCK AND BROADCAST ON TOP.
    protected void tick50() {
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
     * payload is decoded and validated here, and its effects apply on the player's own
     * cadence without touching the caller thread.
     * <p>
     * Followers accept {@link Sync} and {@link Config}; an authority accepts {@link Watch},
     * {@link Report}, {@link Control} and {@link Unwatch}. Packets for the opposite role
     * are ignored.
     * @param payload the packet bytes, consumed
     * @throws IllegalArgumentException if the payload is malformed (see {@link Packet#of(ByteBuffer)})
     */
    public final void sync(final ByteBuffer payload) {
        this.sync(Packet.of(payload));
    }

    /**
     * Feeds an already decoded packet into this player. Use it to inspect traffic before it
     * takes effect — decode with {@link Packet#of(ByteBuffer)}, apply your own rules (say,
     * dropping a {@link Control} from a player without permission) and forward what survives.
     * Safe to call from any thread.
     * @param packet the decoded packet
     */
    public final void sync(final Packet packet) {
        if (packet == null) throw new IllegalArgumentException("Packet cannot be null");
        if (this.role != Role.FOLLOWER) {
            this.upstream(packet);
            return;
        }
        if (packet instanceof final Sync snapshot) {
            // A SNAPSHOT SUPERSEDES EVERY EARLIER ONE — THERE IS NOTHING TO QUEUE, ONLY THE LATEST
            // MATTERS. AN OLDER REVISION IS REORDERED TRANSPORT NOISE; AN EQUAL ONE IS A HEARTBEAT
            // CARRYING A FRESHER TIMESTAMP, SO IT MUST LAND. THE ARRIVAL STAMP GOES FIRST: A READER
            // SEEING THE NEW SNAPSHOT MUST NEVER PAIR IT WITH THE PREVIOUS ONE'S AGE.
            final Sync known = this.target;
            if (known == null || snapshot.revision() >= known.revision()) {
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
    // HAS NO AUDIENCE TO SERVE AND JUST DROPS IT.
    protected void upstream(final Packet packet) {
        LOGGER.debug(IT, "Ignored packet on a non-authority player: {}", packet);
    }

    // SENDS A PACKET THROUGH THE DEV'S CARRIER. A BROKEN CARRIER NEVER TEARS DOWN PLAYBACK.
    protected final void send(final Packet packet) {
        final Bridge bridge = this.bridge;
        if (bridge == null) return;
        try {
            bridge.send(packet.toBytes());
        } catch (final Throwable t) {
            LOGGER.error(IT, "Bridge send failed", t);
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
     * wraps instead of overrunning; a stopped one reports its frozen position.
     * @return the authoritative position in milliseconds, or 0 on a non-follower
     */
    public final long authorityTime() {
        final Sync target = this.target;
        if (target == null) return 0;
        if (target.status() != Status.PLAYING) return target.time();
        // THE SNAPSHOT AGED HALF THE ROUND TRIP IN FLIGHT, AND KEPT AGEING SINCE IT LANDED
        final long elapsed = (System.nanoTime() - this.targetNanos) / 1_000_000L + this.aheadMs;
        final long t = target.time() + (long) (elapsed * target.speed());
        final long d = target.duration();
        if (d <= 0) return t;
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
        if (target.repeat() && d > 0) {
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
        if (current != this.lastReport || now - this.lastReportNanos >= REPORT_NANOS) {
            this.lastReport = current;
            this.lastReportNanos = now;
            this.send(new Report(this.watcherId, current, this.duration(), this.liveSource()));
        }

        // RE-SEND THE HELLO UNTIL A CONFIG ANSWERS IT — LOSSY OR LATE-WIRED TRANSPORTS MAY DROP THE FIRST
        final long watch = this.watchNanos;
        if (watch != 0 && now - watch >= REPORT_NANOS) {
            this.watchNanos = now;
            this.send(new Watch(this.watcherId));
        }

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
                    if (!this.startIssued && (authority.repeat() || authority.duration() <= 0
                            || authority.duration() - this.authorityTime() > this.toleranceMs)) {
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
                if (current != Status.STOPPED && current != Status.WAITING) this.stop();
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
        if (Math.abs(this.drift()) > this.toleranceMs && now - this.lastSeekNanos >= SEEK_COOLDOWN_NANOS) {
            this.seekQuick(this.authorityTime());
            this.lastSeekNanos = now;
        }
    }

    // HANDS A CONTROL CALL TO THE AUTHORITY WHEN THIS PLAYER FOLLOWS ONE, AND ANSWERS WHETHER THE
    // CALL WAS TAKEN OVER — true MEANS THE CALLER MUST NOT TOUCH LOCAL STATE. A CALL RACING THE
    // HANDSHAKE STILL GOES OUT: THE AUTHORITY IS THE REAL ENFORCEMENT POINT, AND SWALLOWING THE
    // USER'S FIRST CLICK BECAUSE THE CONFIG HASN'T LANDED IS WORSE THAN ASKING FOR TOO MUCH.
    private boolean request(final Control.Op op, final long value) {
        if (this.role != Role.FOLLOWER || this.applying) return false;
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
     * the session rules in a UI (e.g. disabling controls the audience is not allowed to drive).
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
     * Changes the selected quality.
     * The media player will detect this change in its playback loop
     * and switch to the new quality while maintaining the current timestamp.
     * @param quality the new quality to use
     */
    public void quality(final MediaQuality quality) {
        if (quality == null) throw new IllegalArgumentException("Quality cannot be null.");
        this.quality = quality;
    }

    public MediaQuality quality() { return this.quality; }

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
    public boolean withVideo() { return this.gfx != null; }

    /**
     * Indicates if the media player has audio support enabled.
     * @return true if audio support is enabled, false otherwise.
     */
    public boolean withAudio() { return this.sfx != null; }

    /**
     * Returns the width of the video in pixels, as uploaded to the GPU
     * (after any {@link #maxSize(int, int)} / {@link #lod(LodLevel)} downscale).
     * @return the width of the video, or {@link MediaPlayer#NO_SIZE NO_SIZE} if not available.
     */
    public final int width() { return this.gfx == null ? NO_SIZE : this.gfx.width(); }

    /**
     * Returns the height of the video in pixels, as uploaded to the GPU
     * (after any {@link #maxSize(int, int)} / {@link #lod(LodLevel)} downscale).
     * @return the height of the video, or {@link MediaPlayer#NO_SIZE NO_SIZE} if not available.
     */
    public final int height() { return this.gfx == null ? NO_SIZE : this.gfx.height(); }

    /**
     * Returns the native width of the source video in pixels, before any
     * {@link #maxSize(int, int)} / {@link #lod(LodLevel)} downscale is applied.
     * @return the source width, or {@link MediaPlayer#NO_SIZE NO_SIZE} if not yet known.
     */
    public final int sourceWidth() { return this.sourceWidth; }

    /**
     * Returns the native height of the source video in pixels, before any
     * {@link #maxSize(int, int)} / {@link #lod(LodLevel)} downscale is applied.
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
    public void maxSize(final int width, final int height) {
        if (width < 0 || height < 0) throw new IllegalArgumentException("Dimensions cannot be negative.");
        this.scaleWidth = width;
        this.scaleHeight = height;
    }

    /**
     * Returns the maximum upload width set by {@link #maxSize(int, int)}.
     * @return the maximum width in pixels, or {@link MediaPlayer#NO_SIZE NO_SIZE} when unlimited.
     */
    public int maxWidth() { return this.scaleWidth; }

    /**
     * Returns the maximum upload height set by {@link #maxSize(int, int)}.
     * @return the maximum height in pixels, or {@link MediaPlayer#NO_SIZE NO_SIZE} when unlimited.
     */
    public int maxHeight() { return this.scaleHeight; }

    /**
     * Sets the level of detail for video uploads.
     * Each level shrinks the effective maximum video dimensions to a percentage of their
     * value (see {@link LodLevel}), so distant or less important media decodes, scales and
     * uploads fewer pixels. The change is applied on the fly to the next uploaded frame
     * without interrupting playback.
     * @param lod the new level of detail
     * @throws IllegalArgumentException if lod is null
     * @see #maxSize(int, int)
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
     * @see org.watermedia.api.media.engines.GFXEngine#texture()
     */
    public long texture() { return this.gfx == null ? NO_TEXTURE : this.gfx.texture(); }

    /**
     * Returns the audio source handle exposed by the backing {@link SFXEngine}.
     * The concrete handle type depends on the engine (an OpenAL source ID for OpenAL,
     * an internal id for JavaSound).
     * @return the audio source handle, or {@link MediaPlayer#NO_SOURCE NO_SOURCE} if audio is not supported.
     */
    public int audioSource() { return this.sfx != null ? this.sfx.source() : NO_SOURCE; }

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

    /**
     * Starts media playback from the beginning.
     * <p>If the media is already playing, it will restart from the beginning.</p>
     * If the media is paused, it will resume playback from the current position.
     * <p>If the media is stopped, it will start playback from the beginning.</p>
     * If the media is in an error state, it will attempt to recover and start playback.
     * <p>If the media is loading or buffering, it will wait until the media is ready before starting playback.</p>
     * If the media is ended, it will restart playback from the beginning.
     * <p>If the media uri is invalid, it will log an error and not start playback.</p>
     * This method is non-blocking and returns immediately.
     * @return true if playback was started locally, false when it was requested from the
     *         sync authority instead (see {@link #sync(ByteBuffer)}) or refused.
     */
    public boolean start() { return !this.request(Control.Op.START, 0L); }

    /**
     * Starts media playback in a paused state from the beginning.
     * <p>If the media is already playing, it will restart from the beginning and pause
     * immediately.</p>
     * If the media is paused, it will restart playback from the beginning and remain paused.
     * <p>If the media is stopped, it will start playback from the beginning and remain paused.</p>
     * If the media is in an error state, it will attempt to recover and start playback in a paused state.
     * <p>If the media is loading or buffering,
     * it will wait until the media is ready before starting playback in a paused state.</p>
     * If the media is ended, it will restart playback from the beginning and remain paused.
     * <p>If the media uri is invalid, it will log an error and not start playback.</p>
     * This method is non-blocking and returns immediately.
     * @implNote Semantically like {@link #start()} that lands in a paused state, but not a plain
     *           {@code start()} then {@link #pause()}: the two calls race the pipeline setup, so
     *           implementations latch the pause intent before playback begins.
     * @return true if playback was started locally, false when it was requested from the
     *         sync authority instead or refused.
     * @see MediaPlayer#start()
     */
    public boolean startPaused() { return !this.request(Control.Op.START_PAUSED, 0L); }

    /**
     * Resumes media playback from the current position.
     * @see #pause(boolean)
     * @return true if the operation was successful, false otherwise.
     */
    public boolean resume() { return this.pause(false); }

    /**
     * Pauses media playback at the current position.
     * @see #pause(boolean)
     * @return true if the operation was successful, false otherwise.
     */
    public boolean pause() { return this.pause(true); }

    /**
     * Pauses or resumes media playback based on the provided parameter.
     * If paused is true, the media playback will be paused.
     * If paused is false, the media playback will be resumed.
     * @param paused true to pause the media playback, false to resume
     * @return true if the operation was successful, false otherwise.
     */
    public boolean pause(final boolean paused) { return !this.request(Control.Op.PAUSE, paused ? 1L : 0L); }

    /**
     * Stops media playback and resets the position to the beginning.
     * This method is non-blocking and returns immediately.
     * @return true if the operation was successful, false otherwise.
     */
    public boolean stop() { return !this.request(Control.Op.STOP, 0L); }

    /**
     * Toggles media playback between play and pause states.
     * If the media is currently playing, it will be paused.
     * If the media is currently paused, it will be resumed.
     * @return true if the operation was successful, false otherwise.
     */
    public boolean togglePlay() { return !this.request(Control.Op.TOGGLE, 0L); }

    /**
     * Seeks to a specific time in the media.
     * The time is specified in milliseconds.
     * @param time the time to seek to, in milliseconds.
     * @return true if the operation was successful, false otherwise.
     */
    public boolean seek(final long time) { return !this.request(Control.Op.SEEK, time); }

    /**
     * Returns the current playback time in milliseconds.
     * @return the current playback time in milliseconds, or {@link MediaPlayer#NO_DURATION NO_DURATION} if unknown.
     */
    public abstract long time();

    /**
     * Skips forward or backward by a specific time in the media.
     * The time is specified in milliseconds.
     * A positive value skips forward, while a negative value skips backward.
     * @param time the time to skip, in milliseconds.
     * @return true if the operation was successful, false otherwise.
     */
    public boolean skipTime(final long time) { return this.seek(this.time() + time); }

    /**
     * Quickly seeks to a specific time in the media without precise accuracy.
     * This method is useful for fast seeking operations where exact frame accuracy is not required.
     * The time is specified in milliseconds.
     * @param time the time to seek to, in milliseconds.
     * @return true if the operation was successful, false otherwise.
     */
    public boolean seekQuick(final long time) { return !this.request(Control.Op.SEEK_QUICK, time); }

    /**
     * Skips forward 5 seconds in the media. Players with a finer natural step
     * (e.g. animated images) may override the distance.
     * @return true if the operation was successful, false otherwise.
     */
    public boolean forward() { return this.skipTime(5000); }

    /**
     * Skips backward 5 seconds in the media. Players with a finer natural step
     * (e.g. animated images) may override the distance.
     * @return true if the operation was successful, false otherwise.
     */
    public boolean rewind() { return this.skipTime(-5000); }

    /**
     * Provides the Frames per Second
     * Result value its a float because 29.97 framerate exists.
     * Check this <a href="https://www.youtube.com/watch?v=3GJUM6pCpew">full explanation</a>
     * @return the FPS count
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
     * @return true if the operation was successful, false when the value is out of
     *         range or the player cannot change speed ({@link #canSpeed()}).
     */
    public boolean speed(final float speed) {
        // NaN FAILS BOTH COMPARISONS, WHICH IS THE POINT — IT MUST NEVER REACH THE CLOCKS
        if (!(speed > 0f && speed <= 4.0f)) return false;
        if (this.request(Control.Op.SPEED, Float.floatToIntBits(speed) & 0xFFFFFFFFL)) return false;
        // RE-REQUESTING THE CURRENT SPEED IS A SUCCESSFUL NO-OP EVEN WHEN LOCKED (e.g. 1.0x ON SETUP)
        if (speed != this.speed && !this.canSpeed()) return false;
        this.speed = speed;
        if (this.sfx != null) this.sfx.speed(speed);
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
     * Sets whether the media should repeat playback when it reaches the end.
     * @param repeat true to enable repeat playback, false to disable
     * @return the new repeat state
     */
    public boolean repeat(final boolean repeat) {
        if (this.request(Control.Op.REPEAT, repeat ? 1L : 0L)) return this.repeat;
        return this.repeat = repeat;
    }

    /**
     * Returns the current status of the media player.
     * @return the current status of the media player.
     */
    public abstract Status status();

    /**
     * Registers a status-transition listener, replacing any previous one. The listener is
     * invoked with the previous and new status on terminal transitions (at least {@link Status#ENDED}
     * and {@link Status#ERROR}), letting playlist consumers advance without polling {@link #status()}.
     * It is called from internal player threads, so keep the callback short and non-blocking.
     * @param listener the listener to notify, or {@code null} to clear it
     */
    public void onStatus(final BiConsumer<Status, Status> listener) { this.statusListener = listener; }

    // NOTIFIES THE STATUS LISTENER OF A REAL TRANSITION. SWALLOWS LISTENER FAILURES SO A BROKEN
    // CONSUMER NEVER TEARS DOWN THE PLAYBACK THREAD.
    protected void invokeStatus(final Status prev, final Status next) {
        final BiConsumer<Status, Status> l = this.statusListener;
        if (l != null && prev != next) {
            try {
                l.accept(prev, next);
            } catch (final Throwable t) {
                LOGGER.error(IT, "Status listener failed", t);
            }
        }
    }

    /**
     * Sets how long non-animated media (a static image) should remain displayed before it
     * transitions to {@link Status#ENDED}. The base player has no timed display and ignores this;
     * image-backed players override it. A value {@code <= 0} means no time limit.
     * @param ms display duration in milliseconds
     */
    public void displayTime(final long ms) {}

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
     * Indicates if the media uri is a live stream.
     * Live streams typically do not support seeking and have an indefinite duration.
     * @return true if the media uri is a live stream, false otherwise.
     */
    public abstract boolean liveSource();

    /**
     * Indicates if the media player supports seeking operations.
     * Some media formats or live streams may not support seeking.
     * @return true if seeking is supported, false otherwise.
     */
    public abstract boolean canSeek();
    /**
     * Indicates if the media player is ready to start playback.
     * This typically means that the media has been loaded and buffered sufficiently.
     * @return true if the media player can start playback, false otherwise.
     */
    public abstract boolean canPlay();

    /**
     * Indicates if the playback speed can be changed.
     * Live streams cannot change speed, and neither can media whose audio engine
     * reports no speed support ({@link SFXEngine#speed()}) — scaling the timeline
     * against audio stuck at 1.0× would desync the playback clock.
     * @return true if {@link #speed(float)} can take effect, false otherwise.
     */
    public boolean canSpeed() {
        return !this.liveSource() && (this.sfx == null || this.sfx.speed());
    }


    /**
     * Releases all resources associated with the media player, including the GPU and audio
     * resources held by the backing engines. A follower deregisters from its authority.
     * <p>Implementations may block the calling thread while their internal decode/IO threads
     * finish. After calling this method, the media player should not be used again.
     */
    public void release() {
        // LEAVE THE SESSION FIRST: SAY GOODBYE SO THE AUTHORITY DROPS US INSTEAD OF WAITING OUT THE TTL
        if (this.role == Role.FOLLOWER) this.send(new Unwatch(this.watcherId));
        TICKING.remove(this);
        // SUBCLASSES STOP/JOIN THEIR DECODE THREADS BEFORE CALLING super.release(), SO NEITHER ENGINE
        // IS STILL IN USE HERE. RELEASING gfx FREES ITS GPU TEXTURES (FOR VULKAN, VIA DEFERRED DESTRUCTION).
        if (this.gfx != null) {
            this.gfx.release();
        }
        if (this.sfx != null) {
            this.sfx.release();
        }
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
         * @throws ArrayIndexOutOfBoundsException if the value is out of range
         */
        public static Status of(final int value) { return VALUES[value]; }
    }

    /**
     * Level of detail applied to video uploads.
     * Each level keeps a percentage of the effective maximum video dimensions
     * ({@link #maxSize(int, int)} when set, otherwise the source size), reducing the
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