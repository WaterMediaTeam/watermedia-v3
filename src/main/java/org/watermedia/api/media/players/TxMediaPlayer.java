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
import org.watermedia.tools.ThreadTool;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Media player for static and animated images (PNG, APNG, GIF, WebP, NETPBM, JPEG).
 * <p>
 * Bootstrap runs on a shared single-frame pool. Static images upload once and keep no
 * dedicated player thread alive. Animated images choose the cheapest viable path:
 * <ul>
 *   <li><b>Small multi-frame:</b> if the known frame count is below the configured threshold,
 *       every frame is uploaded as its own texture and playback only switches the active handle.</li>
 *   <li><b>Multi-frame:</b> a streaming {@link ImageReader} is driven from the playback clock —
 *       each {@code next()} call decodes one frame on demand, like a video decoder. During
 *       the current frame's display window the loop pre-decodes upcoming frames into a bounded
 *       queue ("decode ahead instead of just sleeping"). If the queue empties because decoding
 *       fell behind the clock, the player drops into {@link Status#BUFFERING} and pre-decodes
 *       a handful of frames before resuming.</li>
 * </ul>
 * <p>
 * Seek / loop / step-backwards re-open the source and decode forward to the target. Animated
 * image formats are not random-access because each frame's canvas depends on prior frames,
 * exactly like video's GOP semantics.
 */
public final class TxMediaPlayer extends MediaPlayer {
    private static final Marker IT = MarkerManager.getMarker(TxMediaPlayer.class.getSimpleName());

    // PREFETCH: hold up to PREFETCH_MAX pre-decoded frames; refill PREFETCH_REFILL on BUFFERING stall.
    // Pixel buffers cycle through a pool (allocateDirect is expensive) so steady-state allocations
    // are zero. Total live memory ~= (prefetchMax + inFlight + poolSpare) * w*h*4.
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
    // METADATA (resolved on first reader open; duration may remain unknown until EOF)
    private volatile int width;
    private volatile int height;
    private volatile boolean animated;
    private volatile boolean loaded;
    private volatile long knownDuration;       // 0 = unknown / static. Set by reader metadata or by EOF.

    // STATUS
    private volatile Status status = Status.WAITING;
    private volatile float speed = 1.0f;

    // LIFECYCLE THREAD
    private volatile Thread lifecycleThread;
    private volatile Future<?> lifecycleTask;
    private volatile ImageReader activeReader;

    // TRIGGERS (CALLER → LIFECYCLE THREAD)
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

    // PREFETCH STATE (accessed only by the lifecycle thread)
    private final ArrayDeque<PrefetchedFrame> prefetchQueue = new ArrayDeque<>(PREFETCH_MAX);
    private int prefetchMax = PREFETCH_MAX;
    private int prefetchRefill = PREFETCH_REFILL;
    private int nextDecodedIndex;
    private boolean readerExhausted;

    // BUFFER POOL — direct byte buffers cycle through (queue → upload → pool) instead of being
    // allocated per frame. Uploaded buffers are retained in an in-flight ring for a few frame
    // intervals before being recycled, guarding async render-thread consumption.
    private final ArrayDeque<ByteBuffer> bufferPool = new ArrayDeque<>();
    private final ArrayDeque<InFlightFrame> inFlight = new ArrayDeque<>(IN_FLIGHT_RING_SIZE + IN_FLIGHT_SAFETY_FRAMES);
    private long uploadSerial;
    private int bufferByteSize;

    public TxMediaPlayer(final MRL mrl, final int sourceIndex, final GFXEngine gfxEngine) {
        super(mrl, sourceIndex, gfxEngine, null);
    }

    // START / STOP / RELEASE
    @Override
    public void start() {
        final boolean initialPause = this.triggerPause;
        if ((this.lifecycleThread != null && this.lifecycleThread.isAlive()) || this.lifecycleTask != null) {
            this.stop();
        }
        final Thread old = this.lifecycleThread;
        final Future<?> oldTask = this.lifecycleTask;

        this.resetStartState(initialPause);
        this.lifecycleTask = SINGLE_FRAME_POOL.submit(() -> {
            if (old != null) ThreadTool.join(old);
            if (oldTask != null && !oldTask.isDone()) oldTask.cancel(true);
            this.bootstrap();
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
        closeQuietly(this.activeReader);
        final Future<?> task = this.lifecycleTask;
        if (task != null) task.cancel(true);
        if (this.lifecycleThread != null) this.lifecycleThread.interrupt();
        return true;
    }

    @Override
    public void release() {
        this.triggerStop = true;
        closeQuietly(this.activeReader);
        final Future<?> task = this.lifecycleTask;
        if (task != null) task.cancel(true);
        final Thread t = this.lifecycleThread;
        if (t != null) t.interrupt();
        if (t != null && Thread.currentThread() != t) ThreadTool.join(t);
        this.lifecycleThread = null;
        this.lifecycleTask = null;
        this.resetDetachedState();
        super.release();
    }

    // LIFECYCLE
    private void bootstrap() {
        ImageReader reader = null;
        boolean handedOff = false;
        try {
            this.status = Status.LOADING;
            reader = this.openReader();
            this.configureReader(reader);

            if (this.triggerStop || Thread.currentThread().isInterrupted()) {
                this.status = Status.STOPPED;
                return;
            }

            if (!this.animated) {
                this.loadSingleFrame(reader);
                return;
            }

            if (this.shouldUseFrameTextures(reader)) {
                this.loadFrameTextureAnimation(reader);
                return;
            }

            final ImageReader streamingReader = reader;
            reader = null;
            handedOff = true;
            this.lifecycleThread = new Thread(() -> this.streamingLifecycle(streamingReader),
                    "TxPlayer-" + Integer.toHexString(this.source.hashCode()));
            this.lifecycleThread.setDaemon(true);
            this.lifecycleThread.start();
        } catch (final Exception e) {
            if (!Thread.currentThread().isInterrupted()) {
                LOGGER.error(IT, "Lifecycle error: {}", this.source, e);
            }
            this.status = Status.ERROR;
        } finally {
            if (!handedOff) {
                this.clearRuntimeBuffers();
                closeQuietly(reader);
                this.activeReader = null;
            }
            this.lifecycleTask = null;
        }
    }

    private void streamingLifecycle(ImageReader reader) {
        try {
            this.prepareFirstFrame(reader);
            LOGGER.debug(IT, "Loaded: {} ({}x{}, animated, duration={}ms, prefetch<={})",
                    this.source, this.width, this.height, this.knownDuration, this.prefetchMax);
            reader = this.animationLoop(reader);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            if (this.triggerStop) this.status = Status.STOPPED;
        } catch (final Exception e) {
            if (!Thread.currentThread().isInterrupted()) {
                LOGGER.error(IT, "Lifecycle error: {}", this.source, e);
            }
            this.status = Status.ERROR;
        } finally {
            this.clearRuntimeBuffers();
            closeQuietly(reader);
            this.activeReader = null;
        }
    }

    private void configureReader(final ImageReader reader) {
        this.width = reader.width();
        this.height = reader.height();
        this.animated = reader.frameCount() != 1;
        this.knownDuration = Math.max(0L, reader.duration());
        this.bufferByteSize = this.width * this.height * 4;
        this.recomputePrefetchCaps();
        this.gfx.setVideoFormat(reader.pixelFormat(), this.width, this.height);
    }

    private void prepareFirstFrame(final ImageReader reader) throws IOException {
        this.status = Status.BUFFERING;
        this.resetRuntimeQueues();

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

    private void loadSingleFrame(final ImageReader reader) throws IOException {
        this.prepareFirstFrame(reader);
        this.readerExhausted = true;
        LOGGER.debug(IT, "Loaded: {} ({}x{}, static, cache/threadless)",
                this.source, this.width, this.height);
    }

    private boolean shouldUseFrameTextures(final ImageReader reader) {
        final int threshold = Math.max(0, WaterMediaConfig.media.txMultiTextureFrameThreshold);
        final int frames = reader.frameCount();
        return threshold > 1
                && frames > 1
                && frames <= threshold
                && this.gfx.supportsFrameTextures();
    }

    private void loadFrameTextureAnimation(final ImageReader reader) throws IOException {
        this.status = Status.BUFFERING;
        this.resetRuntimeQueues();

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

        this.lifecycleThread = new Thread(() -> this.frameTextureLoop(data.delay()),
                "TxPlayer-Frames-" + Integer.toHexString(this.source.hashCode()));
        this.lifecycleThread.setDaemon(true);
        this.lifecycleThread.start();
    }

    private void frameTextureLoop(final long[] delays) {
        while (!Thread.currentThread().isInterrupted()) {
            if (this.triggerStop) {
                this.triggerStop = false;
                this.status = Status.STOPPED;
                return;
            }

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

            final long target = this.seekTarget;
            if (target >= 0L) {
                this.seekTarget = -1L;
                this.showFrameTexture(this.frameAtTime(delays, target), delays);
                if (this.paused) {
                    ThreadTool.sleep(10);
                    continue;
                }
            }

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
    }

    private void showFrameTexture(final int frame, final long[] delays) {
        this.gfx.useFrameTexture(frame);
        this.currentFrameIndex = frame;
        this.currentDelayMs = delayAt(delays, frame);
        this.time = timeAtFrame(delays, frame);
    }

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

    private void resolveInitialStatus() {
        if (this.triggerPause) {
            this.paused = true;
            this.triggerPause = false;
            this.status = Status.PAUSED;
        } else {
            this.status = Status.PLAYING;
        }
    }

    /**
     * Drives the animation clock with prefetch. Each iteration: the current frame is on screen
     * with delay {@code currentDelayMs}; while sleeping for that delay we opportunistically
     * decode upcoming frames into {@link #prefetchQueue}. When the sleep budget elapses, we
     * pop the next frame from the queue and upload it. If the queue is empty (decode fell
     * behind), we drop into {@link Status#BUFFERING}, decode {@link #prefetchRefill} frames,
     * and resume.
     */
    private ImageReader animationLoop(ImageReader reader) throws InterruptedException, IOException {
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

            // SEEK (re-open + decode forward to target). Drops the prefetch queue.
            final long target = this.seekTarget;
            if (target >= 0) {
                this.seekTarget = -1L;
                this.clearPrefetchQueue();
                reader.close();
                reader = this.openReader();
                final ByteBuffer frame = this.advanceToTime(reader, target);
                if (frame != null) this.uploadFrame(frame);
                this.nextDecodedIndex = this.currentFrameIndex + 1;
                this.readerExhausted = false;
                if (this.paused) { ThreadTool.sleep(10); continue; }
            }

            // PAUSED: FRAME STEPPING (no clock running; pre-fetch isn't active)
            if (this.paused) {
                if (this.triggerNextFrame) {
                    this.triggerNextFrame = false;
                    this.stepForward(reader);
                }
                if (this.triggerPrevFrame) {
                    this.triggerPrevFrame = false;
                    final int targetIdx = this.currentFrameIndex - 1;
                    if (targetIdx >= 0) {
                        this.clearPrefetchQueue();
                        reader.close();
                        reader = this.openReader();
                        final ByteBuffer frame = this.advanceToFrame(reader, targetIdx);
                        if (frame != null) this.uploadFrame(frame);
                        this.nextDecodedIndex = this.currentFrameIndex + 1;
                        this.readerExhausted = false;
                    }
                }
                ThreadTool.sleep(10);
                continue;
            }

            // SLEEP for the current frame's delay, decoding ahead during the wait.
            final long sleepBudgetMs = Math.max(1L, (long) (this.currentDelayMs / this.speed));
            final long deadline = System.currentTimeMillis() + sleepBudgetMs;
            this.prefetchUntil(reader, deadline);
            final long remaining = deadline - System.currentTimeMillis();
            if (remaining > 0L) ThreadTool.sleep(remaining);

            if (this.seekTarget >= 0 || this.triggerStop) continue;

            // ADVANCE: pop a frame from the queue, or stall to BUFFERING and refill.
            this.time += this.currentDelayMs;
            PrefetchedFrame next = this.prefetchQueue.pollFirst();
            if (next == null && !this.readerExhausted) {
                // We fell behind. Switch to BUFFERING, refill, then resume.
                this.status = Status.BUFFERING;
                while (this.prefetchQueue.size() < this.prefetchRefill && !this.readerExhausted) {
                    if (!this.decodeOneAhead(reader)) break;
                }
                this.status = this.paused ? Status.PAUSED : Status.PLAYING;
                next = this.prefetchQueue.pollFirst();
            }

            if (next != null) {
                this.uploadAndPin(next.pixels);
                this.currentDelayMs = next.delay;
                this.currentFrameIndex = next.index;
                continue;
            }

            // No frame available => EOF.
            if (this.knownDuration <= 0L) this.knownDuration = this.time;
            if (this.repeat()) {
                this.clearPrefetchQueue();
                reader.close();
                reader = this.openReader();
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
        return reader;
    }

    // ----- PREFETCH HELPERS -----

    /**
     * While the deadline has not elapsed and the queue has room, decode one frame ahead.
     * Leaves a small safety margin so a long decode doesn't push us past the next display tick.
     */
    private void prefetchUntil(final ImageReader reader, final long deadlineMs) throws IOException {
        while (this.prefetchQueue.size() < this.prefetchMax && !this.readerExhausted) {
            final long remaining = deadlineMs - System.currentTimeMillis();
            if (remaining <= PREFETCH_SAFETY_MARGIN_MS) break;
            if (!this.decodeOneAhead(reader)) break;
        }
    }

    /** Decodes one frame from the reader into the prefetch queue. Returns false at EOF. */
    private boolean decodeOneAhead(final ImageReader reader) throws IOException {
        if (this.readerExhausted) return false;
        if (!reader.hasNext()) {
            this.readerExhausted = true;
            return false;
        }
        final int idx = this.nextDecodedIndex++;
        this.prefetchQueue.offerLast(this.snapshot(reader.next(), delayAt(reader, idx), idx));
        return true;
    }

    /**
     * Paused step-forward: prefer a queued frame, else decode one directly.
     */
    private void stepForward(final ImageReader reader) throws IOException {
        final PrefetchedFrame queued = this.prefetchQueue.pollFirst();
        if (queued != null) {
            this.uploadAndPin(queued.pixels);
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

    // ----- BUFFER POOL -----

    /**
     * Borrows a {@code w*h*4} direct buffer. Allocates if the pool is empty; reuses (clearing
     * position/limit) otherwise.
     */
    private ByteBuffer borrowBuffer() {
        final ByteBuffer pooled = this.bufferPool.pollFirst();
        if (pooled != null && pooled.capacity() == this.bufferByteSize) {
            pooled.clear();
            return pooled;
        }
        return ByteBuffer.allocateDirect(this.bufferByteSize).order(ByteOrder.nativeOrder());
    }

    /**
     * Returns a buffer to the pool. Caps pool size at {@code prefetchMax + POOL_SPARE} so a
     * shrunk prefetch budget doesn't leave a fleet of pinned direct buffers behind.
     */
    private void returnBuffer(final ByteBuffer buffer) {
        if (buffer == null || buffer.capacity() != this.bufferByteSize) return;
        if (this.bufferPool.size() >= this.prefetchMax + POOL_SPARE) return;
        buffer.clear();
        this.bufferPool.offerLast(buffer);
    }

    /** Hands {@code buffer} to {@code gfx.upload}, retaining uploads in an in-flight safety ring. */
    private void uploadAndPin(final ByteBuffer buffer) {
        this.gfx.upload(buffer, 0);
        this.inFlight.offerLast(new InFlightFrame(buffer, this.uploadSerial++));
        this.recycleSafeInFlightBuffers();
    }

    /** Drains the prefetch queue, returning each queued buffer to the pool. */
    private void clearPrefetchQueue() {
        for (final PrefetchedFrame f: this.prefetchQueue) this.returnBuffer(f.pixels);
        this.prefetchQueue.clear();
    }

    /**
     * Recycles uploaded buffers only after they've stayed in-flight for a minimum number of
     * frame submissions and the ring has exceeded the configured retention size.
     */
    private void recycleSafeInFlightBuffers() {
        final long safeSerial = this.uploadSerial - IN_FLIGHT_SAFETY_FRAMES;
        while (this.inFlight.size() > IN_FLIGHT_RING_SIZE) {
            final InFlightFrame oldest = this.inFlight.peekFirst();
            if (oldest == null || oldest.serial() >= safeSerial) break;
            this.inFlight.pollFirst();
            this.returnBuffer(oldest.buffer());
        }
    }

    /** Caps the prefetch queue size so total queued direct-buffer memory stays under the budget. */
    private void recomputePrefetchCaps() {
        final long bytesPerFrame = (long) Math.max(1, this.width) * Math.max(1, this.height) * 4L;
        final int cap = (int) Math.max(1L, Math.min(PREFETCH_MAX, PREFETCH_BUDGET_BYTES / Math.max(bytesPerFrame, 1L)));
        this.prefetchMax = cap;
        this.prefetchRefill = Math.min(PREFETCH_REFILL, cap);
    }

    // ----- READER HELPERS -----

    private ImageReader openReader() throws IOException {
        final URI uri = this.source.uri(this.quality);
        super.quality(this.source.qualityOf(uri));
        final long maxBytes = this.maxSourceBytes();
        try {
            final NetworkCache.CachedBytes sourceBytes = NetworkCache.read(uri, this.source.headers(), "image/*,*/*", maxBytes);
            final String type = sourceBytes.contentType();
            if (!sourceBytes.cached() && (type == null || !type.startsWith("image/"))) {
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

    private ByteBuffer advanceToTime(final ImageReader reader, final long targetMs) throws IOException {
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

    private ByteBuffer advanceToFrame(final ImageReader reader, final int targetIdx) throws IOException {
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

    /**
     * Copies the reader's reused internal direct buffer into a pooled direct buffer and hands
     * it to {@link GFXEngine#upload}. The copy is required because {@code upload} may dispatch
     * asynchronously to the render thread and the reader will overwrite its buffer on the next
     * {@code next()} call.
     */
    private void uploadFrame(final ByteBuffer frame) {
        this.uploadAndPin(this.copyFrame(frame));
    }

    private PrefetchedFrame snapshot(final ByteBuffer frame, final long delay, final int idx) {
        return new PrefetchedFrame(this.copyFrame(frame), delay, idx);
    }

    /** Copies {@code src} into a borrowed pool buffer, leaving the result flipped for reading. */
    private ByteBuffer copyFrame(final ByteBuffer src) {
        final int savedPos = src.position();
        final ByteBuffer dst = this.borrowBuffer();
        dst.put(src);
        dst.flip();
        src.position(savedPos);
        return dst;
    }

    private long maxSourceBytes() {
        final long limitMb = Math.max(1L, WaterMediaConfig.decoders.maxImageSourceBytesMB);
        return limitMb * 1024L * 1024L;
    }

    private void resetStartState(final boolean initialPause) {
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
        this.resetRuntimeQueues();
    }

    private void resetRuntimeQueues() {
        this.clearPrefetchQueue();
        this.bufferPool.clear();
        this.inFlight.clear();
        this.uploadSerial = 0L;
    }

    private void resetDetachedState() {
        this.loaded = false;
        this.clearRuntimeBuffers();
    }

    private void clearRuntimeBuffers() {
        this.prefetchQueue.clear();
        this.bufferPool.clear();
        this.inFlight.clear();
    }

    private static void closeQuietly(final ImageReader r) {
        if (r == null) return;
        try { r.close(); } catch (final IOException ignored) {}
    }

    private static long delayAt(final ImageReader reader, final int index) {
        return delayAt(reader.delays(), index);
    }

    private static long delayAt(final long[] delays, final int index) {
        if (delays == null || delays.length == 0) return 0L;
        if (index < delays.length) return delays[Math.max(0, index)];
        return delays[delays.length - 1];
    }

    // CONTROLS
    @Override
    public boolean pause() { return this.pause(true); }

    @Override
    public boolean resume() { return this.pause(false); }

    @Override
    public boolean pause(final boolean paused) {
        if (!this.animated) {
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

    // STATE QUERIES
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
        if (speed <= 0 || speed > 4.0f) return false;
        this.speed = speed;
        return true;
    }

    /** A single pre-decoded frame held in the prefetch queue. */
    private record PrefetchedFrame(ByteBuffer pixels, long delay, int index) {}

    /** A previously-uploaded buffer retained to avoid premature reuse on async render paths. */
    private record InFlightFrame(ByteBuffer buffer, long serial) {}
}
