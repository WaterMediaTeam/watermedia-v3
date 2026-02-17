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
import org.watermedia.api.media.players.util.MasterClock;
import org.watermedia.api.util.MathUtil;
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
 * MediaPlayer instance implementing FFMPEG
 */
public final class FFMediaPlayer extends MediaPlayer {
    private static final Marker IT = MarkerManager.getMarker(FFMediaPlayer.class.getSimpleName());
    private static final ThreadFactory DEFAULT_THREAD_FACTORY = ThreadTool.createFactory("FFThread", Thread.NORM_PRIORITY);
    private static boolean LOADED;

    // CONSTANTS
    private static final int AUDIO_SAMPLE_RATE = 48000;
    private static final int AUDIO_CHANNELS = 2;
    private static final int AUDIO_SAMPLES = 2048;
    private static final int[] VIDEO_HW_CODECS = {
            AV_HWDEVICE_TYPE_CUDA,          // NVIDIA
            AV_HWDEVICE_TYPE_QSV,           // INTEL
            //  OS DEDICATED HARDWARE
            AV_HWDEVICE_TYPE_D3D12VA,       // Windows DirectX 12
            AV_HWDEVICE_TYPE_D3D11VA,       // Windows DirectX 11
            AV_HWDEVICE_TYPE_VIDEOTOOLBOX,  // MACOS
            AV_HWDEVICE_TYPE_VAAPI,         // Linux VA-API
            AV_HWDEVICE_TYPE_VDPAU,         // Linux VDPAU
            AV_HWDEVICE_TYPE_DRM,           // Linux DRM
            AV_HWDEVICE_TYPE_MEDIACODEC,    // Android MEDIACODEC
            //  GENERIC HARDWARE DECODERS
            AV_HWDEVICE_TYPE_VULKAN,        // VULKAN
            AV_HWDEVICE_TYPE_OPENCL,        // OPENCL
    };

    // FFMPEG COMPONENTS
    private AVFormatContext formatContext;
    private AVCodecContext videoCodecContext;
    private AVCodecContext audioCodecContext;
    private SwsContext swsContext;
    private SwrContext swrContext;

    // HW
    private AVBufferRef hwDeviceCtx;
    private AVFrame hwTransferFrame;
    private int hwPixelFormat = AV_PIX_FMT_NONE;
    private int swsInputFormat = AV_PIX_FMT_NONE;

    // STREAM INDEXES
    private int videoStreamIndex = -1;
    private int audioStreamIndex = -1;

    // STATUS (SHARED IN PLAYERLOOP)
    private Thread playerThread;
    private volatile Status status = Status.WAITING;
    private volatile boolean pauseRequested = false;
    private volatile boolean qualityRequest = false;
    private volatile long seekTo = -1;
    private volatile long preciseSeekTo = -1;

    // CLOCK SYSTEM
    private final MasterClock clock = new MasterClock();

    // REUSABLE BUFFERS
    private AVPacket packet;
    private AVFrame videoFrame;
    private AVFrame audioFrame;
    private AVFrame scaledFrame;
    private AVFrame resampledFrame;
    private ByteBuffer videoBuffer;
    private boolean videoBufferInitialized = false;

    // TIMES
    private double videoTimeBase;
    private double audioTimeBase;

    // STATS (unused yet)
    private int consecutiveSkips = 0;
    private long totalSkippedFrames = 0;
    private long totalRenderedFrames = 0;

    private static final int MAX_DECODE_THREADS = ThreadTool.halfThreads();

    public FFMediaPlayer(final MRL.Source source, final Thread renderThread, final Executor renderThreadEx,
                         final GLEngine gl, final ALEngine al, final boolean video, final boolean audio) {
        super(source, renderThread, renderThreadEx, gl, al, video, audio);
    }

