package org.watermedia.api.media.players;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.ffmpeg.swresample.*;
import org.bytedeco.ffmpeg.swscale.*;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swresample;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.lwjgl.openal.AL10;
import org.lwjgl.opengl.GL12;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.engines.ALEngine;
import org.watermedia.api.media.engines.GLEngine;
import org.watermedia.api.media.players.util.FrameQueue;
import org.watermedia.api.media.players.util.MasterClock;
import org.watermedia.api.media.players.util.PacketQueue;
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.ThreadTool;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.watermedia.WaterMedia.LOGGER;

/**
 * MediaPlayer implementation using FFmpeg with multi-threaded architecture.
 *
 * 3 internal threads (demux, video decode, audio decode) produce decoded frames
 * into thread-safe queues. The caller thread consumes frames via pollVideoFrame()
 * and pollAudioFrame().
 *
 * Architecture follows ffplay's proven design: PacketQueues between demux and
 * decode threads, FrameQueues between decode and render, serial-based seek
 * invalidation, and pts_drift clock synchronization.
 */
public final class FFMediaPlayer extends MediaPlayer {
    private static final Marker IT = MarkerManager.getMarker(FFMediaPlayer.class.getSimpleName());
    private static final ThreadFactory DEFAULT_THREAD_FACTORY = ThreadTool.createFactory("FFThread", Thread.NORM_PRIORITY);
    private static boolean LOADED;

    // CONSTANTS
    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final int AUDIO_CHANNELS = 2;
    private static final int AUDIO_SAMPLES = 2048;
    private static final int MAX_DECODE_THREADS = ThreadTool.halfThreads();
    private static final int[] VIDEO_HW_CODECS = {
            AV_HWDEVICE_TYPE_CUDA,          // NVIDIA
            AV_HWDEVICE_TYPE_QSV,           // INTEL
            //  OS DEDICATED HARDWARE
            AV_HWDEVICE_TYPE_D3D12VA,       // WINDOWS DIRECTX 12
            AV_HWDEVICE_TYPE_D3D11VA,       // WINDOWS DIRECTX 11
            AV_HWDEVICE_TYPE_VIDEOTOOLBOX,  // MACOS
            AV_HWDEVICE_TYPE_VAAPI,         // LINUX VA-API
            AV_HWDEVICE_TYPE_VDPAU,         // LINUX VDPAU
            AV_HWDEVICE_TYPE_DRM,           // LINUX DRM
            AV_HWDEVICE_TYPE_MEDIACODEC,    // ANDROID MEDIACODEC
            //  GENERIC HARDWARE DECODERS
            AV_HWDEVICE_TYPE_VULKAN,        // VULKAN
            AV_HWDEVICE_TYPE_OPENCL,        // OPENCL
    };

    // FFMPEG COMPONENTS
    private AVFormatContext formatContext;
    private AVFormatContext slaveFormatContext;
    private AVCodecContext videoCodecContext;
    private AVCodecContext audioCodecContext;
    private SwsContext swsContext;
    private SwrContext swrContext;

    // HW
    private AVBufferRef hwDeviceCtx;
    private int hwPixelFormat = AV_PIX_FMT_NONE;
    private int swsInputFormat = AV_PIX_FMT_NONE;

    // STREAM INDEXES
    private int videoStreamIndex = -1;
    private int audioStreamIndex = -1;
    private boolean useAudioSlave = false;

    // THREADS
    private Thread lifecycleThread;
    private Thread demuxThread;
    private Thread videoDecodeThread;
    private Thread audioDecodeThread;

    // INIT SYNCHRONIZATION (DEMUX THREAD RUNS init, LIFECYCLE WAITS)
    // 0 = PENDING, 1 = SUCCESS, -1 = FAILED
    private volatile int initState = 0;

    // QUEUES
    private PacketQueue videoPacketQueue;
    private PacketQueue audioPacketQueue;
    private FrameQueue videoFrameQueue;
    private FrameQueue audioFrameQueue;

    // STATUS
    private volatile Status status = Status.WAITING;
    private volatile boolean pauseRequested = false;
    private volatile boolean qualityRequest = false;

    // SEEK (WRITTEN BY CALLER THREAD, READ BY DEMUX THREAD)
    private volatile boolean seekRequested = false;
    private volatile long seekTargetMs = -1;
    private volatile boolean seekPrecise = false;
    private final Object eofLock = new Object(); // WAKE DEMUX WHEN PARKED AT EOF


    // CLOCK
    private final MasterClock clock = new MasterClock();

    // RENDER BUFFERS (CALLER/RENDER THREAD ONLY)
    private AVFrame scaledFrame;
    private ByteBuffer videoBuffer;
    private boolean videoBufferInitialized = false;

    // TIMES
    private double videoTimeBase;
    private double audioTimeBase;

    // STATS
    private long totalSkippedFrames = 0;
    private long totalRenderedFrames = 0;

    // FORMAT CHANGE DETECTION (RENDER THREAD ONLY)
    private int lastFrameWidth;
    private int lastFrameHeight;

    public FFMediaPlayer(final MRL.Source source, final Thread renderThread, final Executor renderThreadEx,
                         final GLEngine gl, final ALEngine al, final boolean video, final boolean audio) {
        super(source, renderThread, renderThreadEx, gl, al, video, audio);
    }

    // ═══════════════════════════════════════════
    //  MEDIAPLAYER OVERRIDES
    // ═══════════════════════════════════════════

    @Override
    public void start() {
        if (this.lifecycleThread != null && this.lifecycleThread.isAlive() && !this.lifecycleThread.isInterrupted()) {
            this.stop();
        }
        final Thread oldThread = this.lifecycleThread;

        this.lifecycleThread = DEFAULT_THREAD_FACTORY.newThread(() -> {
            if (oldThread != null && !ThreadTool.join(oldThread)) {
                return;
            }
            this.lifecycle();
        });
        this.lifecycleThread.setDaemon(true);
        this.lifecycleThread.start();
    }

    @Override
    public void startPaused() {
        this.pauseRequested = true;
        this.start();
    }

    @Override
    public void release() {
        this.stop();
        super.release();
    }

    @Override
    public boolean pause(final boolean paused) {
        if (this.status == Status.ENDED || this.status == Status.STOPPED || this.status == Status.ERROR) return false;
        this.pauseRequested = paused;
        if (paused) {
            this.clock.pause();
            if (this.status == Status.PLAYING) this.status = Status.PAUSED;
        } else {
            this.clock.resume();
            if (this.status == Status.PAUSED) this.status = Status.PLAYING;
        }
        return true;
    }

    @Override
    public boolean stop() {
        if (this.lifecycleThread != null) {
            this.lifecycleThread.interrupt();
            // WAKE DEMUX IF PARKED AT EOF — interrupt ALONE WON'T UNBLOCK eofLock.wait()
            if (this.demuxThread != null) this.demuxThread.interrupt();
            return true;
        }
        return false;
    }

    @Override
    public boolean togglePlay() { return this.pause(!this.pauseRequested); }

    @Override
    public boolean seek(long timeMs) {
        if (!this.canSeek() || this.status == Status.STOPPED || this.status == Status.ERROR) return false;
        this.seekTargetMs = Math.max(0, Math.min(timeMs, this.duration()));
        this.seekPrecise = true;
        this.seekRequested = true;
        synchronized (this.eofLock) { this.eofLock.notifyAll(); } // WAKE DEMUX IF PARKED AT EOF
        return true;
    }

    @Override
    public boolean seekQuick(final long timeMs) {
        if (!this.canSeek() || this.status == Status.STOPPED || this.status == Status.ERROR) return false;
        this.seekTargetMs = Math.max(0, Math.min(timeMs, this.duration()));
        this.seekPrecise = false;
        this.seekRequested = true;
        synchronized (this.eofLock) { this.eofLock.notifyAll(); }
        return true;
    }

    @Override
    public boolean skipTime(final long timeMs) { return this.seek(this.time() + timeMs); }

    @Override
    public boolean previousFrame() {
        if (!this.canSeek()) return false;
        return this.seek(Math.max(0, this.time() - this.clock.frameDurationMs()));
    }

    @Override
    public boolean nextFrame() {
        if (!this.canSeek()) return false;
        return this.seek(this.time() + this.clock.frameDurationMs());
    }

    @Override
    public boolean foward() { return this.skipTime(5000); }

    @Override
    public boolean rewind() { return this.skipTime(-5000); }

    @Override
    public float fps() { return this.clock.fps(); }

    @Override
    public Status status() { return this.status; }

    @Override
    public boolean liveSource() { return !isNull(this.formatContext) && this.formatContext.duration() == avutil.AV_NOPTS_VALUE; }

    @Override
    public boolean canSeek() { return !this.liveSource(); }

    @Override
    public boolean canPlay() { return !isNull(this.formatContext) && (this.videoStreamIndex >= 0 || this.audioStreamIndex >= 0); }

    @Override
    public long duration() { return (isNull(this.formatContext) || this.liveSource()) ? NO_DURATION : this.formatContext.duration() / 1000; }

    @Override
    public void quality(final MRL.Quality quality) {
        super.quality(quality);
        if (this.lifecycleThread != null && this.lifecycleThread.isAlive() && !this.lifecycleThread.isInterrupted()) {
            this.qualityRequest = true;
        }
    }

    @Override
    public long time() { return this.clock.timeMs(); }

    @Override
    public boolean speed(float speed) {
        if (super.speed(speed)) {
            this.clock.speed(speed);
            return true;
        }
        return false;
    }

    public boolean isHwAccel() { return !isNull(this.hwDeviceCtx); }

