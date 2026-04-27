package org.watermedia.api.media.players;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avformat.AVIOInterruptCB.Callback_Pointer;
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
import org.watermedia.api.media.engines.GFXEngine;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.engines.SFXEngine;
import org.watermedia.api.media.players.util.FrameQueue;
import org.watermedia.api.media.players.util.MasterClock;
import org.watermedia.api.media.players.util.PacketQueue;
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.HlsTool;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.ThreadTool;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.BiFunction;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.avutil.av_get_pix_fmt_name;
import static org.watermedia.WaterMedia.LOGGER;

/**
 * MediaPlayer implementation using FFmpeg with multi-threaded architecture.
 *
 * 3 internal threads (demux, video decode, audio decode) produce decoded frames
 * into thread-safe queues. The caller thread consumes frames via pollVideoFrame()
 * and pollAudioFrame().
 *
 * Video frames are uploaded to GFXEngine as native YUV planes whenever the pixel
 * format is directly supported (YUV420P, NV12, etc.), avoiding CPU-side sws_scale.
 * For unsupported formats, sws_scale to BGRA is used as a fallback.
 */
public final class FFMediaPlayer extends MediaPlayer {
    private static final Marker IT = MarkerManager.getMarker(FFMediaPlayer.class.getSimpleName());
    private static final ThreadTool.ThreadGroupFactory DEFAULT_THREAD_FACTORY = ThreadTool.createThreadGroupFactory("FFThread", Thread.NORM_PRIORITY);
    private static boolean LOADED;

    // AUDIO OUTPUT FORMAT
    private static final int AUDIO_SAMPLES = 2048;

    // THREADING
    private static final int MAX_DECODE_THREADS = ThreadTool.halfThreads();

    // QUEUE CAPACITIES
    private static final long VIDEO_PACKET_QUEUE_BYTES = 16L * 1024 * 1024;
    private static final long AUDIO_PACKET_QUEUE_BYTES = 8L * 1024 * 1024;
    private static final int VIDEO_FRAME_QUEUE_SLOTS = 3;
    private static final int AUDIO_FRAME_QUEUE_SLOTS = 9;

    // A/V SYNC THRESHOLDS (SECONDS)
    private static final double AV_SYNC_TOLERANCE = 0.002;
    private static final double AV_SYNC_TOO_EARLY = 0.040;

    // PERFORMANCE MONITORING
    private static final long PERF_CHECK_INTERVAL_MS = 2000;
    private static final double PERF_RATE_WARN_THRESHOLD = 0.90;

    // STARVATION DETECTION
    private static final long STARVATION_THRESHOLD_MS = 500;
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
    private SwsContext swsContext;        // ONLY USED FOR FALLBACK PATH
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
    private final BiFunction<String, Runnable, Thread> factory = DEFAULT_THREAD_FACTORY.newFactory();
    private Thread lifecycleThread;
    private Thread demuxThread;
    private Thread videoDecodeThread;
    private Thread audioDecodeThread;

    // QUEUES
    private PacketQueue videoPacketQueue;
    private PacketQueue audioPacketQueue;
    private FrameQueue videoFrameQueue;
    private FrameQueue audioFrameQueue;

    // STATUS + SYNCHRONIZATION
    private final MasterClock clock = new MasterClock();
    private volatile boolean qualityRequest = false;
    private volatile boolean ioAbortRequested = false;
    private volatile Boolean hlsLiveSource;
    private Callback_Pointer interruptCallback;

    // FALLBACK RENDER BUFFERS (LAZY-INIT — ONLY WHEN sws_scale IS NEEDED)
    private AVFrame scaledFrame;
    private ByteBuffer videoBuffer;

    // PERSISTENT PLANE BUFFERS FOR NATIVE YUV UPLOAD
    // FRAME DATA FROM FrameQueue IS EPHEMERAL — RECYCLED AFTER next().
    // GLEngine MAY DISPATCH THE UPLOAD ASYNC TO THE RENDER THREAD,
    // SO WE COPY PLANE DATA HERE BEFORE PASSING TO gfx.upload().
    private ByteBuffer planeY;
    private ByteBuffer planeU; // ALSO USED FOR UV IN NV12/NV21
    private ByteBuffer planeV;
    private ByteBuffer planeA;

    // TIMES
    private double videoTimeBase;
    private double audioTimeBase;

    // STATS
    private long totalSkippedFrames = 0;
    private long totalRenderedFrames = 0;

    // STARVATION DETECTION
    private long starvationStartMs;
    private long lastStarvationRecoveryMs;

    // PTS DISCONTINUITY HANDLING (HLS AD STITCHING)
    // TWITCH AND SIMILAR SERVICES INSERT ADS VIA #EXT-X-DISCONTINUITY.
    // FFMPEG'S HLS DEMUXER DOESN'T ALWAYS NORMALIZE PTS ACROSS THESE
    // BOUNDARIES — RAW PTS CAN JUMP BY HOURS (STREAM ELAPSED TIME).
    // WE TRACK AN OFFSET PER STREAM TO KEEP PTS CONTINUOUS.
    private static final double PTS_DISCONTINUITY_THRESHOLD = 10.0;
    private volatile double videoPtsOffset;
    private volatile double lastRawVideoPts = Double.NaN;
    private volatile double audioPtsOffset;
    private volatile double lastRawAudioPts = Double.NaN;

    // FORMAT CHANGE DETECTION (RENDER THREAD ONLY)
    private int lastFrameWidth;
    private int lastFrameHeight;
    private GFXEngine.ColorSpace lastColorSpace;
    private int lastBitsPerComponent;

    // AUDIO FORMAT NEGOTIATION
    private boolean audioPassthrough;
    private int audioOutputChannels;
    private int audioOutputSampleRate;
    private int audioOutputAvFormat;   // FFmpeg AV_SAMPLE_FMT_* for the resample output (unused when audioPassthrough=true)

    // ADAPTIVE FRAME DROPPING
    private double renderDebtSec;

    // PERFORMANCE COUNTERS
    private long perfLastCheckMs;
    private long perfLastClockMs;
    private int perfAudioUploads;
    private int perfVideoRenders;

    public FFMediaPlayer(final MRL mrl, final MRL.Source source, final GFXEngine gfx, final SFXEngine sfx) {
        super(mrl, source, gfx, sfx);
    }

