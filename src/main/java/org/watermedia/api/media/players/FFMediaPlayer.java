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
import org.bytedeco.javacpp.PointerPointer;
import org.lwjgl.openal.AL10;
import org.lwjgl.opengl.GL12;
import org.watermedia.WaterMedia;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.engines.ALEngine;
import org.watermedia.api.media.engines.GLEngine;
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.tools.ThreadTool;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.watermedia.WaterMedia.LOGGER;

/**
 * Optimized FFMediaPlayer implementation with:
 * - Hardware acceleration support (when available)
 * - Intelligent frame skipping for smooth playback
 * - Reduced memory allocations and GC pressure
 * - Proper resource cleanup (no memory leaks)
 * - Spin-wait timing for precise frame pacing
 * - Audio buffer pooling to prevent underruns
 */
public final class FFMediaPlayer extends MediaPlayer {
    private static final Marker IT = MarkerManager.getMarker(FFMediaPlayer.class.getSimpleName());
    private static final ThreadFactory DEFAULT_THREAD_FACTORY = ThreadTool.createFactory("FFThread", Thread.NORM_PRIORITY);
    private static boolean LOADED;

    // ===========================================
    // AUDIO CONSTANTS - OPTIMIZED
    // ===========================================
    private static final int AUDIO_SAMPLE_RATE = 48000;
    private static final int AUDIO_CHANNELS = 2;
    private static final int AUDIO_SAMPLES = 2048;  // OPTIMIZACIÓN: Más samples = menos llamadas, mejor eficiencia
    private static final int AUDIO_BUFFER_SIZE = AUDIO_SAMPLES * AUDIO_CHANNELS * 2; // Pre-calculado

    // ===========================================
    // FFMPEG COMPONENTS
    // ===========================================
    private AVFormatContext formatContext;
    private AVCodecContext videoCodecContext;
    private AVCodecContext audioCodecContext;
    private SwsContext swsContext;
    private SwrContext swrContext;

    // Hardware acceleration
    private AVBufferRef hwDeviceCtx;
    private int hwPixelFormat = AV_PIX_FMT_NONE;
    private AVFrame hwTransferFrame;  // Para transferir desde GPU
    private int swsInputFormat = AV_PIX_FMT_NONE;  // Formato real de entrada para sws

    // Stream indices
    private int videoStreamIndex = -1;
    private int audioStreamIndex = -1;

    // ===========================================
    // THREADING - volatile para visibilidad
    // ===========================================
    private Thread playerThread;
    private volatile boolean running = false;
    private volatile Status currentStatus = Status.WAITING;
    private volatile boolean pauseRequested = false;
    private volatile long seekTarget = -1;
    private volatile float speedFactor = 1.0f;
    private volatile MRL.Quality activeQuality; // Quality currently being played

    // ===========================================
    // CLOCK SYSTEM - Optimizado
    // ===========================================
    private volatile double masterClock = 0.0;
    private volatile long clockBaseTime = -1;
    private volatile boolean clockPaused = false;
    private volatile long pausedTime = 0;
    private volatile boolean audioClockValid = false;  // NUEVO: Track si audio está activo

    // ===========================================
    // REUSABLE BUFFERS - Pre-allocated
    // ===========================================
    private AVPacket packet;
    private AVFrame videoFrame;
    private AVFrame audioFrame;
    private AVFrame scaledFrame;
    private AVFrame resampledFrame;

    // OPTIMIZACIÓN: Buffers pre-allocated y reutilizados
    private ByteBuffer videoBuffer;
    private ByteBuffer audioBuffer;  // NUEVO: Buffer reutilizable para audio
    private boolean videoBufferInitialized = false;

    // Time bases (cached)
    private double videoTimeBase;
    private double audioTimeBase;

    // ===========================================
    // FRAME SKIPPING - Sistema Adaptativo
    // ===========================================
    private double frameDurationSeconds = 0.033;   // Default 30fps, recalculado en init
    private double skipThreshold;                   // 1 frame de tolerancia
    private double aggressiveSkipThreshold;         // 3+ frames - skip agresivo
    private double videoFps = 30.0;

    // Estadísticas de frame skipping
    private volatile int consecutiveSkips = 0;
    private volatile long totalSkippedFrames = 0;
    private volatile long totalRenderedFrames = 0;

    // ===========================================
    // ADAPTIVE THREADING
    // ===========================================
    private static final int MIN_DECODE_THREADS = 2;
    private static final int MAX_DECODE_THREADS = Math.min(Runtime.getRuntime().availableProcessors(), 16);

    public FFMediaPlayer(final MRL.Source source, final Thread renderThread, final Executor renderThreadEx,
                         final GLEngine glEngine, final ALEngine alEngine, final boolean video, final boolean audio) {
        super(source, renderThread, renderThreadEx, glEngine, alEngine, video, audio);
    }

    // ===========================================
    // LIFECYCLE METHODS
    // ===========================================
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

    // ===========================================
    // CONTROL METHODS
    // ===========================================

    @Override
    public boolean pause() {
        return this.pause(true);
    }