    // ═══════════════════════════════════════════
    //  PUBLIC POLLING (CALLER/RENDER THREAD)
    // ═══════════════════════════════════════════

    /**
     * Poll for the next video frame. Non-blocking.
     * Call from the render/game thread in each tick.
     *
     * Performs A/V sync, sws_scale, and GL upload.
     * Drops frames that are too far behind the clock.
     *
     * @return true if a frame was rendered, false if no frame available or not time yet.
     */
    public boolean pollVideoFrame() {
        if (this.videoFrameQueue == null || !this.video || this.videoStreamIndex < 0) return false;
        if (this.scaledFrame == null) return false;

        while (true) {
            final FrameQueue.Slot slot = this.videoFrameQueue.peek();
            if (slot == null) return false;

            // SERIAL CHECK — DISCARD FRAMES FROM BEFORE A SEEK
            if (slot.serial != this.clock.serial()) {
                this.videoFrameQueue.next();
                continue;
            }

            // A/V SYNC
            final double clockSec = this.clock.time();
            final double frameSec = slot.ptsMs / 1000.0;
            final double diff = frameSec - clockSec;

            if (diff > 0.040) return false;  // FRAME TOO EARLY
            if (diff < -(this.clock.skipThresholdMs() / 1000.0)) { // FRAME TOO LATE — DROP
                this.videoFrameQueue.next();
                this.totalSkippedFrames++;
                continue;
            }

            // FORMAT CHANGE DETECTION
            if (this.swsContext == null
                    || this.swsInputFormat != slot.format
                    || this.lastFrameWidth != slot.width
                    || this.lastFrameHeight != slot.height) {
                this.recreateSwsContext(slot.width, slot.height, slot.format);
                this.lastFrameWidth = slot.width;
                this.lastFrameHeight = slot.height;
            }

            if (this.swsContext == null) {
                this.videoFrameQueue.next();
                return false;
            }

            // LAZY INIT VIDEO BUFFER
            if (!this.videoBufferInitialized) {
                this.videoBuffer = this.scaledFrame.data(0).asBuffer();
                this.videoBufferInitialized = true;
            }

            // SWS_SCALE + GL UPLOAD
            synchronized (this.videoBuffer) {
                final int result = swscale.sws_scale(
                        this.swsContext,
                        slot.frame.data(),
                        slot.frame.linesize(),
                        0,
                        slot.height,
                        this.scaledFrame.data(),
                        this.scaledFrame.linesize()
                );

                if (result <= 0) {
                    LOGGER.error(IT, "sws_scale failed: result={}, fmt={}, size={}x{}",
                            result, slot.format, slot.width, slot.height);
                    this.videoFrameQueue.next();
                    return false;
                }
            }

            final int stride = this.scaledFrame.linesize(0);
            this.upload(this.videoBuffer, stride / 4);

            // SAVE VALUES BEFORE CONSUMING (next() CLEARS THE SLOT)
            final int frameSerial = slot.serial;
            final double frameTimeSec = frameSec;

            // CONSUME
            this.videoFrameQueue.next();
            this.totalRenderedFrames++;

            // CLOCK UPDATE — ALWAYS FOR VIDEO-ONLY, OR WHEN FROZEN (POST-SEEK
            // UNFREEZE: AUDIO MAY NOT HAVE ARRIVED YET TO CALL update())
            if (this.audioStreamIndex < 0 || this.clock.frozen()) {
                this.clock.update(frameTimeSec, frameSerial);
            }

            return true;
        }
    }

    /**
     * Poll for the next audio frame. Non-blocking.
     * Call from the thread that manages OpenAL.
     *
     * Uploads decoded+resampled audio data to OpenAL buffers.
     * The clock was already updated by the audio decode thread.
     *
     * @return true if audio was uploaded, false if no frame available.
     */
    public boolean pollAudioFrame() {
        if (this.audioFrameQueue == null || !this.audio || this.audioStreamIndex < 0) return false;

        while (true) {
            final FrameQueue.Slot slot = this.audioFrameQueue.peek();
            if (slot == null) return false;

            // SERIAL CHECK
            if (slot.serial != this.clock.serial()) {
                this.audioFrameQueue.next();
                continue;
            }

            // SAVE TIMING BEFORE CONSUMING
            final long framePtsMs = slot.ptsMs;
            final int frameSerial = slot.serial;

            // EXTRACT + UPLOAD (NON-BLOCKING: IF OpenAL HAS NO BUFFER, SKIP AND RETRY LATER)
            final int dataSize = slot.frame.nb_samples() * AUDIO_CHANNELS * 2; // S16 = 2 BYTES/SAMPLE
            final ByteBuffer audioData = slot.frame.data(0)
                    .limit(dataSize)
                    .asBuffer()
                    .clear();

            if (!this.upload(audioData, AL10.AL_FORMAT_STEREO16, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS)) {
                return false; // OpenAL BUFFERS FULL — DON'T CONSUME, RETRY NEXT ITERATION
            }

            // CONSUME (ONLY AFTER SUCCESSFUL UPLOAD)
            this.audioFrameQueue.next();

            // CLOCK UPDATE — AUDIO IS THE PRIMARY CLOCK SOURCE
            this.clock.updateMs(framePtsMs, frameSerial);

            return true;
        }
    }

    // ═══════════════════════════════════════════
    //  LIFECYCLE (SUPERVISOR THREAD)
    // ═══════════════════════════════════════════

    private void lifecycle() {
        try {
            this.clock.reset();
            this.totalSkippedFrames = 0;
            this.totalRenderedFrames = 0;
            this.status = Status.LOADING;

            // CREATE QUEUES (BEFORE THREADS START)
            this.videoPacketQueue = new PacketQueue(16 * 1024 * 1024);   // 16MB VIDEO (~25s AT 5Mbps)
            this.audioPacketQueue = new PacketQueue(128 * 1024 * 1024); // 128MB AUDIO (~2h AT 140kbps)
            this.videoFrameQueue = new FrameQueue(3);
            this.audioFrameQueue = new FrameQueue(9);

            // START DEMUX THREAD — IT RUNS init() INTERNALLY SO THAT
            // avformat_open_input, avformat_find_stream_info, AND av_read_frame
            // ALL HAPPEN ON THE SAME THREAD. FFMPEG'S INTERNAL I/O STATE (HTTP
            // CONNECTION, PACKET CACHE FROM find_stream_info) IS NOT SAFELY
            // SHARED ACROSS THREADS.
            this.initState = 0;
            this.demuxThread = DEFAULT_THREAD_FACTORY.newThread(this::demuxLoop);
            this.demuxThread.setDaemon(true);
            this.demuxThread.start();

            // WAIT FOR INIT (DEMUX THREAD SIGNALS VIA VOLATILE initState)
            while (this.initState == 0 && !Thread.currentThread().isInterrupted()) {
                Thread.sleep(10);
            }

            if (this.initState != 1) {
                this.status = Status.ERROR;
                return;
            }

            // START DECODE THREADS (CODEC CONTEXTS ARE NOW SET UP BY init ON DEMUX THREAD)
            if (this.video && this.videoStreamIndex >= 0 && this.videoCodecContext != null) {
                this.videoDecodeThread = DEFAULT_THREAD_FACTORY.newThread(this::videoDecodeLoop);
                this.videoDecodeThread.setDaemon(true);
                this.videoDecodeThread.start();
            }
            if (this.audio && this.audioStreamIndex >= 0 && this.audioCodecContext != null) {
                this.audioDecodeThread = DEFAULT_THREAD_FACTORY.newThread(this::audioDecodeLoop);
                this.audioDecodeThread.setDaemon(true);
                this.audioDecodeThread.start();
            }

            if (this.pauseRequested) {
                this.status = Status.PAUSED;
                this.clock.pause();
            } else {
                this.status = Status.PLAYING;
            }

            // CONSUMPTION LOOP — POLL DECODED FRAMES AND UPLOAD TO GL/AL
            // THIS REPLACES THE OLD SINGLE-THREADED playerLoop: DECODE THREADS
            // PRODUCE INTO QUEUES, THIS LOOP CONSUMES AND UPLOADS.
            // AUDIO UPLOAD TO OpenAL BLOCKS WHEN BUFFERS ARE FULL (~40MS),
            // PROVIDING NATURAL PACING FOR AUDIO+VIDEO STREAMS.
            while (!Thread.currentThread().isInterrupted()) {
                // QUALITY SWITCH
                if (this.qualityRequest) {
                    this.qualityRequest = false;
                    this.handleQualitySwitch();
                    if (this.status == Status.ERROR) break;
                    continue;
                }

                // EXIT WHEN PLAYBACK ENDED + DEMUX DEAD (NOT PARKED FOR SEEKS)
                if (this.status == Status.ENDED && (this.demuxThread == null || !this.demuxThread.isAlive())) {
                    final boolean videoQueueEmpty = this.videoFrameQueue == null || this.videoFrameQueue.isEmpty();
                    final boolean audioQueueEmpty = this.audioFrameQueue == null || this.audioFrameQueue.isEmpty();
                    if (videoQueueEmpty && audioQueueEmpty) break;
                }

                // DETECT DEMUX DEATH DURING BUFFERING (SEEK CRASHED / AV_SEEK_FRAME FAILED)
                if (this.status == Status.BUFFERING && this.demuxThread != null && !this.demuxThread.isAlive()) {
                    LOGGER.error(IT, "Demux thread died during BUFFERING — setting ERROR");
                    this.status = Status.ERROR;
                    break;
                }

                boolean didWork = false;

                if (!this.pauseRequested) {
                    // AUDIO FIRST — DRAIN ALL DUE FRAMES BEFORE TOUCHING VIDEO.
                    // AUDIO IS THE PRIMARY CLOCK SOURCE AND MUST NOT FALL BEHIND.
                    // VIDEO sws_scale+UPLOAD AT 1080P TAKES 20-30MS PER FRAME,
                    // WHICH STEALS WALL-CLOCK TIME FROM AUDIO PACING. BY DRAINING
                    // ALL READY AUDIO FIRST, THE CLOCK STAYS ACCURATE EVEN IF
                    // VIDEO PROCESSING IS SLOW.
                    // LIMIT TO 2 AUDIO FRAMES PER ITERATION TO PREVENT
                    // THE CLOCK FROM JUMPING TOO FAR AHEAD OF VIDEO.
                    // EACH AUDIO UPDATE ADVANCES THE CLOCK ~46ms.
                    // 2 FRAMES = ~92ms, WELL WITHIN THE 200ms SKIP THRESHOLD.
                    // DRAINING ALL AT ONCE CAUSES 400ms+ CLOCK JUMPS → VIDEO DROPPED.
                    if (this.audio && this.audioStreamIndex >= 0 && this.audioFrameQueue != null) {
                        for (int i = 0; i < 2; i++) {
                            final FrameQueue.Slot aSlot = this.audioFrameQueue.peek();
                            if (aSlot == null) break;
                            if (aSlot.serial != this.clock.serial()) {
                                this.audioFrameQueue.next();
                                didWork = true;
                                i--; // SERIAL DISCARD DOESN'T COUNT TOWARDS LIMIT
                                continue;
                            }
                            final double diff = (aSlot.ptsMs / 1000.0) - this.clock.time();
                            if (diff > 0.002) break; // NOT DUE YET
                            didWork |= this.pollAudioFrame();
                        }
                    }

                    // VIDEO: ONE FRAME PER ITERATION (A/V SYNC HANDLES TIMING)
                    if (this.video && this.videoStreamIndex >= 0 && this.videoFrameQueue != null) {
                        didWork |= this.pollVideoFrame();
                    }
                }

                if (!didWork) {
                    // SLEEP UNTIL NEXT FRAME IS APPROXIMATELY DUE
                    Thread.sleep(1);
                }
            }

            LOGGER.info(IT, "Consumption loop exited — R: {}, S: {}, clock: {}ms, status: {}, " +
                            "demuxAlive: {}, vDecodeAlive: {}, aDecodeAlive: {}, vQueueSize: {}, aQueueSize: {}",
                    this.totalRenderedFrames, this.totalSkippedFrames, this.clock.timeMs(), this.status,
                    this.demuxThread != null && this.demuxThread.isAlive(),
                    this.videoDecodeThread != null && this.videoDecodeThread.isAlive(),
                    this.audioDecodeThread != null && this.audioDecodeThread.isAlive(),
                    this.videoFrameQueue != null ? this.videoFrameQueue.remaining() : -1,
                    this.audioFrameQueue != null ? this.audioFrameQueue.remaining() : -1);

            this.stopThreads();

            if (!Thread.currentThread().isInterrupted() && this.status != Status.ERROR) {
                // PIPELINE DRAINED NATURALLY → ENDED
                this.status = Status.ENDED;
            }

        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            this.stopThreads();
            this.status = Status.STOPPED;
        } catch (final Throwable e) {
            LOGGER.fatal(IT, "Error in lifecycle for URI {}", this.source.uri(this.quality), e);
            this.stopThreads();
            this.status = Status.ERROR;
        } finally {
            this.freeQueues();
            this.cleanup();
        }
    }