    @Override
    public void start() {
        if (this.playerThread != null && this.playerThread.isAlive() && !this.playerThread.isInterrupted()) {
            this.stop(); // CANNOT TRUST AUTHORS
        }
        final Thread oldThread = this.playerThread;

        this.playerThread = DEFAULT_THREAD_FACTORY.newThread(() -> {
            if (oldThread != null && !ThreadTool.join(oldThread)) {
                return; // IF THE OLD THREAD STILL EXIST BUT WHILE WE JOIN CURRENT THREAD IS INTERRUPTED, THEN DO NOTHING.
            }
            this.playerLoop();
        });
        this.playerThread.setDaemon(true);
        this.playerThread.start();
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
        // super.pause(paused); // MOVED INTO PLAYER LOOP
        this.pauseRequested = paused;
        if (paused) {
            this.clock.pause();
        } else {
            this.clock.resume();
        }
        return true;
    }

    @Override
    public boolean stop() {
        if (this.playerThread != null) {
            this.playerThread.interrupt();
            return true;
        }
        return false;
    }

    @Override
    public boolean togglePlay() { return this.pause(!this.pauseRequested); }

    @Override
    public boolean seek(long timeMs) {
        if (!this.canSeek()) return false;

        this.preciseSeekTo = Math.max(0, Math.min(timeMs, this.duration()));
        return true;
    }

    @Override
    public boolean seekQuick(final long timeMs) {
        if (!this.canSeek()) return false;

        this.seekTo = Math.max(0, Math.min(timeMs, this.duration()));  // USA FAST SEEK
        return true;
    }

    @Override
    public boolean skipTime(final long timeMs) { return this.seek(this.time() + timeMs); }

    @Override
    public boolean previousFrame() {
        if (!this.canSeek()) return false;

        final long frameTimeMs = this.clock.frameDuration();
        return this.seek(Math.max(0, this.time() - frameTimeMs));
    }

    @Override
    public boolean nextFrame() {
        if (!this.canSeek()) return false;

        final long frameTimeMs = this.clock.frameDuration();
        return this.seek(this.time() + frameTimeMs);
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
        if (this.playerThread != null && this.playerThread.isAlive() && !this.playerThread.isInterrupted()) {
            this.qualityRequest = true;
        }
    }

    @Override
    public long time() {
        return this.clock.time();
    }

    @Override
    public boolean speed(float speed) {
        if (super.speed(speed)) {
            this.clock.speed(speed);
            return true;
        }
        return false;
    }

    public boolean isHwAccel() { return !isNull(this.hwDeviceCtx); }