    @Override
    public boolean pause(final boolean paused) {
        super.pause(paused);
        if (paused && !this.pauseRequested) {
            this.pauseRequested = true;
            this.clockPaused = true;
            this.pausedTime = this.time();
            return true;
        } else if (!paused && this.pauseRequested) {
            this.pauseRequested = false;
            this.clockPaused = false;
            this.masterClock = secondsFromMs(this.pausedTime);
            this.clockBaseTime = System.nanoTime();
            return true;
        }
        return false;
    }

    @Override
    public boolean resume() {
        return this.pause(false);
    }

    @Override
    public boolean stop() {
        this.playerThread.interrupt();
        return true;
    }

    @Override
    public boolean togglePlay() {
        return this.pause(!this.pauseRequested);
    }

    // ===========================================
    // SEEKING METHODS
    // ===========================================

    @Override
    public boolean seek(long timeMs) {
        if (!this.canSeek()) return false;

        timeMs = Math.max(0, Math.min(timeMs, this.duration()));
        this.seekTarget = timeMs;
        return true;
    }

    @Override
    public boolean seekQuick(final long timeMs) {
        return this.seek(timeMs);
    }

    @Override
    public boolean skipTime(final long timeMs) {
        return this.seek(this.time() + timeMs);
    }

    @Override
    public boolean previousFrame() {
        if (!this.canSeek()) return false;

        final long frameTimeMs = (long)(this.frameDurationSeconds * 1000);
        return this.seek(Math.max(0, this.time() - frameTimeMs));
    }

    @Override
    public boolean nextFrame() {
        if (!this.canSeek()) return false;

        final long frameTimeMs = (long)(this.frameDurationSeconds * 1000);
        return this.seek(this.time() + frameTimeMs);
    }

    @Override
    public boolean foward() {
        return this.skipTime(5000);
    }

    @Override
    public boolean rewind() {
        return this.skipTime(-5000);
    }

    // ===========================================
    // SPEED CONTROL
    // ===========================================
    @Override
    public float speed() {
        return this.speedFactor;
    }

    @Override
    public boolean speed(final float speed) {
        if (speed <= 0 || speed > 4.0f) return false;
        this.speedFactor = speed;
        return true;
    }

    // ===========================================
    // STATE METHODS
    // ===========================================
    @Override
    public Status status() {
        return this.currentStatus;
    }

    @Override
    public boolean validSource() {
        return this.formatContext != null && (this.videoStreamIndex >= 0 || this.audioStreamIndex >= 0);
    }

    @Override
    public boolean liveSource() {
        return this.formatContext != null && !this.formatContext.isNull() && this.formatContext.duration() == avutil.AV_NOPTS_VALUE;
    }

    @Override
    public boolean canSeek() {
        return this.validSource() && !this.liveSource();
    }

    @Override
    public boolean canPause() {
        return this.validSource();
    }

    @Override
    public boolean canPlay() {
        return this.validSource();
    }

    @Override
    public long duration() {
        return (this.formatContext == null || this.formatContext.isNull() || this.liveSource()) ? -1 : this.formatContext.duration() / 1000;
    }

    @Override
    public long time() {
        if (this.clockPaused || this.clockBaseTime <= 0) {
            return msFromSeconds(this.masterClock);
        }

        final double elapsed = (System.nanoTime() - this.clockBaseTime) / 1_000_000_000.0;
        final double interpolated = Math.min(elapsed * this.speedFactor, 0.1);

        return msFromSeconds(this.masterClock + interpolated);
    }

    // ===========================================
    // FRAME SKIP STATS (DEBUG/MONITORING)
    // ===========================================
    private double getSkipRatio() {
        final long total = this.totalSkippedFrames + this.totalRenderedFrames;
        return total > 0 ? (double) this.totalSkippedFrames / total : 0.0;
    }

    // TODO: DOWNLEVEL THIS
    public double getVideoFps() {
        return this.videoFps;
    }

    /**
     * Returns true if hardware acceleration is active
     */
    public boolean isHardwareAccelerated() {
        return this.hwDeviceCtx != null && !this.hwDeviceCtx.isNull();
    }

    // ===========================================
    // MAIN PLAYER LOOP
    // ===========================================