    // MEDIAPLAYER OVERRIDES
    @Override
    public void start() {
        if (this.lifecycleThread != null && this.lifecycleThread.isAlive() && !this.lifecycleThread.isInterrupted()) {
            this.stop();
        }
        final Thread oldThread = this.lifecycleThread;

        this.lifecycleThread = this.factory.apply("lifecycle", () -> {
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
        this.clock.setPaused(true);
        this.start();
    }

    @Override
    public void release() {
        this.stop();
        super.release();
    }

    @Override
    public boolean pause(final boolean paused) {
        return this.clock.setPaused(paused);
    }

    @Override
    public boolean stop() {
        if (this.lifecycleThread != null) {
            this.lifecycleThread.interrupt();
            if (this.demuxThread != null) this.demuxThread.interrupt();
            return true;
        }
        return false;
    }

    @Override
    public boolean togglePlay() { return this.pause(!this.clock.pauseRequested()); }

    @Override
    public boolean seek(final long timeMs) {
        if (!this.canSeek()) return false;
        return this.clock.requestSeek(Math.max(0, Math.min(timeMs, this.duration())), true);
    }

    @Override
    public boolean seekQuick(final long timeMs) {
        if (!this.canSeek()) return false;
        return this.clock.requestSeek(Math.max(0, Math.min(timeMs, this.duration())), false);
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
    public Status status() { return this.clock.status(); }

    @Override
    public boolean liveSource() {
        final Boolean hls = this.hlsLiveSource;
        if (hls != null) return hls;
        return !this.loading() && !isNull(this.formatContext) && this.formatContext.duration() == avutil.AV_NOPTS_VALUE;
    }

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
    public boolean speed(final float speed) {
        if (super.speed(speed)) {
            this.clock.speed(speed);
            return true;
        }
        return false;
    }

    public boolean isHwAccel() { return !isNull(this.hwDeviceCtx); }

    // FORMAT MAPPING — NATIVE GPU UPLOAD
    private record PixFmtMapping(GFXEngine.ColorSpace cs, int bits) {}

    private static PixFmtMapping mapPixelFormat(final int avPixFmt) {
        return switch (avPixFmt) {
            // 8-BIT PLANAR YUV
            case AV_PIX_FMT_YUV420P, AV_PIX_FMT_YUVJ420P -> new PixFmtMapping(GFXEngine.ColorSpace.YUV420P, 8);
            case AV_PIX_FMT_YUV422P, AV_PIX_FMT_YUVJ422P -> new PixFmtMapping(GFXEngine.ColorSpace.YUV422P, 8);
            case AV_PIX_FMT_YUV444P, AV_PIX_FMT_YUVJ444P -> new PixFmtMapping(GFXEngine.ColorSpace.YUV444P, 8);
            // 8-BIT SEMI-PLANAR
            case AV_PIX_FMT_NV12 -> new PixFmtMapping(GFXEngine.ColorSpace.NV12, 8);
            case AV_PIX_FMT_NV21 -> new PixFmtMapping(GFXEngine.ColorSpace.NV21, 8);
            // 8-BIT PACKED RGB
            case AV_PIX_FMT_BGRA  -> new PixFmtMapping(GFXEngine.ColorSpace.BGRA, 8);
            case AV_PIX_FMT_RGBA  -> new PixFmtMapping(GFXEngine.ColorSpace.RGBA, 8);
            case AV_PIX_FMT_RGB24 -> new PixFmtMapping(GFXEngine.ColorSpace.RGB, 8);
            // 8-BIT GRAYSCALE
            case AV_PIX_FMT_GRAY8 -> new PixFmtMapping(GFXEngine.ColorSpace.GRAY, 8);
            // 8-BIT PACKED YUV
            case AV_PIX_FMT_YUYV422 -> new PixFmtMapping(GFXEngine.ColorSpace.YUYV, 8);
            case AV_PIX_FMT_UYVY422 -> new PixFmtMapping(GFXEngine.ColorSpace.YUYV2, 8);
            // 8-BIT YUVA (4-PLANE)
            case AV_PIX_FMT_YUVA420P -> new PixFmtMapping(GFXEngine.ColorSpace.YUVA420P, 8);
            case AV_PIX_FMT_YUVA422P -> new PixFmtMapping(GFXEngine.ColorSpace.YUVA422P, 8);
            case AV_PIX_FMT_YUVA444P -> new PixFmtMapping(GFXEngine.ColorSpace.YUVA444P, 8);
            // 10-BIT PLANAR YUV
            case AV_PIX_FMT_YUV420P10LE -> new PixFmtMapping(GFXEngine.ColorSpace.YUV420P, 10);
            case AV_PIX_FMT_YUV422P10LE -> new PixFmtMapping(GFXEngine.ColorSpace.YUV422P, 10);
            case AV_PIX_FMT_YUV444P10LE -> new PixFmtMapping(GFXEngine.ColorSpace.YUV444P, 10);
            // 10-BIT YUVA
            case AV_PIX_FMT_YUVA420P10LE -> new PixFmtMapping(GFXEngine.ColorSpace.YUVA420P, 10);
            case AV_PIX_FMT_YUVA422P10LE -> new PixFmtMapping(GFXEngine.ColorSpace.YUVA422P, 10);
            case AV_PIX_FMT_YUVA444P10LE -> new PixFmtMapping(GFXEngine.ColorSpace.YUVA444P, 10);
            // 12-BIT PLANAR YUV
            case AV_PIX_FMT_YUV420P12LE -> new PixFmtMapping(GFXEngine.ColorSpace.YUV420P, 12);
            case AV_PIX_FMT_YUV422P12LE -> new PixFmtMapping(GFXEngine.ColorSpace.YUV422P, 12);
            case AV_PIX_FMT_YUV444P12LE -> new PixFmtMapping(GFXEngine.ColorSpace.YUV444P, 12);
            // 12-BIT YUVA
            case AV_PIX_FMT_YUVA422P12LE -> new PixFmtMapping(GFXEngine.ColorSpace.YUVA422P, 12);
            case AV_PIX_FMT_YUVA444P12LE -> new PixFmtMapping(GFXEngine.ColorSpace.YUVA444P, 12);
            // 16-BIT PLANAR YUV
            case AV_PIX_FMT_YUV420P16LE -> new PixFmtMapping(GFXEngine.ColorSpace.YUV420P, 16);
            case AV_PIX_FMT_YUV422P16LE -> new PixFmtMapping(GFXEngine.ColorSpace.YUV422P, 16);
            case AV_PIX_FMT_YUV444P16LE -> new PixFmtMapping(GFXEngine.ColorSpace.YUV444P, 16);
            // 16-BIT YUVA
            case AV_PIX_FMT_YUVA420P16LE -> new PixFmtMapping(GFXEngine.ColorSpace.YUVA420P, 16);
            case AV_PIX_FMT_YUVA422P16LE -> new PixFmtMapping(GFXEngine.ColorSpace.YUVA422P, 16);
            case AV_PIX_FMT_YUVA444P16LE -> new PixFmtMapping(GFXEngine.ColorSpace.YUVA444P, 16);
            // P010/P016 — LEFT-SHIFTED 10/16-BIT NV12, MAP AS 16-BIT (bitScale=1.0 IS CORRECT)
            case AV_PIX_FMT_P010LE -> new PixFmtMapping(GFXEngine.ColorSpace.NV12, 16);
            case AV_PIX_FMT_P016LE -> new PixFmtMapping(GFXEngine.ColorSpace.NV12, 16);
            // HIGH-BIT GRAYSCALE
            case AV_PIX_FMT_GRAY10LE  -> new PixFmtMapping(GFXEngine.ColorSpace.GRAY, 10);
            case AV_PIX_FMT_GRAY12LE  -> new PixFmtMapping(GFXEngine.ColorSpace.GRAY, 12);
            case AV_PIX_FMT_GRAY16LE  -> new PixFmtMapping(GFXEngine.ColorSpace.GRAY, 16);
            case AV_PIX_FMT_GRAYF32LE -> new PixFmtMapping(GFXEngine.ColorSpace.GRAY, 32);
            // 16-BIT PACKED RGB
            case AV_PIX_FMT_RGB48LE  -> new PixFmtMapping(GFXEngine.ColorSpace.RGB, 16);
            case AV_PIX_FMT_RGBA64LE -> new PixFmtMapping(GFXEngine.ColorSpace.RGBA, 16);
            default -> null;
        };
    }

    // MAPS AN FFMPEG SAMPLE FORMAT (PACKED OR PLANAR) TO THE CANONICAL SFXEngine.SampleType.
    // PLANARNESS IS HANDLED SEPARATELY — THE CALLER CHECKS av_sample_fmt_is_planar DIRECTLY.
    // RETURNS null IF THE FFMPEG FORMAT HAS NO PCM EQUIVALENT.
    private static SFXEngine.SampleType toSampleType(final int avSampleFormat) {
        if (avSampleFormat == AV_SAMPLE_FMT_U8  || avSampleFormat == AV_SAMPLE_FMT_U8P)  return SFXEngine.SampleType.U8;
        if (avSampleFormat == AV_SAMPLE_FMT_S16 || avSampleFormat == AV_SAMPLE_FMT_S16P) return SFXEngine.SampleType.S16;
        if (avSampleFormat == AV_SAMPLE_FMT_S32 || avSampleFormat == AV_SAMPLE_FMT_S32P) return SFXEngine.SampleType.S32;
        if (avSampleFormat == AV_SAMPLE_FMT_FLT || avSampleFormat == AV_SAMPLE_FMT_FLTP) return SFXEngine.SampleType.FLT;
        if (avSampleFormat == AV_SAMPLE_FMT_DBL || avSampleFormat == AV_SAMPLE_FMT_DBLP) return SFXEngine.SampleType.DBL;
        return null;
    }

    // INVERSE OF toSampleType — MAPS CANONICAL SampleType BACK TO AN FFMPEG PACKED AV_SAMPLE_FMT_*
    // USED TO CONFIGURE swr's out_sample_fmt WHEN RESAMPLING TO A CHOSEN TARGET TYPE.
    private static int sampleTypeToAvFormat(final SFXEngine.SampleType type) {
        return switch (type) {
            case U8  -> avutil.AV_SAMPLE_FMT_U8;
            case S16 -> avutil.AV_SAMPLE_FMT_S16;
            case S32 -> avutil.AV_SAMPLE_FMT_S32;
            case FLT -> avutil.AV_SAMPLE_FMT_FLT;
            case DBL -> avutil.AV_SAMPLE_FMT_DBL;
        };
    }

    // MEMBERSHIP CHECK FOR INT ARRAYS.
    private static boolean contains(final int[] arr, final int value) {
        for (final int v: arr) if (v == value) return true;
        return false;
    }

    // EXTRACTS THE CHANNEL COUNT STORED IN MSB (byte 7) OF A SUPPORT ENTRY.
    private static int channelsOf(final long entry) {
        return DataTool.bytesAt(entry, 7) & 0xFF;
    }

    // CHECKS WHETHER THE TYPE AT typeIdx IS SUPPORTED IN THE GIVEN ENTRY.
    // TYPE BYTES OCCUPY POSITIONS 6..0 (MAX 7 TYPES).
    private static boolean typeOkAt(final long entry, final int typeIdx) {
        if (typeIdx < 0 || typeIdx > 6) return false;
        return DataTool.bytesAt(entry, 6 - typeIdx) == (byte) 0xFF;
    }

    // FINDS THE ENTRY MATCHING channels EXACTLY, OR 0 IF NONE.
    // VALID ENTRIES HAVE channels > 0 IN THE MSB, SO 0 IS A SAFE "NOT FOUND" SENTINEL.
    private static long entryFor(final long[] table, final int channels) {
        for (final long e: table) if (channelsOf(e) == channels) return e;
        return 0L;
    }

    // PICKS THE ENTRY WITH CHANNEL COUNT CLOSEST TO target. ON TIE, PREFERS THE LOWER COUNT
    // (DOWNMIX IS MORE PREDICTABLE THAN UPMIX IN AUDIO PROCESSING).
    private static long closestEntry(final long[] table, final int target) {
        long best = table[0];
        int bestCh = channelsOf(best);
        int bestDiff = Math.abs(bestCh - target);
        for (final long e: table) {
            final int ch = channelsOf(e);
            final int diff = Math.abs(ch - target);
            if (diff < bestDiff || (diff == bestDiff && ch < bestCh)) {
                best = e;
                bestCh = ch;
                bestDiff = diff;
            }
        }
        return best;
    }

    // FINDS THE INDEX OF t IN types, OR -1.
    private static int typeIndex(final SFXEngine.SampleType[] types, final SFXEngine.SampleType t) {
        if (t == null) return -1;
        for (int i = 0; i < types.length; i++) if (types[i] == t) return i;
        return -1;
    }

    // PICKS A FALLBACK TYPE SUPPORTED AT entry. PREFERS S16 (UNIVERSAL) IF AVAILABLE.
    private static SFXEngine.SampleType fallbackType(final SFXEngine.SampleType[] types, final long entry) {
        final int s16Idx = typeIndex(types, SFXEngine.SampleType.S16);
        if (s16Idx >= 0 && typeOkAt(entry, s16Idx)) return SFXEngine.SampleType.S16;
        for (int i = 0; i < types.length; i++) {
            if (typeOkAt(entry, i)) return types[i];
        }
        return null;
    }

    /**
     * Poll for the next video frame. Non-blocking.
     * Call from the render/game thread in each tick.
     *
     * Performs A/V sync, uploads native YUV planes to GFXEngine when the pixel
     * format is directly supported. Falls back to sws_scale → BGRA for other formats.
     *
     * @return true if a frame was rendered, false if no frame available or not time yet.
     */
    public boolean pollVideoFrame() {
        if (this.videoFrameQueue == null || this.gfx == null || this.videoStreamIndex < 0) return false;

        while (true) {
            final FrameQueue.Slot slot = this.videoFrameQueue.peek();
            if (slot == null) return false;

            // SERIAL CHECK — DISCARD PRE-SEEK FRAMES
            if (slot.serial != this.clock.serial()) {
                this.videoFrameQueue.next();
                continue;
            }

            // A/V SYNC — SKIP TIMING CHECKS DURING BUFFERING SO THAT THE
            // FIRST AVAILABLE FRAME CAN RECALIBRATE THE CLOCK (VIA clock.update)
            // AND RESOLVE BUFFERING. WITHOUT THIS BYPASS, A TIMESTAMP GAP CAUSES
            // FRAMES TO BE REJECTED AS "TOO EARLY" INDEFINITELY (CLOCK IS FROZEN).
            final boolean buffering = this.clock.status() == Status.BUFFERING;
            final double clockSec = this.clock.time();
            final double frameSec = slot.ptsMs / 1000.0;
            final double diff = frameSec - clockSec;

            if (!buffering && diff > AV_SYNC_TOO_EARLY) return false;
            if (!buffering && diff < -(this.clock.skipThresholdMs() / 1000.0)) {
                this.videoFrameQueue.next();
                this.totalSkippedFrames++;
                continue;
            }

            // ADAPTIVE FRAME DROP
            final double frameDurSec = this.clock.frameDurationSec();
            if (this.renderDebtSec >= frameDurSec) {
                final FrameQueue.Slot nextSlot = this.videoFrameQueue.peekNext();
                if (nextSlot != null) {
                    this.renderDebtSec -= frameDurSec;
                    this.videoFrameQueue.next();
                    this.totalSkippedFrames++;
                    continue;
                }
            }

            // DETERMINE UPLOAD PATH
            final PixFmtMapping mapping = mapPixelFormat(slot.format);
            final boolean fallback = (mapping == null);
            final GFXEngine.ColorSpace cs = fallback ? GFXEngine.ColorSpace.BGRA : mapping.cs;
            final int bits = fallback ? 8 : mapping.bits;

            // FORMAT CHANGE DETECTION — RECONFIGURE GFXEngine
            if (cs != this.lastColorSpace || bits != this.lastBitsPerComponent
                    || slot.width != this.lastFrameWidth || slot.height != this.lastFrameHeight) {
                this.gfx.setVideoFormat(cs, slot.width, slot.height, bits);
                this.lastColorSpace = cs;
                this.lastBitsPerComponent = bits;
                this.lastFrameWidth = slot.width;
                this.lastFrameHeight = slot.height;
                LOGGER.info(IT, "GFX format: {} {}x{} {}bit (fallback={})", cs, slot.width, slot.height, bits, fallback);
            }

            // NATIVE YUV UPLOAD (NO sws_scale)
            if (!fallback) {
                final long uploadStart = System.nanoTime();
                this.uploadNativePlanes(slot.frame, cs, slot.width, slot.height);
                final long uploadMs = (System.nanoTime() - uploadStart) / 1_000_000;

                // TRACK RENDER DEBT (UPLOAD TIME VS FRAME BUDGET)
                final double uploadSec = uploadMs / 1000.0;
                if (uploadSec > frameDurSec) {
                    this.renderDebtSec += (uploadSec - frameDurSec);
                } else {
                    this.renderDebtSec = Math.max(0, this.renderDebtSec - (frameDurSec - uploadSec));
                }
            } else { // FALLBACK: sws_scale TO BGRA
                if (!this.ensureFallbackResources(slot.width, slot.height, slot.format)) {
                    this.videoFrameQueue.next();
                    return false;
                }

                final long swsStart = System.nanoTime();
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
                    LOGGER.error(IT, "sws_scale failed: result={}, fmt={}, size={}x{}", result, slot.format, slot.width, slot.height);
                    this.videoFrameQueue.next();
                    return false;
                }

                final long swsMs = (System.nanoTime() - swsStart) / 1_000_000;
                final double swsSec = swsMs / 1000.0;
                if (swsSec > frameDurSec) {
                    this.renderDebtSec += (swsSec - frameDurSec);
                } else {
                    this.renderDebtSec = Math.max(0, this.renderDebtSec - (frameDurSec - swsSec));
                }

                this.gfx.upload(this.videoBuffer, this.scaledFrame.linesize(0));
            }

            // SAVE PTS BEFORE CONSUMING
            final double frameTimeSec = frameSec;

            // CONSUME
            this.videoFrameQueue.next();
            this.totalRenderedFrames++;
            this.perfVideoRenders++;

            // CLOCK UPDATE
            if (this.audioStreamIndex < 0 || this.clock.status() == Status.BUFFERING) {
                this.clock.update(frameTimeSec, this.videoPacketQueue.serial());
            }

            return true;
        }
    }

    // COPIES AVFRAME PLANE DATA TO PERSISTENT BUFFERS, THEN UPLOADS TO GFXENGINE. THE COPY IS NECESSARY BECAUSE GLENGINE MAY DISPATCH THE UPLOAD TO THE RENDER THREAD ASYNCHRONOUSLY, BUT THE AVFRAME DATA IS RECYCLED BY FRAMEQUEUE.NEXT(). STRIDES ARE PASSED IN BYTES (FFMPEG LINESIZE CONVENTION).
    private void uploadNativePlanes(final AVFrame frame, final GFXEngine.ColorSpace cs, final int width, final int height) {
        switch (cs) {
            case BGRA, RGBA, RGB, GRAY, YUYV, YUYV2 -> {
                final int yStride = frame.linesize(0);
                final long ySize = (long) yStride * height;
                this.planeY = ensurePlane(this.planeY, ySize);
                copyPlane(frame.data(0), ySize, this.planeY);
                this.gfx.upload(this.planeY, yStride);
            }
            case NV12, NV21 -> {
                final int yStride = frame.linesize(0);
                final int uvStride = frame.linesize(1);
                final long ySize = (long) yStride * height;
                final long uvSize = (long) uvStride * (height / 2);
                this.planeY = ensurePlane(this.planeY, ySize);
                this.planeU = ensurePlane(this.planeU, uvSize);
                copyPlane(frame.data(0), ySize, this.planeY);
                copyPlane(frame.data(1), uvSize, this.planeU);
                this.gfx.upload(this.planeY, yStride, this.planeU, uvStride);
            }
            case YUV420P, YUV422P, YUV444P -> {
                final int chromaH = (cs == GFXEngine.ColorSpace.YUV420P) ? height / 2 : height;
                final int yStride = frame.linesize(0);
                final int uStride = frame.linesize(1);
                final int vStride = frame.linesize(2);
                final long ySize = (long) yStride * height;
                final long uSize = (long) uStride * chromaH;
                final long vSize = (long) vStride * chromaH;
                this.planeY = ensurePlane(this.planeY, ySize);
                this.planeU = ensurePlane(this.planeU, uSize);
                this.planeV = ensurePlane(this.planeV, vSize);
                copyPlane(frame.data(0), ySize, this.planeY);
                copyPlane(frame.data(1), uSize, this.planeU);
                copyPlane(frame.data(2), vSize, this.planeV);
                this.gfx.upload(this.planeY, yStride, this.planeU, uStride, this.planeV, vStride);
            }
            case YUVA420P, YUVA422P, YUVA444P -> {
                final int chromaH = (cs == GFXEngine.ColorSpace.YUVA420P) ? height / 2 : height;
                final int yStride = frame.linesize(0);
                final int uStride = frame.linesize(1);
                final int vStride = frame.linesize(2);
                final int aStride = frame.linesize(3);
                final long ySize = (long) yStride * height;
                final long uSize = (long) uStride * chromaH;
                final long vSize = (long) vStride * chromaH;
                final long aSize = (long) aStride * height;
                this.planeY = ensurePlane(this.planeY, ySize);
                this.planeU = ensurePlane(this.planeU, uSize);
                this.planeV = ensurePlane(this.planeV, vSize);
                this.planeA = ensurePlane(this.planeA, aSize);
                copyPlane(frame.data(0), ySize, this.planeY);
                copyPlane(frame.data(1), uSize, this.planeU);
                copyPlane(frame.data(2), vSize, this.planeV);
                copyPlane(frame.data(3), aSize, this.planeA);
                this.gfx.upload(this.planeY, yStride, this.planeU, uStride, this.planeV, vStride, this.planeA, aStride);
            }
            default -> LOGGER.warn(IT, "Unsupported native upload: {}", cs);
        }
    }

    private static ByteBuffer ensurePlane(final ByteBuffer buf, final long needed) {
        if (buf == null || buf.capacity() < needed) {
            return ByteBuffer.allocateDirect((int) needed);
        }
        return buf;
    }

    private static void copyPlane(final BytePointer src, final long bytes, final ByteBuffer dst) {
        src.capacity(bytes);
        dst.clear();
        dst.put(src.asByteBuffer());
        dst.flip();
    }

    // LAZY-INIT SWS_SCALE FALLBACK RESOURCES. RETURNS FALSE IF INIT FAILS.
    private boolean ensureFallbackResources(final int width, final int height, final int srcFormat) {
        // ALLOCATE SCALED FRAME ON FIRST FALLBACK USE (OR DIMENSION CHANGE)
        if (this.scaledFrame == null
                || this.scaledFrame.width() != width
                || this.scaledFrame.height() != height) {
            if (this.scaledFrame != null) avutil.av_frame_free(this.scaledFrame);
            this.scaledFrame = avutil.av_frame_alloc();
            this.scaledFrame.format(AV_PIX_FMT_BGRA);
            this.scaledFrame.width(width);
            this.scaledFrame.height(height);
            if (avutil.av_frame_get_buffer(this.scaledFrame, 32) < 0) {
                LOGGER.error(IT, "Failed to allocate fallback frame buffer");
                avutil.av_frame_free(this.scaledFrame);
                this.scaledFrame = null;
                return false;
            }
            this.videoBuffer = this.scaledFrame.data(0).asBuffer();
        }

        // (RE)CREATE SWS CONTEXT ON FORMAT/DIMENSION CHANGE
        if (this.swsContext == null || this.swsInputFormat != srcFormat
                || this.scaledFrame.width() != width || this.scaledFrame.height() != height) {
            this.recreateSwsContext(width, height, srcFormat);
        }

        return this.swsContext != null;
    }

    /**
     * Poll for the next audio frame. Non-blocking.
     */
    public boolean pollAudioFrame() {
        if (this.audioFrameQueue == null || this.sfx == null || this.audioStreamIndex < 0) return false;

        while (true) {
            final FrameQueue.Slot slot = this.audioFrameQueue.peek();
            if (slot == null) return false;

            if (slot.serial != this.audioPacketQueue.serial()) {
                this.audioFrameQueue.next();
                continue;
            }

            final long framePtsMs = slot.ptsMs;
            final int frameSerial = slot.serial;

            final int bytesPerSample = av_get_bytes_per_sample(slot.frame.format());
            final int dataSize = slot.frame.nb_samples() * this.audioOutputChannels * bytesPerSample;
            final ByteBuffer audioData = slot.frame.data(0)
                    .limit(dataSize)
                    .asBuffer()
                    .clear();

            if (!this.sfx.upload(audioData)) {
                if (this.clock.status() == Status.BUFFERING) {
                    LOGGER.debug(IT, "pollAudio: OpenAL FULL during BUFFERING — can't upload PTS={}ms", framePtsMs);
                }
                return false;
            }

            this.sfx.play();
            this.audioFrameQueue.next();
            this.perfAudioUploads++;

            final Status beforeUpdate = this.clock.status();
            this.clock.updateMs(framePtsMs, frameSerial);
            final Status afterUpdate = this.clock.status();
            if (beforeUpdate != afterUpdate) {
                LOGGER.info(IT, "Clock update: {} → {} (PTS={}ms, serial={}, clockSerial={})",
                        beforeUpdate, afterUpdate, framePtsMs, frameSerial, this.clock.serial());
            }

            return true;
        }
    }

    // LIFECYCLE (SUPERVISOR THREAD)
    private void lifecycle() {
        try {
            this.clock.reset();
            this.clock.transition(Status.LOADING);
            this.totalSkippedFrames = 0;
            this.totalRenderedFrames = 0;
            this.starvationStartMs = 0;
            this.lastStarvationRecoveryMs = 0;
            this.videoPtsOffset = 0;
            this.lastRawVideoPts = Double.NaN;
            this.audioPtsOffset = 0;
            this.lastRawAudioPts = Double.NaN;
            this.hlsLiveSource = null;

            this.videoPacketQueue = new PacketQueue(VIDEO_PACKET_QUEUE_BYTES);
            this.audioPacketQueue = new PacketQueue(AUDIO_PACKET_QUEUE_BYTES);
            this.videoFrameQueue = new FrameQueue(VIDEO_FRAME_QUEUE_SLOTS);
            this.audioFrameQueue = new FrameQueue(AUDIO_FRAME_QUEUE_SLOTS);

            this.demuxThread = this.factory.apply("demux", this::demuxLoop);
            this.demuxThread.setDaemon(true);
            this.demuxThread.start();

            final Status postInit = this.clock.awaitNotStatus(Status.LOADING, 0);

            if (postInit == Status.ERROR) {
                return;
            }

            if (this.gfx != null && this.videoStreamIndex >= 0 && this.videoCodecContext != null) {
                this.videoDecodeThread = this.factory.apply("video", this::videoDecodeLoop);
                this.videoDecodeThread.setDaemon(true);
                this.videoDecodeThread.start();
            }
            if (this.sfx != null && this.audioStreamIndex >= 0 && this.audioCodecContext != null) {
                this.audioDecodeThread = this.factory.apply("audio", this::audioDecodeLoop);
                this.audioDecodeThread.setDaemon(true);
                this.audioDecodeThread.start();
            }

            // CONSUMPTION LOOP
            while (!Thread.currentThread().isInterrupted()) {
                final Status current = this.clock.status();

                if (this.qualityRequest) {
                    this.qualityRequest = false;
                    this.handleQualitySwitch();
                    if (this.clock.status() == Status.ERROR) break;
                    continue;
                }

                if (this.clock.isDemuxFinished()) {
                    final boolean vDone = this.videoDecodeThread == null || !this.videoDecodeThread.isAlive();
                    final boolean aDone = this.audioDecodeThread == null || !this.audioDecodeThread.isAlive();
                    final boolean vEmpty = this.videoFrameQueue == null || this.videoFrameQueue.isEmpty();
                    final boolean aEmpty = this.audioFrameQueue == null || this.audioFrameQueue.isEmpty();
                    if (vDone && aDone && vEmpty && aEmpty) {
                        if (this.repeat()) {
                            // ALL DATA CONSUMED — SEEK TO BEGINNING FOR REPEAT.
                            // THIS TRIGGERS AFTER FULL DRAIN, NOT AT DEMUX EOF,
                            // SO NO UNPROCESSED PACKETS ARE THROWN AWAY.
                            this.clock.requestSeek(0, false);
                            continue;
                        }
                        this.clock.transition(Status.ENDED);
                        break;
                    }
                }

                if (current == Status.BUFFERING && this.demuxThread != null && !this.demuxThread.isAlive()) {
                    LOGGER.error(IT, "Demux thread died during BUFFERING — setting ERROR");
                    this.clock.transition(Status.ERROR);
                    break;
                }

                if (current == Status.PAUSED) {
                    this.clock.awaitChange(50);
                    continue;
                }

                if (current == Status.STOPPED || current == Status.ERROR) {
                    break;
                }

                if (current != Status.PLAYING && current != Status.BUFFERING) {
                    break;
                }

                boolean didWork = false;

                // PLAYBACK RATE MONITOR
                final long nowMs = System.currentTimeMillis();
                if (current == Status.PLAYING && nowMs - this.perfLastCheckMs > PERF_CHECK_INTERVAL_MS && this.perfLastClockMs > 0) {
                    final long wallElapsed = nowMs - this.perfLastCheckMs;
                    final long clockElapsed = this.clock.timeMs() - this.perfLastClockMs;
                    final double rate = (double) clockElapsed / wallElapsed;
                    if (rate < PERF_RATE_WARN_THRESHOLD || rate > (2.0 - PERF_RATE_WARN_THRESHOLD)) {
                        LOGGER.warn(IT, "PLAYBACK RATE: {}/{}ms = {}, vQ={}, aQ={}, aUploads={}, vRenders={}",
                                clockElapsed, wallElapsed, String.format("%.3f", rate),
                                this.videoFrameQueue != null ? this.videoFrameQueue.remaining() : -1,
                                this.audioFrameQueue != null ? this.audioFrameQueue.remaining() : -1,
                                this.perfAudioUploads, this.perfVideoRenders);
                    }
                    this.perfAudioUploads = 0;
                    this.perfVideoRenders = 0;
                    this.perfLastCheckMs = nowMs;
                    this.perfLastClockMs = this.clock.timeMs();
                } else if (this.perfLastCheckMs == 0 || current != Status.PLAYING) {
                    this.perfLastCheckMs = nowMs;
                    this.perfLastClockMs = this.clock.timeMs();
                    this.perfAudioUploads = 0;
                    this.perfVideoRenders = 0;
                }

                final long iterStart = System.nanoTime();

                // AUDIO: UP TO 2 FRAMES PER ITERATION
                if (this.sfx != null && this.audioStreamIndex >= 0 && this.audioFrameQueue != null) {
                    for (int i = 0; i < 2; i++) {
                        final FrameQueue.Slot aSlot = this.audioFrameQueue.peek();
                        if (aSlot == null) break;
                        if (aSlot.serial != this.clock.serial()) {
                            this.audioFrameQueue.next();
                            didWork = true;
                            i--;
                            continue;
                        }
                        if (current != Status.BUFFERING) {
                            final double adiff = (aSlot.ptsMs / 1000.0) - this.clock.time();
                            if (adiff > AV_SYNC_TOLERANCE) break;
                        }
                        didWork |= this.pollAudioFrame();
                    }
                }
                final long afterAudio = System.nanoTime();

                // VIDEO: ONE FRAME PER ITERATION
                if (this.gfx != null && this.videoStreamIndex >= 0 && this.videoFrameQueue != null) {
                    didWork |= this.pollVideoFrame();
                }
                final long afterVideo = System.nanoTime();

                // AUDIO AGAIN AFTER VIDEO (CATCH AUDIO THAT BECAME DUE DURING VIDEO UPLOAD)
                if (this.sfx != null && this.audioStreamIndex >= 0 && this.audioFrameQueue != null) {
                    for (int i = 0; i < 2; i++) {
                        final FrameQueue.Slot aSlot = this.audioFrameQueue.peek();
                        if (aSlot == null) break;
                        if (aSlot.serial != this.clock.serial()) {
                            this.audioFrameQueue.next();
                            didWork = true;
                            i--;
                            continue;
                        }
                        if (current != Status.BUFFERING) {
                            final double adiff = (aSlot.ptsMs / 1000.0) - this.clock.time();
                            if (adiff > AV_SYNC_TOLERANCE) break;
                        }
                        didWork |= this.pollAudioFrame();
                    }
                }

                // PERFORMANCE MONITOR
                final long audioMs = (afterAudio - iterStart) / 1_000_000;
                final long videoMs = (afterVideo - afterAudio) / 1_000_000;
                final long totalMs = (afterVideo - iterStart) / 1_000_000;
                if (totalMs > this.clock.frameDurationMs() && didWork) {
                    LOGGER.warn(IT, "SLOW ITERATION: total={}ms (audio={}ms, video={}ms), frameDur={}ms",
                            totalMs, audioMs, videoMs, this.clock.frameDurationMs());
                }

                // TRACK BUFFERING→PLAYING RECOVERY FOR STARVATION COOLDOWN
                if (current == Status.BUFFERING && this.clock.status() == Status.PLAYING) {
                    this.lastStarvationRecoveryMs = System.currentTimeMillis();
                }

                if (!didWork) {
                    // STARVATION DETECTION — TRANSITION TO BUFFERING INSTEAD OF SEEKING
                    // A full seek (requestSeek) is destructive: it increments serial, flushes
                    // queues, and performs av_seek_frame. This causes a feedback loop when
                    // frames arrive in bursts (e.g., GC pauses, render lag in Minecraft).
                    // Instead, just transition to BUFFERING — the pipeline is still running,
                    // and clock.update() will transition back to PLAYING when frames arrive.
                    if (current == Status.PLAYING && !this.clock.isDemuxFinished()) {
                        final long starvNowMs = System.currentTimeMillis();
                        final boolean queuesEmpty =
                                (this.videoFrameQueue == null || this.videoFrameQueue.isEmpty()) &&
                                        (this.audioFrameQueue == null || this.audioFrameQueue.isEmpty());

                        // ALSO DETECT UNREACHABLE FRAMES: QUEUES HAVE DATA BUT ALL
                        // FRAMES ARE >10s AHEAD OF THE CLOCK. THIS HAPPENS FOR LIVE
                        // HLS STREAMS WHERE PTS STARTS AT THE STREAM'S ELAPSED TIME
                        // (E.G., 14890s) BUT THE CLOCK STARTS AT 0 AFTER clock.start().
                        // WITHOUT THIS, THE PLAYER DEADLOCKS: FRAMES ARE "TOO EARLY"
                        // BUT QUEUES AREN'T EMPTY SO STARVATION NEVER FIRES.
                        boolean unreachable = false;
                        if (!queuesEmpty) {
                            final double clockSec = this.clock.time();
                            unreachable = true;
                            if (this.audioFrameQueue != null) {
                                final FrameQueue.Slot a = this.audioFrameQueue.peek();
                                if (a != null && a.serial == this.clock.serial()
                                        && (a.ptsMs / 1000.0 - clockSec) <= PTS_DISCONTINUITY_THRESHOLD) {
                                    unreachable = false;
                                }
                            }
                            if (unreachable && this.videoFrameQueue != null) {
                                final FrameQueue.Slot v = this.videoFrameQueue.peek();
                                if (v != null && v.serial == this.clock.serial()
                                        && (v.ptsMs / 1000.0 - clockSec) <= PTS_DISCONTINUITY_THRESHOLD) {
                                    unreachable = false;
                                }
                            }
                        }

                        final boolean starving = queuesEmpty || unreachable;
                        if (starving && starvNowMs - this.lastStarvationRecoveryMs > STARVATION_THRESHOLD_MS * 4) {
                            if (this.starvationStartMs == 0) {
                                this.starvationStartMs = starvNowMs;
                            } else if (starvNowMs - this.starvationStartMs > STARVATION_THRESHOLD_MS) {
                                LOGGER.warn(IT, "Starvation — BUFFERING (clock={}ms)", this.clock.timeMs());
                                this.clock.transition(Status.BUFFERING);
                                this.starvationStartMs = 0;
                            }
                        } else {
                            this.starvationStartMs = 0;
                        }
                    } else {
                        this.starvationStartMs = 0;
                    }
                    Thread.sleep(1);
                } else {
                    this.starvationStartMs = 0;
                }
            }

            LOGGER.info(IT, "Consumption loop exited — R: {}, S: {}, clock: {}ms, status: {}",
                    this.totalRenderedFrames, this.totalSkippedFrames, this.clock.timeMs(), this.clock.status());

            this.stopThreads();

            final Status finalStatus = this.clock.status();
            if (!Thread.currentThread().isInterrupted()
                    && finalStatus != Status.ERROR && finalStatus != Status.ENDED && finalStatus != Status.STOPPED) {
                this.clock.transition(Status.ENDED);
            }

        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            this.stopThreads();
            this.clock.transition(Status.STOPPED);
        } catch (final Throwable e) {
            LOGGER.fatal(IT, "Error in lifecycle for URI {}", this.source.uri(this.quality), e);
            this.stopThreads();
            this.clock.transition(Status.ERROR);
        } finally {
            this.freeQueues();
            this.cleanup();
        }
    }

    // DEMUX THREAD
    private void demuxLoop() {
        try {
            if (!this.init()) {
                this.clock.transition(Status.ERROR);
                return;
            }
        } catch (final Throwable e) {
            LOGGER.error(IT, "Init failed with exception", e);
            this.clock.transition(Status.ERROR);
            return;
        }
        this.clock.start(this.clock.pauseRequested());

        final AVPacket packet = avcodec.av_packet_alloc();
        final AVPacket slavePacket = this.useAudioSlave ? avcodec.av_packet_alloc() : null;
        long demuxVideoPackets = 0;
        long demuxAudioPackets = 0;
        boolean mainEof = false;
        boolean slaveEof = false;
        final long demuxIgnoredPackets = 0;

        LOGGER.info(IT, "Demux loop starting — videoIdx={}, audioIdx={}, useAudioSlave={}, nb_streams={}",
                this.videoStreamIndex, this.audioStreamIndex, this.useAudioSlave,
                this.formatContext != null ? this.formatContext.nb_streams() : -1);

        try {
            while (!Thread.currentThread().isInterrupted()) {
                // SEEK HANDLING
                final MasterClock.SeekRequest seekReq = this.clock.consumeSeek();
                if (seekReq != null) {
                    final long targetMs = seekReq.targetMs();
                    final boolean precise = seekReq.precise();

                    this.clock.signalDemuxResumed();

                    if (precise) {
                        this.stopDecodeThreads();
                        this.videoPacketQueue.reset();
                        this.audioPacketQueue.reset();
                        if (this.videoCodecContext != null) avcodec.avcodec_flush_buffers(this.videoCodecContext);
                        if (this.audioCodecContext != null) avcodec.avcodec_flush_buffers(this.audioCodecContext);
                    } else {
                        // MUST use reset() NOT flush() — flush() doesn't clear the
                        // 'finished' flag. After EOF→repeat, finish() was called,
                        // and flush() alone leaves finished=true. Decode threads
                        // then call get() on an empty+finished queue → null → exit
                        // immediately, producing 0 frames. reset() clears both
                        // 'finished' and 'aborted', making the queue reusable.
                        this.videoPacketQueue.reset();
                        this.audioPacketQueue.reset();
                    }

                    this.clock.setSerial(this.videoPacketQueue.serial());
                    LOGGER.info(IT, "Seek serial sync: clockSerial={}, vQueueSerial={}, aQueueSerial={}",
                            this.clock.serial(), this.videoPacketQueue.serial(), this.audioPacketQueue.serial());
                    mainEof = false;
                    slaveEof = false;

                    if (this.clock.hasSeekPending()) {
                        LOGGER.info(IT, "Seek to {}ms superseded — skipping", targetMs);
                        this.ensureDecodeThreads();
                        continue;
                    }

                    final long ffTs = targetMs * 1000L;
                    final int seekFlags = avformat.AVSEEK_FLAG_BACKWARD;
                    boolean reopened = false;

                    int seekResult = avformat.av_seek_frame(this.formatContext, -1, ffTs, seekFlags);
                    if (seekResult < 0) {
                        seekResult = avformat.avformat_seek_file(this.formatContext, -1, Long.MIN_VALUE, ffTs, ffTs, seekFlags);
                    }
                    if (seekResult < 0) {
                        seekResult = avformat.av_seek_frame(this.formatContext, -1, ffTs, 0);
                    }
                    if (seekResult < 0 && this.reopenFormat()) {
                        LOGGER.info(IT, "Seek to {}ms failed — reopened format from beginning", targetMs);
                        reopened = true;
                        seekResult = 0;
                        if (targetMs > 0) {
                            seekResult = avformat.av_seek_frame(this.formatContext, -1, ffTs, 0);
                            if (seekResult < 0) seekResult = avformat.avformat_seek_file(this.formatContext, -1, Long.MIN_VALUE, ffTs, ffTs, 0);
                        }
                    }
                    if (seekResult < 0) {
                        LOGGER.error(IT, "Seek failed on main context (target={}ms, error={}) — staying in BUFFERING, pipeline will recover from current position", targetMs, seekResult);
                        this.ensureDecodeThreads();
                        continue;
                    }

                    if (this.clock.hasSeekPending()) {
                        LOGGER.info(IT, "Seek to {}ms superseded after main seek — skipping drain", targetMs);
                        this.ensureDecodeThreads();
                        continue;
                    }

                    if (this.useAudioSlave && this.slaveFormatContext != null) {
                        avformat.av_seek_frame(this.slaveFormatContext, -1, ffTs, seekFlags);
                    }

                    if (precise && !(reopened && targetMs == 0)) {
                        this.syncDrain(targetMs, packet, slavePacket);
                    }

                    this.ensureDecodeThreads();

                    LOGGER.info(IT, "Seek completed to {}ms (precise={}, serial={}, clockMs={})",
                            targetMs, precise, this.videoPacketQueue.serial(), this.clock.timeMs());
                    continue;
                }

                // PAUSE HANDLING
                if (this.clock.pauseRequested() && this.clock.status() == Status.PAUSED) {
                    this.clock.awaitChange(0);
                    continue;
                }

                // SLAVE AUDIO
                if (this.useAudioSlave && !slaveEof && this.slaveFormatContext != null && slavePacket != null) {
                    final int reads = WaterMediaConfig.media.ffmpegSlavePacketReads;
                    for (int i = 0; i < reads; i++) {
                        final int slaveResult = avformat.av_read_frame(this.slaveFormatContext, slavePacket);
                        if (slaveResult >= 0) {
                            try {
                                if (slavePacket.stream_index() == this.audioStreamIndex && this.sfx != null) {
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
                            break;
                        }
                    }
                }

                // READ PACKET (MAIN CONTEXT)
                if (!mainEof) {
                    final int result = avformat.av_read_frame(this.formatContext, packet);
                    if (result < 0) {
                        mainEof = true;
                        LOGGER.info(IT, "Main context EOF after {} video packets (R: {}, S: {})",
                                demuxVideoPackets, this.totalRenderedFrames, this.totalSkippedFrames);
                        if (this.videoPacketQueue != null) this.videoPacketQueue.finish();
                        // FOR NON-SLAVE AUDIO, AUDIO PACKETS COME FROM THE SAME FORMAT
                        // CONTEXT — FINISH THE AUDIO QUEUE SO DECODE THREADS DRAIN CLEANLY.
                        if (!this.useAudioSlave && this.audioPacketQueue != null) this.audioPacketQueue.finish();
                    }
                }

                // CHECK BOTH EOF — SIGNAL DEMUX FINISHED AND WAIT FOR SEEK.
                // THE CONSUMPTION LOOP HANDLES BOTH REPEAT AND ENDED AFTER
                // ALL DECODED FRAMES HAVE BEEN DRAINED AND RENDERED.
                // PREVIOUSLY, repeat() TRIGGERED requestSeek(0) HERE IMMEDIATELY,
                // WHICH FLUSHED ALL UNPROCESSED PACKETS — CAUSING A RAPID
                // EOF→SEEK→EOF LOOP WITH ALMOST NO FRAMES RENDERED.
                if (mainEof && (slaveEof || !this.useAudioSlave)) {
                    LOGGER.info(IT, "All contexts EOF (video: {}, audio: {})", demuxVideoPackets, demuxAudioPackets);

                    this.clock.signalDemuxFinished();
                    LOGGER.info(IT, "Demux finished — consumption loop will handle ENDED/repeat after drain");

                    while (!this.clock.hasSeekPending() && !Thread.currentThread().isInterrupted()) {
                        this.clock.awaitChange(0);
                    }
                    if (!this.clock.hasSeekPending()) break;

                    LOGGER.info(IT, "Seek after EOF — restarting pipeline");
                    mainEof = false;
                    slaveEof = false;
                    if (this.videoPacketQueue != null) this.videoPacketQueue.reset();
                    if (this.audioPacketQueue != null) this.audioPacketQueue.reset();
                    continue;
                }

                if (mainEof) {
                    continue;
                }

                try {
                    final int streamIndex = packet.stream_index();
                    if (streamIndex == this.videoStreamIndex && this.gfx != null) {
                        while (!this.videoPacketQueue.tryPut(packet)) {
                            if (Thread.currentThread().isInterrupted() || this.clock.hasSeekPending()) break;
                            if (this.useAudioSlave && this.slaveFormatContext != null && slavePacket != null) {
                                final int sr = avformat.av_read_frame(this.slaveFormatContext, slavePacket);
                                if (sr >= 0) {
                                    try {
                                        if (slavePacket.stream_index() == this.audioStreamIndex && this.sfx != null) {
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
                            && streamIndex == this.audioStreamIndex && this.sfx != null) {
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

            if (this.videoPacketQueue != null) this.videoPacketQueue.finish();
            if (this.audioPacketQueue != null) this.audioPacketQueue.finish();

            avcodec.av_packet_free(packet);
            if (slavePacket != null) avcodec.av_packet_free(slavePacket);
        }
    }

    // VIDEO DECODE THREAD
    private void videoDecodeLoop() {
        final AVFrame tempFrame = avutil.av_frame_alloc();
        final AVFrame hwTransfer = (this.hwDeviceCtx != null) ? avutil.av_frame_alloc() : null;
        final int[] serialOut = new int[1];
        int lastSerial = -1;
        long packetsProcessed = 0;
        long framesProduced = 0;
        final long framesDropped = 0;

        try {
            while (!Thread.currentThread().isInterrupted()) {
                final AVPacket packet = this.videoPacketQueue.get(serialOut);
                if (packet == null) break;

                final int packetSerial = serialOut[0];

                try {
                    if (packetSerial != lastSerial) {
                        if (lastSerial >= 0) avcodec.avcodec_flush_buffers(this.videoCodecContext);
                        lastSerial = packetSerial;
                        this.lastRawVideoPts = Double.NaN;
                        this.videoPtsOffset = 0;
                    }

                    if (packetSerial != this.videoPacketQueue.serial()) continue;

                    if (avcodec.avcodec_send_packet(this.videoCodecContext, packet) < 0) continue;

                    while (avcodec.avcodec_receive_frame(this.videoCodecContext, tempFrame) >= 0) {
                        AVFrame frameToQueue = tempFrame;

                        if (hwTransfer != null && tempFrame.format() == this.hwPixelFormat) {
                            if (av_hwframe_transfer_data(hwTransfer, tempFrame, 0) < 0) {
                                LOGGER.warn(IT, "Failed to transfer frame from GPU");
                                continue;
                            }
                            frameToQueue = hwTransfer;
                        }

                        final double rawPtsSec = tempFrame.pts() * this.videoTimeBase;

                        // PTS DISCONTINUITY DETECTION (HLS AD STITCHING)
                        if (!Double.isNaN(this.lastRawVideoPts)) {
                            final double jump = rawPtsSec - this.lastRawVideoPts;
                            if (Math.abs(jump) > PTS_DISCONTINUITY_THRESHOLD) {
                                final double expected = this.lastRawVideoPts + this.videoPtsOffset + this.clock.frameDurationSec();
                                this.videoPtsOffset = expected - rawPtsSec;
                                LOGGER.info(IT, "Video PTS discontinuity: jump={}s, new offset={}s", String.format("%.1f", jump), String.format("%.1f", this.videoPtsOffset));
                            }
                        }
                        this.lastRawVideoPts = rawPtsSec;

                        final double ptsSec = rawPtsSec + this.videoPtsOffset;
                        final long ptsMs = (long) (ptsSec * 1000.0);

                        final FrameQueue.Slot slot = this.videoFrameQueue.peekWritable();
                        if (slot == null) break;

                        av_frame_unref(slot.frame);
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

    // AUDIO DECODE THREAD
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
                    if (packetSerial != lastSerial) {
                        if (lastSerial >= 0) avcodec.avcodec_flush_buffers(this.audioCodecContext);
                        lastSerial = packetSerial;
                        this.lastRawAudioPts = Double.NaN;
                        this.audioPtsOffset = 0;
                    }

                    if (packetSerial != this.audioPacketQueue.serial()) continue;

                    if (avcodec.avcodec_send_packet(this.audioCodecContext, packet) < 0) continue;

                    while (avcodec.avcodec_receive_frame(this.audioCodecContext, tempFrame) >= 0) {
                        final double rawPtsSec = tempFrame.pts() * this.audioTimeBase;

                        // PTS DISCONTINUITY DETECTION (HLS AD STITCHING)
                        if (!Double.isNaN(this.lastRawAudioPts)) {
                            final double jump = rawPtsSec - this.lastRawAudioPts;
                            if (Math.abs(jump) > PTS_DISCONTINUITY_THRESHOLD) {
                                final double audioDur = (double) tempFrame.nb_samples() / tempFrame.sample_rate();
                                final double expected = this.lastRawAudioPts + this.audioPtsOffset + audioDur;
                                this.audioPtsOffset = expected - rawPtsSec;
                                LOGGER.info(IT, "Audio PTS discontinuity: jump={}s, new offset={}s", String.format("%.1f", jump), String.format("%.1f", this.audioPtsOffset));
                            }
                        }
                        this.lastRawAudioPts = rawPtsSec;

                        final double ptsSec = rawPtsSec + this.audioPtsOffset;

                        if (!this.enqueueResampledAudio(tempFrame, ptsSec, packetSerial)) break;
                        framesProduced++;

                        if (!this.audioPassthrough) {
                            final long delay = swresample.swr_get_delay(this.swrContext, this.audioOutputSampleRate);
                            if (delay > AUDIO_SAMPLES / 2) {
                                if (!this.enqueueResamplerFlush(packetSerial)) break;
                                framesProduced++;
                            }
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

    private boolean enqueueResampledAudio(final AVFrame srcFrame, final double ptsSec, final int serial) {
        final FrameQueue.Slot slot = this.audioFrameQueue.peekWritable();
        if (slot == null) return false;

        av_frame_unref(slot.frame);

        if (this.audioPassthrough) {
            av_frame_ref(slot.frame, srcFrame);
            slot.ptsMs = (long) (ptsSec * 1000.0);
            slot.durationMs = (long) ((double) srcFrame.nb_samples() / srcFrame.sample_rate() * 1000.0);
            slot.serial = serial;
            this.audioFrameQueue.push();
            return true;
        }

        slot.frame.format(this.audioOutputAvFormat);
        slot.frame.ch_layout().nb_channels(this.audioOutputChannels);
        av_channel_layout_default(slot.frame.ch_layout(), this.audioOutputChannels);
        slot.frame.sample_rate(this.audioOutputSampleRate);
        slot.frame.nb_samples(AUDIO_SAMPLES);

        if (av_frame_get_buffer(slot.frame, 0) < 0) {
            LOGGER.warn(IT, "Failed to allocate audio buffer in slot");
            return true;
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
        slot.durationMs = (long) ((double) samplesConverted / this.audioOutputSampleRate * 1000.0);
        slot.serial = serial;

        this.audioFrameQueue.push();
        return true;
    }

    private boolean enqueueResamplerFlush(final int serial) {
        final FrameQueue.Slot slot = this.audioFrameQueue.peekWritable();
        if (slot == null) return false;

        av_frame_unref(slot.frame);
        slot.frame.format(this.audioOutputAvFormat);
        slot.frame.ch_layout().nb_channels(this.audioOutputChannels);
        av_channel_layout_default(slot.frame.ch_layout(), this.audioOutputChannels);
        slot.frame.sample_rate(this.audioOutputSampleRate);
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
        slot.durationMs = (long) ((double) flushed / this.audioOutputSampleRate * 1000.0);
        slot.serial = serial;

        this.audioFrameQueue.push();
        return true;
    }

    // THREAD MANAGEMENT
    private void stopThreads() {
        this.ioAbortRequested = true;
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

    private void ensureDecodeThreads() {
        if ((this.videoDecodeThread == null || !this.videoDecodeThread.isAlive()) && this.gfx != null && this.videoStreamIndex >= 0 && this.videoCodecContext != null) {
            this.videoDecodeThread = this.factory.apply("video", this::videoDecodeLoop);
            this.videoDecodeThread.setDaemon(true);
            this.videoDecodeThread.start();
        }
        if ((this.audioDecodeThread == null || !this.audioDecodeThread.isAlive()) && this.sfx != null && this.audioStreamIndex >= 0 && this.audioCodecContext != null) {
            this.audioDecodeThread = this.factory.apply("audio", this::audioDecodeLoop);
            this.audioDecodeThread.setDaemon(true);
            this.audioDecodeThread.start();
        }
    }

    private void syncDrain(final long targetMs, final AVPacket packet, final AVPacket slavePacket) {
        final AVFrame drainFrame = avutil.av_frame_alloc();
        final long threshold = targetMs - this.clock.frameDurationMs();
        int maxDrain = 500;
        boolean videoReached = this.gfx == null || this.videoCodecContext == null;
        boolean audioReached = this.sfx == null || this.audioCodecContext == null;

        while (maxDrain-- > 0 && !Thread.currentThread().isInterrupted()
                && !this.clock.hasSeekPending() && (!videoReached || !audioReached)) {

            if (this.useAudioSlave) {
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
                final int readRes = avformat.av_read_frame(this.formatContext, packet);
                if (readRes < 0) break;
                try {
                    final int idx = packet.stream_index();
                    if (idx == this.videoStreamIndex) {
                        if (!videoReached) {
                            videoReached = this.drainDecode(this.videoCodecContext, packet, drainFrame,
                                    this.videoTimeBase, threshold);
                        } else {
                            // threshold already met — still feed codec to keep DPB intact
                            avcodec.avcodec_send_packet(this.videoCodecContext, packet);
                            while (avcodec.avcodec_receive_frame(this.videoCodecContext, drainFrame) >= 0) { }
                        }
                    } else if (idx == this.audioStreamIndex) {
                        if (!audioReached) {
                            audioReached = this.drainDecode(this.audioCodecContext, packet, drainFrame,
                                    this.audioTimeBase, threshold);
                        } else {
                            avcodec.avcodec_send_packet(this.audioCodecContext, packet);
                            while (avcodec.avcodec_receive_frame(this.audioCodecContext, drainFrame) >= 0) { }
                        }
                    }
                } finally {
                    avcodec.av_packet_unref(packet);
                }
            }
        }
        avutil.av_frame_free(drainFrame);
    }

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

    // QUALITY SWITCH
    private void handleQualitySwitch() {
        final long currentMs = this.clock.timeMs();
        final boolean wasPaused = this.clock.pauseRequested();
        final boolean live = this.liveSource();

        LOGGER.info(IT, "Switching quality to {} at {}ms (live={})", this.quality, currentMs, live);

        this.clock.transition(Status.BUFFERING);
        this.stopThreads();
        this.cleanup();

        this.totalSkippedFrames = 0;
        this.totalRenderedFrames = 0;

        // RESET PTS DISCONTINUITY STATE FOR NEW PIPELINE
        this.videoPtsOffset = 0;
        this.lastRawVideoPts = Double.NaN;
        this.audioPtsOffset = 0;
        this.lastRawAudioPts = Double.NaN;

        this.videoPacketQueue.reset();
        this.audioPacketQueue.reset();
        this.videoFrameQueue.reset();
        this.audioFrameQueue.reset();

        this.clock.transition(Status.LOADING);

        this.demuxThread = this.factory.apply("demux", this::demuxLoop);
        this.demuxThread.setDaemon(true);
        this.demuxThread.start();

        try {
            final Status postInit = this.clock.awaitNotStatus(Status.LOADING, 0);
            if (postInit == Status.ERROR) {
                return;
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            this.clock.transition(Status.ERROR);
            return;
        }

        if (live) {
            // LIVE STREAMS: DON'T SEEK — FFMPEG STARTS AT THE LIVE EDGE.
            // SEEKING TO currentMs FAILS ON LIVE HLS AND CAUSES A FEEDBACK LOOP.
            // TRANSITION TO BUFFERING SO THE FIRST DECODED FRAME CALIBRATES THE CLOCK
            // VIA clock.update() → BUFFERING→PLAYING. WITHOUT THIS, clock.start() SETS
            // PLAYING AT PTS=0 AND STARVATION DETECTION FIRES BEFORE FRAMES ARRIVE.
            this.clock.transition(Status.BUFFERING);
            this.ensureDecodeThreads();
        } else {
            this.clock.requestSeek(currentMs, true);
        }

        if (wasPaused) {
            this.clock.setPaused(true);
        }

        LOGGER.info(IT, "Successfully switched quality to {}", this.quality);
    }

    private boolean reopenFormat() {
        final var uri = this.source.uri(this.quality);
        final var url = uri.getScheme().contains("file") ? uri.getPath().substring(1) : uri.toString();

        if (this.formatContext != null) {
            avformat.avformat_close_input(this.formatContext);
        }

        this.formatContext = avformat.avformat_alloc_context();
        if (this.interruptCallback != null) {
            this.formatContext.interrupt_callback().callback(this.interruptCallback);
        }

        final AVDictionary options = new AVDictionary();
        try {
            av_dict_set(options, "headers", "User-Agent: " + WaterMedia.USER_AGENT + "\r\n" +
                    "Accept: video/*,audio/*,image/*,application/vnd.apple.mpegurl,application/x-mpegurl,application/dash+xml,application/ogg,*/*;q=0.8\r\n" +
                    "Referer: " + this.mrl.uri.getScheme() + "://" + this.mrl.uri.getHost() + "/\r\n", 0);
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
                LOGGER.error(IT, "reopenFormat: failed to open input: {}", url);
                return false;
            }
        } finally {
            av_dict_free(options);
        }

        if (avformat.avformat_find_stream_info(this.formatContext, (PointerPointer<?>) null) < 0) {
            LOGGER.error(IT, "reopenFormat: failed to find stream info");
            return false;
        }

        if (this.videoCodecContext != null) avcodec.avcodec_flush_buffers(this.videoCodecContext);
        if (this.audioCodecContext != null) avcodec.avcodec_flush_buffers(this.audioCodecContext);

        return true;
    }

    // INIT
    private boolean init() {
        try {
            final var uri = this.source.uri(this.quality);
            final var url = uri.getScheme().contains("file") ? uri.getPath().substring(1) : uri.toString();

            final var audioSlaves = this.source.audioSlaves();
            MRL.Slave audioSlave = null;
            if (this.sfx != null && !audioSlaves.isEmpty()) {
                audioSlave = audioSlaves.get(0);
            }

            this.formatContext = avformat.avformat_alloc_context();
            this.ioAbortRequested = false;
            this.interruptCallback = new Callback_Pointer() {
                @Override
                public int call(final Pointer opaque) {
                    return FFMediaPlayer.this.ioAbortRequested ? 1 : 0;
                }
            };
            this.formatContext.interrupt_callback().callback(this.interruptCallback);

            final AVDictionary options = new AVDictionary();

            try {
                LOGGER.debug("Referer: {}", uri.getScheme() + "://" + uri.getHost());
                av_dict_set(options, "headers", "User-Agent: " + WaterMedia.USER_AGENT + "\r\n" +
                        "Accept: video/*,audio/*,image/*,application/vnd.apple.mpegurl,application/x-mpegurl,application/dash+xml,application/ogg,*/*;q=0.8\r\n" +
                        "Referer: " + this.mrl.uri.getScheme() + "://" + this.mrl.uri.getHost() + "/\r\n", 0);
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

            final String fmtName = this.formatContext.iformat() != null ? this.formatContext.iformat().name().getString() : "";
            if (url.contains(".m3u8") || fmtName.contains("hls")) {
                var hlsResult = HlsTool.fetch(uri);
                if (hlsResult instanceof final HlsTool.MasterResult master && !master.variants().isEmpty()) {
                    final var resolved = uri.resolve(master.variants().get(0).uri());
                    hlsResult = HlsTool.fetch(resolved);
                }
                if (hlsResult instanceof final HlsTool.MediaResult media) {
                    this.hlsLiveSource = media.live();
                    LOGGER.info(IT, "HLS probe: live={}, vod={}, totalDuration={}s", media.live(), media.vod(), media.totalDuration());
                } else {
                    this.hlsLiveSource = false;
                    LOGGER.warn(IT, "HLS probe: inconclusive ({}), defaulting to VOD", hlsResult.getClass().getSimpleName());
                }
            }

            for (int i = 0; i < this.formatContext.nb_streams(); i++) {
                final AVStream stream = this.formatContext.streams(i);
                final int codecType = stream.codecpar().codec_type();

                if (codecType == avutil.AVMEDIA_TYPE_VIDEO && this.videoStreamIndex < 0 && this.gfx != null) {
                    this.videoStreamIndex = i;
                    this.videoTimeBase = av_q2d(stream.time_base());
                } else if (codecType == avutil.AVMEDIA_TYPE_AUDIO && this.audioStreamIndex < 0 && this.sfx != null && audioSlave == null) {
                    this.audioStreamIndex = i;
                    this.audioTimeBase = av_q2d(stream.time_base());
                }
            }

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
        if (this.interruptCallback != null) {
            this.slaveFormatContext.interrupt_callback().callback(this.interruptCallback);
        }
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
        if (this.gfx == null || this.videoStreamIndex < 0) {
            LOGGER.warn(IT, "No video stream found or video disabled");
            return true;
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

        final AVCodecParameters codecpar = videoStream.codecpar();
        final int codecId = codecpar.codec_id();

        final String codecName = getString(avcodec_get_name(codecId), null);
        final AVCodecDescriptor descriptor = avcodec_descriptor_get(codecId);
        final String codecLongName = descriptor != null ? getString(descriptor.long_name(), "unknown") : "unknown";
        final long bitrate = codecpar.bit_rate();
        final int profile = codecpar.profile();
        final int level = codecpar.level();
        final int pixFmt = codecpar.format();

        final var profilePointer = avcodec_profile_name(codecId, profile);
        final var fmtNamePointer = av_get_pix_fmt_name(pixFmt);
        LOGGER.info(IT, "Video codec: {} ({}), bitrate: {} kbps, profile: {}, level: {}, pixel format: {}",
                codecName, codecLongName, bitrate > 0 ? bitrate / 1000 : "N/A", getString(profilePointer, profile), level, getString(fmtNamePointer, pixFmt));

        final AVCodec decoder = avcodec_find_decoder(codecId);
        if (decoder == null)
            LOGGER.error(IT, "Failed to find video codec with id {} for videoIndex {}", codecId, this.videoStreamIndex);

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

        final int w = this.videoCodecContext.width();
        final int h = this.videoCodecContext.height();
        final PixFmtMapping initialMapping = mapPixelFormat(this.videoCodecContext.pix_fmt());
        if (initialMapping != null) {
            this.gfx.setVideoFormat(initialMapping.cs, w, h, initialMapping.bits);
            this.lastColorSpace = initialMapping.cs;
            this.lastBitsPerComponent = initialMapping.bits;
            this.lastFrameWidth = w;
            this.lastFrameHeight = h;
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
                        LOGGER.info(IT, "Hardware decoder initialized: {}", getString(hwName, "unknown (" + hw + ")"));
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
        if (this.sfx == null || this.audioStreamIndex < 0)
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

        final var audioCodecPointer = avcodec_get_name(codecId);
        final var sampleNamePointer = av_get_sample_fmt_name(codecParams.format());
        final String audioCodecName = getString(audioCodecPointer, null);
        LOGGER.info(IT, "Audio codec: {} (id={}), channels: {}, sample_rate: {}, format: {}",
                audioCodecName, codecId,
                codecParams.ch_layout().nb_channels(),
                codecParams.sample_rate(),
                getString(sampleNamePointer, codecParams.format()));

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

        // NEGOTIATE AUDIO FORMAT WITH SFX ENGINE
        final int srcFormat = codecParams.format();
        final int srcChannels = codecParams.ch_layout().nb_channels();
        final boolean srcPlanar = av_sample_fmt_is_planar(srcFormat) != 0;
        final SFXEngine.SampleType srcType = toSampleType(srcFormat);

        final long[] supCh = this.sfx.supportedChannels();
        final SFXEngine.SampleType[] supTypes = this.sfx.supportedTypes();
        final int typeIdx = typeIndex(supTypes, srcType);
        final long exactEntry = entryFor(supCh, srcChannels);
        final boolean exactCombinationOk = exactEntry != 0L && typeIdx >= 0 && typeOkAt(exactEntry, typeIdx);

        this.audioOutputSampleRate = codecParams.sample_rate();

        // PASSTHROUGH REQUIRES: INTERLEAVED SOURCE + (TYPE,CHANNELS) COMBINATION HONESTLY SUPPORTED
        if (!srcPlanar && exactCombinationOk) {
            this.audioPassthrough = true;
            this.audioOutputChannels = srcChannels;
            LOGGER.info(IT, "Audio passthrough: type={}, channels={}, rate={}", srcType, srcChannels, this.audioOutputSampleRate);
            if (!this.sfx.setAudioFormat(srcType, srcChannels, this.audioOutputSampleRate)) {
                LOGGER.error(IT, "SFX engine rejected passthrough format: type={}, ch={}, rate={}", srcType, srcChannels, this.audioOutputSampleRate);
                return false;
            }
            return true;
        }

        // RESAMPLE — PICK TARGET FROM THE SUPPORT TABLE:
        //   CHANNELS: exact match if possible, else closest supported count
        //   TYPE:     preserve if supported AT TARGET CHANNEL COUNT, else fallback (prefers S16)
        this.audioPassthrough = false;
        final long targetEntry = exactEntry != 0L ? exactEntry : closestEntry(supCh, srcChannels);
        this.audioOutputChannels = channelsOf(targetEntry);

        final SFXEngine.SampleType outType;
        if (typeIdx >= 0 && typeOkAt(targetEntry, typeIdx)) {
            outType = srcType;
        } else {
            outType = fallbackType(supTypes, targetEntry);
            if (outType == null) {
                LOGGER.error(IT, "SFX engine exposes no supported types for {} channels", this.audioOutputChannels);
                return false;
            }
        }
        this.audioOutputAvFormat = sampleTypeToAvFormat(outType);

        LOGGER.info(IT, "Audio resample ({}): {} {}ch {}Hz → {} {}ch {}Hz", srcPlanar ? "de-planarize only" : "format conversion",
                getString(av_get_sample_fmt_name(srcFormat), srcFormat),
                srcChannels, this.audioOutputSampleRate,
                outType, this.audioOutputChannels, this.audioOutputSampleRate);

        this.swrContext = swresample.swr_alloc();
        if (this.swrContext == null) {
            LOGGER.error(IT, "Failed to allocate SWResampler context");
            return false;
        }

        final AVChannelLayout inputLayout = new AVChannelLayout();
        final AVChannelLayout outputLayout = new AVChannelLayout();

        try {
            if (srcChannels > 0) {
                avutil.av_channel_layout_copy(inputLayout, codecParams.ch_layout());
            } else {
                LOGGER.warn(IT, "Audio codec has no channel layout info, defaulting to stereo");
                avutil.av_channel_layout_default(inputLayout, 2);
            }

            avutil.av_channel_layout_default(outputLayout, this.audioOutputChannels);

            avutil.av_opt_set_chlayout(this.swrContext, "in_chlayout", inputLayout, 0);
            avutil.av_opt_set_int(this.swrContext, "in_sample_rate", this.audioOutputSampleRate, 0);
            avutil.av_opt_set_sample_fmt(this.swrContext, "in_sample_fmt", srcFormat, 0);

            avutil.av_opt_set_chlayout(this.swrContext, "out_chlayout", outputLayout, 0);
            avutil.av_opt_set_int(this.swrContext, "out_sample_rate", this.audioOutputSampleRate, 0);
            avutil.av_opt_set_sample_fmt(this.swrContext, "out_sample_fmt", sampleTypeToAvFormat(outType), 0);

            if (swresample.swr_init(this.swrContext) < 0) {
                LOGGER.error(IT, "Failed to initialize SWResampler context");
                return false;
            }
        } finally {
            av_channel_layout_uninit(inputLayout);
            av_channel_layout_uninit(outputLayout);
        }

        if (!this.sfx.setAudioFormat(outType, this.audioOutputChannels, this.audioOutputSampleRate)) {
            LOGGER.error(IT, "SFX engine rejected resample output format: type={}, ch={}, rate={}", outType, this.audioOutputChannels, this.audioOutputSampleRate);
            return false;
        }

        return true;
    }

    // SWS CONTEXT (FALLBACK ONLY)
    private void recreateSwsContext(final int srcWidth, final int srcHeight, final int srcFormat) {
        if (this.swsContext != null) {
            swscale.sws_freeContext(this.swsContext);
            this.swsContext = null;
        }

        this.swsContext = swscale.sws_getContext(
                srcWidth, srcHeight, srcFormat,
                srcWidth, srcHeight, avutil.AV_PIX_FMT_BGRA,
                swscale.SWS_FAST_BILINEAR, null, null, (double[]) null
        );

        final var pointerName = av_get_pix_fmt_name(srcFormat);
        final String fmtName = getString(pointerName, srcFormat);

        if (this.swsContext == null) {
            LOGGER.error(IT, "Failed to create fallback SwsContext: {} ({}x{}) -> BGRA", fmtName, srcWidth, srcHeight);
        } else {
            this.swsInputFormat = srcFormat;
            LOGGER.info(IT, "Fallback SwsContext: {} ({}x{}) -> BGRA", fmtName, srcWidth, srcHeight);
        }
    }

    // CLEANUP
    private void cleanup() {
        LOGGER.info(IT, "Cleaning up FFMPEG resources...");

        if (this.hwDeviceCtx != null) {
            av_buffer_unref(this.hwDeviceCtx);
            this.hwDeviceCtx = null;
        }
        this.hwPixelFormat = AV_PIX_FMT_NONE;

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
        this.interruptCallback = null;

        if (this.scaledFrame != null) {
            avutil.av_frame_free(this.scaledFrame);
            this.scaledFrame = null;
        }

        this.videoBuffer = null;
        this.planeY = null;
        this.planeU = null;
        this.planeV = null;
        this.planeA = null;
        this.lastColorSpace = null;
        this.lastBitsPerComponent = 0;

        this.videoStreamIndex = -1;
        this.audioStreamIndex = -1;
        this.swsInputFormat = AV_PIX_FMT_NONE;

        LOGGER.info(IT, "Cleanup completed");
    }

    // UTILITY
    private static String getString(final BytePointer p, final Object orElse) { return !isNull(p) ? p.getString() : orElse != null ? String.valueOf(orElse) : null; }
    private static String getString(final BytePointer p, final int orElse) { return !isNull(p) ? p.getString() : String.valueOf(orElse); }
    private static boolean isNull(final Pointer p) { return p == null || p.isNull(); }

    // STATIC
    public static boolean load(final WaterMedia watermedia) {
        Objects.requireNonNull(watermedia, "WaterMedia instance cannot be null");

        LOGGER.info(IT, "Starting FFMPEG...");
        if (WaterMediaConfig.media.disableFFMPEG) {
            LOGGER.warn(IT, "FFMPEG startup was cancelled, user settings disables it");
            return false;
        }

        try {
            final String ffmpegPath = WaterMediaBinaries.pathOf(WaterMediaBinaries.FFMPEG_ID).toAbsolutePath().toString();
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

            LOGGER.info(IT, "=== FFMPEG Build Info ===");
            LOGGER.info(IT, "avformat: {}", avformat.avformat_version());
            LOGGER.info(IT, "avcodec:  {}", avcodec.avcodec_version());
            LOGGER.info(IT, "avutil:   {}", avutil.avutil_version());
            LOGGER.info(IT, "swscale:  {}", swscale.swscale_version());
            LOGGER.info(IT, "swresample: {}", swresample.swresample_version());

            try {
                final BytePointer config = avformat.avformat_configuration();
                LOGGER.info(IT, "Configuration: {}", getString(config, "unavailable"));
            } catch (final Exception e) {
                LOGGER.warn(IT, "Configuration: unavailable");
            }

            LOGGER.info(IT, "Hardware Acceleration:");
            int hwType = avutil.AV_HWDEVICE_TYPE_NONE;
            int hwCount = 0;
            do {
                hwType = avutil.av_hwdevice_iterate_types(hwType);
                if (hwType == avutil.AV_HWDEVICE_TYPE_NONE) break;

                final BytePointer hwName = avutil.av_hwdevice_get_type_name(hwType);
                final String hwNameStr = getString(hwName, null);
                if (hwNameStr != null) {
                    LOGGER.info(IT, "  • {}", hwNameStr);
                    hwCount++;
                }
                IOTool.closeQuietly(hwName);
            } while (true);

            if (hwCount == 0) {
                LOGGER.info(IT, "  (none available)");
            }

            final BytePointer license = avformat.avformat_license();
            LOGGER.info(IT, "FFMPEG started, running version {} under {}", avformat.avformat_version(), getString(license, "unknown"));
            IOTool.closeQuietly(license);
            return LOADED = true;
        } catch (final Throwable t) {
            LOGGER.error(IT, "Failed to load FFMPEG", t);
            return false;
        }
    }

    public static boolean loaded() { return LOADED; }
}