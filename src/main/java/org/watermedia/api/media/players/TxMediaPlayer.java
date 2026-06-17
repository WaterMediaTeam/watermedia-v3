package org.watermedia.api.media.players;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.readers.BCReader;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.players.util.NetworkCache;
import org.watermedia.api.media.engines.GFXEngine;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.ThreadTool;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Media player for static and animated images (PNG, APNG, GIF, WebP, NETPBM, JPEG).
 * <p>
 * Preparation runs on a shared single-frame pool. After the source is opened and metadata is
 * resolved, {@link #prepare(int)} dispatches to one of three playback modes implemented in
 * dedicated sections below:
 * <ul>
 *   <li><b>Mode 1 — Static image:</b> a single frame is uploaded once. No lifecycle thread is
 *       spawned and the texture remains live until {@link #stop()} or {@link #release()}.</li>
 *   <li><b>Mode 2 — Pre-uploaded frame textures:</b> when the animation's decoded RGBA frames
 *       fit under the configured VRAM budget <i>and</i> the engine reports
 *       {@link GFXEngine#supportsFrameTextures()}, every frame is uploaded as its own texture.
 *       Playback is driven by a passive clock: no thread exists, the displayed frame is
 *       resolved from wall time on each {@link #texture()} call, so steady-state cost is zero
 *       uploads and zero wakeups.</li>
 *   <li><b>Mode 3 — Streaming decode with prefetch:</b> a streaming {@link ImageReader} is
 *       driven from the playback clock — each {@code next()} call decodes one frame on demand,
 *       like a video decoder. During the current frame's display window the loop pre-decodes
 *       upcoming frames into a bounded queue ("decode ahead instead of just sleeping"). If the
 *       queue empties because decoding fell behind the clock, playback drops into
 *       {@link Status#BUFFERING} and pre-decodes a handful of frames before resuming. Aggregate
 *       decode CPU across all players is bounded by a shared permit pool.</li>
 * </ul>
 * <p>
 * Seek / loop / step-backwards rewind the source ({@link ImageReader#reset()} when supported,
 * otherwise a full re-open) and decode forward to the target. Animated image formats are not
 * random-access because each frame's canvas depends on prior frames, exactly like video's GOP
 * semantics.
 * <p>
 * When {@link #maxSize(int, int)} or a {@link LodLevel} below MAX is active, decoded frames
 * are downscaled (area average) before upload. Streaming playback (Mode 3) picks up LOD
 * changes on the fly; static images and preloaded texture sets (Modes 1-2) apply the target
 * at preparation time.
 */
public final class TxMediaPlayer extends MediaPlayer {
    private static final Marker IT = MarkerManager.getMarker(TxMediaPlayer.class.getSimpleName());

    // ==========================================================================
    // CONSTANTS
    // ==========================================================================
    // PREFETCH: HOLD UP TO PREFETCH_MAX PRE-DECODED FRAMES; REFILL PREFETCH_REFILL ON BUFFERING STALL.
    // PIXEL BUFFERS CYCLE THROUGH A POOL SO STEADY-STATE ALLOCATIONS ARE ZERO.
    // TOTAL LIVE MEMORY IS ROUGHLY PREFETCH AND IN-FLIGHT BUFFER COUNT TIMES THE FRAME BYTE SIZE.
    private static final int PREFETCH_MAX = 6;
    private static final int PREFETCH_REFILL = 3;
    private static final int POOL_SPARE = 2;
    private static final long PREFETCH_BUDGET_BYTES = 64L * 1024 * 1024;
    private static final long PREFETCH_SAFETY_MARGIN_MS = 2L;
    // ENGINES CONSUME OR DROP A SUBMITTED FRAME BEFORE THE SECOND SUBSEQUENT upload() RETURNS,
    // SO ONLY THE LAST TWO SUBMITTED BUFFERS MUST STAY UNTOUCHED.
    private static final int IN_FLIGHT_KEEP = 2;
    // MODE 2 HARD CAP: AVOIDS CREATING THOUSANDS OF GL TEXTURE OBJECTS FOR TINY LONG ANIMATIONS
    private static final int MAX_FRAME_TEXTURES = 256;
    private static final long PAUSE_WAIT_MS = 200L;
    private static final long REFILL_PERMIT_TIMEOUT_MS = 5_000L;
    private static final ExecutorService SINGLE_FRAME_POOL = Executors.newFixedThreadPool(
            Math.max(1, ThreadTool.minThreads()),
            ThreadTool.createFactory("TxPlayer-SingleFrame", Thread.NORM_PRIORITY - 1));
    // GLOBAL PERMITS BOUNDING AGGREGATE DECODE CPU ACROSS ALL TX PLAYERS — MANY ANIMATED
    // IMAGES THROTTLE EACH OTHER INSTEAD OF STARVING THE GAME'S OWN THREADS
    private static final Semaphore DECODE_PERMITS = new Semaphore(Math.max(2, ThreadTool.minThreads()));
    // ACCEPT HEADER FOR IMAGE FETCHES — SHARED BY THE NETWORK AND CODEC CACHE KEYS SO BOTH TIERS
    // DERIVE THE SAME LOGICAL-RESOURCE KEY FOR A GIVEN SOURCE.
    private static final String IMAGE_ACCEPT = "image/*,*/*";

    // ==========================================================================
    // FIELDS
    // ==========================================================================
    // METADATA (RESOLVED ON FIRST READER OPEN; DURATION MAY REMAIN UNKNOWN UNTIL EOF)
    private volatile int width;
    private volatile int height;
    private volatile boolean animated;
    private volatile boolean loaded;
    private volatile long knownDuration;       // 0 = UNKNOWN / STATIC. SET BY READER METADATA OR EOF.
    private volatile PixelFormat pixelFormat = PixelFormat.BGRA;
    private volatile int planeCount = 1;

    // UPLOAD TARGET — SOURCE DIMENSIONS SHRUNK BY maxSize/LOD (SEE MediaPlayer#targetSize).
    // WRITTEN ONLY BY THE PREPARE/LIFECYCLE THREAD THAT DECODES AND UPLOADS FRAMES.
    private int outWidth;
    private int outHeight;

    // STATUS
    private volatile Status status = Status.WAITING;
    private volatile float speed = 1.0f;

    // LIFECYCLE THREAD (MODE 3 ONLY)
    private volatile Thread lifecycleThread;
    private volatile Future<?> lifecycleTask;
    private volatile ImageReader activeReader;
    private volatile int lifecycleSerial;

    // TRIGGERS (CALLER TO LIFECYCLE THREAD); signals WAKES THE LIFECYCLE OUT OF ITS WAITS
    private final Object signals = new Object();
    private volatile boolean paused;
    private volatile boolean triggerStop;
    private volatile boolean triggerPause;
    private volatile boolean triggerResume;
    private volatile boolean triggerNextFrame;
    private volatile boolean triggerPrevFrame;
    private volatile long seekTarget = -1L;

    // PLAYBACK CLOCK (LIFECYCLE WRITES, CALLER READS)
    private volatile long time;
    private volatile long currentDelayMs;
    private volatile int currentFrameIndex = -1;

    // MODE 2 PASSIVE CLOCK — texTimeline[i] IS THE CUMULATIVE START TIME OF FRAME i. WHEN
    // NON-NULL, THE DISPLAYED FRAME IS RESOLVED FROM WALL TIME ON EACH texture() CALL AND NO
    // LIFECYCLE THREAD EXISTS. clock GUARDS THE (clockBase, wallBase) PAIR.
    private final Object clock = new Object();
    private volatile long[] texTimeline;
    private volatile long[] texDelays;
    private long clockBase;     // MEDIA TIME AT wallBase
    private long wallBase;      // WALL MILLIS WHEN clockBase WAS TAKEN

    // PREFETCH STATE (ACCESSED ONLY BY THE LIFECYCLE THREAD IN STREAMING MODE)
    private final ArrayDeque<PrefetchedFrame> prefetchQueue = new ArrayDeque<>(PREFETCH_MAX);
    private int prefetchMax = PREFETCH_MAX;
    private int prefetchRefill = PREFETCH_REFILL;
    private int nextDecodedIndex;
    private boolean readerExhausted;

    // BUFFER POOL: DIRECT BYTE BUFFERS CYCLE THROUGH QUEUE, UPLOAD, AND POOL INSTEAD OF BEING
    // ALLOCATED PER FRAME. THE LAST IN_FLIGHT_KEEP UPLOADED BUFFERS ARE HELD BACK TO HONOR THE
    // ENGINE'S ASYNC CONSUMPTION CONTRACT.
    private final ArrayDeque<ByteBuffer> bufferPool = new ArrayDeque<>();
    private final ArrayDeque<ByteBuffer> inFlight = new ArrayDeque<>(IN_FLIGHT_KEEP + 1);
    private int bufferByteSize;

    // CODEC CACHE (BC OVER DDS) — WRITE SIDE. codecActive IS RESOLVED AT PREPARE; codecWriter HOLDS
    // THE IN-PROGRESS SESSION (OWNED BY THE PRODUCER THREAD) AND codecExpect GUARDS STRICT IN-ORDER
    // FIRST-PASS FEEDING SO A SEEK/LOOP/SCRUB ABORTS INSTEAD OF WRITING A CORRUPT TEXTURE.
    private volatile boolean codecActive;
    private volatile NetworkCache.CodecWriter codecWriter;
    private int codecExpect;

    public TxMediaPlayer(final MRL mrl, final int sourceIndex, final GFXEngine gfxEngine) {
        super(mrl, sourceIndex, gfxEngine, null);
    }

    // ==========================================================================
    // LIFECYCLE PUBLIC API
    // ==========================================================================
    @Override
    public void start() {
        final boolean initialPause = this.triggerPause;
        final Future<?> oldTask = this.lifecycleTask;
        if ((this.lifecycleThread != null && this.lifecycleThread.isAlive()) || (oldTask != null && !oldTask.isDone())) {
            this.stop();
        }
        final Thread old = this.lifecycleThread;
        final int serial = this.lifecycleSerial + 1;
        this.lifecycleSerial = serial;

        this.resetForStart(initialPause);
        this.lifecycleTask = SINGLE_FRAME_POOL.submit(() -> {
            if (old != null) ThreadTool.join(old);
            if (oldTask != null && !oldTask.isDone()) oldTask.cancel(true);
            this.prepare(serial);
        });
    }

    @Override
    public void startPaused() {
        this.triggerPause = true;
        this.start();
    }

    @Override
    public boolean stop() {
        this.triggerStop = true;
        this.texTimeline = null;
        this.texDelays = null;
        IOTool.closeQuietly(this.activeReader);
        final Future<?> task = this.lifecycleTask;
        if (task != null) task.cancel(true);
        if (this.lifecycleThread != null) this.lifecycleThread.interrupt();
        this.paused = false;
        this.loaded = false;
        this.status = Status.STOPPED;
        return true;
    }

    @Override
    public void release() {
        this.triggerStop = true;
        this.texTimeline = null;
        this.texDelays = null;
        IOTool.closeQuietly(this.activeReader);
        final Future<?> task = this.lifecycleTask;
        if (task != null) task.cancel(true);
        final Thread t = this.lifecycleThread;
        if (t != null) t.interrupt();
        if (t != null && Thread.currentThread() != t) ThreadTool.join(t);
        this.lifecycleThread = null;
        this.lifecycleTask = null;
        this.resetAfterRelease();
        super.release();
    }

    // ==========================================================================
    // MODE DISPATCH
    // ==========================================================================
    // OPENS THE SOURCE ONCE, READS METADATA, AND DISPATCHES TO EXACTLY ONE OF THE
    // THREE PLAYBACK MODES BELOW. THE READER IS HANDED OFF TO A LIFECYCLE THREAD
    // FOR MODE 3; OTHERWISE IT IS CLOSED IN THE FINALLY BLOCK.
    // THE 'handedOff' FLAG GUARDS AGAINST DOUBLE-CLOSING A READER STILL IN USE.
    private void prepare(final int serial) {
        ImageReader reader = null;
        boolean handedOff = false;
        try {
            this.status = Status.LOADING;

            // CODEC FAST PATH — REPLAY A CACHED BC TEXTURE STRAIGHT TO THE GPU, SKIPPING THE
            // NETWORK FETCH AND THE SOFTWARE DECODE ENTIRELY. FALLS THROUGH WHEN UNAVAILABLE.
            if (this.tryCodecTextures()) return;

            reader = this.openSource();
            this.width = reader.width();
            this.height = reader.height();
            if (this.width <= 0 || this.height <= 0) {
                throw new IOException("Invalid image dimensions: " + this.width + "x" + this.height);
            }
            if (this.quality == MediaQuality.UNKNOWN) {
                final var realQuality = MediaQuality.of(this.width, this.height);
                this.mrl.moveQuality(this.sourceIndex, this.quality, realQuality);
                LOGGER.info(IT, "Moved URI {} from Quality {} to {}", this.source.uri(this.quality), this.quality, realQuality);
                this.quality = realQuality;
            }
            this.animated = reader.frameCount() != 1;
            this.knownDuration = Math.max(0L, reader.duration());
            this.pixelFormat = reader.pixelFormat();
            this.planeCount = reader.planeCount();
            this.applyTarget();
            this.gfx.setVideoFormat(this.pixelFormat, this.outWidth, this.outHeight);
            // CODEC WRITE APPLIES ONLY FOR BC-ENCODABLE LAYOUTS WHEN THE CODEC CACHE IS ACTIVE.
            this.codecActive = NetworkCache.codecEnabled() && bcEncodable(this.pixelFormat);

            if (this.triggerStop || Thread.currentThread().isInterrupted()) {
                this.status = Status.STOPPED;
                return;
            }

            // MODE 1: STATIC IMAGE — UPLOAD AND DONE, NO LIFECYCLE THREAD.
            if (!this.animated) {
                this.showStatic(reader);
                return;
            }

            // MODE 2: PRE-UPLOADED FRAME TEXTURES — FITS THE VRAM BUDGET ON A CAPABLE ENGINE.
            if (this.shouldUseFrameTextures(reader)) {
                this.prepareTextures(reader);
                return;
            }

            // MODE 3: STREAMING DECODE — HAND THE READER OFF TO THE LIFECYCLE THREAD.
            final ImageReader streamingReader = reader;
            reader = null;
            handedOff = true;
            this.lifecycleThread = new Thread(() -> this.playStreaming(streamingReader, serial),
                    "TxPlayer-" + Integer.toHexString(this.source.hashCode()));
            this.lifecycleThread.setDaemon(true);
            this.lifecycleThread.start();
        } catch (final Exception e) {
            if (this.triggerStop || Thread.currentThread().isInterrupted()) {
                if (this.lifecycleSerial == serial) this.status = Status.STOPPED;
            } else {
                LOGGER.error(IT, "Lifecycle error: {}", this.source, e);
                if (this.lifecycleSerial == serial) this.status = Status.ERROR;
            }
        } finally {
            if (!handedOff) {
                // SAFETY NET: STATIC/MODE-2 PATHS ALREADY COMMITTED (WRITER NULL); THIS DROPS A
                // SESSION LEFT OPEN BY AN EXCEPTION SO NO HALF-WRITTEN TEXTURE SURVIVES.
                this.abortCodec();
                if (this.lifecycleSerial == serial) this.clearBuffers();
                IOTool.closeQuietly(reader);
                if (this.lifecycleSerial == serial) this.activeReader = null;
            }
            if (this.lifecycleSerial == serial) this.lifecycleTask = null;
        }
    }

    // ==========================================================================
    // MODE 1 — STATIC IMAGE
    // ==========================================================================
    // ONE FRAME IS UPLOADED ONCE AND THE READER IS MARKED EXHAUSTED. NO LIFECYCLE
    // THREAD IS NEEDED — THE GFX HANDLE REMAINS LIVE UNTIL STOP/RELEASE.
    private void showStatic(final ImageReader reader) throws IOException {
        this.showFirstFrame(reader);
        this.readerExhausted = true;
        this.commitCodec(); // SINGLE-FRAME TEXTURE: THE ONE FRAME WAS FED IN showFirstFrame
        LOGGER.debug(IT, "Loaded: {} ({}x{}, static, cache/threadless)",
                this.source, this.width, this.height);
    }

    // ==========================================================================
    // MODE 2 — PRE-UPLOADED FRAME TEXTURES + PASSIVE CLOCK
    // ==========================================================================
    // ALL FRAMES ARE DECODED UP FRONT AND UPLOADED AS PER-FRAME TEXTURES. NO THREAD EXISTS:
    // texture() RESOLVES THE DISPLAYED FRAME FROM WALL TIME ON EVERY CALL, SO PLAYBACK COSTS
    // ZERO UPLOADS, ZERO WAKEUPS, AND HAS NO SLEEP DRIFT.

    // ELIGIBLE WHEN THE DECODED RGBA FRAME SET FITS THE CONFIGURED VRAM BUDGET, THE FRAME
    // COUNT IS KNOWN AND SANE, AND THE GFX BACKEND SUPPORTS THE PATH.
    private boolean shouldUseFrameTextures(final ImageReader reader) {
        final int frames = reader.frameCount();
        if (frames <= 1 || frames > MAX_FRAME_TEXTURES || !this.gfx.supportsFrameTextures()) return false;
        // VRAM COST IS RGBA8 PER FRAME (AT THE UPLOAD TARGET) REGARDLESS OF THE SOURCE PIXEL LAYOUT
        final long budget = Math.max(0L, WaterMediaConfig.media.txFrameTexturesBudgetMB) * 1024L * 1024L;
        return (long) frames * this.outWidth * this.outHeight * 4L <= budget;
    }

    private void prepareTextures(final ImageReader reader) throws IOException {
        this.status = Status.BUFFERING;
        this.clearQueues();

        // DECODE EVERY FRAME. IF readAll() YIELDS ONLY ONE FRAME (E.G. APNG WITH A SINGLE FCTL)
        // FALL BACK TO STATIC SEMANTICS RATHER THAN BUILDING A CLOCK FOR NOTHING.
        final ImageData data = reader.readAll();
        if (data.frames().length <= 1) {
            this.uploadFrame(data.frames()[0]);
            this.currentFrameIndex = 0;
            this.currentDelayMs = delayAt(data.delay(), 0);
            this.time = 0L;
            this.loaded = true;
            this.animated = false;
            this.readerExhausted = true;
            this.resolveInitialStatus();
            return;
        }

        ByteBuffer[] frames = data.frames();
        if (this.outWidth != this.width || this.outHeight != this.height) {
            // DOWNSCALE THE WHOLE SET BEFORE THE BULK UPLOAD — VRAM AND UPLOAD COST
            // FOLLOW THE TARGET SIZE, NOT THE SOURCE SIZE
            final ByteBuffer[] scaled = new ByteBuffer[frames.length];
            for (int i = 0; i < scaled.length; i++) {
                final ByteBuffer dst = ByteBuffer.allocateDirect(this.bufferByteSize).order(ByteOrder.nativeOrder());
                this.scaleFrame(frames[i], dst);
                dst.flip();
                scaled[i] = dst;
            }
            frames = scaled;
        }

        if (!this.gfx.uploadFrameTextures(frames, 0)) {
            throw new IOException("GFXEngine rejected preloaded frame textures for: " + this.source);
        }
        this.gfx.useFrameTexture(0);

        // BUILD THE CUMULATIVE TIMELINE THAT DRIVES THE PASSIVE CLOCK
        final long[] delays = data.delay();
        final long[] timeline = new long[data.frames().length];
        long total = 0L;
        for (int i = 0; i < timeline.length; i++) {
            timeline[i] = total;
            total += Math.max(1L, delayAt(delays, i));
        }
        this.knownDuration = total;
        this.currentFrameIndex = 0;
        this.currentDelayMs = delayAt(delays, 0);
        this.nextDecodedIndex = timeline.length;
        this.readerExhausted = true;
        synchronized (this.clock) {
            this.clockBase = 0L;
            this.wallBase = System.currentTimeMillis();
        }
        this.texDelays = delays;
        this.texTimeline = timeline;
        this.loaded = true;
        this.resolveInitialStatus();

        // CODEC CACHE: PERSIST THE WHOLE (SCALED) FRAME SET AS A BC TEXTURE FOR FAST REPLAYS.
        this.cacheCodecFrames(frames, delays);

        LOGGER.debug(IT, "Loaded: {} ({}x{}, {} frame textures, passive clock, duration={}ms)",
                this.source, this.width, this.height, timeline.length, this.knownDuration);
    }

    // RESOLVES THE PASSIVE-CLOCK MEDIA TIME, FOLDING LOOP WRAPS AND THE ENDED TRANSITION.
    private long texTime() {
        synchronized (this.clock) {
            final long duration = Math.max(1L, this.knownDuration);
            long t = this.clockBase;
            if (this.status == Status.PLAYING) {
                t += (long) ((System.currentTimeMillis() - this.wallBase) * this.speed);
            }
            if (t < duration) return t;
            if (this.repeat()) {
                t %= duration;
                this.clockBase = t;
                this.wallBase = System.currentTimeMillis();
                return t;
            }
            this.clockBase = duration;
            this.wallBase = System.currentTimeMillis();
            if (this.status == Status.PLAYING) this.status = Status.ENDED;
            return duration;
        }
    }

    private boolean stepTexture(final int direction) {
        final long[] timeline = this.texTimeline;
        if (timeline == null) return false;
        synchronized (this.clock) {
            final int idx = frameAt(timeline, Math.min(this.clockBase, this.knownDuration - 1L));
            final int target = Math.max(0, Math.min(timeline.length - 1, idx + direction));
            this.clockBase = timeline[target];
            this.wallBase = System.currentTimeMillis();
            this.currentFrameIndex = target;
            this.currentDelayMs = delayAt(this.texDelays, target);
        }
        return true;
    }

    // FINDS THE FRAME WHOSE DISPLAY WINDOW CONTAINS time IN A CUMULATIVE TIMELINE.
    private static int frameAt(final long[] timeline, final long time) {
        int idx = Arrays.binarySearch(timeline, time);
        if (idx < 0) idx = -idx - 2;
        return Math.max(0, Math.min(idx, timeline.length - 1));
    }

    @Override
    public long texture() {
        final long[] timeline = this.texTimeline;
        if (timeline != null) {
            // PASSIVE CLOCK: THE FRAME IS RESOLVED AT RENDER TIME
            final int frame = frameAt(timeline, this.texTime());
            if (frame != this.currentFrameIndex) {
                this.currentFrameIndex = frame;
                this.currentDelayMs = delayAt(this.texDelays, frame);
            }
            this.gfx.useFrameTexture(frame);
        }
        return super.texture();
    }

    // ==========================================================================
    // MODE 3 — STREAMING DECODE WITH PREFETCH
    // ==========================================================================
    // EACH next() CALL DECODES ONE FRAME ON DEMAND. WHILE THE CURRENT FRAME IS
    // DISPLAYED, THE LOOP DECODES AHEAD INTO A BOUNDED QUEUE. IF THE QUEUE
    // EMPTIES THE PLAYER ENTERS BUFFERING, REFILLS A SMALL BATCH, AND RESUMES.
    // SEEK / LOOP / STEP-BACKWARDS REWIND THE SOURCE (reset() WHEN SUPPORTED) AND
    // DECODE FORWARD BECAUSE ANIMATED IMAGE FRAMES DEPEND ON PRIOR ONES.

    // WRAPS playStream(...) WITH FIRST-FRAME PUBLISH AND READER LIFECYCLE.
    private void playStreaming(ImageReader reader, final int serial) {
        try {
            this.showFirstFrame(reader);
            LOGGER.debug(IT, "Loaded: {} ({}x{}, animated, duration={}ms, prefetch<={})",
                    this.source, this.width, this.height, this.knownDuration, this.prefetchMax);
            reader = this.playStream(reader);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            if (this.triggerStop && this.lifecycleSerial == serial) this.status = Status.STOPPED;
        } catch (final Exception e) {
            if (this.triggerStop || Thread.currentThread().isInterrupted()) {
                if (this.lifecycleSerial == serial) this.status = Status.STOPPED;
            } else {
                LOGGER.error(IT, "Lifecycle error: {}", this.source, e);
                if (this.lifecycleSerial == serial) this.status = Status.ERROR;
            }
        } finally {
            // SAFETY NET: A CLEAN EOF ALREADY COMMITTED (WRITER NULL); THIS DROPS A SESSION LEFT
            // OPEN BY STOP/ERROR/INTERRUPT SO NO HALF-WRITTEN TEXTURE SURVIVES.
            this.abortCodec();
            if (this.lifecycleSerial == serial) this.clearBuffers();
            IOTool.closeQuietly(reader);
            if (this.lifecycleSerial == serial) this.activeReader = null;
        }
    }

    // STREAMING PLAYBACK LOOP. DRIVES THE ANIMATION CLOCK, PREFETCHES AHEAD,
    // AND ABSORBS PAUSE / SEEK / STEP TRIGGERS WITHOUT LEAKING DECODE WORK.
    private ImageReader playStream(ImageReader reader) throws InterruptedException, IOException {
        while (!Thread.currentThread().isInterrupted()) {
            // HOT maxSize/LOD CHANGES — FRAMES DECODED FROM NOW ON SCALE TO THE NEW
            // TARGET; FRAMES ALREADY QUEUED KEEP THEIR SIZE AND DRAIN FIRST
            // (uploadBuffer RECONFIGURES THE ENGINE WHEN THE SIZE FLIPS).
            this.applyTarget();

            // STOP
            if (this.triggerStop) {
                this.triggerStop = false;
                this.status = Status.STOPPED;
                return reader;
            }

            // PAUSE / RESUME. STRAY TRIGGERS ARE CLEARED SO SIGNAL WAITS NEVER SPIN.
            if (this.triggerPause && !this.paused) {
                this.paused = true;
                this.triggerPause = false;
                this.status = Status.PAUSED;
                this.bufferPool.clear(); // TRIM SPARE BUFFERS WHILE PAUSED — QUEUE AND IN-FLIGHT STAY
            }
            if (this.triggerResume) {
                this.triggerResume = false;
                if (this.paused) {
                    this.paused = false;
                    this.status = Status.PLAYING;
                }
            }
            if (this.triggerPause && this.paused) this.triggerPause = false;
            if (!this.paused) {
                this.triggerNextFrame = false;
                this.triggerPrevFrame = false;
            }

            // SEEK BY REWINDING AND DECODING FORWARD TO THE TARGET, THEN DROP THE PREFETCH QUEUE.
            final long target = this.seekTarget;
            if (target >= 0) {
                this.seekTarget = -1L;
                this.abortCodec(); // SEEK BREAKS THE IN-ORDER FIRST PASS — DROP THE PARTIAL TEXTURE
                this.clearPrefetch();
                reader = this.reopen(reader);
                final ByteBuffer frame = this.seekReaderToTime(reader, target);
                if (frame != null) this.uploadFrame(frame);
                this.nextDecodedIndex = this.currentFrameIndex + 1;
                this.readerExhausted = false;
                if (this.paused) { this.awaitSignal(PAUSE_WAIT_MS); continue; }
            }

            // PAUSED FRAME STEPPING: THE CLOCK IS STOPPED AND PREFETCH IS INACTIVE.
            if (this.paused) {
                if (this.triggerNextFrame) {
                    this.triggerNextFrame = false;
                    this.stepForward(reader);
                }
                if (this.triggerPrevFrame) {
                    this.triggerPrevFrame = false;
                    final int targetIdx = this.currentFrameIndex - 1;
                    if (targetIdx >= 0) {
                        this.abortCodec(); // STEP-BACK BREAKS THE IN-ORDER PASS
                        this.clearPrefetch();
                        reader = this.reopen(reader);
                        final ByteBuffer frame = this.seekReaderToFrame(reader, targetIdx);
                        if (frame != null) this.uploadFrame(frame);
                        this.nextDecodedIndex = this.currentFrameIndex + 1;
                        this.readerExhausted = false;
                    }
                }
                this.awaitSignal(PAUSE_WAIT_MS);
                continue;
            }

            // DISPLAY WINDOW: DECODE AHEAD, THEN WAIT OUT THE REMAINDER. THE WAIT IS SIGNALED
            // BY SEEK/PAUSE/STOP SO LONG FRAME DELAYS DON'T DELAY CONTROL REACTIONS.
            final long sleepBudgetMs = Math.max(1L, (long) (this.currentDelayMs / this.speed));
            final long deadline = System.currentTimeMillis() + sleepBudgetMs;
            this.fillQueueUntil(reader, deadline);
            long remaining;
            while (!Thread.currentThread().isInterrupted() && !this.signaled()
                    && (remaining = deadline - System.currentTimeMillis()) > 0L) {
                this.awaitSignal(remaining);
            }
            if (this.signaled()) continue; // RE-ENTER WITHOUT ADVANCING THE CLOCK

            // ADVANCE BY POPPING A QUEUED FRAME OR STALLING TO BUFFERING AND REFILLING.
            this.time += this.currentDelayMs;
            PrefetchedFrame next = this.prefetchQueue.pollFirst();
            if (next == null && !this.readerExhausted) {
                // DECODING FELL BEHIND, SO SWITCH TO BUFFERING, REFILL, THEN RESUME.
                this.status = Status.BUFFERING;
                if (ThreadTool.tryAcquireLock(DECODE_PERMITS, REFILL_PERMIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    try {
                        while (this.prefetchQueue.size() < this.prefetchRefill && !this.readerExhausted) {
                            if (!this.queueNextFrame(reader)) break;
                        }
                    } finally {
                        DECODE_PERMITS.release();
                    }
                }
                this.status = this.paused ? Status.PAUSED : Status.PLAYING;
                next = this.prefetchQueue.pollFirst();
            }

            if (next != null) {
                this.uploadBuffer(next.pixels, next.width, next.height);
                this.currentDelayMs = next.delay;
                this.currentFrameIndex = next.index;
                continue;
            }

            // NO FRAME AVAILABLE MEANS EOF — A CLEAN FORWARD PASS COMPLETED, SO PUBLISH THE TEXTURE.
            if (this.knownDuration <= 0L) this.knownDuration = this.time;
            this.commitCodec();
            if (this.repeat()) {
                this.clearPrefetch();
                reader = this.reopen(reader);
                if (!reader.hasNext()) {
                    this.status = Status.ENDED;
                    return reader;
                }
                this.uploadFrame(reader.next());
                this.time = 0L;
                this.currentFrameIndex = 0;
                this.currentDelayMs = delayAt(reader, 0);
                this.nextDecodedIndex = 1;
                this.readerExhausted = false;
            } else {
                this.status = Status.ENDED;
                return reader;
            }
        }
        if (this.triggerStop) {
            this.triggerStop = false;
            this.status = Status.STOPPED;
        }
        return reader;
    }

    // DECODES AHEAD UNTIL THE DEADLINE OR QUEUE LIMIT, LEAVING A SMALL SAFETY MARGIN.
    // THE SHARED PERMIT POOL BOUNDS HOW MANY PLAYERS DECODE CONCURRENTLY.
    private void fillQueueUntil(final ImageReader reader, final long deadlineMs) throws IOException {
        if (this.readerExhausted || this.prefetchQueue.size() >= this.prefetchMax) return;
        final long budget = deadlineMs - System.currentTimeMillis() - PREFETCH_SAFETY_MARGIN_MS;
        if (budget <= 0L) return;
        if (!ThreadTool.tryAcquireLock(DECODE_PERMITS, budget, TimeUnit.MILLISECONDS)) return;
        try {
            while (this.prefetchQueue.size() < this.prefetchMax && !this.readerExhausted) {
                if (deadlineMs - System.currentTimeMillis() <= PREFETCH_SAFETY_MARGIN_MS) break;
                if (!this.queueNextFrame(reader)) break;
            }
        } finally {
            DECODE_PERMITS.release();
        }
    }

    // DECODES ONE FRAME INTO THE PREFETCH QUEUE AND RETURNS FALSE AT EOF.
    private boolean queueNextFrame(final ImageReader reader) throws IOException {
        if (this.readerExhausted) return false;
        if (!reader.hasNext()) {
            this.readerExhausted = true;
            return false;
        }
        final int idx = this.nextDecodedIndex++;
        this.prefetchQueue.offerLast(this.snapshot(reader.next(), delayAt(reader, idx), idx));
        return true;
    }

    // PAUSED STEP-FORWARD PREFERS A QUEUED FRAME, THEN DECODES DIRECTLY IF NEEDED.
    private void stepForward(final ImageReader reader) throws IOException {
        this.abortCodec(); // MANUAL STEPPING DESYNCS THE IN-ORDER PASS — DROP THE PARTIAL TEXTURE
        final PrefetchedFrame queued = this.prefetchQueue.pollFirst();
        if (queued != null) {
            this.uploadBuffer(queued.pixels, queued.width, queued.height);
            this.time += this.currentDelayMs;
            this.currentDelayMs = queued.delay;
            this.currentFrameIndex = queued.index;
            return;
        }
        if (this.readerExhausted) return;
        if (reader.hasNext()) {
            final int idx = this.nextDecodedIndex++;
            this.uploadFrame(reader.next());
            this.time += this.currentDelayMs;
            this.currentDelayMs = delayAt(reader, idx);
            this.currentFrameIndex = idx;
        } else {
            this.readerExhausted = true;
        }
    }

    // ==========================================================================
    // CODEC CACHE (BC OVER DDS)
    // ==========================================================================
    // READ: a committed BC texture replays straight to the GPU — no fetch, no decode. WRITE: decoded
    // frames are recompressed to BC and persisted so the next playback takes the read path. Dormant
    // until a native BC codec is available, so today these helpers no-op transparently.

    // REPLAYS A CACHED BC TEXTURE WHEN ONE EXISTS AND THE ENGINE CAN SAMPLE IT. RETURNS FALSE TO
    // FALL BACK TO THE NORMAL FETCH+DECODE PATH; ENGINE STATE IS ONLY TOUCHED ONCE COMMITTED.
    private boolean tryCodecTextures() {
        if (!NetworkCache.codecEnabled()) return false;
        final URI uri = this.source.uri(this.quality);
        BCReader bc = null;
        try {
            if (!NetworkCache.codecReadable(uri, this.source.headers(), IMAGE_ACCEPT)) return false;
            bc = NetworkCache.openCodecReader(uri, this.source.headers(), IMAGE_ACCEPT);
            if (bc == null || !this.gfx.supportsCompressedTextures(bc.version())) return false;

            super.quality(this.source.qualityOf(uri));
            this.width = bc.width();
            this.height = bc.height();
            this.outWidth = this.width;
            this.outHeight = this.height;
            this.planeCount = 1;
            this.pixelFormat = PixelFormat.BGRA; // BC SAMPLES AS RGBA; THE RENDER PATH TREATS IT AS BGRA
            if (this.quality == MediaQuality.UNKNOWN) {
                final var realQuality = MediaQuality.of(this.width, this.height);
                this.mrl.moveQuality(this.sourceIndex, this.quality, realQuality);
                this.quality = realQuality;
            }

            final ByteBuffer[] blocks = bc.blocks();
            final long[] delays = bc.delays();
            this.gfx.setVideoFormat(this.pixelFormat, this.outWidth, this.outHeight);
            if (!this.gfx.uploadCompressedFrames(blocks, bc.version(), bc.blockBytes())) return false;
            this.gfx.useFrameTexture(0);

            this.animated = blocks.length > 1;
            this.currentFrameIndex = 0;
            this.currentDelayMs = delayAt(delays, 0);
            this.nextDecodedIndex = blocks.length;
            this.readerExhausted = true;
            if (this.animated) {
                // PASSIVE CLOCK — SAME AS MODE 2: THE DISPLAYED FRAME IS RESOLVED FROM WALL TIME.
                final long[] timeline = new long[blocks.length];
                long total = 0L;
                for (int i = 0; i < timeline.length; i++) {
                    timeline[i] = total;
                    total += Math.max(1L, delayAt(delays, i));
                }
                this.knownDuration = total;
                synchronized (this.clock) {
                    this.clockBase = 0L;
                    this.wallBase = System.currentTimeMillis();
                }
                this.texDelays = delays;
                this.texTimeline = timeline;
            } else {
                this.knownDuration = 0L;
            }
            this.loaded = true;
            this.resolveInitialStatus();
            LOGGER.debug(IT, "Loaded from codec cache: {} ({}x{}, {} {} frame(s), {})",
                    this.source, this.width, this.height, blocks.length, bc.version(),
                    this.animated ? "passive clock" : "static");
            return true;
        } catch (final Exception e) {
            LOGGER.debug(IT, "Codec cache read failed for {}; decoding from source", this.source, e);
            return false;
        } finally {
            IOTool.closeQuietly(bc);
        }
    }

    // BULK-WRITES A FULLY DECODED FRAME SET (MODE 2) TO THE CODEC CACHE IN ONE SHOT.
    private void cacheCodecFrames(final ByteBuffer[] frames, final long[] delays) {
        if (!this.codecActive) return;
        final URI uri = this.source.uri(this.quality);
        try (final NetworkCache.CodecWriter writer = NetworkCache.openCodecWriter(
                uri, this.source.headers(), IMAGE_ACCEPT, this.outWidth, this.outHeight, this.pixelFormat)) {
            if (writer == null) return;
            for (int i = 0; i < frames.length; i++) {
                final ByteBuffer f = frames[i];
                final int pos = f.position();
                writer.write(f, delayAt(delays, i));
                f.position(pos);
            }
            writer.commit();
        } catch (final IOException e) {
            LOGGER.debug(IT, "Codec cache write failed for {}", this.source, e);
        }
    }

    // OPENS A STREAMING CODEC SESSION (MODE 1/3) BEFORE THE FIRST FRAME. NO-OP WHEN INACTIVE.
    private void openCodec() {
        if (!this.codecActive || this.codecWriter != null) return;
        try {
            this.codecWriter = NetworkCache.openCodecWriter(this.source.uri(this.quality),
                    this.source.headers(), IMAGE_ACCEPT, this.outWidth, this.outHeight, this.pixelFormat);
            this.codecExpect = 0;
        } catch (final IOException e) {
            LOGGER.debug(IT, "Codec cache open failed for {}", this.source, e);
            this.codecWriter = null;
        }
    }

    // FEEDS ONE FRAME TO THE STREAMING SESSION, ONLY IN STRICT FIRST-PASS ORDER. ANY GAP (SEEK,
    // LOOP, SCRUB) ABORTS THE SESSION SO THE CACHED TEXTURE IS NEVER PARTIAL OR REORDERED.
    private void feedCodec(final ByteBuffer frame, final long delay, final int idx) {
        final NetworkCache.CodecWriter writer = this.codecWriter;
        if (writer == null) return;
        if (idx != this.codecExpect) {
            this.abortCodec();
            return;
        }
        final int pos = frame.position();
        try {
            writer.write(frame, delay);
            this.codecExpect++;
        } catch (final IOException e) {
            LOGGER.debug(IT, "Codec cache write failed for {}; aborting", this.source, e);
            this.abortCodec();
        } finally {
            frame.position(pos);
        }
    }

    // FINALIZES AND PUBLISHES THE STREAMING SESSION AFTER A CLEAN FULL FORWARD PASS.
    private void commitCodec() {
        final NetworkCache.CodecWriter writer = this.codecWriter;
        if (writer == null) return;
        this.codecWriter = null;
        try {
            writer.commit();
        } catch (final IOException e) {
            LOGGER.debug(IT, "Codec cache commit failed for {}", this.source, e);
        }
    }

    // DISCARDS THE STREAMING SESSION WITHOUT PUBLISHING (SEEK/LOOP/STOP/SIZE-CHANGE/ERROR).
    private void abortCodec() {
        final NetworkCache.CodecWriter writer = this.codecWriter;
        if (writer != null) {
            this.codecWriter = null;
            writer.abort();
        }
    }

    // BC ENCODES PACKED COLOUR LAYOUTS ONLY — YUV/GRAY FRAMES SKIP CODEC CACHING.
    private static boolean bcEncodable(final PixelFormat cs) {
        return cs == PixelFormat.BGRA || cs == PixelFormat.RGBA || cs == PixelFormat.GBRA || cs == PixelFormat.RGB;
    }

    // ==========================================================================
    // SHARED — SOURCE LIFECYCLE
    // ==========================================================================
    private ImageReader openSource() throws IOException {
        final URI uri = this.source.uri(this.quality);
        super.quality(this.source.qualityOf(uri));
        final long maxBytes = this.maxSourceBytes();
        try {
            final NetworkCache.CachedBytes sourceBytes = NetworkCache.read(uri, this.source.headers(), IMAGE_ACCEPT, maxBytes);
            final String type = sourceBytes.contentType();
            if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("image/")) {
                throw new IllegalArgumentException("Invalid content type: " + type);
            }

            final ImageReader reader = CodecsAPI.decodeImage(ByteBuffer.wrap(sourceBytes.bytes()));
            this.activeReader = reader;
            return reader;
        } catch (final Throwable t) {
            if (t instanceof final IOException io) throw io;
            if (t instanceof final RuntimeException re) throw re;
            throw new IOException("Failed to open image source: " + uri, t);
        }
    }

    // REWINDS THE READER IN PLACE WHEN THE CODEC SUPPORTS IT; OTHERWISE REOPENS THE SOURCE.
    // RESET AVOIDS RE-READING THE CACHED SOURCE FROM DISK ON EVERY LOOP ITERATION.
    private ImageReader reopen(final ImageReader reader) throws IOException {
        if (reader.reset()) return reader;
        reader.close();
        return this.openSource();
    }

    // DECODES, UPLOADS, AND PUBLISHES THE FIRST FRAME. SHARED BETWEEN STATIC AND
    // STREAMING MODES — TEXTURE MODE NEVER CALLS IT BECAUSE IT BULK-UPLOADS UPFRONT.
    private void showFirstFrame(final ImageReader reader) throws IOException {
        this.status = Status.BUFFERING;
        this.clearQueues();

        if (!reader.hasNext()) {
            throw new IOException("No frames decoded from: " + this.source);
        }

        // OPEN THE CODEC SESSION BEFORE FRAME 0 SO IT IS CAPTURED IN-ORDER (NO-OP WHEN INACTIVE).
        this.openCodec();
        final ByteBuffer first = this.copyFrame(reader.next());
        this.feedCodec(first, delayAt(reader, 0), 0);
        this.uploadBuffer(first, this.outWidth, this.outHeight);
        this.currentFrameIndex = 0;
        this.currentDelayMs = delayAt(reader, 0);
        this.time = 0L;
        this.loaded = true;
        this.nextDecodedIndex = 1;
        this.readerExhausted = false;
        this.resolveInitialStatus();
    }

    private ByteBuffer seekReaderToTime(final ImageReader reader, final long targetMs) throws IOException {
        if (!reader.hasNext()) {
            this.currentFrameIndex = 0;
            this.time = 0L;
            this.currentDelayMs = 0L;
            return null;
        }
        ByteBuffer frame = reader.next();
        long accum = 0L;
        int idx = 0;
        long delay = delayAt(reader, idx);
        while (accum + delay <= targetMs) {
            if (!reader.hasNext()) break;
            frame = reader.next();
            accum += delay;
            idx++;
            delay = delayAt(reader, idx);
        }
        this.currentFrameIndex = idx;
        this.time = accum;
        this.currentDelayMs = delay;
        return frame;
    }

    private ByteBuffer seekReaderToFrame(final ImageReader reader, final int targetIdx) throws IOException {
        if (!reader.hasNext()) {
            this.currentFrameIndex = 0;
            this.time = 0L;
            this.currentDelayMs = 0L;
            return null;
        }
        ByteBuffer frame = reader.next();
        long accum = 0L;
        int idx = 0;
        long delay = delayAt(reader, idx);
        while (idx < targetIdx) {
            if (!reader.hasNext()) break;
            frame = reader.next();
            accum += delay;
            idx++;
            delay = delayAt(reader, idx);
        }
        this.currentFrameIndex = idx;
        this.time = accum;
        this.currentDelayMs = delay;
        return frame;
    }

    private long maxSourceBytes() {
        final long limitMb = Math.max(1L, WaterMediaConfig.decoders.maxImageSourceBytesMB);
        return limitMb * 1024L * 1024L;
    }

    // RESOLVES THE PLAYBACK STATUS AFTER THE FIRST FRAME GOES LIVE, RESPECTING
    // A PENDING PAUSE TRIGGER LEFT BY startPaused().
    private void resolveInitialStatus() {
        if (this.triggerPause) {
            this.paused = true;
            this.triggerPause = false;
            this.status = Status.PAUSED;
        } else {
            this.status = Status.PLAYING;
        }
    }

    // ==========================================================================
    // SHARED — SIGNALS (CALLER TO LIFECYCLE THREAD)
    // ==========================================================================
    private boolean signaled() {
        return this.triggerStop || this.triggerPause || this.triggerResume
                || this.triggerNextFrame || this.triggerPrevFrame || this.seekTarget >= 0L;
    }

    // WAITS UP TO timeoutMs UNLESS A TRIGGER IS ALREADY PENDING. wake() INTERRUPTS THE WAIT
    // SO CONTROLS REACT IMMEDIATELY EVEN DURING LONG FRAME DELAYS.
    private void awaitSignal(final long timeoutMs) {
        synchronized (this.signals) {
            if (this.signaled()) return;
            try {
                this.signals.wait(Math.max(1L, timeoutMs));
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void wake() {
        synchronized (this.signals) {
            this.signals.notifyAll();
        }
    }

    // ==========================================================================
    // SHARED — BUFFER POOL & GPU UPLOAD
    // ==========================================================================
    // BORROWS A FRAME-SIZED DIRECT BUFFER, REUSING A POOLED BUFFER WHEN POSSIBLE.
    private ByteBuffer borrowBuffer() {
        final ByteBuffer pooled = this.bufferPool.pollFirst();
        if (pooled != null && pooled.capacity() == this.bufferByteSize) {
            pooled.clear();
            return pooled;
        }
        return ByteBuffer.allocateDirect(this.bufferByteSize).order(ByteOrder.nativeOrder());
    }

    // RETURNS A BUFFER TO THE POOL WITHOUT KEEPING MORE SPARES THAN THE PREFETCH BUDGET NEEDS.
    private void returnBuffer(final ByteBuffer buffer) {
        if (buffer == null || buffer.capacity() != this.bufferByteSize) return;
        if (this.bufferPool.size() >= this.prefetchMax + POOL_SPARE) return;
        buffer.clear();
        this.bufferPool.offerLast(buffer);
    }

    // PUSHES A BUFFER TO THE GPU AND HOLDS BACK THE LAST IN_FLIGHT_KEEP SUBMISSIONS.
    // FOR MULTI-PLANE FORMATS THE BUFFER IS THE PACKED LAYOUT [PLANE0 | PLANE1 | ...] WITH TIGHT
    // STRIDES — WE SLICE PER-PLANE VIEWS FROM IT AND CALL THE MATCHING gfx.upload OVERLOAD.
    private void uploadBuffer(final ByteBuffer buffer, final int w, final int h) {
        // SIZE FLIP (HOT maxSize/LOD CHANGE) — RECONFIGURE THE ENGINE FOR THIS FRAME
        if (this.gfx.width() != w || this.gfx.height() != h) {
            this.gfx.setVideoFormat(this.pixelFormat, w, h);
        }
        if (this.planeCount <= 1) {
            this.gfx.upload(buffer, 0);
        } else {
            this.uploadMultiPlane(buffer, w, h);
        }
        this.inFlight.offerLast(buffer);
        while (this.inFlight.size() > IN_FLIGHT_KEEP) {
            this.returnBuffer(this.inFlight.pollFirst());
        }
    }

    private void uploadMultiPlane(final ByteBuffer buffer, final int w, final int h) {
        final PixelFormat cs = this.pixelFormat;
        switch (cs) {
            case NV12, NV21 -> {
                final int chromaH = (h + 1) >> 1;
                final ByteBuffer y = sliceView(buffer, 0, w * h);
                final ByteBuffer uv = sliceView(buffer, w * h, w * chromaH);
                this.gfx.upload(y, w, uv, w);
            }
            case YUV420P -> {
                final int chromaW = (w + 1) >> 1;
                final int chromaH = (h + 1) >> 1;
                final int yLen = w * h;
                final int uvLen = chromaW * chromaH;
                final ByteBuffer y = sliceView(buffer, 0, yLen);
                final ByteBuffer u = sliceView(buffer, yLen, uvLen);
                final ByteBuffer v = sliceView(buffer, yLen + uvLen, uvLen);
                this.gfx.upload(y, w, u, chromaW, v, chromaW);
            }
            case YUV422P -> {
                final int chromaW = (w + 1) >> 1;
                final int yLen = w * h;
                final int uvLen = chromaW * h;
                final ByteBuffer y = sliceView(buffer, 0, yLen);
                final ByteBuffer u = sliceView(buffer, yLen, uvLen);
                final ByteBuffer v = sliceView(buffer, yLen + uvLen, uvLen);
                this.gfx.upload(y, w, u, chromaW, v, chromaW);
            }
            case YUV444P -> {
                final int yLen = w * h;
                final ByteBuffer y = sliceView(buffer, 0, yLen);
                final ByteBuffer u = sliceView(buffer, yLen, yLen);
                final ByteBuffer v = sliceView(buffer, yLen + yLen, yLen);
                this.gfx.upload(y, w, u, w, v, w);
            }
            case YUVA420P -> {
                final int chromaW = (w + 1) >> 1;
                final int chromaH = (h + 1) >> 1;
                final int yLen = w * h;
                final int uvLen = chromaW * chromaH;
                final ByteBuffer y = sliceView(buffer, 0, yLen);
                final ByteBuffer u = sliceView(buffer, yLen, uvLen);
                final ByteBuffer v = sliceView(buffer, yLen + uvLen, uvLen);
                final ByteBuffer a = sliceView(buffer, yLen + 2 * uvLen, yLen);
                this.gfx.upload(y, w, u, chromaW, v, chromaW, a, w);
            }
            case YUVA422P -> {
                final int chromaW = (w + 1) >> 1;
                final int yLen = w * h;
                final int uvLen = chromaW * h;
                final ByteBuffer y = sliceView(buffer, 0, yLen);
                final ByteBuffer u = sliceView(buffer, yLen, uvLen);
                final ByteBuffer v = sliceView(buffer, yLen + uvLen, uvLen);
                final ByteBuffer a = sliceView(buffer, yLen + 2 * uvLen, yLen);
                this.gfx.upload(y, w, u, chromaW, v, chromaW, a, w);
            }
            case YUVA444P -> {
                final int yLen = w * h;
                final ByteBuffer y = sliceView(buffer, 0, yLen);
                final ByteBuffer u = sliceView(buffer, yLen, yLen);
                final ByteBuffer v = sliceView(buffer, 2 * yLen, yLen);
                final ByteBuffer a = sliceView(buffer, 3 * yLen, yLen);
                this.gfx.upload(y, w, u, w, v, w, a, w);
            }
            default -> {
                LOGGER.warn(IT, "Multi-plane upload not implemented for {}; falling back to single-plane", cs);
                this.gfx.upload(buffer, 0);
            }
        }
    }

    private static ByteBuffer sliceView(final ByteBuffer src, final int offset, final int length) {
        final ByteBuffer view = src.duplicate().order(src.order());
        view.position(offset).limit(offset + length);
        return view.slice().order(src.order());
    }

    // BYTE BUDGET FOR ONE FRAME, TIGHTLY PACKED, AS A FUNCTION OF THE READER'S NATIVE LAYOUT.
    // KEEPS THE BUFFER POOL FORMAT-AGNOSTIC: ALLOCATION SIZE COMES STRAIGHT FROM (CS, W, H).
    private static long totalBufferBytes(final PixelFormat cs, final int w, final int h) {
        final long pixels = (long) w * h;
        final long chromaW = (w + 1L) >> 1;
        final long chromaH = (h + 1L) >> 1;
        return switch (cs) {
            case GRAY -> pixels;
            case YUYV, YUYV2 -> pixels * 2L;
            case RGB -> pixels * 3L;
            case BGRA, RGBA, GBRA -> pixels * 4L;
            case NV12, NV21 -> pixels + 2L * chromaW * chromaH;
            case YUV420P -> pixels + 2L * chromaW * chromaH;
            case YUV422P -> pixels + 2L * chromaW * h;
            case YUV444P -> pixels * 3L;
            case YUVA420P -> pixels * 2L + 2L * chromaW * chromaH;
            case YUVA422P -> pixels * 2L + 2L * chromaW * h;
            case YUVA444P -> pixels * 4L;
        };
    }

    // COPIES THE REUSED READER BUFFER BEFORE UPLOAD BECAUSE RENDER WORK MAY RUN ASYNCHRONOUSLY.
    private void uploadFrame(final ByteBuffer frame) {
        this.uploadBuffer(this.copyFrame(frame), this.outWidth, this.outHeight);
    }

    // COPIES A FRAME INTO A BORROWED POOL BUFFER (DOWNSCALING WHEN A maxSize/LOD
    // TARGET IS ACTIVE) AND LEAVES IT FLIPPED FOR READING.
    private ByteBuffer copyFrame(final ByteBuffer src) {
        final ByteBuffer dst = this.borrowBuffer();
        if (this.outWidth != this.width || this.outHeight != this.height) {
            this.scaleFrame(src, dst);
        } else {
            final int savedPos = src.position();
            dst.put(src);
            src.position(savedPos);
        }
        dst.flip();
        return dst;
    }

    // RESOLVES THE UPLOAD TARGET FROM maxSize/LOD AND REBUILDS THE BUFFER BUDGET ON
    // CHANGE. UNSCALABLE FORMATS UPLOAD AT SOURCE SIZE. CALLED ONLY FROM THE
    // PREPARE/LIFECYCLE THREAD SO outWidth/outHeight NEVER TEAR AGAINST A DECODE.
    private void applyTarget() throws IOException {
        int w = this.width;
        int h = this.height;
        if (scalable(this.pixelFormat)) {
            final long target = this.targetSize(w, h);
            w = (int) (target >>> 32);
            h = (int) target;
        }
        if (w == this.outWidth && h == this.outHeight) return;
        final long byteSize = totalBufferBytes(this.pixelFormat, w, h);
        if (byteSize > Integer.MAX_VALUE) {
            throw new IOException("Image dimensions exceed upload buffer limit: " + w + "x" + h);
        }
        // A HOT maxSize/LOD CHANGE MID-STREAM RESIZES SUBSEQUENT FRAMES; THE CODEC TEXTURE IS
        // FIXED-SIZE, SO DROP ANY IN-PROGRESS WRITE (NO-OP WHEN NO SESSION IS OPEN).
        this.abortCodec();
        this.outWidth = w;
        this.outHeight = h;
        this.bufferByteSize = (int) byteSize;
        this.capPrefetch();
    }

    // FORMATS THE JAVA AREA SCALER UNDERSTANDS — PACKED YUYV VARIANTS ARE EXCLUDED
    // (PIXEL PAIRS SHARE CHROMA; AVERAGING THEM BYTE-WISE WOULD MIX COMPONENTS).
    private static boolean scalable(final PixelFormat cs) {
        return cs != PixelFormat.YUYV && cs != PixelFormat.YUYV2;
    }

    // DOWNSCALES A READER FRAME (TIGHTLY PACKED PLANE LAYOUT) INTO dst AT THE UPLOAD
    // TARGET. MIRRORS THE PLANE LAYOUT USED BY totalBufferBytes/uploadMultiPlane.
    private void scaleFrame(final ByteBuffer src, final ByteBuffer dst) {
        final int sw = this.width;
        final int sh = this.height;
        final int dw = this.outWidth;
        final int dh = this.outHeight;
        final int base = src.position();
        switch (this.pixelFormat) {
            case GRAY -> scaleArea(src, base, sw, sh, dst, dw, dh, 1);
            case RGB -> scaleArea(src, base, sw, sh, dst, dw, dh, 3);
            case BGRA, RGBA, GBRA -> scaleArea(src, base, sw, sh, dst, dw, dh, 4);
            case NV12, NV21 -> {
                scaleArea(src, base, sw, sh, dst, dw, dh, 1);
                scaleArea(src, base + sw * sh, (sw + 1) >> 1, (sh + 1) >> 1, dst, (dw + 1) >> 1, (dh + 1) >> 1, 2);
            }
            case YUV420P, YUVA420P -> {
                final int scw = (sw + 1) >> 1;
                final int sch = (sh + 1) >> 1;
                final int dcw = (dw + 1) >> 1;
                final int dch = (dh + 1) >> 1;
                scaleArea(src, base, sw, sh, dst, dw, dh, 1);
                scaleArea(src, base + sw * sh, scw, sch, dst, dcw, dch, 1);
                scaleArea(src, base + sw * sh + scw * sch, scw, sch, dst, dcw, dch, 1);
                if (this.pixelFormat == PixelFormat.YUVA420P) {
                    scaleArea(src, base + sw * sh + 2 * scw * sch, sw, sh, dst, dw, dh, 1);
                }
            }
            case YUV422P, YUVA422P -> {
                final int scw = (sw + 1) >> 1;
                final int dcw = (dw + 1) >> 1;
                scaleArea(src, base, sw, sh, dst, dw, dh, 1);
                scaleArea(src, base + sw * sh, scw, sh, dst, dcw, dh, 1);
                scaleArea(src, base + sw * sh + scw * sh, scw, sh, dst, dcw, dh, 1);
                if (this.pixelFormat == PixelFormat.YUVA422P) {
                    scaleArea(src, base + sw * sh + 2 * scw * sh, sw, sh, dst, dw, dh, 1);
                }
            }
            case YUV444P, YUVA444P -> {
                final int planes = this.pixelFormat == PixelFormat.YUVA444P ? 4 : 3;
                for (int p = 0; p < planes; p++) {
                    scaleArea(src, base + p * sw * sh, sw, sh, dst, dw, dh, 1);
                }
            }
            default -> {
                // UNREACHABLE — applyTarget NEVER ACTIVATES A TARGET FOR UNSCALABLE FORMATS
                final int savedPos = src.position();
                dst.put(src);
                src.position(savedPos);
            }
        }
    }

    // AREA-AVERAGE DOWNSCALE OF AN 8-BIT PLANE WITH ch INTERLEAVED COMPONENTS.
    // EACH OUTPUT PIXEL AVERAGES ITS WHOLE SOURCE BLOCK, SO STRONG REDUCTIONS
    // (LOD FAR/FAR_AWAY) STAY ALIAS-FREE. READS src ABSOLUTE, WRITES dst RELATIVE.
    private static void scaleArea(final ByteBuffer src, final int srcOff, final int sw, final int sh,
                                  final ByteBuffer dst, final int dw, final int dh, final int ch) {
        final int[] acc = new int[ch];
        for (int dy = 0; dy < dh; dy++) {
            final int sy0 = (int) ((long) dy * sh / dh);
            final int sy1 = Math.max(sy0 + 1, (int) ((long) (dy + 1) * sh / dh));
            for (int dx = 0; dx < dw; dx++) {
                final int sx0 = (int) ((long) dx * sw / dw);
                final int sx1 = Math.max(sx0 + 1, (int) ((long) (dx + 1) * sw / dw));
                Arrays.fill(acc, 0);
                for (int sy = sy0; sy < sy1; sy++) {
                    int p = srcOff + (sy * sw + sx0) * ch;
                    for (int sx = sx0; sx < sx1; sx++) {
                        for (int c = 0; c < ch; c++) acc[c] += src.get(p++) & 0xFF;
                    }
                }
                final int count = (sy1 - sy0) * (sx1 - sx0);
                for (int c = 0; c < ch; c++) dst.put((byte) (acc[c] / count));
            }
        }
    }

    private PrefetchedFrame snapshot(final ByteBuffer frame, final long delay, final int idx) {
        final ByteBuffer pixels = this.copyFrame(frame);
        this.feedCodec(pixels, delay, idx); // STREAMING IN-ORDER FEED (NO-OP WHEN INACTIVE)
        return new PrefetchedFrame(pixels, delay, idx, this.outWidth, this.outHeight);
    }

    // DRAINS THE PREFETCH QUEUE AND RETURNS EACH QUEUED BUFFER TO THE POOL.
    private void clearPrefetch() {
        for (final PrefetchedFrame f: this.prefetchQueue) this.returnBuffer(f.pixels);
        this.prefetchQueue.clear();
    }

    // CAPS THE PREFETCH QUEUE SO DIRECT BUFFER MEMORY STAYS UNDER THE BUDGET.
    private void capPrefetch() {
        final long bytesPerFrame = Math.max(1L, this.bufferByteSize);
        final int cap = (int) Math.max(1L, Math.min(PREFETCH_MAX, PREFETCH_BUDGET_BYTES / bytesPerFrame));
        this.prefetchMax = cap;
        this.prefetchRefill = Math.min(PREFETCH_REFILL, cap);
    }

    // ==========================================================================
    // SHARED — STATE RESET
    // ==========================================================================
    private void resetForStart(final boolean initialPause) {
        this.triggerStop = false;
        this.triggerPause = initialPause;
        this.triggerResume = false;
        this.triggerNextFrame = false;
        this.triggerPrevFrame = false;
        this.seekTarget = -1L;
        this.paused = false;
        this.loaded = false;
        this.animated = false;
        this.status = Status.WAITING;
        this.time = 0L;
        this.currentDelayMs = 0L;
        this.currentFrameIndex = -1;
        this.nextDecodedIndex = 0;
        this.readerExhausted = false;
        // FORCE A TARGET RECOMPUTE — THE NEW SOURCE MAY REUSE DIMENSIONS WITH A
        // DIFFERENT PIXEL FORMAT, WHICH CHANGES THE BUFFER BYTE SIZE
        this.outWidth = 0;
        this.outHeight = 0;
        this.texTimeline = null;
        this.texDelays = null;
        this.lifecycleThread = null;
        this.activeReader = null;
        // CODEC WRITE STATE — codecActive IS RE-RESOLVED IN prepare(); THE WRITER ITSELF IS OWNED
        // AND TORN DOWN BY THE PRODUCER THREAD'S finally, SO IT IS NOT TOUCHED HERE.
        this.codecActive = false;
        this.codecExpect = 0;
        this.clearQueues();
    }

    private void clearQueues() {
        this.clearPrefetch();
        this.bufferPool.clear();
        this.inFlight.clear();
    }

    private void resetAfterRelease() {
        this.loaded = false;
        this.paused = false;
        this.status = Status.STOPPED;
        this.clearBuffers();
    }

    private void clearBuffers() {
        this.prefetchQueue.clear();
        this.bufferPool.clear();
        this.inFlight.clear();
    }

    // ==========================================================================
    // SHARED — DELAY HELPERS
    // ==========================================================================
    private static long delayAt(final ImageReader reader, final int index) {
        return delayAt(reader.delays(), index);
    }

    private static long delayAt(final long[] delays, final int index) {
        if (delays == null || delays.length == 0) return 0L;
        if (index < delays.length) return delays[Math.max(0, index)];
        return delays[delays.length - 1];
    }

    // ==========================================================================
    // PUBLIC CONTROLS
    // ==========================================================================
    @Override
    public boolean pause() { return this.pause(true); }

    @Override
    public boolean resume() { return this.pause(false); }

    @Override
    public boolean pause(final boolean paused) {
        if (!this.animated && this.loaded) {
            this.paused = paused;
            this.status = paused ? Status.PAUSED : Status.PLAYING;
            return true;
        }
        if (this.texTimeline != null) {
            // PASSIVE CLOCK: FREEZE OR REBASE WITHOUT A LIFECYCLE THREAD
            synchronized (this.clock) {
                final long now = System.currentTimeMillis();
                if (paused && this.status == Status.PLAYING) {
                    this.clockBase += (long) ((now - this.wallBase) * this.speed);
                    this.paused = true;
                    this.status = Status.PAUSED;
                } else if (!paused && this.status == Status.PAUSED) {
                    this.paused = false;
                    this.status = Status.PLAYING;
                }
                this.wallBase = now;
            }
            return true;
        }
        if (paused) { this.triggerPause = true; this.triggerResume = false; }
        else { this.triggerResume = true; this.triggerPause = false; }
        this.wake();
        return true;
    }

    @Override
    public boolean togglePlay() {
        return this.paused() ? this.resume() : this.pause();
    }

    @Override
    public boolean seek(final long time) {
        if (!this.loaded || !this.animated) return false;
        long target = Math.max(0L, time);
        if (this.knownDuration > 0L) target = Math.min(target, this.knownDuration - 1L);
        if (this.texTimeline != null) {
            synchronized (this.clock) {
                this.clockBase = target;
                this.wallBase = System.currentTimeMillis();
                if (this.status == Status.ENDED) this.status = this.paused ? Status.PAUSED : Status.PLAYING;
            }
            return true;
        }
        this.seekTarget = target;
        this.wake();
        return true;
    }

    @Override
    public boolean seekQuick(final long time) { return this.seek(time); }

    @Override
    public boolean skipTime(final long time) { return this.seek(this.time() + time); }

    @Override
    public boolean forward() { return this.skipTime(1000); }

    @Override
    public boolean rewind() { return this.skipTime(-1000); }

    @Override
    public boolean previousFrame() {
        if (!this.paused()) return false;
        if (this.texTimeline != null) return this.stepTexture(-1);
        this.triggerPrevFrame = true;
        this.wake();
        return true;
    }

    @Override
    public boolean nextFrame() {
        if (!this.paused()) return false;
        if (this.texTimeline != null) return this.stepTexture(1);
        this.triggerNextFrame = true;
        this.wake();
        return true;
    }

    // ==========================================================================
    // PUBLIC STATE QUERIES
    // ==========================================================================
    @Override
    public Status status() {
        // TEXTURE MODE RESOLVES THE ENDED TRANSITION LAZILY FROM THE PASSIVE CLOCK
        if (this.texTimeline != null && this.status == Status.PLAYING && !this.repeat()) this.texTime();
        return this.status;
    }

    @Override
    public long time() {
        return this.texTimeline != null ? this.texTime() : this.time;
    }

    @Override
    public float fps() {
        if (!this.loaded || this.currentDelayMs <= 0L) return 0f;
        return 1000f / this.currentDelayMs;
    }

    @Override public float speed() { return this.speed; }
    @Override public boolean liveSource() { return false; }
    @Override public boolean canSeek() { return this.animated; }
    @Override public boolean canPlay() { return this.loaded; }

    @Override
    public long duration() {
        return this.knownDuration > 0L ? this.knownDuration : 0L;
    }

    @Override
    public boolean speed(final float speed) {
        if (!Float.isFinite(speed) || speed <= 0 || speed > 4.0f) return false;
        synchronized (this.clock) {
            // REBASE THE PASSIVE CLOCK SO THE SPEED CHANGE DOESN'T JUMP THE MEDIA TIME
            if (this.texTimeline != null && this.status == Status.PLAYING) {
                final long now = System.currentTimeMillis();
                this.clockBase += (long) ((now - this.wallBase) * this.speed);
                this.wallBase = now;
            }
            this.speed = speed;
        }
        return true;
    }

    // ==========================================================================
    // RECORDS
    // ==========================================================================
    // SINGLE PRE-DECODED FRAME HELD IN THE PREFETCH QUEUE. CARRIES ITS OWN UPLOAD
    // DIMENSIONS SO FRAMES QUEUED BEFORE A HOT maxSize/LOD CHANGE DRAIN CORRECTLY.
    private record PrefetchedFrame(ByteBuffer pixels, long delay, int index, int width, int height) {}
}