    private void playerLoop() {
        try {
            // RESET
            this.consecutiveSkips = 0;
            this.totalSkippedFrames = 0;
            this.totalRenderedFrames = 0;
            this.clock.reset();

            this.status = Status.LOADING;
            if (!this.init()) {
                this.status = Status.ERROR;
                return;
            }

            if (this.pauseRequested) {
                this.status = Status.PAUSED;
            } else {
                this.status = Status.PLAYING;
                this.clock.start();
            }

            while (!ThreadTool.isInterrupted()) {
                // HANDLE QUALITY SWITCH
                if (this.qualityRequest) {
                    final long currentTimeMs = this.time();
                    final boolean wasPaused = this.pauseRequested;

                    LOGGER.info(IT, "Switching quality to {} at {}s", this.quality, MathUtil.msToSeconds(currentTimeMs));

                    // CLEAN FFMPEG IN CASE QUALITY SWAP
                    this.cleanup();

                    // RESET FOR NEW URL
                    this.consecutiveSkips = 0;
                    this.totalSkippedFrames = 0;
                    this.totalRenderedFrames = 0;

                    // NO resetear el clock aquí, solo pausarlo temporalmente
                    this.clock.pause();

                    this.status = Status.BUFFERING;

                    // RE-INIT
                    if (!this.init()) {
                        this.status = Status.ERROR;
                        return;
                    }

                    // SEEK TO THE CURRENT TIMESTAMP
                    if (currentTimeMs > 0 && this.canSeek()) {
                        this.preciseSeekTo = currentTimeMs;
                    }

                    this.clock.time(currentTimeMs);

                    // RESTORE PAUSE/PLAY state
                    if (wasPaused) {
                        this.status = Status.PAUSED;
                        this.clock.pause();
                    } else {
                        this.status = Status.PLAYING;
                        this.clock.start(); // Esto reinicia baseTimeMs pero masterTimeMs ya está en currentTimeMs
                    }

                    LOGGER.info(IT, "Successfully switched quality to {}", this.quality);
                    this.qualityRequest = false;
                    continue;
                }

                // HANDLE PRECISE SEEK
                if (this.preciseSeekTo >= 0) {
                    final long targetMs = this.preciseSeekTo;
                    this.preciseSeekTo = -1;

                    if (this.formatContext == null || !this.canSeek()) {
                        LOGGER.warn(IT, "Failed to perform precise seek - cannot seek on current context");
                        continue;
                    }

                    final long currentMs = this.clock.time();
                    LOGGER.info(IT, "Precise seeking to {}ms - current time {}ms", targetMs, currentMs);

                    // FLUSH CODECS
                    if (this.videoCodecContext != null) avcodec.avcodec_flush_buffers(this.videoCodecContext);
                    if (this.audioCodecContext != null) avcodec.avcodec_flush_buffers(this.audioCodecContext);

                    // SEEK BACKWARD FIRST TO FIND A KEYFRAME BEFORE TARGET
                    final long ffmpegTimestamp = (targetMs / 1000L) * avutil.AV_TIME_BASE;
                    final int result = avformat.av_seek_frame(this.formatContext, -1, ffmpegTimestamp, avformat.AVSEEK_FLAG_BACKWARD);

                    if (result >= 0) {
                        // NOW DECODE FRAMES UNTIL WE REACH THE TARGET
                        int maxFramesToSkip = 500;
                        long lastAudioPts = -1;
                        long lastVideoPts = -1;
                        boolean reachedTarget = false;

                        while (maxFramesToSkip-- > 0 && !ThreadTool.isInterrupted() && !reachedTarget) {
                            final int readResult = avformat.av_read_frame(this.formatContext, this.packet);
                            if (readResult < 0) break;

                            try {
                                final int streamIndex = this.packet.stream_index();
                                final long packetPts = this.packet.pts();

                                if (streamIndex == this.audioStreamIndex && this.audio && packetPts != avutil.AV_NOPTS_VALUE) {
                                    lastAudioPts = (long) (packetPts * this.audioTimeBase * 1000);

                                    // SIEMPRE enviar al decoder, pero sin reproducir
                                    avcodec.avcodec_send_packet(this.audioCodecContext, this.packet);

                                    // Drenar frames decodificados sin reproducirlos
                                    while (avcodec.avcodec_receive_frame(this.audioCodecContext, this.audioFrame) >= 0);

                                    if (lastAudioPts >= targetMs - this.clock.frameDuration()) {
                                        reachedTarget = true;
                                    }
                                } else if (streamIndex == this.videoStreamIndex && this.video && packetPts != avutil.AV_NOPTS_VALUE) {
                                    lastVideoPts = (long) (packetPts * this.videoTimeBase * 1000);

                                    // SIEMPRE enviar al decoder para mantener la cadena de referencia
                                    avcodec.avcodec_send_packet(this.videoCodecContext, this.packet);

                                    // Drenar frames decodificados sin renderizarlos
                                    while (avcodec.avcodec_receive_frame(this.videoCodecContext, this.videoFrame) >= 0) {
                                        // Solo consumir, no renderizar
                                    }

                                    if (!this.audio && lastVideoPts >= targetMs - this.clock.frameDuration()) {
                                        reachedTarget = true;
                                    }
                                }
                            } finally {
                                avcodec.av_packet_unref(this.packet);
                            }
                        }

                        this.clock.time(targetMs);
                        this.consecutiveSkips = 0;
                        LOGGER.info(IT, "Precise seek completed to {}ms (audio: {}ms, video: {}ms)", targetMs, lastAudioPts, lastVideoPts);
                    } else {
                        LOGGER.error(IT, "Precise seek failed with error: {}", result);
                    }
                    continue;
                }

                // HANDLE FAST SEEK (imprecise, keyframe-based)
                if (this.seekTo >= 0) {
                    final long targetMs = this.seekTo;
                    this.seekTo = -1;

                    if (this.formatContext == null || !this.canSeek()) {
                        LOGGER.warn(IT, "Failed to perform seek - cannot seek on current context");
                        continue;
                    }

                    final long currentMs = this.clock.time();
                    LOGGER.info(IT, "Fast seeking to {}ms - current time {}ms", targetMs, currentMs);

                    // FLUSH CODECS
                    if (this.videoCodecContext != null) avcodec.avcodec_flush_buffers(this.videoCodecContext);
                    if (this.audioCodecContext != null) avcodec.avcodec_flush_buffers(this.audioCodecContext);

                    // SIMPLE KEYFRAME SEEK
                    final long ffmpegTimestamp = (targetMs / 1000L) * avutil.AV_TIME_BASE;
                    final int seekFlags = targetMs < currentMs ? avformat.AVSEEK_FLAG_BACKWARD : 0;
                    final int result = avformat.av_seek_frame(this.formatContext, -1, ffmpegTimestamp, seekFlags);

                    if (result >= 0) {
                        this.clock.time(targetMs);
                        this.consecutiveSkips = 0;
                        LOGGER.info(IT, "Fast seek completed to {}ms", targetMs);
                    } else {
                        LOGGER.error(IT, "Fast seek failed with error: {}", result);
                    }
                    continue;
                }

                // UPDATE STATUS BASED ON PAUSE
                if (this.pauseRequested && this.status != Status.PAUSED) {
                    this.status = Status.PAUSED;
                    // TODO: THIS SHOULD NOT BE HERE
                    super.pause();
                } else if (!this.pauseRequested && this.status == Status.PAUSED) {
                    this.status = Status.PLAYING;
                }

                if (this.pauseRequested) {
                    Thread.yield();
                    continue;
                }

                // Read and process frame
                final int result = avformat.av_read_frame(this.formatContext, this.packet);
                if (result < 0) {
                    LOGGER.info(IT, "Finished! - R: {}, S: {}", this.totalRenderedFrames, this.totalSkippedFrames);

                    if (this.repeat()) {
                        LOGGER.info(IT, "Repeating playback");
                        this.seekTo = 0;
                        continue; // GO BACK
                    } else {
                        LOGGER.info(IT, "Playback ended naturally");
                        this.status = Status.ENDED;
                        Thread.yield();
                    }

                    if (this.status != Status.PLAYING) {
                        break;
                    }
                    continue;
                }

                // PROCESS PACKETS
                try {
                    if (this.packet.stream_index() == this.videoStreamIndex && this.video) this.processVideoPacket();
                    if (this.packet.stream_index() == this.audioStreamIndex && this.audio) this.processAudioPacket();
                } finally {
                    avcodec.av_packet_unref(this.packet);
                }
            }
            this.status = ThreadTool.isInterrupted() ? Status.STOPPED : Status.ENDED;
        } catch (final Throwable e) {
            LOGGER.fatal(IT, "Error handled in in player loop for URI {}", this.source.uri(this.quality), e);
            this.status = Status.ERROR;
        } finally {
            this.cleanup();
        }
    }