    // ═══════════════════════════════════════════
    //  DEMUX THREAD
    // ═══════════════════════════════════════════

    /**
     * Runs init() then reads from one or two AVFormatContexts, distributing packets to queues.
     * All format context I/O (open, find_stream_info, read_frame, seek) runs on THIS thread
     * to avoid cross-thread issues with FFmpeg's internal I/O state.
     */
    private void demuxLoop() {
        // INIT ON DEMUX THREAD — KEEPS avformat_find_stream_info AND av_read_frame
        // ON THE SAME THREAD. FFMPEG CACHES PACKETS INTERNALLY DURING find_stream_info
        // AND SERVES THEM VIA av_read_frame, BUT THIS ONLY WORKS RELIABLY ON THE SAME THREAD.
        try {
            if (!this.init()) {
                this.initState = -1;
                return;
            }
        } catch (final Throwable e) {
            LOGGER.error(IT, "Init failed with exception", e);
            this.initState = -1;
            return;
        }
        this.initState = 1;

        final AVPacket packet = avcodec.av_packet_alloc();
        final AVPacket slavePacket = this.useAudioSlave ? avcodec.av_packet_alloc() : null;
        long demuxVideoPackets = 0;
        long demuxAudioPackets = 0;
        boolean mainEof = false;
        boolean slaveEof = false;
        long demuxIgnoredPackets = 0;

        LOGGER.info(IT, "Demux loop starting — videoIdx={}, audioIdx={}, useAudioSlave={}, nb_streams={}",
                this.videoStreamIndex, this.audioStreamIndex, this.useAudioSlave,
                this.formatContext != null ? this.formatContext.nb_streams() : -1);

        try {
            while (!Thread.currentThread().isInterrupted()) {
                // ── SEEK HANDLING ──
                if (this.seekRequested) {
                    final long targetMs = this.seekTargetMs;
                    final boolean precise = this.seekPrecise;
                    this.seekRequested = false;

                    // SET BUFFERING WHILE SEEK IS PROCESSING
                    this.status = Status.BUFFERING;

                    if (precise) {
                        // PRECISE SEEK: STOP DECODE THREADS, FLUSH CODECS, SYNC DRAIN, RESTART
                        this.stopDecodeThreads();
                        this.videoPacketQueue.reset();
                        this.audioPacketQueue.reset();
                        if (this.videoCodecContext != null) avcodec.avcodec_flush_buffers(this.videoCodecContext);
                        if (this.audioCodecContext != null) avcodec.avcodec_flush_buffers(this.audioCodecContext);
                    } else {
                        // FAST SEEK: SERIAL++ → DECODE THREADS FLUSH THEIR OWN CODECS
                        this.videoPacketQueue.flush();
                        this.audioPacketQueue.flush();
                    }

                    // FORCE CLOCK TO TARGET (FROZEN UNTIL FIRST update())
                    this.clock.forceMs(targetMs, this.videoPacketQueue.serial());
                    mainEof = false;
                    slaveEof = false;

                    // SEEK BOTH CONTEXTS (MAY BLOCK ON NETWORK — clock.time() == targetMs)
                    final long ffTs = targetMs * 1000L;
                    final int flags = precise ? avformat.AVSEEK_FLAG_BACKWARD
                            : ((targetMs < this.clock.timeMs()) ? avformat.AVSEEK_FLAG_BACKWARD : 0);

                    if (avformat.av_seek_frame(this.formatContext, -1, ffTs, flags) < 0) {
                        LOGGER.error(IT, "Seek failed on main context (target={}ms)", targetMs);
                        this.status = this.pauseRequested ? Status.PAUSED : Status.PLAYING;
                        continue;
                    }
                    if (this.useAudioSlave && this.slaveFormatContext != null) {
                        avformat.av_seek_frame(this.slaveFormatContext, -1, ffTs, flags);
                    }

                    // SYNC DRAIN: FEED DECODER DIRECTLY TO BUILD REFERENCE FRAMES (PRECISE ONLY)
                    if (precise) {
                        this.syncDrain(targetMs, packet, slavePacket);
                    }

                    // RESTORE STATUS — CLOCK IS ALREADY FROZEN AT TARGET, WILL UNFREEZE
                    // WHEN THE FIRST AUDIO FRAME IS CONSUMED (clock.update())
                    this.status = this.pauseRequested ? Status.PAUSED : Status.PLAYING;
                    this.ensureDecodeThreads();

                    LOGGER.info(IT, "Seek completed to {}ms (precise={}, serial={}, clockMs={})",
                            targetMs, precise, this.videoPacketQueue.serial(), this.clock.timeMs());
                    continue;
                }

                // ── PAUSE HANDLING ──
                if (this.pauseRequested) {
                    Thread.sleep(10);
                    continue;
                }

                // ── SLAVE AUDIO: 1:1 WITH VIDEO ──
                if (this.useAudioSlave && !slaveEof && this.slaveFormatContext != null && slavePacket != null) {
                    final int slaveResult = avformat.av_read_frame(this.slaveFormatContext, slavePacket);
                    if (slaveResult >= 0) {
                        try {
                            if (slavePacket.stream_index() == this.audioStreamIndex && this.audio) {
                                if (!this.audioPacketQueue.put(slavePacket)) break;
                                demuxAudioPackets++;
                            }
                        } finally {
                            avcodec.av_packet_unref(slavePacket);
                        }
                    } else {
                        slaveEof = true;
                        LOGGER.info(IT, "Slave audio reached EOF after {} packets", demuxAudioPackets);
                        if (this.audioPacketQueue != null) this.audioPacketQueue.finish();
                    }
                }

                // ── READ PACKET (MAIN CONTEXT) ──
                if (!mainEof) {
                    final int result = avformat.av_read_frame(this.formatContext, packet);
                    if (result < 0) {
                        mainEof = true;
                        LOGGER.info(IT, "Main context EOF after {} video packets (R: {}, S: {})",
                                demuxVideoPackets, this.totalRenderedFrames, this.totalSkippedFrames);
                        if (this.videoPacketQueue != null) this.videoPacketQueue.finish();

                        if (this.repeat() && slaveEof) {
                            this.seekTargetMs = 0;
                            this.seekPrecise = false;
                            this.seekRequested = true;
                            continue;
                        }
                    }
                }

                // ── CHECK IF BOTH CONTEXTS ARE EOF ──
                if (mainEof && (slaveEof || !this.useAudioSlave)) {
                    if (this.repeat()) {
                        mainEof = false;
                        slaveEof = false;
                        this.clock.forceMs(0, this.videoPacketQueue.serial()); // IMMEDIATE RESET
                        this.seekTargetMs = 0;
                        this.seekPrecise = false;
                        this.seekRequested = true;
                        continue;
                    }

                    LOGGER.info(IT, "All contexts EOF (video: {}, audio: {})",
                            demuxVideoPackets, demuxAudioPackets);
                    if (!slaveEof && this.audioPacketQueue != null) this.audioPacketQueue.finish();

                    // DON'T JOIN DECODE THREADS — THEY DRAIN NATURALLY VIA finish().
                    // JOINING BLOCKS THE DEMUX AND PREVENTS seekRequested FROM BEING PROCESSED.
                    // THE SEEK HANDLER WILL STOP THEM IF NEEDED.
                    this.status = Status.ENDED;
                    LOGGER.info(IT, "Playback ended — demux staying alive for seeks");

                    // PARK UNTIL SEEK OR STOP (ZERO CPU)
                    synchronized (this.eofLock) {
                        while (!this.seekRequested && !Thread.currentThread().isInterrupted()) {
                            this.eofLock.wait();
                        }
                    }
                    if (!this.seekRequested) break;

                    // SEEK AFTER EOF: RESET PIPELINE (SEEK HANDLER WILL START DECODE THREADS)
                    LOGGER.info(IT, "Seek after EOF — restarting pipeline");
                    this.status = Status.BUFFERING;
                    mainEof = false;
                    slaveEof = false;
                    if (this.videoPacketQueue != null) this.videoPacketQueue.reset();
                    if (this.audioPacketQueue != null) this.audioPacketQueue.reset();
                    continue;
                }

                // ── SKIP VIDEO READ IF MAIN ALREADY AT EOF (SLAVE AUDIO STILL GOING) ──
                if (mainEof) {
                    // ONLY READ SLAVE AUDIO (HANDLED ABOVE), SKIP VIDEO PACKET PROCESSING
                    continue;
                }

                try {
                    final int streamIndex = packet.stream_index();
                    if (streamIndex == this.videoStreamIndex && this.video) {
                        // TRY NON-BLOCKING PUT. IF VIDEO QUEUE IS FULL, KEEP
                        // FEEDING AUDIO FROM SLAVE WHILE WAITING FOR SPACE.
                        // THIS PREVENTS AUDIO STARVATION DURING VIDEO BACKPRESSURE.
                        while (!this.videoPacketQueue.tryPut(packet)) {
                            if (Thread.currentThread().isInterrupted() || this.seekRequested) break;
                            if (this.useAudioSlave && this.slaveFormatContext != null && slavePacket != null) {
                                final int sr = avformat.av_read_frame(this.slaveFormatContext, slavePacket);
                                if (sr >= 0) {
                                    try {
                                        if (slavePacket.stream_index() == this.audioStreamIndex && this.audio) {
                                            this.audioPacketQueue.put(slavePacket);
                                            demuxAudioPackets++;
                                        }
                                    } finally {
                                        avcodec.av_packet_unref(slavePacket);
                                    }
                                }
                            }
                            Thread.sleep(1);
                        }
                        demuxVideoPackets++;
                    } else if (!this.useAudioSlave
                            && streamIndex == this.audioStreamIndex && this.audio) {
                        if (!this.audioPacketQueue.put(packet)) break;
                        demuxAudioPackets++;
                    }
                } finally {
                    avcodec.av_packet_unref(packet);
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            LOGGER.info(IT, "Demux loop exiting — video: {}, audio: {}, ignored: {}, interrupted: {}",
                    demuxVideoPackets, demuxAudioPackets, demuxIgnoredPackets,
                    Thread.currentThread().isInterrupted());

            // SIGNAL EOF TO DECODE THREADS: finish() (NOT abort()!) THE PacketQueues.
            // finish() LETS DECODE THREADS DRAIN ALL REMAINING PACKETS BEFORE
            // get() RETURNS NULL. abort() WOULD DISCARD PENDING PACKETS.
            // AFTER DECODE THREADS FINISH, THE LIFECYCLE'S CONSUMPTION LOOP
            // DRAINS THE FrameQueues BEFORE EXITING.
            if (this.videoPacketQueue != null) this.videoPacketQueue.finish();
            if (this.audioPacketQueue != null) this.audioPacketQueue.finish();

            avcodec.av_packet_free(packet);
            if (slavePacket != null) avcodec.av_packet_free(slavePacket);
        }
    }

    // ═══════════════════════════════════════════
    //  VIDEO DECODE THREAD
    // ═══════════════════════════════════════════

    /**
     * Consumes video packets from the queue, decodes them, handles HW transfer,
     * and pushes decoded frames into the video FrameQueue.
     */
    private void videoDecodeLoop() {
        final AVFrame tempFrame = avutil.av_frame_alloc();
        final AVFrame hwTransfer = (this.hwDeviceCtx != null) ? avutil.av_frame_alloc() : null;
        final int[] serialOut = new int[1];
        int lastSerial = -1;
        long packetsProcessed = 0;
        long framesProduced = 0;
        long framesDropped = 0;

        try {
            while (!Thread.currentThread().isInterrupted()) {
                // GET PACKET (BLOCKING)
                final AVPacket packet = this.videoPacketQueue.get(serialOut);
                if (packet == null) break; // ABORTED

                final int packetSerial = serialOut[0];

                try {
                    // SERIAL CHANGE — FLUSH CODEC TO RESET DECODER STATE AFTER SEEK.
                    // CUDA/NVDEC REQUIRES avcodec_flush_buffers TO PROPERLY RESET ITS
                    // DPB (DECODED PICTURE BUFFER). WITHOUT FLUSH, THE HW DECODER RETAINS
                    // STALE REFERENCES AND CAN'T PRODUCE OUTPUT UNTIL THE NEXT KEYFRAME.
                    // THE FLUSH TRIGGERS A CUDA REINIT (~500ms) BUT THIS IS UNAVOIDABLE
                    // FOR CORRECT HW DECODE AFTER SEEK.
                    if (packetSerial != lastSerial) {
                        if (lastSerial >= 0) avcodec.avcodec_flush_buffers(this.videoCodecContext);
                        lastSerial = packetSerial;
                    }

                    // DISCARD STALE PACKETS (SERIAL MISMATCH AFTER SEEK)
                    if (packetSerial != this.videoPacketQueue.serial()) continue;

                    // DECODE
                    if (avcodec.avcodec_send_packet(this.videoCodecContext, packet) < 0) continue;

                    while (avcodec.avcodec_receive_frame(this.videoCodecContext, tempFrame) >= 0) {
                        AVFrame frameToQueue = tempFrame;

                        // HW TRANSFER (GPU → CPU MEMORY)
                        if (hwTransfer != null && tempFrame.format() == this.hwPixelFormat) {
                            if (av_hwframe_transfer_data(hwTransfer, tempFrame, 0) < 0) {
                                LOGGER.warn(IT, "Failed to transfer frame from GPU");
                                continue;
                            }
                            frameToQueue = hwTransfer;
                        }

                        // PTS
                        final double ptsSec = tempFrame.pts() * this.videoTimeBase;
                        final long ptsMs = (long) (ptsSec * 1000.0);

                        // EARLY FRAME DROP — SKIP FRAMES TOO FAR BEHIND THE CLOCK
                        if (this.audioStreamIndex >= 0) {
                            final double drift = ptsSec - this.clock.time();
                            if (drift < -(this.clock.skipThresholdMs() / 1000.0)) {
                                this.totalSkippedFrames++;
                                framesDropped++;
                                continue;
                            }
                        }

                        // ENQUEUE — BLOCKS IF FrameQueue IS FULL (BACKPRESSURE)
                        final FrameQueue.Slot slot = this.videoFrameQueue.peekWritable();
                        if (slot == null) break; // ABORTED

                        // TRANSFER: AFTER THIS, slot.frame OWNS THE PIXEL BUFFERS
                        av_frame_unref(slot.frame); // SAFETY: ENSURE CLEAN
                        av_frame_move_ref(slot.frame, frameToQueue);
                        slot.ptsMs = ptsMs;
                        slot.durationMs = this.clock.frameDurationMs();
                        slot.serial = packetSerial;
                        slot.width = slot.frame.width();
                        slot.height = slot.frame.height();
                        slot.format = slot.frame.format();

                        this.videoFrameQueue.push();
                        framesProduced++;
                    }

                    packetsProcessed++;
                } finally {
                    avcodec.av_packet_free(packet);
                }
            }
        } finally {
            LOGGER.info(IT, "Video decode exiting — packets: {}, frames produced: {}, frames dropped: {}, interrupted: {}",
                    packetsProcessed, framesProduced, framesDropped, Thread.currentThread().isInterrupted());
            avutil.av_frame_free(tempFrame);
            if (hwTransfer != null) avutil.av_frame_free(hwTransfer);
        }
    }

    // ═══════════════════════════════════════════
    //  AUDIO DECODE THREAD
    // ═══════════════════════════════════════════

    /**
     * Consumes audio packets, decodes, resamples to S16 stereo, and pushes
     * resampled frames into the audio FrameQueue. Also updates the MasterClock
     * (audio is the primary clock source).
     */
    private void audioDecodeLoop() {
        final AVFrame tempFrame = avutil.av_frame_alloc();
        final int[] serialOut = new int[1];
        int lastSerial = -1;
        long packetsProcessed = 0;
        long framesProduced = 0;

        try {
            while (!Thread.currentThread().isInterrupted()) {
                final AVPacket packet = this.audioPacketQueue.get(serialOut);
                if (packet == null) break;

                final int packetSerial = serialOut[0];

                try {
                    // SERIAL CHANGE — FLUSH CODEC TO CLEAR STALE STATE AFTER SEEK
                    if (packetSerial != lastSerial) {
                        if (lastSerial >= 0) avcodec.avcodec_flush_buffers(this.audioCodecContext);
                        lastSerial = packetSerial;
                    }

                    // DISCARD STALE PACKETS
                    if (packetSerial != this.audioPacketQueue.serial()) continue;

                    if (avcodec.avcodec_send_packet(this.audioCodecContext, packet) < 0) continue;

                    while (avcodec.avcodec_receive_frame(this.audioCodecContext, tempFrame) >= 0) {
                        final double ptsSec = tempFrame.pts() * this.audioTimeBase;

                        // RESAMPLE + ENQUEUE
                        if (!this.enqueueResampledAudio(tempFrame, ptsSec, packetSerial)) break;
                        framesProduced++;

                        // FLUSH RESAMPLER RESIDUALS
                        final long delay = swresample.swr_get_delay(this.swrContext, AUDIO_SAMPLE_RATE);
                        if (delay > AUDIO_SAMPLES / 2) {
                            if (!this.enqueueResamplerFlush(packetSerial)) break;
                            framesProduced++;
                        }
                    }

                    packetsProcessed++;
                } finally {
                    avcodec.av_packet_free(packet);
                }
            }
        } finally {
            LOGGER.info(IT, "Audio decode exiting — packets: {}, frames produced: {}, interrupted: {}",
                    packetsProcessed, framesProduced, Thread.currentThread().isInterrupted());
            avutil.av_frame_free(tempFrame);
        }
    }

    /** Resample audio and enqueue into audioFrameQueue. Returns false if queue aborted. */
    private boolean enqueueResampledAudio(final AVFrame srcFrame, final double ptsSec, final int serial) {
        final FrameQueue.Slot slot = this.audioFrameQueue.peekWritable();
        if (slot == null) return false;

        // SETUP SLOT FRAME FOR RESAMPLED OUTPUT
        av_frame_unref(slot.frame);
        slot.frame.format(AV_SAMPLE_FMT_S16);
        slot.frame.ch_layout().nb_channels(AUDIO_CHANNELS);
        av_channel_layout_default(slot.frame.ch_layout(), AUDIO_CHANNELS);
        slot.frame.sample_rate(AUDIO_SAMPLE_RATE);
        slot.frame.nb_samples(AUDIO_SAMPLES);

        if (av_frame_get_buffer(slot.frame, 0) < 0) {
            LOGGER.warn(IT, "Failed to allocate audio buffer in slot");
            return true; // CONTINUE, DON'T BREAK THE LOOP
        }

        final int samplesConverted = swresample.swr_convert(
                this.swrContext,
                slot.frame.data(), slot.frame.nb_samples(),
                srcFrame.data(), srcFrame.nb_samples()
        );

        if (samplesConverted <= 0) {
            av_frame_unref(slot.frame);
            return true;
        }

        slot.frame.nb_samples(samplesConverted);
        slot.ptsMs = (long) (ptsSec * 1000.0);
        slot.durationMs = (long) ((double) samplesConverted / AUDIO_SAMPLE_RATE * 1000.0);
        slot.serial = serial;

        this.audioFrameQueue.push();
        return true;
    }

    /** Flush remaining samples from the resampler into the queue. */
    private boolean enqueueResamplerFlush(final int serial) {
        final FrameQueue.Slot slot = this.audioFrameQueue.peekWritable();
        if (slot == null) return false;

        av_frame_unref(slot.frame);
        slot.frame.format(AV_SAMPLE_FMT_S16);
        slot.frame.ch_layout().nb_channels(AUDIO_CHANNELS);
        av_channel_layout_default(slot.frame.ch_layout(), AUDIO_CHANNELS);
        slot.frame.sample_rate(AUDIO_SAMPLE_RATE);
        slot.frame.nb_samples(AUDIO_SAMPLES);

        if (av_frame_get_buffer(slot.frame, 0) < 0) return true;

        final int flushed = swresample.swr_convert(
                this.swrContext,
                slot.frame.data(), slot.frame.nb_samples(),
                null, 0
        );

        if (flushed <= 0) {
            av_frame_unref(slot.frame);
            return true;
        }

        slot.frame.nb_samples(flushed);
        slot.ptsMs = this.clock.timeMs();
        slot.durationMs = (long) ((double) flushed / AUDIO_SAMPLE_RATE * 1000.0);
        slot.serial = serial;

        this.audioFrameQueue.push();
        return true;
    }

    // ═══════════════════════════════════════════
    //  THREAD MANAGEMENT
    // ═══════════════════════════════════════════


    private void stopThreads() {
        if (this.videoPacketQueue != null) this.videoPacketQueue.abort();
        if (this.audioPacketQueue != null) this.audioPacketQueue.abort();
        if (this.videoFrameQueue != null) this.videoFrameQueue.abort();
        if (this.audioFrameQueue != null) this.audioFrameQueue.abort();
        if (this.demuxThread != null) this.demuxThread.interrupt();
        if (this.videoDecodeThread != null) this.videoDecodeThread.interrupt();
        if (this.audioDecodeThread != null) this.audioDecodeThread.interrupt();
        final boolean wasInterrupted = Thread.interrupted();
        if (this.demuxThread != null) ThreadTool.join(this.demuxThread);
        if (this.videoDecodeThread != null) ThreadTool.join(this.videoDecodeThread);
        if (this.audioDecodeThread != null) ThreadTool.join(this.audioDecodeThread);
        if (wasInterrupted) Thread.currentThread().interrupt();
        this.demuxThread = null;
        this.videoDecodeThread = null;
        this.audioDecodeThread = null;
    }

    /** STOP ONLY DECODE THREADS (DEMUX STAYS ALIVE). USED FOR PRECISE SEEK. */
    private void stopDecodeThreads() {
        if (this.videoPacketQueue != null) this.videoPacketQueue.abort();
        if (this.audioPacketQueue != null) this.audioPacketQueue.abort();
        final boolean wasInterrupted = Thread.interrupted();
        if (this.videoDecodeThread != null) ThreadTool.join(this.videoDecodeThread);
        if (this.audioDecodeThread != null) ThreadTool.join(this.audioDecodeThread);
        if (wasInterrupted) Thread.currentThread().interrupt();
        this.videoDecodeThread = null;
        this.audioDecodeThread = null;
    }

    /** START DECODE THREADS IF NOT ALREADY RUNNING. */
    private void ensureDecodeThreads() {
        if ((this.videoDecodeThread == null || !this.videoDecodeThread.isAlive())
                && this.video && this.videoStreamIndex >= 0 && this.videoCodecContext != null) {
            this.videoDecodeThread = DEFAULT_THREAD_FACTORY.newThread(this::videoDecodeLoop);
            this.videoDecodeThread.setDaemon(true);
            this.videoDecodeThread.start();
        }
        if ((this.audioDecodeThread == null || !this.audioDecodeThread.isAlive())
                && this.audio && this.audioStreamIndex >= 0 && this.audioCodecContext != null) {
            this.audioDecodeThread = DEFAULT_THREAD_FACTORY.newThread(this::audioDecodeLoop);
            this.audioDecodeThread.setDaemon(true);
            this.audioDecodeThread.start();
        }
    }

    /**
     * SYNC DRAIN: READ PACKETS + DECODE FORWARD TO TARGET PTS ON THE DEMUX THREAD.
     * FEEDS THE DECODER DIRECTLY (NO QUEUES) SO REFERENCE FRAMES ARE BUILT IMMEDIATELY.
     * DECODE THREADS MUST BE STOPPED BEFORE CALLING THIS (CODEC CONTEXTS NOT THREAD-SAFE).
     */
    private void syncDrain(final long targetMs, final AVPacket packet, final AVPacket slavePacket) {
        final AVFrame drainFrame = avutil.av_frame_alloc();
        final long threshold = targetMs - this.clock.frameDurationMs();
        int maxDrain = 500;
        boolean videoReached = !this.video || this.videoCodecContext == null;
        boolean audioReached = !this.audio || this.audioCodecContext == null;

        while (maxDrain-- > 0 && !Thread.currentThread().isInterrupted()
                && !this.seekRequested && (!videoReached || !audioReached)) {

            if (this.useAudioSlave) {
                // SLAVE: VIDEO FROM MAIN, AUDIO FROM SLAVE (SEPARATE CONTEXTS)
                if (!videoReached) {
                    videoReached = this.drainRead(this.formatContext, packet,
                            this.videoStreamIndex, this.videoCodecContext, drainFrame,
                            this.videoTimeBase, threshold);
                }
                if (!audioReached && slavePacket != null && this.slaveFormatContext != null) {
                    audioReached = this.drainRead(this.slaveFormatContext, slavePacket,
                            this.audioStreamIndex, this.audioCodecContext, drainFrame,
                            this.audioTimeBase, threshold);
                }
            } else {
                // NON-SLAVE: INTERLEAVED — READ ONE PACKET, ROUTE BY STREAM INDEX
                final int readRes = avformat.av_read_frame(this.formatContext, packet);
                if (readRes < 0) break;
                try {
                    final int idx = packet.stream_index();
                    if (idx == this.videoStreamIndex && !videoReached) {
                        videoReached = this.drainDecode(this.videoCodecContext, packet, drainFrame,
                                this.videoTimeBase, threshold);
                    } else if (idx == this.audioStreamIndex && !audioReached) {
                        audioReached = this.drainDecode(this.audioCodecContext, packet, drainFrame,
                                this.audioTimeBase, threshold);
                    }
                } finally {
                    avcodec.av_packet_unref(packet);
                }
            }
        }
        avutil.av_frame_free(drainFrame);
    }

    /** READ ONE PACKET FROM CONTEXT, DECODE IF STREAM MATCHES, RETURN TRUE IF TARGET REACHED. */
    private boolean drainRead(final AVFormatContext ctx, final AVPacket pkt,
                              final int streamIndex, final AVCodecContext codec,
                              final AVFrame frame, final double timeBase, final long threshold) {
        final int res = avformat.av_read_frame(ctx, pkt);
        if (res < 0) return false;
        try {
            if (pkt.stream_index() == streamIndex) {
                return this.drainDecode(codec, pkt, frame, timeBase, threshold);
            }
        } finally {
            avcodec.av_packet_unref(pkt);
        }
        return false;
    }

    /** SEND PACKET TO DECODER, RETURN TRUE IF ANY OUTPUT FRAME REACHED THE THRESHOLD PTS. */
    private boolean drainDecode(final AVCodecContext codec, final AVPacket pkt,
                                final AVFrame frame, final double timeBase, final long threshold) {
        avcodec.avcodec_send_packet(codec, pkt);
        while (avcodec.avcodec_receive_frame(codec, frame) >= 0) {
            if ((long) (frame.pts() * timeBase * 1000) >= threshold) return true;
        }
        return false;
    }

    private void freeQueues() {
        if (this.videoFrameQueue != null) { this.videoFrameQueue.free(); this.videoFrameQueue = null; }
        if (this.audioFrameQueue != null) { this.audioFrameQueue.free(); this.audioFrameQueue = null; }
        if (this.videoPacketQueue != null) { this.videoPacketQueue.free(); this.videoPacketQueue = null; }
        if (this.audioPacketQueue != null) { this.audioPacketQueue.free(); this.audioPacketQueue = null; }
    }

    // ═══════════════════════════════════════════
    //  QUALITY SWITCH
    // ═══════════════════════════════════════════

    private void handleQualitySwitch() {
        final long currentMs = this.clock.timeMs();
        final boolean wasPaused = this.pauseRequested;

        LOGGER.info(IT, "Switching quality to {} at {}ms", this.quality, currentMs);

        this.clock.pause();
        this.status = Status.BUFFERING;

        // STOP ALL INTERNAL THREADS
        this.stopThreads();

        // CLEANUP FFMPEG CONTEXTS
        this.cleanup();

        // RE-INIT VIA DEMUX THREAD (SAME THREAD PATTERN AS STARTUP)
        this.totalSkippedFrames = 0;
        this.totalRenderedFrames = 0;

        // RESET QUEUES (CLEAR + UNFLAG ABORT)
        this.videoPacketQueue.reset();
        this.audioPacketQueue.reset();
        this.videoFrameQueue.reset();
        this.audioFrameQueue.reset();

        // SEEK TO SAVED POSITION (DEMUX THREAD WILL HANDLE THIS AFTER INIT)
        this.seekTargetMs = currentMs;
        this.seekPrecise = true;
        this.seekRequested = true;

        // START DEMUX THREAD (DOES INIT + READ LOOP)
        this.initState = 0;
        this.demuxThread = DEFAULT_THREAD_FACTORY.newThread(this::demuxLoop);
        this.demuxThread.setDaemon(true);
        this.demuxThread.start();

        // WAIT FOR INIT
        try {
            while (this.initState == 0 && !Thread.currentThread().isInterrupted()) {
                Thread.sleep(10);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            this.status = Status.ERROR;
            return;
        }

        if (this.initState != 1) {
            this.status = Status.ERROR;
            return;
        }

        // DO NOT START DECODE THREADS HERE — THE SEEK HANDLER IN THE DEMUX
        // THREAD WILL START THEM AFTER THE PRECISE SEEK DRAIN. STARTING THEM
        // HERE CREATES A RACE: handleQualitySwitch STARTS THEM, THEN THE
        // SEEK HANDLER KILLS THEM (abort+join+null), AND THE CONSUMPTION LOOP
        // SEES decodeThread==null + queuesEmpty → DRAIN CHECK → ENDED.

        // RESTORE CLOCK STATE
        this.clock.forceMs(currentMs, this.videoPacketQueue.serial());

        if (wasPaused) {
            this.status = Status.PAUSED;
            this.clock.pause();
        } else {
            this.status = Status.PLAYING;
            this.clock.resume();
        }

        LOGGER.info(IT, "Successfully switched quality to {}", this.quality);
    }

    // ═══════════════════════════════════════════
    //  INIT
    // ═══════════════════════════════════════════

    private boolean init() {
        try {
            // ALLOCATE RENDER FRAME
            this.scaledFrame = avutil.av_frame_alloc();
            if (this.scaledFrame == null) return false;

            // PREPARE URI
            final var uri = this.source.uri(this.quality);
            final var url = uri.getScheme().contains("file") ? uri.getPath().substring(1) : uri.toString();

            // RESOLVE AUDIO SLAVE (INDEX 0)
            final var audioSlaves = this.source.audioSlaves();
            MRL.Slave audioSlave = null;
            if (this.audio && !audioSlaves.isEmpty()) {
                audioSlave = audioSlaves.get(0);
            }

            // OPEN CONTEXT
            this.formatContext = avformat.avformat_alloc_context();
            final AVDictionary options = new AVDictionary();

            try {
                av_dict_set(options, "headers", "User-Agent: " + WaterMedia.USER_AGENT + "\r\n" +
                        "Accept: video/*,audio/*,image/*,application/vnd.apple.mpegurl,application/x-mpegurl,application/dash+xml,application/ogg,*/*;q=0.8\r\n" +
                        "Referer: " + uri.getScheme() + "://" + uri.getHost() + "/\r\n", 0);
                av_dict_set(options, "buffer_size", "33554432", 0);
                av_dict_set(options, "rtbufsize", "15000000", 0);
                av_dict_set(options, "http_persistent", "1", 0);
                av_dict_set(options, "multiple_requests", "0", 0);
                av_dict_set(options, "reconnect", "1", 0);
                av_dict_set(options, "reconnect_streamed", "1", 0);
                av_dict_set(options, "reconnect_delay_max", "5", 0);
                av_dict_set(options, "timeout", "10000000", 0);
                av_dict_set(options, "rtsp_transport", "tcp", 0);
                av_dict_set(options, "max_delay", "5000000", 0);

                if (avformat.avformat_open_input(this.formatContext, url, null, options) < 0) {
                    LOGGER.error(IT, "Failed to open input: {}", url);
                    return false;
                }
            } finally {
                av_dict_free(options);
            }

            if (avformat.avformat_find_stream_info(this.formatContext, (PointerPointer<?>) null) < 0) {
                LOGGER.error(IT, "Failed to find stream info");
                return false;
            }

            // FIND AVAILABLE STREAMS
            for (int i = 0; i < this.formatContext.nb_streams(); i++) {
                final AVStream stream = this.formatContext.streams(i);
                final int codecType = stream.codecpar().codec_type();

                if (codecType == avutil.AVMEDIA_TYPE_VIDEO && this.videoStreamIndex < 0 && this.video) {
                    this.videoStreamIndex = i;
                    this.videoTimeBase = av_q2d(stream.time_base());
                } else if (codecType == avutil.AVMEDIA_TYPE_AUDIO && this.audioStreamIndex < 0 && this.audio && audioSlave == null) {
                    this.audioStreamIndex = i;
                    this.audioTimeBase = av_q2d(stream.time_base());
                }
            }

            // OPEN AUDIO SLAVE IF AVAILABLE
            if (audioSlave != null) {
                this.useAudioSlave = this.initAudioSlave(audioSlave);
                if (!this.useAudioSlave) {
                    LOGGER.warn(IT, "Audio slave failed to open, falling back to main audio stream");
                    for (int i = 0; i < this.formatContext.nb_streams(); i++) {
                        final AVStream stream = this.formatContext.streams(i);
                        if (stream.codecpar().codec_type() == avutil.AVMEDIA_TYPE_AUDIO && this.audioStreamIndex < 0) {
                            this.audioStreamIndex = i;
                            this.audioTimeBase = av_q2d(stream.time_base());
                        }
                    }
                }
            }

            final boolean videoInit = this.initVideo();
            final boolean audioInit = this.initAudio();

            if (!videoInit && !audioInit)
                throw new IllegalStateException("Video and Audio failed to initialize");

            LOGGER.info(IT, "FFMediaPlayer started - video: {} (hw: {}), audio: {}", videoInit, this.isHwAccel(), audioInit);
            return true;
        } catch (final Exception e) {
            LOGGER.error(IT, "Failed to initialize FFMediaPlayer", e);
            return false;
        }
    }

    private boolean initAudioSlave(final MRL.Slave slave) {
        final var slaveUri = slave.uri(this.quality);
        if (slaveUri == null) {
            LOGGER.error(IT, "Audio slave has no URI for quality {}", this.quality);
            return false;
        }

        final var slaveUrl = slaveUri.getScheme().contains("file") ? slaveUri.getPath().substring(1) : slaveUri.toString();

        this.slaveFormatContext = avformat.avformat_alloc_context();
        final AVDictionary slaveOptions = new AVDictionary();

        try {
            av_dict_set(slaveOptions, "headers", "User-Agent: " + WaterMedia.USER_AGENT + "\r\n", 0);
            av_dict_set(slaveOptions, "reconnect", "1", 0);
            av_dict_set(slaveOptions, "reconnect_streamed", "1", 0);
            av_dict_set(slaveOptions, "reconnect_delay_max", "5", 0);
            av_dict_set(slaveOptions, "timeout", "10000000", 0);

            if (avformat.avformat_open_input(this.slaveFormatContext, slaveUrl, null, slaveOptions) < 0) {
                LOGGER.error(IT, "Failed to open audio slave input: {}", slaveUrl);
                this.slaveFormatContext = null;
                return false;
            }
        } finally {
            av_dict_free(slaveOptions);
        }

        if (avformat.avformat_find_stream_info(this.slaveFormatContext, (PointerPointer<?>) null) < 0) {
            LOGGER.error(IT, "Failed to find stream info for audio slave");
            avformat.avformat_close_input(this.slaveFormatContext);
            this.slaveFormatContext = null;
            return false;
        }

        // FIND AUDIO STREAM IN SLAVE CONTEXT
        for (int i = 0; i < this.slaveFormatContext.nb_streams(); i++) {
            final AVStream stream = this.slaveFormatContext.streams(i);
            if (stream.codecpar().codec_type() == avutil.AVMEDIA_TYPE_AUDIO) {
                this.audioStreamIndex = i;
                this.audioTimeBase = av_q2d(stream.time_base());
                LOGGER.info(IT, "Audio slave opened: {}", slaveUrl);
                return true;
            }
        }

        LOGGER.error(IT, "No audio stream found in audio slave: {}", slaveUrl);
        avformat.avformat_close_input(this.slaveFormatContext);
        this.slaveFormatContext = null;
        return false;
    }

    private boolean initVideo() {
        if (!this.video || this.videoStreamIndex < 0) {
            LOGGER.warn(IT, "No video stream found or video disabled");
            return true; // NOT AN ERROR WHEN VIDEO IS DISABLED (AUDIO-ONLY IS VALID)
        }

        final AVStream videoStream = this.formatContext.streams(this.videoStreamIndex);
        final AVRational frameRate = videoStream.avg_frame_rate();

        if (frameRate.num() > 0 && frameRate.den() > 0) {
            this.clock.fps((float) av_q2d(frameRate));
        } else {
            final AVRational tbr = videoStream.r_frame_rate();
            if (tbr.num() > 0 && tbr.den() > 0) {
                this.clock.fps((float) av_q2d(tbr));
            }
        }

        LOGGER.info(IT, "Video timing: {}fps, frame duration: {}ms, skip threshold: {}ms",
                this.clock.fps(), this.clock.frameDurationMs(), this.clock.skipThresholdMs());

        // FIND DECODER
        final AVCodecParameters codecpar = videoStream.codecpar();
        final int codecId = codecpar.codec_id();

        final String codecName = avcodec_get_name(codecId).getString();
        final AVCodecDescriptor descriptor = avcodec_descriptor_get(codecId);
        final String codecLongName = descriptor != null ? descriptor.long_name().getString() : "unknown";
        final long bitrate = codecpar.bit_rate();
        final int profile = codecpar.profile();
        final int level = codecpar.level();
        final int pixFmt = codecpar.format();

        LOGGER.info(IT, "Video codec: {} ({}), bitrate: {} kbps, profile: {}, level: {}, pixel format: {}",
                codecName, codecLongName,
                bitrate > 0 ? bitrate / 1000 : "N/A",
                avcodec.avcodec_profile_name(codecId, profile) != null
                        ? avcodec.avcodec_profile_name(codecId, profile).getString() : profile,
                level,
                av_get_pix_fmt_name(pixFmt) != null ? av_get_pix_fmt_name(pixFmt).getString() : pixFmt);

        final AVCodec decoder = avcodec_find_decoder(codecId);
        if (decoder == null)
            LOGGER.error(IT, "Failed to find video codec with id {} for videoIndex {}", codecId, this.videoStreamIndex);

        // TRY HW DECODER, FALLBACK TO SW
        if (!this.initHwDecoder(decoder, videoStream)) {
            this.videoCodecContext = avcodec.avcodec_alloc_context3(decoder);
            if (avcodec_parameters_to_context(this.videoCodecContext, videoStream.codecpar()) < 0) {
                LOGGER.error(IT, "Failed to copy video codec parameters to context");
                return false;
            }

            final int width = this.videoCodecContext.width();
            final int height = this.videoCodecContext.height();
            final int pixels = width * height;

            final int threads = pixels <= 921600
                    ? Math.min(2, MAX_DECODE_THREADS) : pixels <= 2073600
                    ? Math.min(4, MAX_DECODE_THREADS) : pixels <= 3686400
                    ? Math.min(6, MAX_DECODE_THREADS) : MAX_DECODE_THREADS;

            this.videoCodecContext.thread_count(threads);
            this.videoCodecContext.thread_type(AVCodecContext.FF_THREAD_FRAME | AVCodecContext.FF_THREAD_SLICE);

            LOGGER.debug(IT, "Decoder threading: {} threads for {}x{}", threads, width, height);

            if (avcodec.avcodec_open2(this.videoCodecContext, decoder, (PointerPointer<?>) null) < 0) {
                LOGGER.error(IT, "Failed to open video codec");
                return false;
            }
        }

        // SET VIDEO FORMAT
        this.setVideoFormat(GL12.GL_BGRA, this.videoCodecContext.width(), this.videoCodecContext.height());

        // SETUP SCALED FRAME (OUTPUT OF sws_scale)
        this.scaledFrame.format(avutil.AV_PIX_FMT_BGRA);
        this.scaledFrame.width(this.width());
        this.scaledFrame.height(this.height());

        if (avutil.av_frame_get_buffer(this.scaledFrame, 32) < 0) {
            LOGGER.error(IT, "Failed to allocate scaled video frame buffer");
            return false;
        }

        return true;
    }

    private boolean initHwDecoder(final AVCodec decoder, final AVStream videoStream) {
        for (final int hw: VIDEO_HW_CODECS) {
            for (int i = 0; ; i++) {
                final AVCodecHWConfig config = avcodec.avcodec_get_hw_config(decoder, i);
                if (config == null) break;
                if (config.device_type() != hw || (config.methods() & avcodec.AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX) == 0) continue;

                this.hwDeviceCtx = new AVBufferRef();
                if (av_hwdevice_ctx_create(this.hwDeviceCtx, hw, (String) null, null, 0) >= 0) {
                    this.videoCodecContext = avcodec.avcodec_alloc_context3(decoder);
                    if (avcodec_parameters_to_context(this.videoCodecContext, videoStream.codecpar()) < 0) {
                        this.cleanupHwContext();
                        continue;
                    }

                    this.videoCodecContext.hw_device_ctx(av_buffer_ref(this.hwDeviceCtx));
                    this.hwPixelFormat = config.pix_fmt();

                    if (avcodec.avcodec_open2(this.videoCodecContext, decoder, (PointerPointer<?>) null) >= 0) {
                        final BytePointer hwName = av_hwdevice_get_type_name(hw);
                        LOGGER.info(IT, "Hardware decoder initialized: {}", hwName != null ? hwName.getString() : "unknown (" + hw + ")");
                        IOTool.closeQuietly(hwName);
                        return true;
                    }

                    this.cleanupHwContext();
                } else {
                    this.hwDeviceCtx.releaseReference();
                    this.hwDeviceCtx = null;
                }
            }
        }

        return false;
    }

    private void cleanupHwContext() {
        if (this.videoCodecContext != null) {
            avcodec.avcodec_free_context(this.videoCodecContext);
            this.videoCodecContext = null;
        }
        if (this.hwDeviceCtx != null) {
            av_buffer_unref(this.hwDeviceCtx);
            this.hwDeviceCtx = null;
        }
        this.hwPixelFormat = AV_PIX_FMT_NONE;
    }

    private boolean initAudio() {
        if (!this.audio || this.audioStreamIndex < 0)
            return false;

        final AVFormatContext audioContext = this.useAudioSlave ? this.slaveFormatContext : this.formatContext;
        final AVStream audioStream = audioContext.streams(this.audioStreamIndex);
        final AVCodecParameters codecParams = audioStream.codecpar();
        final int codecId = codecParams.codec_id();
        final AVCodec audioCodec = avcodec.avcodec_find_decoder(codecId);

        int errorCode;
        if (audioCodec == null) {
            LOGGER.error(IT, "Failed to find audio codec with id {} for audioIndex {}", codecId, this.audioStreamIndex);
            return false;
        }

        final String audioCodecName = avcodec_get_name(codecId).getString();
        LOGGER.info(IT, "Audio codec: {} (id={}), channels: {}, sample_rate: {}, format: {}",
                audioCodecName, codecId,
                codecParams.ch_layout().nb_channels(),
                codecParams.sample_rate(),
                av_get_sample_fmt_name(codecParams.format()) != null
                        ? av_get_sample_fmt_name(codecParams.format()).getString() : codecParams.format());

        this.audioCodecContext = avcodec.avcodec_alloc_context3(audioCodec);
        if (this.audioCodecContext == null) {
            LOGGER.error(IT, "Failed to allocate audio codec context");
            return false;
        }

        if ((errorCode = avcodec_parameters_to_context(this.audioCodecContext, codecParams)) < 0) {
            LOGGER.error(IT, "Failed to copy audio codec parameters to context - err: {}", errorCode);
            return false;
        }

        if ((errorCode = avcodec.avcodec_open2(this.audioCodecContext, audioCodec, (PointerPointer<?>) null)) < 0) {
            LOGGER.error(IT, "Failed to open audio codec - err: {}", errorCode);
            return false;
        }

        // INITIALIZE RESAMPLER
        this.swrContext = swresample.swr_alloc();
        if (this.swrContext == null) {
            LOGGER.error(IT, "Failed to allocate SWResampler context");
            return false;
        }

        final AVChannelLayout inputLayout = new AVChannelLayout();
        final AVChannelLayout outputLayout = new AVChannelLayout();

        try {
            if (codecParams.ch_layout().nb_channels() > 0) {
                avutil.av_channel_layout_copy(inputLayout, codecParams.ch_layout());
            } else {
                LOGGER.warn(IT, "Audio codec has no channel layout info, defaulting to stereo");
                avutil.av_channel_layout_default(inputLayout, 2);
            }

            avutil.av_channel_layout_default(outputLayout, AUDIO_CHANNELS);

            avutil.av_opt_set_chlayout(this.swrContext, "in_chlayout", inputLayout, 0);
            avutil.av_opt_set_int(this.swrContext, "in_sample_rate", codecParams.sample_rate(), 0);
            avutil.av_opt_set_sample_fmt(this.swrContext, "in_sample_fmt", codecParams.format(), 0);

            avutil.av_opt_set_chlayout(this.swrContext, "out_chlayout", outputLayout, 0);
            avutil.av_opt_set_int(this.swrContext, "out_sample_rate", AUDIO_SAMPLE_RATE, 0);
            avutil.av_opt_set_sample_fmt(this.swrContext, "out_sample_fmt", avutil.AV_SAMPLE_FMT_S16, 0);

            if (swresample.swr_init(this.swrContext) < 0) {
                LOGGER.error(IT, "Failed to initialize SWResampler context");
                return false;
            }
        } finally {
            av_channel_layout_uninit(inputLayout);
            av_channel_layout_uninit(outputLayout);
        }

        return true;
    }

    // ═══════════════════════════════════════════
    //  SWS CONTEXT
    // ═══════════════════════════════════════════

    private void recreateSwsContext(final int srcWidth, final int srcHeight, final int srcFormat) {
        if (this.swsContext != null) {
            swscale.sws_freeContext(this.swsContext);
            this.swsContext = null;
        }

        this.swsContext = swscale.sws_getContext(
                srcWidth, srcHeight, srcFormat,
                this.width(), this.height(), avutil.AV_PIX_FMT_BGRA,
                swscale.SWS_FAST_BILINEAR, null, null, (double[]) null
        );

        final String fmtName = av_get_pix_fmt_name(srcFormat) != null
                ? av_get_pix_fmt_name(srcFormat).getString() : String.valueOf(srcFormat);

        if (this.swsContext == null) {
            LOGGER.error(IT, "Failed to create SwsContext: {} ({}x{}) -> BGRA ({}x{})",
                    fmtName, srcWidth, srcHeight, this.width(), this.height());
        } else {
            this.swsInputFormat = srcFormat;
            LOGGER.info(IT, "SwsContext: {} ({}x{}) -> BGRA ({}x{})",
                    fmtName, srcWidth, srcHeight, this.width(), this.height());
        }
    }

    // ═══════════════════════════════════════════
    //  CLEANUP
    // ═══════════════════════════════════════════

    private void cleanup() {
        LOGGER.info(IT, "Cleaning up FFMPEG resources...");

        // HW
        if (this.hwDeviceCtx != null) {
            av_buffer_unref(this.hwDeviceCtx);
            this.hwDeviceCtx = null;
        }
        this.hwPixelFormat = AV_PIX_FMT_NONE;

        // CONTEXTS
        if (this.swsContext != null) {
            swscale.sws_freeContext(this.swsContext);
            this.swsContext = null;
        }
        if (this.swrContext != null) {
            swresample.swr_free(this.swrContext);
            this.swrContext = null;
        }
        if (this.videoCodecContext != null) {
            avcodec.avcodec_free_context(this.videoCodecContext);
            this.videoCodecContext = null;
        }
        if (this.audioCodecContext != null) {
            avcodec.avcodec_free_context(this.audioCodecContext);
            this.audioCodecContext = null;
        }
        if (this.formatContext != null) {
            try {
                avformat.avformat_close_input(this.formatContext);
            } catch (final Exception e) {
                LOGGER.warn(IT, "Error closing format context", e);
            }
            this.formatContext = null;
        }
        if (this.slaveFormatContext != null) {
            try {
                avformat.avformat_close_input(this.slaveFormatContext);
            } catch (final Exception e) {
                LOGGER.warn(IT, "Error closing slave format context", e);
            }
            this.slaveFormatContext = null;
        }
        this.useAudioSlave = false;

        // FRAMES
        if (this.scaledFrame != null) {
            avutil.av_frame_free(this.scaledFrame);
            this.scaledFrame = null;
        }

        // CLEAR
        this.videoBuffer = null;
        this.videoBufferInitialized = false;

        // RESET
        this.videoStreamIndex = -1;
        this.audioStreamIndex = -1;
        this.swsInputFormat = AV_PIX_FMT_NONE;

        LOGGER.info(IT, "Cleanup completed");
    }

    // ═══════════════════════════════════════════
    //  UTILITY
    // ═══════════════════════════════════════════

    private static boolean isNull(Pointer p) { return p == null || p.isNull(); }

    // ═══════════════════════════════════════════
    //  STATIC
    // ═══════════════════════════════════════════

    public static boolean load(final WaterMedia watermedia) {
        Objects.requireNonNull(watermedia, "WaterMedia instance cannot be null");

        LOGGER.info(IT, "Starting FFMPEG...");
        if (WaterMediaConfig.media.disableFFMPEG) {
            LOGGER.warn(IT, "FFMPEG startup was cancelled, user settings disables it");
            return true;
        }

        try {
            // ASK WATERMEDIA BINARIES WHERE FFMPEG IS LOCATED
            final String ffmpegPath = WaterMediaBinaries.pathOf(WaterMediaBinaries.FFMPEG_ID).toAbsolutePath().toString();
            // ALSO LOOK IN USER-DEFINED FOLDERS (OR RUNTIME FOLDER AS FALLBACK)
            final String configPath = WaterMediaConfig.media.customFFMPEGPath != null ? WaterMediaConfig.media.customFFMPEGPath.toAbsolutePath().toString() : null;
            final String paths = configPath != null ? ffmpegPath + File.pathSeparator + configPath : ffmpegPath;

            System.setProperty("org.bytedeco.javacpp.platform.preloadpath", paths);
            System.setProperty("org.bytedeco.javacpp.pathsFirst", "true");

            final String currentLibPath = System.getProperty("java.library.path");
            if (currentLibPath == null || currentLibPath.isEmpty()) {
                System.setProperty("java.library.path", ffmpegPath);
            } else if (!currentLibPath.contains(ffmpegPath)) {
                System.setProperty("java.library.path", ffmpegPath + java.io.File.pathSeparator + currentLibPath);
            }

            LOGGER.info(IT, "Configured JavaCPP bindings with: {}", paths);

            // THIS IS QUITE PROBLEMATIC
            avutil.av_log_set_flags(avutil.AV_LOG_PRINT_LEVEL | avutil.AV_LOG_SKIP_REPEATED);
            avutil.av_log_set_level(LOGGER.isDebugEnabled() ? avutil.AV_LOG_DEBUG : avutil.AV_LOG_INFO);

            LOGGER.info(IT, "=== FFMPEG Build Info ===");
            LOGGER.info(IT, "avformat: {}", avformat.avformat_version());
            LOGGER.info(IT, "avcodec:  {}", avcodec.avcodec_version());
            LOGGER.info(IT, "avutil:   {}", avutil.avutil_version());
            LOGGER.info(IT, "swscale:  {}", swscale.swscale_version());
            LOGGER.info(IT, "swresample: {}", swresample.swresample_version());

            try {
                final BytePointer config = avformat.avformat_configuration();
                if (config != null && !config.isNull()) {
                    LOGGER.info(IT, "Configuration: {}", config.getString());
                }
            } catch (final Exception e) {
                LOGGER.warn(IT, "Configuration: unavailable");
            }

            // HW ACCELERATION
            LOGGER.info(IT, "Hardware Acceleration:");
            int hwType = avutil.AV_HWDEVICE_TYPE_NONE;
            int hwCount = 0;
            do {
                hwType = avutil.av_hwdevice_iterate_types(hwType);
                if (hwType == avutil.AV_HWDEVICE_TYPE_NONE) break;

                final BytePointer hwName = avutil.av_hwdevice_get_type_name(hwType);
                if (hwName != null && !hwName.isNull()) {
                    LOGGER.info(IT, "  • {}", hwName.getString());
                    hwCount++;
                }
                IOTool.closeQuietly(hwName);
            } while (true);

            if (hwCount == 0) {
                LOGGER.info(IT, "  (none available)");
            }

            final BytePointer license = avformat.avformat_license();
            LOGGER.info(IT, "FFMPEG started, running version {} under {}", avformat.avformat_version(), license != null && !license.isNull() ? license.getString() : "unknown");
            IOTool.closeQuietly(license);
            return LOADED = true;
        } catch (final Throwable t) {
            LOGGER.error(IT, "Failed to load FFMPEG", t);
            return false;
        }
    }

    public static boolean loaded() { return LOADED; }
}
