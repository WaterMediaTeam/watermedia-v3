package org.watermedia.api.media.players;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.players.util.NetworkCache;
import org.watermedia.api.media.engines.GFXEngine;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.ThreadTool;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
 *   <li><b>Mode 2 — Pre-uploaded frame textures:</b> when the known frame count sits at or
 *       below the configured threshold <i>and</i> the engine reports
 *       {@link GFXEngine#supportsFrameTextures()}, every frame is uploaded as its own texture.
 *       Playback only switches which texture handle is exposed.</li>
 *   <li><b>Mode 3 — Streaming decode with prefetch:</b> a streaming {@link ImageReader} is
 *       driven from the playback clock — each {@code next()} call decodes one frame on demand,
 *       like a video decoder. During the current frame's display window the loop pre-decodes
 *       upcoming frames into a bounded queue ("decode ahead instead of just sleeping"). If the
 *       queue empties because decoding fell behind the clock, playback drops into
 *       {@link Status#BUFFERING} and pre-decodes a handful of frames before resuming.</li>
 * </ul>
 * <p>
 * Seek / loop / step-backwards re-open the source and decode forward to the target. Animated
 * image formats are not random-access because each frame's canvas depends on prior frames,
 * exactly like video's GOP semantics.
 */
public final class TxMediaPlayer extends MediaPlayer {
    private static final Marker IT = MarkerManager.getMarker(TxMediaPlayer.class.getSimpleName());

    // ==========================================================================
    // CONSTANTS
    // ==========================================================================
    // PREFETCH: HOLD UP TO PREFETCH_MAX PRE-DECODED FRAMES; REFILL PREFETCH_REFILL ON BUFFERING STALL.
    // PIXEL BUFFERS CYCLE THROUGH A POOL SO STEADY-STATE ALLOCATIONS ARE ZERO.
    // TOTAL LIVE MEMORY IS ROUGHLY PREFETCH AND IN-FLIGHT BUFFER COUNT TIMES WIDTH*HEIGHT*4.
    private static final int PREFETCH_MAX = 6;
    private static final int PREFETCH_REFILL = 3;
    private static final int POOL_SPARE = 2;
    private static final long PREFETCH_BUDGET_BYTES = 64L * 1024 * 1024;
    private static final long PREFETCH_SAFETY_MARGIN_MS = 2L;
    private static final int IN_FLIGHT_RING_SIZE = 4;
    private static final int IN_FLIGHT_SAFETY_FRAMES = 2;
    private static final ExecutorService SINGLE_FRAME_POOL = Executors.newFixedThreadPool(
            Math.max(1, ThreadTool.minThreads()),
            ThreadTool.createFactory("TxPlayer-SingleFrame", Thread.NORM_PRIORITY - 1));

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

    // STATUS
    private volatile Status status = Status.WAITING;
    private volatile float speed = 1.0f;

    // LIFECYCLE THREAD
    private volatile Thread lifecycleThread;
    private volatile Future<?> lifecycleTask;
    private volatile ImageReader activeReader;
    private volatile int lifecycleSerial;

    // TRIGGERS (CALLER TO LIFECYCLE THREAD)
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

    // PREFETCH STATE (ACCESSED ONLY BY THE LIFECYCLE THREAD IN STREAMING MODE)
    private final ArrayDeque<PrefetchedFrame> prefetchQueue = new ArrayDeque<>(PREFETCH_MAX);
    private int prefetchMax = PREFETCH_MAX;
    private int prefetchRefill = PREFETCH_REFILL;
    private int nextDecodedIndex;
    private boolean readerExhausted;

    // BUFFER POOL: DIRECT BYTE BUFFERS CYCLE THROUGH QUEUE, UPLOAD, AND POOL INSTEAD OF BEING
    // ALLOCATED PER FRAME. UPLOADED BUFFERS STAY IN AN IN-FLIGHT RING FOR A FEW FRAME
    // INTERVALS BEFORE RECYCLING, GUARDING ASYNC RENDER-THREAD CONSUMPTION.
    private final ArrayDeque<ByteBuffer> bufferPool = new ArrayDeque<>();
    private final ArrayDeque<InFlightFrame> inFlight = new ArrayDeque<>(IN_FLIGHT_RING_SIZE + IN_FLIGHT_SAFETY_FRAMES);
    private long uploadSerial;
    private int bufferByteSize;

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
    // FOR MODES THAT NEED ONE; OTHERWISE IT IS CLOSED IN THE FINALLY BLOCK.
    // THE 'handedOff' FLAG GUARDS AGAINST DOUBLE-CLOSING A READER STILL IN USE.
    private void prepare(final int serial) {
        ImageReader reader = null;
        boolean handedOff = false;
        try {
            this.status = Status.LOADING;
            reader = this.openSource();
            this.width = reader.width();
            this.height = reader.height();
            if (this.width <= 0 || this.height <= 0) {
                throw new IOException("Invalid image dimensions: " + this.width + "x" + this.height);
            }
            this.animated = reader.frameCount() != 1;
            this.knownDuration = Math.max(0L, reader.duration());
            this.pixelFormat = reader.pixelFormat();
            this.planeCount = reader.planeCount();
            final long byteSize = totalBufferBytes(this.pixelFormat, this.width, this.height);
            if (byteSize > Integer.MAX_VALUE) {
                throw new IOException("Image dimensions exceed upload buffer limit: " + this.width + "x" + this.height);
            }
            this.bufferByteSize = (int) byteSize;
            this.capPrefetch();
            this.gfx.setVideoFormat(this.pixelFormat, this.width, this.height);

            if (this.triggerStop || Thread.currentThread().isInterrupted()) {
                this.status = Status.STOPPED;
                return;
            }

            // MODE 1: STATIC IMAGE — UPLOAD AND DONE, NO LIFECYCLE THREAD.
            if (!this.animated) {
                this.showStatic(reader);
                return;
            }

            // MODE 2: PRE-UPLOADED FRAME TEXTURES — SHORT ANIMATION ON A CAPABLE ENGINE.
            if (this.shouldUseFrameTextures(reader)) {
                this.prepareTextures(reader, serial);
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
        LOGGER.debug(IT, "Loaded: {} ({}x{}, static, cache/threadless)",
                this.source, this.width, this.height);
    }

    // ==========================================================================
    // MODE 2 — PRE-UPLOADED FRAME TEXTURES
    // ==========================================================================
    // ALL FRAMES ARE DECODED UP FRONT AND UPLOADED AS PER-FRAME TEXTURES. THE
    // LIFECYCLE THREAD ADVANCES THE CLOCK AND SWITCHES THE EXPOSED HANDLE, BUT
    // NEVER RE-DECODES OR RE-UPLOADS. SUITABLE FOR SHORT ANIMATIONS WHERE THE
    // ONE-TIME UPLOAD COST IS WORTH PAYING TO ELIMINATE PER-FRAME GPU TRAFFIC.

    // ONLY ELIGIBLE WHEN: A POSITIVE THRESHOLD IS CONFIGURED, FRAME COUNT IS
    // KNOWN, FITS UNDER THE THRESHOLD, AND THE GFX BACKEND SUPPORTS THE PATH.
    private boolean shouldUseFrameTextures(final ImageReader reader) {
        final int threshold = Math.max(0, WaterMediaConfig.media.txMultiTextureFrameThreshold);
        final int frames = reader.frameCount();
        return threshold > 1
                && frames > 1
                && frames <= threshold
                && this.gfx.supportsFrameTextures();
    }

    private void prepareTextures(final ImageReader reader, final int serial) throws IOException {
        this.status = Status.BUFFERING;
        this.clearQueues();

        // DECODE EVERY FRAME. IF readAll() YIELDS ONLY ONE FRAME (E.G. APNG WITH A SINGLE FCTL)
        // FALL BACK TO STATIC SEMANTICS RATHER THAN SPAWNING A THREAD FOR NOTHING.
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

        if (!this.gfx.uploadFrameTextures(data.frames(), 0)) {
            throw new IOException("GFXEngine rejected preloaded frame textures for: " + this.source);
        }

        this.gfx.useFrameTexture(0);
        this.knownDuration = Math.max(this.knownDuration, data.duration());
        this.currentFrameIndex = 0;
        this.currentDelayMs = delayAt(data.delay(), 0);
        this.time = 0L;
        this.loaded = true;
        this.nextDecodedIndex = data.frames().length;
        this.readerExhausted = true;
        this.resolveInitialStatus();

        LOGGER.debug(IT, "Loaded: {} ({}x{}, {} preloaded textures, duration={}ms)",
                this.source, this.width, this.height, data.frames().length, this.knownDuration);

        this.lifecycleThread = new Thread(() -> this.playTextures(data.delay(), serial),
                "TxPlayer-Frames-" + Integer.toHexString(this.source.hashCode()));
        this.lifecycleThread.setDaemon(true);
        this.lifecycleThread.start();
    }

    // FRAME-TEXTURE CLOCK LOOP. ONLY MUTATES PLAYBACK STATE AND SWITCHES TEXTURE
    // HANDLES — NEVER DECODES OR UPLOADS. SEEK / STEP / LOOP ALL RESOLVE LOCALLY
    // USING THE PRECOMPUTED DELAYS ARRAY.
    private void playTextures(final long[] delays, final int serial) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                // STOP
                if (this.triggerStop) {
                    this.triggerStop = false;
                    this.status = Status.STOPPED;
                    return;
                }

                // PAUSE / RESUME
                if (this.triggerPause && !this.paused) {
                    this.paused = true;
                    this.triggerPause = false;
                    this.status = Status.PAUSED;
                }
                if (this.triggerResume && this.paused) {
                    this.paused = false;
                    this.triggerResume = false;
                    this.status = Status.PLAYING;
                }

                // SEEK — RESOLVED LOCALLY BY WALKING THE DELAYS TABLE.
                final long target = this.seekTarget;
                if (target >= 0L) {
                    this.seekTarget = -1L;
                    this.showFrameTexture(this.frameAtTime(delays, target), delays);
                    if (this.paused) {
                        ThreadTool.sleep(10);
                        continue;
                    }
                }

                // PAUSED FRAME STEPPING (CLAMPED TO TEXTURE BOUNDS).
                if (this.paused) {
                    if (this.triggerNextFrame) {
                        this.triggerNextFrame = false;
                        this.showFrameTexture(Math.min(delays.length - 1, this.currentFrameIndex + 1), delays);
                    }
                    if (this.triggerPrevFrame) {
                        this.triggerPrevFrame = false;
                        this.showFrameTexture(Math.max(0, this.currentFrameIndex - 1), delays);
                    }
                    ThreadTool.sleep(10);
                    continue;
                }

                // WAIT THE CURRENT FRAME DELAY THEN ADVANCE, LOOP, OR END.
                final long sleepBudgetMs = Math.max(1L, (long) (this.currentDelayMs / this.speed));
                ThreadTool.sleep(sleepBudgetMs);
                if (this.seekTarget >= 0L || this.triggerStop) continue;

                final int next = this.currentFrameIndex + 1;
                if (next < delays.length) {
                    this.showFrameTexture(next, delays);
                    continue;
                }

                if (this.knownDuration <= 0L) this.knownDuration = this.time + this.currentDelayMs;
                if (this.repeat()) {
                    this.showFrameTexture(0, delays);
                } else {
                    this.time = this.knownDuration;
                    this.status = Status.ENDED;
                    return;
                }
            }
        } finally {
            if (this.triggerStop && this.lifecycleSerial == serial) {
                this.triggerStop = false;
                this.status = Status.STOPPED;
            }
        }
    }

    private void showFrameTexture(final int frame, final long[] delays) {
        this.gfx.useFrameTexture(frame);
        this.currentFrameIndex = frame;
        this.currentDelayMs = delayAt(delays, frame);
        this.time = timeAtFrame(delays, frame);
    }

    // WALK THE DELAYS TABLE TO FIND THE FRAME WHOSE DISPLAY WINDOW CONTAINS targetMs.
    // RETURNS THE LAST FRAME WHEN targetMs EXTENDS BEYOND THE ANIMATION DURATION.
    private int frameAtTime(final long[] delays, final long targetMs) {
        long accum = 0L;
        for (int i = 0; i < delays.length; i++) {
            final long delay = Math.max(1L, delayAt(delays, i));
            if (targetMs < accum + delay || i == delays.length - 1) return i;
            accum += delay;
        }
        return 0;
    }

    private static long timeAtFrame(final long[] delays, final int frame) {
        long time = 0L;
        for (int i = 0; i < frame && i < delays.length; i++) {
            time += delayAt(delays, i);
        }
        return time;
    }

    // ==========================================================================
    // MODE 3 — STREAMING DECODE WITH PREFETCH
    // ==========================================================================
    // EACH next() CALL DECODES ONE FRAME ON DEMAND. WHILE THE CURRENT FRAME IS
    // DISPLAYED, THE LOOP DECODES AHEAD INTO A BOUNDED QUEUE. IF THE QUEUE
    // EMPTIES THE PLAYER ENTERS BUFFERING, REFILLS A SMALL BATCH, AND RESUMES.
    // SEEK / LOOP / STEP-BACKWARDS REOPEN THE SOURCE AND DECODE FORWARD BECAUSE
    // ANIMATED IMAGE FRAMES DEPEND ON PRIOR ONES — THERE IS NO RANDOM ACCESS.

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
            if (this.lifecycleSerial == serial) this.clearBuffers();
            IOTool.closeQuietly(reader);
            if (this.lifecycleSerial == serial) this.activeReader = null;
        }
    }

    // STREAMING PLAYBACK LOOP. DRIVES THE ANIMATION CLOCK, PREFETCHES AHEAD,
    // AND ABSORBS PAUSE / SEEK / STEP TRIGGERS WITHOUT LEAKING DECODE WORK.
    private ImageReader playStream(ImageReader reader) throws InterruptedException, IOException {
        while (!Thread.currentThread().isInterrupted()) {
            // STOP
            if (this.triggerStop) {
                this.triggerStop = false;
                this.status = Status.STOPPED;
                return reader;
            }

            // PAUSE / RESUME
            if (this.triggerPause && !this.paused) {
                this.paused = true;
                this.triggerPause = false;
                this.status = Status.PAUSED;
            }
            if (this.triggerResume && this.paused) {
                this.paused = false;
                this.triggerResume = false;
                this.status = Status.PLAYING;
            }

            // SEEK BY REOPENING AND DECODING FORWARD TO THE TARGET, THEN DROP THE PREFETCH QUEUE.
            final long target = this.seekTarget;
            if (target >= 0) {
                this.seekTarget = -1L;
                this.clearPrefetch();
                reader.close();
                reader = this.openSource();
                final ByteBuffer frame = this.seekReaderToTime(reader, target);
                if (frame != null) this.uploadFrame(frame);
                this.nextDecodedIndex = this.currentFrameIndex + 1;
                this.readerExhausted = false;
                if (this.paused) { ThreadTool.sleep(10); continue; }
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
                        this.clearPrefetch();
                        reader.close();
                        reader = this.openSource();
                        final ByteBuffer frame = this.seekReaderToFrame(reader, targetIdx);
                        if (frame != null) this.uploadFrame(frame);
                        this.nextDecodedIndex = this.currentFrameIndex + 1;
                        this.readerExhausted = false;
                    }
                }
                ThreadTool.sleep(10);
                continue;
            }

            // SLEEP FOR THE CURRENT FRAME DELAY WHILE DECODING AHEAD DURING THE WAIT.
            final long sleepBudgetMs = Math.max(1L, (long) (this.currentDelayMs / this.speed));
            final long deadline = System.currentTimeMillis() + sleepBudgetMs;
            this.fillQueueUntil(reader, deadline);
            final long remaining = deadline - System.currentTimeMillis();
            if (remaining > 0L) ThreadTool.sleep(remaining);

            if (this.seekTarget >= 0 || this.triggerStop) continue;

            // ADVANCE BY POPPING A QUEUED FRAME OR STALLING TO BUFFERING AND REFILLING.
            this.time += this.currentDelayMs;
            PrefetchedFrame next = this.prefetchQueue.pollFirst();
            if (next == null && !this.readerExhausted) {
                // DECODING FELL BEHIND, SO SWITCH TO BUFFERING, REFILL, THEN RESUME.
                this.status = Status.BUFFERING;
                while (this.prefetchQueue.size() < this.prefetchRefill && !this.readerExhausted) {
                    if (!this.queueNextFrame(reader)) break;
                }
                this.status = this.paused ? Status.PAUSED : Status.PLAYING;
                next = this.prefetchQueue.pollFirst();
            }

            if (next != null) {
                this.uploadBuffer(next.pixels);
                this.currentDelayMs = next.delay;
                this.currentFrameIndex = next.index;
                continue;
            }

            // NO FRAME AVAILABLE MEANS EOF.
            if (this.knownDuration <= 0L) this.knownDuration = this.time;
            if (this.repeat()) {
                this.clearPrefetch();
                reader.close();
                reader = this.openSource();
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
    private void fillQueueUntil(final ImageReader reader, final long deadlineMs) throws IOException {
        while (this.prefetchQueue.size() < this.prefetchMax && !this.readerExhausted) {
            final long remaining = deadlineMs - System.currentTimeMillis();
            if (remaining <= PREFETCH_SAFETY_MARGIN_MS) break;
            if (!this.queueNextFrame(reader)) break;
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
        final PrefetchedFrame queued = this.prefetchQueue.pollFirst();
        if (queued != null) {
            this.uploadBuffer(queued.pixels);
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
    // SHARED — SOURCE LIFECYCLE
    // ==========================================================================
    private ImageReader openSource() throws IOException {
        final URI uri = this.source.uri(this.quality);
        super.quality(this.source.qualityOf(uri));
        final long maxBytes = this.maxSourceBytes();
        try {
            final NetworkCache.CachedBytes sourceBytes = NetworkCache.read(uri, this.source.headers(), "image/*,*/*", maxBytes);
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

    // DECODES, UPLOADS, AND PUBLISHES THE FIRST FRAME. SHARED BETWEEN STATIC AND
    // STREAMING MODES — TEXTURE MODE NEVER CALLS IT BECAUSE IT BULK-UPLOADS UPFRONT.
    private void showFirstFrame(final ImageReader reader) throws IOException {
        this.status = Status.BUFFERING;
        this.clearQueues();

        if (!reader.hasNext()) {
            throw new IOException("No frames decoded from: " + this.source);
        }

        this.uploadFrame(reader.next());
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
    // SHARED — BUFFER POOL & GPU UPLOAD
    // ==========================================================================
    // BORROWS A WIDTH*HEIGHT*4 DIRECT BUFFER, REUSING A POOLED BUFFER WHEN POSSIBLE.
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

    // PUSHES A BUFFER TO THE GPU AND RETAINS IT IN AN IN-FLIGHT SAFETY RING.
    // FOR MULTI-PLANE FORMATS THE BUFFER IS THE PACKED LAYOUT [PLANE0 | PLANE1 | ...] WITH TIGHT
    // STRIDES — WE SLICE PER-PLANE VIEWS FROM IT AND CALL THE MATCHING gfx.upload OVERLOAD.
    private void uploadBuffer(final ByteBuffer buffer) {
        if (this.planeCount <= 1) {
            this.gfx.upload(buffer, 0);
        } else {
            this.uploadMultiPlane(buffer);
        }
        this.inFlight.offerLast(new InFlightFrame(buffer, this.uploadSerial++));
        this.recycleBuffers();
    }

    private void uploadMultiPlane(final ByteBuffer buffer) {
        final PixelFormat cs = this.pixelFormat;
        final int w = this.width;
        final int h = this.height;
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
        this.uploadBuffer(this.copyFrame(frame));
    }

    // COPIES A FRAME INTO A BORROWED POOL BUFFER AND LEAVES IT FLIPPED FOR READING.
    private ByteBuffer copyFrame(final ByteBuffer src) {
        final int savedPos = src.position();
        final ByteBuffer dst = this.borrowBuffer();
        dst.put(src);
        dst.flip();
        src.position(savedPos);
        return dst;
    }

    private PrefetchedFrame snapshot(final ByteBuffer frame, final long delay, final int idx) {
        return new PrefetchedFrame(this.copyFrame(frame), delay, idx);
    }

    // RECYCLES UPLOADED BUFFERS ONLY AFTER THE SAFETY RING HAS ENOUGH NEWER SUBMISSIONS.
    private void recycleBuffers() {
        final long safeSerial = this.uploadSerial - IN_FLIGHT_SAFETY_FRAMES;
        while (this.inFlight.size() > IN_FLIGHT_RING_SIZE) {
            final InFlightFrame oldest = this.inFlight.peekFirst();
            if (oldest == null || oldest.serial() >= safeSerial) break;
            this.inFlight.pollFirst();
            this.returnBuffer(oldest.buffer());
        }
    }

    // DRAINS THE PREFETCH QUEUE AND RETURNS EACH QUEUED BUFFER TO THE POOL.
    private void clearPrefetch() {
        for (final PrefetchedFrame f: this.prefetchQueue) this.returnBuffer(f.pixels);
        this.prefetchQueue.clear();
    }

    // CAPS THE PREFETCH QUEUE SO DIRECT BUFFER MEMORY STAYS UNDER THE BUDGET.
    private void capPrefetch() {
        final long bytesPerFrame = (long) Math.max(1, this.width) * Math.max(1, this.height) * 4L;
        final int cap = (int) Math.max(1L, Math.min(PREFETCH_MAX, PREFETCH_BUDGET_BYTES / Math.max(bytesPerFrame, 1L)));
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
        this.uploadSerial = 0L;
        this.lifecycleThread = null;
        this.activeReader = null;
        this.clearQueues();
    }

    private void clearQueues() {
        this.clearPrefetch();
        this.bufferPool.clear();
        this.inFlight.clear();
        this.uploadSerial = 0L;
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
        if (paused) { this.triggerPause = true; this.triggerResume = false; }
        else { this.triggerResume = true; this.triggerPause = false; }
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
        this.seekTarget = target;
        return true;
    }

    @Override
    public boolean seekQuick(final long time) { return this.seek(time); }

    @Override
    public boolean skipTime(final long time) { return this.seek(this.time + time); }

    @Override
    public boolean forward() { return this.skipTime(1000); }

    @Override
    public boolean rewind() { return this.skipTime(-1000); }

    @Override
    public boolean previousFrame() {
        if (!this.paused()) return false;
        this.triggerPrevFrame = true;
        return true;
    }

    @Override
    public boolean nextFrame() {
        if (!this.paused()) return false;
        this.triggerNextFrame = true;
        return true;
    }

    // ==========================================================================
    // PUBLIC STATE QUERIES
    // ==========================================================================
    @Override public Status status() { return this.status; }
    @Override public long time() { return this.time; }

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
        this.speed = speed;
        return true;
    }

    // ==========================================================================
    // RECORDS
    // ==========================================================================
    // SINGLE PRE-DECODED FRAME HELD IN THE PREFETCH QUEUE.
    private record PrefetchedFrame(ByteBuffer pixels, long delay, int index) {}

    // PREVIOUSLY UPLOADED BUFFER RETAINED TO AVOID PREMATURE REUSE ON ASYNC RENDER PATHS.
    private record InFlightFrame(ByteBuffer buffer, long serial) {}
}