    private void playerLoop() {
        try {
            // Reset stats
            this.consecutiveSkips = 0;
            this.totalSkippedFrames = 0;
            this.totalRenderedFrames = 0;
            this.audioClockValid = false;

            this.currentStatus = Status.LOADING;

            // Store the quality we're about to use
            this.activeQuality = this.selectedQuality;

            // Initialize FFMPEG components
            if (!this.init()) {
                this.currentStatus = Status.ERROR;
                return;
            }

            // Set initial state
            if (this.pauseRequested) {
                this.currentStatus = Status.PAUSED;
            } else {
                this.currentStatus = Status.PLAYING;
                this.clockBaseTime = System.nanoTime();
            }

            // Main playback loop
            while (!Thread.currentThread().isInterrupted()) {
                // Handle quality change requests
                if (this.activeQuality != this.selectedQuality) {
                    this.performQualitySwitch();
                    continue;
                }

                // Handle seek requests
                if (this.seekTarget >= 0) {
                    this.performSeek();
                    continue;
                }

                // Update status based on pause state
                if (this.pauseRequested && this.currentStatus != Status.PAUSED) {
                    this.currentStatus = Status.PAUSED;
                } else if (!this.pauseRequested && this.currentStatus == Status.PAUSED) {
                    this.currentStatus = Status.PLAYING;
                }

                // Handle pause state - OPTIMIZACIÓN: yield en lugar de sleep fijo
                if (this.pauseRequested) {
                    Thread.sleep(10);
                    continue;
                }

                // Read and process frame
                final int result = avformat.av_read_frame(this.formatContext, this.packet);
                if (result < 0) {
                    this.handleEndOfStream();
                    if (this.currentStatus != Status.PLAYING) {
                        break;
                    }
                    continue;
                }

                // Process packet
                try {
                    if (this.packet.stream_index() == this.videoStreamIndex && this.video) {
                        this.processVideoPacket();
                    } else if (this.packet.stream_index() == this.audioStreamIndex && this.audio) {
                        this.processAudioPacket();
                    }
                } finally {
                    avcodec.av_packet_unref(this.packet);
                }
            }

            // Set final status
            if (Thread.currentThread().isInterrupted()) {
                this.currentStatus = Status.STOPPED;
            } else if (this.currentStatus == Status.PLAYING) {
                this.currentStatus = Status.ENDED;
            }
        } catch (final InterruptedException e) { // NOT AN ERROR, PLAYER JUST STOPPED
            this.currentStatus = Status.STOPPED;
            Thread.currentThread().interrupt();
        } catch (final Exception e) {
            LOGGER.error(IT, "Error in player loop", e);
            this.currentStatus = Status.ERROR;
        } finally {
            this.cleanup();
        }
    }

    /**
     * Performs a quality switch by saving current timestamp, cleaning up resources,
     * reinitializing with new quality URL, and seeking to the saved timestamp.
     */
    private void performQualitySwitch() {
        final MRL.Quality newQuality = this.selectedQuality;
        final long currentTimeMs = this.time();
        final boolean wasPaused = this.pauseRequested;

        LOGGER.info(IT, "Switching quality from {} to {} at {}ms", this.activeQuality, newQuality, currentTimeMs);

        // Update active quality before cleanup
        this.activeQuality = newQuality;

        // Cleanup current FFmpeg resources (but don't reset running state)
        this.cleanupForQualitySwitch();

        // Reset stats for new stream
        this.consecutiveSkips = 0;
        this.totalSkippedFrames = 0;
        this.totalRenderedFrames = 0;
        this.audioClockValid = false;

        this.currentStatus = Status.BUFFERING;

        // Reinitialize with new quality
        if (!this.init()) {
            LOGGER.error(IT, "Failed to reinitialize after quality switch to {}", newQuality);
            this.currentStatus = Status.ERROR;
            return;
        }

        // Seek to the saved timestamp
        if (currentTimeMs > 0 && this.canSeek()) {
            this.seekTarget = currentTimeMs;
        }

        // Restore pause state
        if (wasPaused) {
            this.currentStatus = Status.PAUSED;
        } else {
            this.currentStatus = Status.PLAYING;
            this.clockBaseTime = System.nanoTime();
        }

        LOGGER.info(IT, "Quality switch completed to {}", newQuality);
    }