    private boolean init() {
        try {
            // ALLOCATE PACKETS
            this.packet = avcodec.av_packet_alloc();
            this.videoFrame = avutil.av_frame_alloc();
            this.audioFrame = avutil.av_frame_alloc();
            this.scaledFrame = avutil.av_frame_alloc();
            this.resampledFrame = avutil.av_frame_alloc();

            if (this.packet == null || this.videoFrame == null || this.audioFrame == null || this.scaledFrame == null || this.resampledFrame == null)
                return false;

            // PREPARE URI
            final var uri = this.source.uri(this.quality);
            final var url = uri.getScheme().contains("file") ? uri.getPath().substring(1) : uri.toString();

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

            // FIND AVAILABLE STREAMS (AUDIO AND VIDEO ONLY)
            for (int i = 0; i < this.formatContext.nb_streams(); i++) {
                final AVStream stream = this.formatContext.streams(i);
                final int codecType = stream.codecpar().codec_type();

                if (codecType == avutil.AVMEDIA_TYPE_VIDEO && this.videoStreamIndex < 0 && this.video) {
                    this.videoStreamIndex = i;
                    this.videoTimeBase = av_q2d(stream.time_base());
                } else if (codecType == avutil.AVMEDIA_TYPE_AUDIO && this.audioStreamIndex < 0 && this.audio) {
                    this.audioStreamIndex = i;
                    this.audioTimeBase = av_q2d(stream.time_base());
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

    private boolean initVideo() {
        if (!this.video || this.videoStreamIndex < 0) {
            LOGGER.warn(IT, "No video stream found or video disabled");
            return true;  // IS NOT AN ERROR WHEN VIDEO IS DISABLED
        }

        final AVStream videoStream = this.formatContext.streams(this.videoStreamIndex);
        final AVRational frameRate = videoStream.avg_frame_rate();

        if (frameRate.num() > 0 && frameRate.den() > 0) {
            this.clock.fps((float) av_q2d(frameRate));
        } else {
            // Fallback basado en time_base si avg_frame_rate no está disponible
            // Aunque personalmente no le encuentro utilidad
            final AVRational tbr = videoStream.r_frame_rate();
            if (tbr.num() > 0 && tbr.den() > 0) {
                this.clock.fps((float) av_q2d(tbr));
            }
        }

        LOGGER.info(IT, "Video timing: {}fps, frame duration: {}ms, skip threshold: {}ms",
                this.clock.fps(),
                this.clock.frameDuration(),
                this.clock.skipThreshold());

        // START DECODER
        final AVCodecParameters codecpar = videoStream.codecpar();
        final int codecId = codecpar.codec_id();

        // DECODER NAME
        final String codecName = avcodec_get_name(codecId).getString();

        // LARGE DECODER NAME
        final AVCodecDescriptor descriptor = avcodec_descriptor_get(codecId);
        final String codecLongName = descriptor != null ? descriptor.long_name().getString() : "unknown";

        // OTHER DATA
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

        // IF NO HW DECODER (GPU), USE SW DECODER (CPU)
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

        // SET FORMAT
        this.setVideoFormat(GL12.GL_BGRA, this.videoCodecContext.width(), this.videoCodecContext.height());

        // NOTA: SwsContext se crea lazily en processVideoPacket porque
        // el formato real del frame puede diferir del codec context cuando
        // se usa hardware acceleration (el frame transferido desde GPU
        // típicamente es NV12/P010, no el formato original del stream)
        this.scaledFrame.format(avutil.AV_PIX_FMT_BGRA);
        this.scaledFrame.width(this.width());
        this.scaledFrame.height(this.height());

        // ALLOCATE FRAME BUFFER ALIGNED AT 32 BYTES
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

                // ALLOCATE HW CONTEXT
                this.hwDeviceCtx = new AVBufferRef();
                if (av_hwdevice_ctx_create(this.hwDeviceCtx, hw, (String) null, null, 0) >= 0) {
                    // CREATE CODEC
                    this.videoCodecContext = avcodec.avcodec_alloc_context3(decoder);
                    if (avcodec_parameters_to_context(this.videoCodecContext, videoStream.codecpar()) < 0) {
                        this.cleanupHwContext();
                        continue;
                    }

                    this.videoCodecContext.hw_device_ctx(av_buffer_ref(this.hwDeviceCtx));
                    this.hwPixelFormat = config.pix_fmt();

                    if (avcodec.avcodec_open2(this.videoCodecContext, decoder, (PointerPointer<?>) null) >= 0) {
                        this.hwTransferFrame = av_frame_alloc();

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

        final AVStream audioStream = this.formatContext.streams(this.audioStreamIndex);
        final AVCodecParameters codecParams = audioStream.codecpar();
        final int codecId = codecParams.codec_id();
        final AVCodec audioCodec = avcodec.avcodec_find_decoder(codecId);

        int errorCode;
        if (audioCodec == null) {
            LOGGER.error(IT, "Failed to find audio codec with id {} for audioIndex {}", codecId, this.audioStreamIndex);
            return false;
        }

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

        // Initialize resampler
        this.swrContext = swresample.swr_alloc();
        if (this.swrContext == null) {
            LOGGER.error(IT, "Failed to allocate SWResampler context");
            return false;
        }

        // CHANNELS LAYOUTS
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

        this.resampledFrame.format(avutil.AV_SAMPLE_FMT_S16);
        this.resampledFrame.ch_layout().nb_channels(AUDIO_CHANNELS);
        avutil.av_channel_layout_default(this.resampledFrame.ch_layout(), AUDIO_CHANNELS);
        this.resampledFrame.sample_rate(AUDIO_SAMPLE_RATE);
        this.resampledFrame.nb_samples(AUDIO_SAMPLES);

        return avutil.av_frame_get_buffer(this.resampledFrame, 0) >= 0;
    }

    private void processVideoPacket() {
        if (avcodec.avcodec_send_packet(this.videoCodecContext, this.packet) < 0) return;

        while (avcodec.avcodec_receive_frame(this.videoCodecContext, this.videoFrame) >= 0) {
            AVFrame frameToScale = this.videoFrame;

            // ON HW DECODING, GET FROM GPU
            if (this.hwDeviceCtx != null && this.videoFrame.format() == this.hwPixelFormat) {
                if (av_hwframe_transfer_data(this.hwTransferFrame, this.videoFrame, 0) < 0) {
                    LOGGER.warn(IT, "Failed to transfer frame from GPU");
                    continue;
                }
                frameToScale = this.hwTransferFrame;
            }

            final long framePtsMs = (long) (this.videoFrame.pts() * this.videoTimeBase * 1000);

            // FRAME SKIPPING
            if (this.clock.skip(framePtsMs)) {
                LOGGER.debug(IT, "Your decoding is taking too long, skipping!");
                this.totalSkippedFrames++;
                this.consecutiveSkips++;

                // RENDER 1 FRAME AFTER 5 FRAMES
                if (this.consecutiveSkips < 5) continue;
            }
            this.consecutiveSkips = 0;

            // BACKPRESSURE WHEN IS NOT AUDIO
            if (this.audioStreamIndex < 0 && (this.pauseRequested || !this.clock.waiting(framePtsMs))) {
                return; // Interrupted or pause requested
            }

            // UPDATE CLOCK WHEN THERE IS NO AUDIO
            if (this.audioStreamIndex < 0) {
                this.clock.update(framePtsMs);
            }

            final int frameFormat = frameToScale.format();
            final int frameWidth = frameToScale.width();
            final int frameHeight = frameToScale.height();

            if (this.swsContext == null || this.swsInputFormat != frameFormat) {
                // FREE OLD CONTEXT
                if (this.swsContext != null) {
                    swscale.sws_freeContext(this.swsContext);
                    this.swsContext = null;
                }

                // CREATE NEW CONTEXT WITH THE NEW FORMAT
                this.swsContext = swscale.sws_getContext(
                        frameWidth, frameHeight, frameFormat,
                        this.width(), this.height(), avutil.AV_PIX_FMT_BGRA,
                        swscale.SWS_BILINEAR, null, null, (double[]) null
                );

                if (this.swsContext == null) {
                    LOGGER.error(IT, "Failed to create SwsContext for format {} ({}x{})", frameFormat, frameWidth, frameHeight);
                    return;
                }

                this.swsInputFormat = frameFormat;
                LOGGER.debug(IT, "Successfully created SwsContext: format {} ({}x{}) -> BGRA ({}x{})", frameFormat, frameWidth, frameHeight, this.width(), this.height());
            }

            // GET A SINGLE BUFFER INSTANCE, SO JAVACPP DOESN'T SPAM BUFFER OBJECTS WITH THE SAME POINTER
            if (!this.videoBufferInitialized) {
                this.videoBuffer = this.scaledFrame.data(0).asBuffer();
                this.videoBufferInitialized = true;
            }

            synchronized (this.videoBuffer) {
                final int result = swscale.sws_scale(
                        this.swsContext,
                        frameToScale.data(),
                        frameToScale.linesize(),
                        0,
                        frameHeight,
                        this.scaledFrame.data(),
                        this.scaledFrame.linesize()
                );

                if (result <= 0) {
                    LOGGER.error(IT, "Failed to scale video frame: result={}, srcFmt={}, srcSize={}x{}", result, frameFormat, frameWidth, frameHeight);
                    return;
                }
            }

            final int stride = this.scaledFrame.linesize(0);
            this.upload(this.videoBuffer, stride / 4);
            this.totalRenderedFrames++;
        }
    }

    private void processAudioPacket() {
        if (avcodec.avcodec_send_packet(this.audioCodecContext, this.packet) < 0) return;

        while (avcodec.avcodec_receive_frame(this.audioCodecContext, this.audioFrame) >= 0) {
            // AUDIO CLOCK IS PRIMARY CLOCK
            final long audioPtsMs = (long) (this.audioFrame.pts() * this.audioTimeBase * 1000);
            this.clock.update(audioPtsMs);

            // RESAMPLE TO NON-FLOATING POINT
            final int samplesConverted = swresample.swr_convert(
                    this.swrContext,
                    this.resampledFrame.data(),
                    this.resampledFrame.nb_samples(),
                    this.audioFrame.data(),
                    this.audioFrame.nb_samples()
            );

            // HAVE SAMPLES, UPLOAD!
            if (samplesConverted > 0) {
                final int dataSize = samplesConverted * AUDIO_CHANNELS * 2;

                final ByteBuffer audioData = this.resampledFrame.data(0)
                        .limit(dataSize)
                        .asBuffer()
                        .clear();

                this.upload(audioData, AL10.AL_FORMAT_STEREO16, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS);
            }

            // CHECK FOR RESAMPLES
            final long delay = swresample.swr_get_delay(this.swrContext, AUDIO_SAMPLE_RATE);

            if (delay > AUDIO_SAMPLES / 2) {
                // FLUSH RESIDUALS
                final int flushed = swresample.swr_convert(
                        this.swrContext,
                        this.resampledFrame.data(),
                        this.resampledFrame.nb_samples(),
                        null,
                        0
                );

                if (flushed > 0) {
                    final int dataSize = flushed * AUDIO_CHANNELS * 2;
                    final ByteBuffer audioData = this.resampledFrame.data(0)
                            .limit(dataSize)
                            .asBuffer()
                            .clear();

                    this.upload(audioData, AL10.AL_FORMAT_STEREO16, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS);
                }
            }
        }
    }

    private void cleanup() {
        LOGGER.info(IT, "Cleaning up FFMPEG resources...");

        // FREE HW
        if (this.hwTransferFrame != null) {
            av_frame_free(this.hwTransferFrame);
            this.hwTransferFrame = null;
        }
        if (this.hwDeviceCtx != null) {
            av_buffer_unref(this.hwDeviceCtx);
            this.hwDeviceCtx = null;
        }
        this.hwPixelFormat = AV_PIX_FMT_NONE;

        // FREE CONTEXTS
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

        // FREE INSTANCES
        if (this.videoFrame != null) {
            avutil.av_frame_free(this.videoFrame);
            this.videoFrame = null;
        }
        if (this.audioFrame != null) {
            avutil.av_frame_free(this.audioFrame);
            this.audioFrame = null;
        }
        if (this.scaledFrame != null) {
            avutil.av_frame_free(this.scaledFrame);
            this.scaledFrame = null;
        }
        if (this.resampledFrame != null) {
            avutil.av_frame_free(this.resampledFrame);
            this.resampledFrame = null;
        }
        if (this.packet != null) {
            avcodec.av_packet_free(this.packet);
            this.packet = null;
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

    // NO NULLPOINTERS ON JAVA, NO NULLPOINTERS ON NATIVE
    private static boolean isNull(Pointer p) { return p == null || p.isNull(); }

    public static boolean load(final WaterMedia watermedia) {
        Objects.requireNonNull(watermedia, "WaterMedia instance cannot be null");

        LOGGER.info(IT, "Starting FFMPEG...");
        if (WaterMediaConfig.media.disableFFMPEG) {
            LOGGER.warn(IT, "FFMPEG startup was cancelled, user settings disables it");
            return true;
        }

        try {
            // ASK WATERMEDIA BINARIES WHERE WAS FFMPEG
            final String ffmpegPath = WaterMediaBinaries.pathOf(WaterMediaBinaries.FFMPEG_ID).toAbsolutePath().toString();
            // ALSO LOOK IN USER-DEFINED FOLDERS (OR RUNTIME FOLDER AS DEFAULT)
            final String configPath = WaterMediaConfig.media.customFFmpegPath != null ? WaterMediaConfig.media.customFFmpegPath.toAbsolutePath().toString() : null;
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

            // BUILD CONFIGURATION, THE ARGUMENTS USED
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

            // LICENSE INFO (binaries ships GPL but that doesn't contaminate WMB or WM itself)
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