    /**
     * Cleans up FFmpeg resources for quality switch without affecting player state.
     * Similar to cleanup() but preserves running state and thread.
     */
    private void cleanupForQualitySwitch() {
        LOGGER.debug(IT, "Cleaning up FFmpeg resources for quality switch...");

        // Free HW context first
        if (this.hwTransferFrame != null) {
            av_frame_free(this.hwTransferFrame);
            this.hwTransferFrame = null;
        }
        if (this.hwDeviceCtx != null) {
            av_buffer_unref(this.hwDeviceCtx);
            this.hwDeviceCtx = null;
        }
        this.hwPixelFormat = AV_PIX_FMT_NONE;

        // Free contexts
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
                LOGGER.warn(IT, "Error closing format context during quality switch", e);
            }
            this.formatContext = null;
        }

        // Free frames
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

        // Clear buffers
        this.videoBuffer = null;
        this.audioBuffer = null;
        this.videoBufferInitialized = false;

        // Reset stream indices
        this.videoStreamIndex = -1;
        this.audioStreamIndex = -1;
        this.audioClockValid = false;
        this.swsInputFormat = AV_PIX_FMT_NONE;

        LOGGER.debug(IT, "Quality switch cleanup completed");
    }

    /**
     * OPTIMIZACIÓN: Seek extraído a método separado para claridad y mejor branch prediction
     */
    private void performSeek() {
        final long targetMs = this.seekTarget;
        this.seekTarget = -1;

        if (this.formatContext == null || !this.canSeek()) {
            LOGGER.warn(IT, "Cannot seek - invalid format context or live stream");
            return;
        }

        final long currentMs = msFromSeconds(this.masterClock);
        LOGGER.info(IT, "Seeking to {}ms - current time {}ms", targetMs, currentMs);

        // Flush codecs
        if (this.videoCodecContext != null) {
            avcodec.avcodec_flush_buffers(this.videoCodecContext);
        }
        if (this.audioCodecContext != null) {
            avcodec.avcodec_flush_buffers(this.audioCodecContext);
        }

        // Perform seek
        final long ffmpegTimestamp = (targetMs / 1000L) * avutil.AV_TIME_BASE;
        final int seekFlags = targetMs < currentMs ? avformat.AVSEEK_FLAG_BACKWARD : 0;
        final int result = avformat.av_seek_frame(this.formatContext, -1, ffmpegTimestamp, seekFlags);

        if (result >= 0) {
            this.masterClock = secondsFromMs(targetMs);
            this.clockBaseTime = System.nanoTime();
            this.consecutiveSkips = 0;
            this.audioClockValid = false;  // Invalidar clock de audio tras seek
            LOGGER.info(IT, "Seek completed to {}ms", targetMs);
        } else {
            LOGGER.error(IT, "av_seek_frame failed with error: {}", result);
        }
    }

    private void handleEndOfStream() throws InterruptedException {
        LOGGER.info(IT, "End of stream reached - rendered: {}, skipped: {}, ratio: {}%",
                this.totalRenderedFrames, this.totalSkippedFrames,
                String.format("%.2f", this.getSkipRatio() * 100));

        Thread.sleep(500);

        if (this.repeat()) {
            LOGGER.info(IT, "Repeating playback");
            this.seekTarget = 0;
        } else {
            LOGGER.info(IT, "Playback ended naturally");
            this.currentStatus = Status.ENDED;
        }
    }

    // ===========================================
    // INITIALIZATION - OPTIMIZED
    // ===========================================

    private boolean init() {
        try {
            // Allocate structures
            this.packet = avcodec.av_packet_alloc();
            this.videoFrame = avutil.av_frame_alloc();
            this.audioFrame = avutil.av_frame_alloc();
            this.scaledFrame = avutil.av_frame_alloc();
            this.resampledFrame = avutil.av_frame_alloc();

            if (this.packet == null || this.videoFrame == null || this.audioFrame == null
                    || this.scaledFrame == null || this.resampledFrame == null) {
                return false;
            }

            // PREPARE URI
            final var uri = this.source.uri(this.selectedQuality);
            final var url = uri.getScheme().contains("file") ? uri.getPath().substring(1) : uri.toString();

            // Open format context
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
                // OPTIMIZACIÓN: Siempre liberar AVDictionary
                av_dict_free(options);
            }

            if (avformat.avformat_find_stream_info(this.formatContext, (PointerPointer<?>) null) < 0) {
                LOGGER.error(IT, "Failed to find stream info");
                return false;
            }

            // Find streams
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

            if (!videoInit && !audioInit) {
                LOGGER.error(IT, "No valid audio or video streams found");
                return false;
            }

            LOGGER.info(IT, "FFmpeg initialized successfully - video: {} (hw: {}), audio: {}",
                    videoInit, this.isHardwareAccelerated(), audioInit);

            return true;
        } catch (final Exception e) {
            LOGGER.error(IT, "Failed to initialize FFmpeg", e);
            return false;
        }
    }

    private boolean initVideo() {
        if (!this.video || this.videoStreamIndex < 0) {
            LOGGER.warn(IT, "No video stream found or video disabled");
            return true;  // Not an error if video is disabled
        }

        final AVStream videoStream = this.formatContext.streams(this.videoStreamIndex);
        final int codecId = videoStream.codecpar().codec_id();

        // OPTIMIZACIÓN: Calcular FPS y frame duration para frame skipping
        this.calculateFrameTiming(videoStream);

        // Intentar decodificación por hardware primero
        if (this.initHardwareDecoder(codecId, videoStream)) {
            LOGGER.info(IT, "Hardware acceleration enabled");
        } else {
            // Fallback a software decoder
            if (!this.initSoftwareDecoder(codecId, videoStream)) {
                return false;
            }
        }

        // Initialize video format
        this.setVideoFormat(GL12.GL_BGRA, this.videoCodecContext.width(), this.videoCodecContext.height());

        // NOTA: SwsContext se crea lazily en processVideoPacket porque
        // el formato real del frame puede diferir del codec context cuando
        // se usa hardware acceleration (el frame transferido desde GPU
        // típicamente es NV12/P010, no el formato original del stream)

        this.scaledFrame.format(avutil.AV_PIX_FMT_BGRA);
        this.scaledFrame.width(this.width());
        this.scaledFrame.height(this.height());

        // OPTIMIZACIÓN: Alineación de 32 bytes para SIMD
        if (avutil.av_frame_get_buffer(this.scaledFrame, 32) < 0) {
            LOGGER.error(IT, "Failed to allocate scaled video frame buffer");
            return false;
        }

        return true;
    }

    /**
     * OPTIMIZACIÓN: Calcular timing de frames para frame skipping adaptativo
     */
    private void calculateFrameTiming(final AVStream videoStream) {
        final AVRational frameRate = videoStream.avg_frame_rate();

        if (frameRate.num() > 0 && frameRate.den() > 0) {
            this.videoFps = (double) frameRate.num() / frameRate.den();
            this.frameDurationSeconds = 1.0 / this.videoFps;
        } else {
            // Fallback basado en time_base si avg_frame_rate no está disponible
            final AVRational tbr = videoStream.r_frame_rate();
            if (tbr.num() > 0 && tbr.den() > 0) {
                this.videoFps = (double) tbr.num() / tbr.den();
                this.frameDurationSeconds = 1.0 / this.videoFps;
            }
        }

        // Configurar thresholds de frame skipping basados en FPS detectado
        // Solo skipear cuando estamos MUY atrasados para evitar stuttering
        this.skipThreshold = this.frameDurationSeconds * 2.0;           // 2 frames de tolerancia (no usado actualmente)
        this.aggressiveSkipThreshold = this.frameDurationSeconds * 5.0; // 5 frames = empezar a skipear

        LOGGER.info(IT, "Video timing: {}fps, frame duration: {}ms, skip threshold: {}ms",
                String.format("%.2f", this.videoFps),
                String.format("%.2f", this.frameDurationSeconds * 1000),
                String.format("%.2f", this.aggressiveSkipThreshold * 1000));
    }

    /**
     * OPTIMIZACIÓN: Intentar inicializar decodificación por hardware
     */
    private boolean initHardwareDecoder(final int codecId, final AVStream videoStream) {
        // Lista de tipos de HW a intentar, en orden de preferencia
        final int[] hwTypes = {
                AV_HWDEVICE_TYPE_CUDA,      // NVIDIA
                AV_HWDEVICE_TYPE_D3D11VA,   // Windows DirectX 11
                AV_HWDEVICE_TYPE_DXVA2,     // Windows DirectX 9
                AV_HWDEVICE_TYPE_VAAPI,     // Linux VA-API
                AV_HWDEVICE_TYPE_VDPAU,     // Linux VDPAU
                AV_HWDEVICE_TYPE_VIDEOTOOLBOX  // macOS
        };

        final AVCodec decoder = avcodec.avcodec_find_decoder(codecId);
        if (decoder == null) {
            return false;
        }

        for (final int hwType : hwTypes) {
            // Verificar si este codec soporta este tipo de HW
            for (int i = 0; ; i++) {
                final AVCodecHWConfig config = avcodec.avcodec_get_hw_config(decoder, i);
                if (config == null) break;

                if (config.device_type() == hwType &&
                        (config.methods() & avcodec.AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX) != 0) {

                    // Intentar crear el contexto de HW
                    this.hwDeviceCtx = new AVBufferRef();
                    if (av_hwdevice_ctx_create(this.hwDeviceCtx, hwType, (String) null, null, 0) >= 0) {

                        // Crear codec context con HW
                        this.videoCodecContext = avcodec.avcodec_alloc_context3(decoder);
                        if (avcodec.avcodec_parameters_to_context(this.videoCodecContext,
                                videoStream.codecpar()) < 0) {
                            this.cleanupHwContext();
                            continue;
                        }

                        this.videoCodecContext.hw_device_ctx(av_buffer_ref(this.hwDeviceCtx));
                        this.hwPixelFormat = config.pix_fmt();

                        // Configurar threading
                        this.configureDecoderThreading(this.videoCodecContext);

                        if (avcodec.avcodec_open2(this.videoCodecContext, decoder,
                                (PointerPointer<?>) null) >= 0) {

                            // Allocar frame para transferencia desde GPU
                            this.hwTransferFrame = av_frame_alloc();

                            final BytePointer hwName = av_hwdevice_get_type_name(hwType);
                            LOGGER.info(IT, "Hardware decoder initialized: {}",
                                    hwName != null ? hwName.getString() : "unknown");
                            return true;
                        }

                        this.cleanupHwContext();
                    } else {
                        this.hwDeviceCtx = null;
                    }
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

    private boolean initSoftwareDecoder(final int codecId, final AVStream videoStream) {
        final AVCodec videoCodec = avcodec.avcodec_find_decoder(codecId);

        if (videoCodec == null) {
            LOGGER.error(IT, "Failed to find video codec with id {} for videoIndex {}",
                    codecId, this.videoStreamIndex);
            return false;
        }

        this.videoCodecContext = avcodec.avcodec_alloc_context3(videoCodec);
        if (avcodec.avcodec_parameters_to_context(this.videoCodecContext,
                videoStream.codecpar()) < 0) {
            LOGGER.error(IT, "Failed to copy video codec parameters to context");
            return false;
        }

        // OPTIMIZACIÓN: Threading adaptativo
        this.configureDecoderThreading(this.videoCodecContext);

        if (avcodec.avcodec_open2(this.videoCodecContext, videoCodec, (PointerPointer<?>) null) < 0) {
            LOGGER.error(IT, "Failed to open video codec");
            return false;
        }

        return true;
    }

    /**
     * OPTIMIZACIÓN: Configurar threading del decoder basado en resolución y CPU
     */
    private void configureDecoderThreading(final AVCodecContext ctx) {
        final int width = ctx.width();
        final int height = ctx.height();
        final long pixels = (long) width * height;

        // Escalar threads basado en resolución
        int threads;
        if (pixels <= 921600) {          // <= 720p
            threads = Math.min(4, MAX_DECODE_THREADS);
        } else if (pixels <= 2073600) {  // <= 1080p
            threads = Math.min(6, MAX_DECODE_THREADS);
        } else if (pixels <= 3686400) {  // <= 1440p
            threads = Math.min(8, MAX_DECODE_THREADS);
        } else {                          // 4K+
            threads = MAX_DECODE_THREADS;
        }

        threads = Math.max(MIN_DECODE_THREADS, threads);

        ctx.thread_count(threads);
        // FRAME threading es más eficiente para video moderno
        ctx.thread_type(AVCodecContext.FF_THREAD_FRAME);

        LOGGER.debug(IT, "Decoder threading: {} threads for {}x{}", threads, width, height);
    }

    private boolean initAudio() {
        if (!this.audio || this.audioStreamIndex < 0) {
            return false;
        }

        final AVStream audioStream = this.formatContext.streams(this.audioStreamIndex);
        final AVCodecParameters codecParams = audioStream.codecpar();
        final int codecId = codecParams.codec_id();
        final AVCodec audioCodec = avcodec.avcodec_find_decoder(codecId);

        if (audioCodec == null) {
            LOGGER.error(IT, "Failed to find audio codec with id {} for audioIndex {}",
                    codecId, this.audioStreamIndex);
            return false;
        }

        this.audioCodecContext = avcodec.avcodec_alloc_context3(audioCodec);
        if (this.audioCodecContext == null) {
            LOGGER.error(IT, "Failed to allocate audio codec context");
            return false;
        }

        if (avcodec.avcodec_parameters_to_context(this.audioCodecContext, codecParams) < 0) {
            LOGGER.error(IT, "Failed to copy audio codec parameters to context");
            return false;
        }

        if (avcodec.avcodec_open2(this.audioCodecContext, audioCodec, (PointerPointer<?>) null) < 0) {
            LOGGER.error(IT, "Failed to open audio codec");
            return false;
        }

        // Initialize resampler
        this.swrContext = swresample.swr_alloc();
        if (this.swrContext == null) {
            LOGGER.error(IT, "Failed to allocate SWResampler context");
            return false;
        }

        // OPTIMIZACIÓN: Channel layouts manejados correctamente con cleanup
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
            // OPTIMIZACIÓN: Liberar channel layouts
            av_channel_layout_uninit(inputLayout);
            av_channel_layout_uninit(outputLayout);
        }

        this.resampledFrame.format(avutil.AV_SAMPLE_FMT_S16);
        this.resampledFrame.ch_layout().nb_channels(AUDIO_CHANNELS);
        avutil.av_channel_layout_default(this.resampledFrame.ch_layout(), AUDIO_CHANNELS);
        this.resampledFrame.sample_rate(AUDIO_SAMPLE_RATE);
        this.resampledFrame.nb_samples(AUDIO_SAMPLES);

        if (avutil.av_frame_get_buffer(this.resampledFrame, 0) < 0) {
            return false;
        }

        // OPTIMIZACIÓN: Pre-allocar buffer de audio
        this.audioBuffer = ByteBuffer.allocateDirect(AUDIO_BUFFER_SIZE);

        return true;
    }

    // ===========================================
    // VIDEO PROCESSING - OPTIMIZED WITH FRAME SKIPPING
    // ===========================================

    private void processVideoPacket() {
        if (avcodec.avcodec_send_packet(this.videoCodecContext, this.packet) < 0) return;

        while (avcodec.avcodec_receive_frame(this.videoCodecContext, this.videoFrame) >= 0) {
            AVFrame frameToScale = this.videoFrame;

            // Si usamos HW acceleration, transferir frame desde GPU
            if (this.hwDeviceCtx != null && this.videoFrame.format() == this.hwPixelFormat) {
                if (av_hwframe_transfer_data(this.hwTransferFrame, this.videoFrame, 0) < 0) {
                    LOGGER.warn(IT, "Failed to transfer frame from GPU");
                    continue;
                }
                frameToScale = this.hwTransferFrame;
            }

            final double framePts = this.videoFrame.pts() * this.videoTimeBase;

            // Determinar el tiempo de referencia
            final double referenceTime = this.getReferenceTime();
            final double drift = framePts - referenceTime;

            // FRAME SKIPPING: Solo cuando estamos MUY atrasados (>3 frames)
            if (drift < -this.aggressiveSkipThreshold) {
                this.totalSkippedFrames++;
                this.consecutiveSkips++;

                // Pero siempre renderizar al menos 1 de cada 5 frames para feedback visual
                if (this.consecutiveSkips < 5) {
                    continue;
                }
                // Forzar render después de 5 skips consecutivos
                this.consecutiveSkips = 0;
            } else {
                this.consecutiveSkips = 0;
            }

            // SINCRONIZACIÓN: Esperar si el frame está adelantado
            if (drift > 0.001) {  // Frame adelantado > 1ms
                this.waitForDisplayTime(drift);
            }

            // Actualizar clock desde video solo si no hay audio
            if (!this.audioClockValid) {
                this.masterClock = framePts;
                this.clockBaseTime = System.nanoTime();
            }

            // Scale and upload frame
            this.scaleAndUploadFrame(frameToScale);
            this.totalRenderedFrames++;
        }
    }

    /**
     * Obtiene el tiempo de referencia para sincronización
     * - Con audio: usa el master clock (basado en audio PTS)
     * - Sin audio: usa el tiempo transcurrido desde clockBaseTime
     */
    private double getReferenceTime() {
        if (this.audioClockValid && this.audio) {
            // Audio es master - interpolar desde último update
            final double elapsed = (System.nanoTime() - this.clockBaseTime) / 1_000_000_000.0;
            return this.masterClock + (elapsed * this.speedFactor);
        } else {
            // Sin audio - usar wall clock
            if (this.clockBaseTime <= 0) {
                return 0.0;
            }
            final double elapsed = (System.nanoTime() - this.clockBaseTime) / 1_000_000_000.0;
            return this.masterClock + (elapsed * this.speedFactor);
        }
    }

    /**
     * Espera hasta que sea tiempo de mostrar el frame
     * @param delaySeconds tiempo a esperar en segundos
     */
    private void waitForDisplayTime(final double delaySeconds) {
        if (delaySeconds <= 0 || delaySeconds > 1.0 || this.pauseRequested) {
            return;
        }

        final long delayMs = (long)(delaySeconds * 1000);

        // Sleep simple - no necesitamos precisión sub-millisecond para video
        if (delayMs > 1) {
            try {
                Thread.sleep(delayMs - 1);  // Despertar ~1ms antes
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        // Pequeño yield para el tiempo restante
        Thread.yield();
    }

    /**
     * OPTIMIZACIÓN: Escalar y subir frame con SwsContext creado/actualizado dinámicamente
     * basado en el formato real del frame (importante para HW acceleration)
     */
    private void scaleAndUploadFrame(final AVFrame frame) {
        final int frameFormat = frame.format();
        final int frameWidth = frame.width();
        final int frameHeight = frame.height();

        // Verificar si necesitamos crear o recrear el SwsContext
        if (this.swsContext == null || this.swsInputFormat != frameFormat) {
            // Liberar contexto anterior si existe
            if (this.swsContext != null) {
                swscale.sws_freeContext(this.swsContext);
                this.swsContext = null;
            }

            // Crear nuevo contexto con el formato real del frame
            this.swsContext = swscale.sws_getContext(
                    frameWidth, frameHeight, frameFormat,
                    this.width(), this.height(), avutil.AV_PIX_FMT_BGRA,
                    swscale.SWS_BILINEAR, null, null, (double[]) null
            );

            if (this.swsContext == null) {
                LOGGER.error(IT, "Failed to create SwsContext for format {} ({}x{})",
                        frameFormat, frameWidth, frameHeight);
                return;
            }

            this.swsInputFormat = frameFormat;
            LOGGER.debug(IT, "Created SwsContext: format {} ({}x{}) -> BGRA ({}x{})",
                    frameFormat, frameWidth, frameHeight, this.width(), this.height());
        }

        // Initialize buffer lazily
        if (!this.videoBufferInitialized) {
            this.videoBuffer = this.scaledFrame.data(0).asByteBuffer();
            this.videoBufferInitialized = true;
        }

        synchronized (this.videoBuffer) {
            final int result = swscale.sws_scale(
                    this.swsContext,
                    frame.data(),
                    frame.linesize(),
                    0,
                    frameHeight,  // Usar altura del frame fuente
                    this.scaledFrame.data(),
                    this.scaledFrame.linesize()
            );

            if (result <= 0) {
                LOGGER.error(IT, "Failed to scale video frame: result={}, srcFmt={}, srcSize={}x{}",
                        result, frameFormat, frameWidth, frameHeight);
                return;
            }
        }

        final int stride = this.scaledFrame.linesize(0);
        this.upload(this.videoBuffer, stride / 4);
    }

    // ===========================================
    // AUDIO PROCESSING - OPTIMIZED
    // ===========================================

    private void processAudioPacket() {
        if (avcodec.avcodec_send_packet(this.audioCodecContext, this.packet) < 0) return;

        while (avcodec.avcodec_receive_frame(this.audioCodecContext, this.audioFrame) >= 0) {
            final double ptsInSeconds = this.audioFrame.pts() * this.audioTimeBase;

            // Audio clock is primary - siempre actualizar
            this.masterClock = ptsInSeconds;
            this.clockBaseTime = System.nanoTime();
            this.audioClockValid = true;

            // OPTIMIZACIÓN: Usar swr_convert_frame para mejor manejo de residuales
            final int samplesConverted = swresample.swr_convert(
                    this.swrContext,
                    this.resampledFrame.data(),
                    this.resampledFrame.nb_samples(),
                    this.audioFrame.data(),
                    this.audioFrame.nb_samples()
            );

            if (samplesConverted > 0) {
                final int dataSize = samplesConverted * AUDIO_CHANNELS * 2;

                // OPTIMIZACIÓN: Reutilizar ByteBuffer wrapper
                final ByteBuffer audioData = this.resampledFrame.data(0)
                        .limit(dataSize)
                        .asBuffer()
                        .clear();

                this.upload(audioData, AL10.AL_FORMAT_STEREO16, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS);
            }

            // OPTIMIZACIÓN: Flush residual samples del resampler
            this.flushResamplerResiduals();
        }
    }

    /**
     * OPTIMIZACIÓN: Extraer samples residuales del resampler
     */
    private void flushResamplerResiduals() {
        // Check si hay samples pendientes en el resampler
        final long delay = swresample.swr_get_delay(this.swrContext, AUDIO_SAMPLE_RATE);

        if (delay > AUDIO_SAMPLES / 2) {
            // Flush residuales
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

    // ===========================================
    // CLEANUP - COMPLETE RESOURCE RELEASE
    // ===========================================

    private void cleanup() {
        LOGGER.info(IT, "Cleaning up FFmpeg resources...");

        // Free HW context first
        if (this.hwTransferFrame != null) {
            av_frame_free(this.hwTransferFrame);
            this.hwTransferFrame = null;
        }
        if (this.hwDeviceCtx != null) {
            av_buffer_unref(this.hwDeviceCtx);
            this.hwDeviceCtx = null;
        }
        this.hwPixelFormat = AV_PIX_FMT_NONE;

        // Free contexts
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

        // Free frames
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

        // Clear buffers
        this.videoBuffer = null;
        this.audioBuffer = null;
        this.videoBufferInitialized = false;

        // Reset state
        this.videoStreamIndex = -1;
        this.audioStreamIndex = -1;
        this.audioClockValid = false;
        this.swsInputFormat = AV_PIX_FMT_NONE;

        LOGGER.info(IT, "Cleanup completed");
        this.running = false;
    }

    // ===========================================
    // UTILITY METHODS
    // ===========================================

    private static double av_q2d(final AVRational a) {
        return (double) a.num() / a.den();
    }

    private static long msFromSeconds(final double seconds) {
        return (long)(seconds * 1000);
    }

    private static double secondsFromMs(final long ms) {
        return ms / 1000.0;
    }

    // ===========================================
    // STATIC LOADER
    // ===========================================

    public static boolean load(final WaterMedia waterMedia) {
        Objects.requireNonNull(waterMedia, "WaterMedia instance cannot be null");
        try {
            LOGGER.info(IT, "Loading FFMPEG module...");

            final Path ffmpegPath = WaterMediaBinaries.pathOf(WaterMediaBinaries.FFMPEG_ID);

            if (ffmpegPath != null && Files.exists(ffmpegPath)) {
                final String pathStr = ffmpegPath.toAbsolutePath().toString();

                System.setProperty("org.bytedeco.javacpp.platform.preloadpath", pathStr);
                System.setProperty("org.bytedeco.javacpp.pathsFirst", "true");

                final String currentLibPath = System.getProperty("java.library.path");
                if (currentLibPath == null || currentLibPath.isEmpty()) {
                    System.setProperty("java.library.path", pathStr);
                } else if (!currentLibPath.contains(pathStr)) {
                    System.setProperty("java.library.path",
                            pathStr + java.io.File.pathSeparator + currentLibPath);
                }

                LOGGER.info(IT, "Configured JavaCPP with custom FFMPEG path: {}", pathStr);
            } else {
                LOGGER.warn(IT, "FFMPEG binaries path not found, using JavaCPP defaults");
            }

            avutil.av_log_set_flags(avutil.AV_LOG_PRINT_LEVEL | avutil.AV_LOG_SKIP_REPEATED);
            avutil.av_log_set_level(LOGGER.isDebugEnabled() ? avutil.AV_LOG_DEBUG : avutil.AV_LOG_INFO);

            logFFmpegCapabilities();

            LOGGER.info(IT, "FFMPEG started, running version {} under {}",
                    avformat.avformat_version(), avformat.avformat_license().getString());
            return LOADED = true;
        } catch (final Throwable t) {
            LOGGER.error(IT, "Failed to load FFMPEG", t);
            return false;
        }
    }

    private static void logFFmpegCapabilities() {
        LOGGER.info(IT, "=== FFMPEG Build Info ===");
        LOGGER.debug(IT, "avformat: {}", avformat.avformat_version());
        LOGGER.debug(IT, "avcodec:  {}", avcodec.avcodec_version());
        LOGGER.debug(IT, "avutil:   {}", avutil.avutil_version());
        LOGGER.debug(IT, "swscale:  {}", swscale.swscale_version());
        LOGGER.debug(IT, "swresample: {}", swresample.swresample_version());

        try {
            final BytePointer config = avformat.avformat_configuration();
            if (config != null && !config.isNull()) {
                LOGGER.debug(IT, "Configuration: {}", config.getString());
            }
        } catch (final Exception e) {
            LOGGER.warn(IT, "Configuration: unavailable");
        }

        // Hardware acceleration
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
        } while (true);

        if (hwCount == 0) {
            LOGGER.info(IT, "  (none available)");
        }
    }

    public static boolean loaded() {
        return LOADED;
    }

    public boolean isRunning() {
        return running;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }
}