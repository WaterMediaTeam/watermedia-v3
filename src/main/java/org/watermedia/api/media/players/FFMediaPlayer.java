package org.watermedia.api.media.players;

import com.sun.jna.Platform;
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
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.lwjgl.openal.AL10;
import org.lwjgl.opengl.GL12;
import org.watermedia.WaterMedia;
import org.watermedia.api.media.platforms.ALEngine;
import org.watermedia.api.media.platforms.GLEngine;
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.tools.ThreadTool;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR;
import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_INFO;
import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_WARNING;
import static org.watermedia.WaterMedia.LOGGER;

public final class FFMediaPlayer extends MediaPlayer {
    private static final Marker IT = MarkerManager.getMarker(FFMediaPlayer.class.getSimpleName());
    private static final ThreadFactory DEFAULT_THREAD_FACTORY = ThreadTool.createFactory("FFThread", Thread.NORM_PRIORITY);
    private static boolean LOADED;

    // Constants
    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final int AUDIO_CHANNELS = 2;
    private static final int AUDIO_SAMPLES = 1024;

    // FFmpeg components
    private AVFormatContext formatContext;
    private AVCodecContext videoCodecContext;
    private AVCodecContext audioCodecContext;
    private SwsContext swsContext;
    private SwrContext swrContext;

    // Stream indices
    private int videoStreamIndex = -1;
    private int audioStreamIndex = -1;

    // Threading
    private Thread playerThread;
    private volatile boolean running = false;

    // State management - all volatile for thread visibility
    private volatile Status currentStatus = Status.WAITING;
    private volatile boolean pauseRequested = false;
    private volatile boolean stopRequested = false;
    private volatile long seekTarget = -1;
    private volatile float speedFactor = 1.0f;

    // Clock system - single source of truth for playback position
    private volatile double masterClock = 0.0;
    private volatile long clockBaseTime = -1;
    private volatile boolean clockPaused = false;
    private volatile long pausedTime = 0;

    // Memory management - reusable buffers
    private AVPacket packet;
    private AVFrame videoFrame;
    private AVFrame audioFrame;
    private AVFrame scaledFrame;
    private AVFrame resampledFrame;
    private ByteBuffer videoBuffer;

    // Time bases
    private double videoTimeBase;
    private double audioTimeBase;

    public FFMediaPlayer(final URI mrl, final Thread renderThread, final Executor renderThreadEx, GLEngine glEngine, ALEngine alEngine, final boolean video, final boolean audio) {
        super(mrl, renderThread, renderThreadEx, glEngine, alEngine, video, audio);
    }

    // ===========================================
    // LIFECYCLE METHODS
    // ===========================================
    @Override
    public void start() {
        if (this.running) {
            this.stop();
        }

        this.playerThread = DEFAULT_THREAD_FACTORY.newThread(this::playerLoop);
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
            this.pausedTime = this.time(); // Guardar el tiempo actual
            return true;
        } else if (!paused && this.pauseRequested) {
            if (!this.pauseRequested) return false;

            this.pauseRequested = false;
            this.clockPaused = false;
            // Restaurar el clock desde el tiempo pausado
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
        this.stopRequested = true;
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

        long frameTimeMs = 33; // Default ~30fps
        if (this.videoStreamIndex >= 0 && this.formatContext != null) {
            final AVStream videoStream = this.formatContext.streams(this.videoStreamIndex);
            final AVRational frameRate = videoStream.avg_frame_rate();
            if (frameRate.num() > 0 && frameRate.den() > 0) {
                frameTimeMs = (long)(1000.0 * frameRate.den() / frameRate.num());
            }
        }

        return this.seek(Math.max(0, this.time() - frameTimeMs));
    }

    @Override
    public boolean nextFrame() {
        if (!this.canSeek()) return false;

        long frameTimeMs = 33; // Default ~30fps
        if (this.videoStreamIndex >= 0 && this.formatContext != null) {
            final AVStream videoStream = this.formatContext.streams(this.videoStreamIndex);
            final AVRational frameRate = videoStream.avg_frame_rate();
            if (frameRate.num() > 0 && frameRate.den() > 0) {
                frameTimeMs = (long)(1000.0 * frameRate.den() / frameRate.num());
            }
        }

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
        return this.formatContext != null && this.formatContext.duration() == avutil.AV_NOPTS_VALUE;
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
        return (this.formatContext == null || this.liveSource()) ? -1 : this.formatContext.duration() / 1000;
    }

    @Override
    public long time() {
        if (this.clockPaused) {
            return msFromSeconds(this.masterClock);
        }

        if (this.clockBaseTime <= 0) {
            return msFromSeconds(this.masterClock);
        }

        final double elapsed = (System.nanoTime() - this.clockBaseTime) / 1_000_000_000.0;
        return msFromSeconds(this.masterClock + (elapsed * this.speedFactor));
    }

    // ===========================================
    // MAIN PLAYER LOOP
    // ===========================================

    @Override
    protected void updateMedia() {
        this.start(); // I BASICALLY DON'T NEED TO DO ANYTHING HERE LOL
    }

    private void playerLoop() {
        try {
            // Wait until previous loop fully stopped
            while (this.running) {
                Thread.sleep(100);
            }
            this.running = true;

            // Set initial loading state
            this.currentStatus = Status.LOADING;

            // Initialize source fetch
            this.openSourcesSync();

            // Initialize FFMPEG components
            if (!this.init()) {
                this.currentStatus = Status.ERROR;
                return;
            }

            // Set initial playing or paused state
            if (this.pauseRequested) {
                this.currentStatus = Status.PAUSED;
            } else {
                this.currentStatus = Status.PLAYING;
                this.clockBaseTime = System.nanoTime();
            }

            // Main playback loop
            while (this.running) {
                if (this.stopRequested) {
                    LOGGER.info(IT, "Stop requested, exiting playback loop");
                    break;
                }

                // Handle seek requests
                if (this.seekTarget >= 0) {
                    final long targetMs = this.seekTarget;
                    this.seekTarget = -1;

                    if (this.formatContext != null && this.canSeek()) {
                        final long currentMs = msFromSeconds(this.masterClock);
                        LOGGER.info(IT, "Seeking to {}ms - current time {}ms", targetMs, currentMs);

                        // Flush codecs
                        if (this.videoCodecContext != null) {
                            avcodec.avcodec_flush_buffers(this.videoCodecContext);
                        }
                        if (this.audioCodecContext != null) {
                            avcodec.avcodec_flush_buffers(this.audioCodecContext);
                        }

                        // Perform the seek with appropriate flag
                        final long ffmpegTimestamp = (targetMs / 1000L) * avutil.AV_TIME_BASE;

                        // Use BACKWARD flag for seeking backwards, no flag for forward
                        int seekFlags = 0;
                        if (targetMs < currentMs) {
                            seekFlags = avformat.AVSEEK_FLAG_BACKWARD;
                        }

                        final int result = avformat.av_seek_frame(this.formatContext, -1, ffmpegTimestamp, seekFlags);

                        if (result >= 0) {// Reset clock to seek position
                            this.masterClock = secondsFromMs(targetMs);
                            this.clockBaseTime = System.nanoTime();

                            LOGGER.info(IT, "Seek completed to {}ms", targetMs);
                            continue;
                        } else {
                            LOGGER.error(IT, "av_seek_frame failed with error: {}", result);
                        }

                    } else {
                        LOGGER.warn(IT, "Cannot seek - invalid format context or live stream");
                    }
                }

                // Update status based on pause state
                if (this.pauseRequested && this.currentStatus != Status.PAUSED) {
                    this.currentStatus = Status.PAUSED;
                } else if (!this.pauseRequested && this.currentStatus == Status.PAUSED) {
                    this.currentStatus = Status.PLAYING;
                }

                // Handle pause state
                if (this.pauseRequested) {
                    Thread.sleep(10);
                    continue;
                }

                // Read and process frame
                final int result = avformat.av_read_frame(this.formatContext, this.packet);
                if (result < 0) {
                    // End of stream
                    try {
                        LOGGER.info(IT, "End of stream reached");

                        // Wait a bit for final frames to play
                        Thread.sleep(500);

                        if (this.repeat()) { // TODO: WHAT SHOULD BE THE BEHAVIOR OF REPEAT?, REPEAT THE WHOLE PLAYLIST? OR THE CURRENT MEDIA?
                            LOGGER.info(IT, "Repeating playback");
                            this.seekTarget = 0;
                            // Status stays PLAYING for repeat
                        } else {
                            LOGGER.info(IT, "Playback ended naturally");
                            this.currentStatus = Status.ENDED;
                        }
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (this.currentStatus != Status.PLAYING) {
                        // Either ended or repeating
                        break;
                    }
                    continue;
                }

                // Process packet immediately based on stream type
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
            if (this.stopRequested) {
                this.currentStatus = Status.STOPPED;
            } else if (this.currentStatus == Status.PLAYING) {
                // Natural end without repeat
                this.currentStatus = Status.ENDED;
            }
        } catch (final Exception e) {
            LOGGER.error(IT, "Error in player loop", e);
            this.currentStatus = Status.ERROR;
        } finally {
            this.cleanup();
        }
    }

    private boolean init() {
        try {
            // Allocate structures
            this.packet = avcodec.av_packet_alloc();
            this.videoFrame = avutil.av_frame_alloc();
            this.audioFrame = avutil.av_frame_alloc();
            this.scaledFrame = avutil.av_frame_alloc();
            this.resampledFrame = avutil.av_frame_alloc();

            if (this.packet == null || this.videoFrame == null || this.audioFrame == null || this.scaledFrame == null || this.resampledFrame == null) {
                return false;
            }

            // Open format context
            this.formatContext = avformat.avformat_alloc_context();
            final AVDictionary options = new AVDictionary(); {
                if (Platform.isMac()) {
                    // Intentar usar VideoToolbox (aceleración de hardware de macOS)
                    av_dict_set(options, "hwaccel", "videotoolbox", 0);

                    // O intentar sin aceleración pero con mejor compatibilidad
                    av_dict_set(options, "threads", "auto", 0);
                    av_dict_set(options, "refcounted_frames", "1", 0);
                }

//                // Buffer size en bytes (ejemplo: 8MB)
//                av_dict_set(options, "buffer_size", "33554432", 0);  // 32MB
//                av_dict_set(options, "rtbufsize", "15000000", 0);  // 15MB buffer RTSP
//
//                // Flags importantes para optimizar el buffering
//                av_dict_set(options, "fflags", "nobuffer+fastseek+flush_packets", 0);
//
//                // Para HTTP - mantener conexión viva y múltiples requests
//                av_dict_set(options, "http_persistent", "1", 0);
//                av_dict_set(options, "multiple_requests", "1", 0);
//
//                // Control de latencia - muy importante para streaming
//                av_dict_set(options, "analyzeduration", "10000000", 0);
//                av_dict_set(options, "max_analyze_duration", "5000000", 0);
//                av_dict_set(options, "probesize", "10000000", 0);
//                av_dict_set(options, "fpsprobesize", "100", 0);
//
//                // Para HLS/DASH si lo usas
//                av_dict_set(options, "live_start_index", "-1", 0);  // Empezar 3 segmentos antes del final
//
//                // Para protocolos HTTP/HTTPS
//                av_dict_set(options, "reconnect", "1", 0);
//                av_dict_set(options, "reconnect_streamed", "1", 0);
//                av_dict_set(options, "reconnect_delay_max", "5", 0);

                // Timeout para operaciones de red (en microsegundos)
                av_dict_set(options, "timeout", "10000000", 0);

                // Para RTSP/RTMP
                av_dict_set(options, "rtsp_transport", "tcp", 0);
                av_dict_set(options, "max_delay", "5000000", 0);
            }

            if (avformat.avformat_open_input(this.formatContext, this.sources[this.sourceIndex].getURIString(this.selectedQuality), null, options) < 0) {
                LOGGER.error("Failed to open input: {}", this.sources[this.sourceIndex].getURIString(this.selectedQuality));
                return false;
            }

            if (avformat.avformat_find_stream_info(this.formatContext, (PointerPointer<?>) null) < 0) {
                LOGGER.error("Failed to find stream info");
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

            final boolean videoInit = this.init$video();
            final boolean audioInit = this.init$audio();

            if (!videoInit && !audioInit) {
                LOGGER.error("No valid audio or video streams found");
                return false;
            }
            LOGGER.info("FFmpeg initialized successfully - videoStreamIndex: {}, audioStreamIndex: {} - videoInit: {}, audioInit {}", this.videoStreamIndex, this.audioStreamIndex, videoInit, audioInit);

            return true;
        } catch (final Exception e) {
            LOGGER.error(IT, "Failed to initialize FFmpeg", e);
            return false;
        }
    }

    private boolean init$video() {
        // Initialize video decoder
        if (this.video && this.videoStreamIndex >= 0) {
            final int codecId =  this.formatContext.streams(this.videoStreamIndex).codecpar().codec_id();
            final AVCodec videoCodec = avcodec.avcodec_find_decoder(codecId);

            if (videoCodec == null) {
                LOGGER.error("Failed to find video codec with id {} for videoIndex {}", codecId, this.videoStreamIndex);
                return false;
            }

            this.videoCodecContext = avcodec.avcodec_alloc_context3(videoCodec);
            if (avcodec.avcodec_parameters_to_context(this.videoCodecContext, this.formatContext.streams(this.videoStreamIndex).codecpar()) < 0) {
                LOGGER.error("Failed to copy video codec parameters to context");
                return false;
            }

            if (avcodec.avcodec_open2(this.videoCodecContext, videoCodec, (PointerPointer<?>) null) < 0) {
                LOGGER.error("Failed to open video codec");
                return false;
            }

            this.setVideoFormat(GL12.GL_BGRA, this.videoCodecContext.width(), this.videoCodecContext.height());

            this.swsContext = swscale.sws_getContext(
                    this.width(), this.height(), this.videoCodecContext.pix_fmt(),
                    this.width(), this.height(), avutil.AV_PIX_FMT_BGRA,
                    swscale.SWS_BILINEAR, null, null, (double[]) null);

            if (this.swsContext == null) {
                LOGGER.error("Failed to initialize SWScale context");
                return false;
            }

            this.scaledFrame.format(avutil.AV_PIX_FMT_BGRA);
            this.scaledFrame.width(this.width());
            this.scaledFrame.height(this.height());

            if (avutil.av_frame_get_buffer(this.scaledFrame, 32) < 0) {
                LOGGER.error("Failed to allocate scaled video frame buffer");
                return false;
            }
        } else {
            LOGGER.warn("No video stream found or video disabled");
        }
        return true;
    }

    private boolean init$audio() {
        // Initialize audio decoder
        if (this.audio && this.audioStreamIndex >= 0) {
            final AVStream audioStream = this.formatContext.streams(this.audioStreamIndex);
            final AVCodecParameters codecParams = audioStream.codecpar();
            final int codecId = codecParams.codec_id();
            final AVCodec audioCodec = avcodec.avcodec_find_decoder(codecId);

            if (audioCodec == null) {
                LOGGER.error("Failed to find audio codec with id {} for audioIndex {}", codecId, this.audioStreamIndex);
                return false;
            }

            this.audioCodecContext = avcodec.avcodec_alloc_context3(audioCodec);
            if (this.audioCodecContext == null) {
                LOGGER.error("Failed to allocate audio codec context");
                return false;
            }

            if (avcodec.avcodec_parameters_to_context(this.audioCodecContext, codecParams) < 0) {
                LOGGER.error("Failed to copy audio codec parameters to context");
                return false;
            }

            if (avcodec.avcodec_open2(this.audioCodecContext, audioCodec, (PointerPointer<?>) null) < 0) {
                LOGGER.error("Failed to open audio codec");
                return false;
            }

            // Initialize resampler
            this.swrContext = swresample.swr_alloc();
            if (this.swrContext == null) {
                LOGGER.error("Failed to allocate SWResampler context");
                return false;
            }

            final AVChannelLayout inputLayout = new AVChannelLayout();
            final AVChannelLayout outputLayout = new AVChannelLayout();

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
                LOGGER.error("Failed to initialize SWResampler context");
                return false;
            }

            this.resampledFrame.format(avutil.AV_SAMPLE_FMT_S16);
            this.resampledFrame.ch_layout().nb_channels(AUDIO_CHANNELS);
            avutil.av_channel_layout_default(this.resampledFrame.ch_layout(), AUDIO_CHANNELS);
            this.resampledFrame.sample_rate(AUDIO_SAMPLE_RATE);
            this.resampledFrame.nb_samples(AUDIO_SAMPLES);

            return avutil.av_frame_get_buffer(this.resampledFrame, 0) >= 0;
        }
        return false;
    }

    private void processVideoPacket() {
        if (avcodec.avcodec_send_packet(this.videoCodecContext, this.packet) < 0) return;

        while (avcodec.avcodec_receive_frame(this.videoCodecContext, this.videoFrame) >= 0) {
            final double ptsInSeconds = this.videoFrame.pts() * this.videoTimeBase;

            // Update master clock
            this.updateClock(ptsInSeconds);

            // Wait for proper display time
            if (!this.audio && !this.pauseRequested) {
                final double currentTime = (System.nanoTime() - this.clockBaseTime) / 1_000_000_000.0 * this.speedFactor;
                final double delay = ptsInSeconds - currentTime;

                // Only sleep if we're ahead and delay is reasonable
                if (delay > 0 && delay < 0.5) {
                    try {
                        Thread.sleep((long)(delay * 1000));
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }

            // Convert ANY format to BGRA
            if (this.videoBuffer == null) {
                this.videoBuffer = this.scaledFrame.data(0).asByteBuffer();
            }
            synchronized (this.videoBuffer) {
                final int result = swscale.sws_scale(this.swsContext, this.videoFrame.data(), this.videoFrame.linesize(),
                        0, this.height(), this.scaledFrame.data(), this.scaledFrame.linesize());
                if (result <= 0) {
                    LOGGER.error("Failed to scale video frame, scale result: {}", result);
                }
            }

            final int stride = this.scaledFrame.linesize(0);
            this.upload(this.videoBuffer, stride / 4);
        }
    }

    private void processAudioPacket() {
        if (avcodec.avcodec_send_packet(this.audioCodecContext, this.packet) < 0) return;

        while (avcodec.avcodec_receive_frame(this.audioCodecContext, this.audioFrame) >= 0) {
            final double ptsInSeconds = this.audioFrame.pts() * this.audioTimeBase;

            // Update master clock from audio (more reliable)
            this.updateClock(ptsInSeconds);

            final int samplesConverted = swresample.swr_convert(this.swrContext, this.resampledFrame.data(),
                    this.resampledFrame.nb_samples(), this.audioFrame.data(), this.audioFrame.nb_samples());

            if (samplesConverted > 0) {
                final int dataSize = samplesConverted * AUDIO_CHANNELS * 2;
                // TODO: add synchronization with the buffer pointer
                this.upload(this.resampledFrame.data(0).limit(dataSize).asBuffer().clear(),
                        AL10.AL_FORMAT_STEREO16, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS);
            }
        }
    }

    private void updateClock(final double ptsInSeconds) {
        // Always prefer audio clock when available
        if (this.audio && this.audioStreamIndex >= 0) {
            this.masterClock = ptsInSeconds;
            if (!this.clockPaused && this.clockBaseTime <= 0) {
                this.clockBaseTime = System.nanoTime() - (long)(ptsInSeconds * 1_000_000_000L);
            }
        } else if (!this.audio && this.video) {
            this.masterClock = ptsInSeconds;
            if (!this.clockPaused && this.clockBaseTime <= 0) {
                this.clockBaseTime = System.nanoTime() - (long)(ptsInSeconds * 1_000_000_000L);
            }
        }
    }

    private void cleanup() {
        // Free FFmpeg resources
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

        this.videoBuffer = null;

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

    public static boolean load(final WaterMedia waterMedia) {
        Objects.requireNonNull(waterMedia, "WaterMedia instance cannot be null");
        try {
            LOGGER.info(IT, "Loading FFMPEG module...");

            // Configure JavaCPP to use our custom FFmpeg binaries
            final Path ffmpegPath = WaterMediaBinaries.getBinaryPath(WaterMediaBinaries.FFMPEG_ID);

            if (ffmpegPath != null && Files.exists(ffmpegPath)) {
                // Set JavaCPP properties for custom binary path
                final String pathStr = ffmpegPath.toAbsolutePath().toString();

                // Set the library path for JavaCPP
                System.setProperty("org.bytedeco.javacpp.platform.preloadpath", pathStr);
                System.setProperty("org.bytedeco.javacpp.pathsFirst", "true");

                // Add to java.library.path
                final String currentLibPath = System.getProperty("java.library.path");
                if (currentLibPath == null || currentLibPath.isEmpty()) {
                    System.setProperty("java.library.path", pathStr);
                } else if (!currentLibPath.contains(pathStr)) {
                    System.setProperty("java.library.path", pathStr + java.io.File.pathSeparator + currentLibPath);
                }

                LOGGER.info(IT, "Configured JavaCPP with custom FFMPEG path: {}", pathStr);
            } else {
                LOGGER.warn(IT, "FFMPEG binaries path not found, using JavaCPP defaults");
            }

//            avutil.av_log_set_callback(LogCallback.INSTANCE);
            avutil.av_log_set_flags(avutil.AV_LOG_PRINT_LEVEL | avutil.AV_LOG_SKIP_REPEATED);
//            avutil.av_log_set_level(LOGGER.isDebugEnabled() ? avutil.AV_LOG_DEBUG : avutil.AV_LOG_INFO);
            avutil.av_log_set_level(avutil.AV_LOG_DEBUG);

            logFFmpegCapabilities();

            LOGGER.info(IT, "FFMPEG started, running version {} under {}", avformat.avformat_version(), avformat.avformat_license().getString());
            return LOADED = true;
        } catch (final Throwable t) {
            LOGGER.error(IT, "Failed to load FFMPEG", t);
            return false;
        }
    }

    private static void logFFmpegCapabilities() {
        LOGGER.info(IT, "=== FFmpeg Build Info ===");
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
            LOGGER.info(IT, "Configuration: unavailable");
        }

        // Video Decoders
        LOGGER.info(IT, "=== Video Decoders ===");
        // H.26x family
        checkDecoder("h261", "H.261");
        checkDecoder("h263", "H.263");
        checkDecoder("h263i", "H.263i / Intel H.263");
        checkDecoder("h263p", "H.263+");
        checkDecoder("h264", "H.264 / AVC / MPEG-4 Part 10");
        checkDecoder("hevc", "H.265 / HEVC");
        checkDecoder("vvc", "H.266 / VVC");
        // MPEG family
        checkDecoder("mpeg1video", "MPEG-1 Video");
        checkDecoder("mpeg2video", "MPEG-2 Video");
        checkDecoder("mpeg4", "MPEG-4 Part 2");
        checkDecoder("msmpeg4v1", "MS MPEG-4 v1");
        checkDecoder("msmpeg4v2", "MS MPEG-4 v2");
        checkDecoder("msmpeg4v3", "MS MPEG-4 v3 / DivX 3");
        // VP/AV family
        checkDecoder("vp3", "VP3");
        checkDecoder("vp4", "VP4");
        checkDecoder("vp5", "VP5");
        checkDecoder("vp6", "VP6");
        checkDecoder("vp6a", "VP6 with Alpha");
        checkDecoder("vp6f", "VP6 Flash");
        checkDecoder("vp7", "VP7");
        checkDecoder("vp8", "VP8");
        checkDecoder("vp9", "VP9");
        checkDecoder("av1", "AV1 / AOMedia Video 1");
        // Image formats
        checkDecoder("png", "PNG");
        checkDecoder("apng", "Animated PNG");
        checkDecoder("gif", "GIF");
        checkDecoder("webp", "WebP");
        checkDecoder("mjpeg", "Motion JPEG");
        checkDecoder("mjpegb", "Motion JPEG B");
        checkDecoder("jpeg2000", "JPEG 2000");
        checkDecoder("jpegls", "JPEG-LS");
        checkDecoder("bmp", "BMP");
        checkDecoder("tiff", "TIFF");
        checkDecoder("targa", "Targa / TGA");
        checkDecoder("ppm", "PPM");
        checkDecoder("pgm", "PGM");
        checkDecoder("pbm", "PBM");
        checkDecoder("pam", "PAM");
        checkDecoder("exr", "OpenEXR");
        checkDecoder("hdr", "HDR / Radiance RGBE");
        checkDecoder("qoi", "QOI / Quite OK Image");
        // Professional/Broadcast codecs
        checkDecoder("theora", "Theora");
        checkDecoder("dirac", "Dirac / VC-2");
        checkDecoder("prores", "Apple ProRes");
        checkDecoder("prores_aw", "Apple ProRes (iCodec)");
        checkDecoder("prores_ks", "Apple ProRes (Kostya)");
        checkDecoder("prores_lgpl", "Apple ProRes (LGPL)");
        checkDecoder("dnxhd", "Avid DNxHD / DNxHR");
        checkDecoder("cfhd", "GoPro CineForm HD");
        checkDecoder("dvvideo", "DV Video");
        // Legacy/Other video codecs
        checkDecoder("cinepak", "Cinepak");
        checkDecoder("indeo2", "Intel Indeo 2");
        checkDecoder("indeo3", "Intel Indeo 3");
        checkDecoder("indeo4", "Intel Indeo 4");
        checkDecoder("indeo5", "Intel Indeo 5");
        checkDecoder("huffyuv", "HuffYUV");
        checkDecoder("ffvhuff", "HuffYUV FFmpeg variant");
        checkDecoder("ffv1", "FFV1 Lossless");
        checkDecoder("flashsv", "Flash Screen Video v1");
        checkDecoder("flashsv2", "Flash Screen Video v2");
        checkDecoder("flv", "FLV / Sorenson Spark");
        checkDecoder("rv10", "RealVideo 1.0");
        checkDecoder("rv20", "RealVideo 2.0");
        checkDecoder("rv30", "RealVideo 3.0");
        checkDecoder("rv40", "RealVideo 4.0");
        checkDecoder("svq1", "Sorenson Vector Quantizer 1");
        checkDecoder("svq3", "Sorenson Vector Quantizer 3");
        checkDecoder("wmv1", "Windows Media Video 7");
        checkDecoder("wmv2", "Windows Media Video 8");
        checkDecoder("wmv3", "Windows Media Video 9");
        checkDecoder("vc1", "VC-1 / WMV9 Advanced Profile");
        checkDecoder("utvideo", "Ut Video");
        checkDecoder("rawvideo", "Raw Video");
        // Screen capture codecs
        checkDecoder("mss1", "MS Screen 1");
        checkDecoder("mss2", "MS Screen 2");
        checkDecoder("tscc", "TechSmith Screen Capture");
        checkDecoder("tscc2", "TechSmith Screen Capture 2");
        checkDecoder("cscd", "CamStudio");
        checkDecoder("fraps", "Fraps");
        checkDecoder("zmbv", "Zip Motion Blocks Video");
        checkDecoder("g2m", "GoToMeeting");
        // Lossless codecs
        checkDecoder("lagarith", "Lagarith Lossless");
        checkDecoder("magicyuv", "MagicYUV Lossless");
        checkDecoder("cllc", "Canopus Lossless");
        checkDecoder("snow", "Snow Wavelet");
        // Animation/Game codecs
        checkDecoder("anm", "Deluxe Paint Animation");
        checkDecoder("binkvideo", "Bink Video");
        checkDecoder("smackvideo", "Smacker Video");
        checkDecoder("roqvideo", "id RoQ Video");
        checkDecoder("idcin", "id CIN Video");
        checkDecoder("interplayvideo", "Interplay MVE Video");
        checkDecoder("vmd", "Sierra VMD Video");
        checkDecoder("flic", "Autodesk FLIC");
        // Hardware accelerated (if available)
        checkDecoder("h264_qsv", "H.264 Intel QuickSync");
        checkDecoder("h264_cuvid", "H.264 NVIDIA CUVID");
        checkDecoder("hevc_qsv", "HEVC Intel QuickSync");
        checkDecoder("hevc_cuvid", "HEVC NVIDIA CUVID");
        checkDecoder("vp9_qsv", "VP9 Intel QuickSync");
        checkDecoder("vp9_cuvid", "VP9 NVIDIA CUVID");
        checkDecoder("av1_qsv", "AV1 Intel QuickSync");
        checkDecoder("av1_cuvid", "AV1 NVIDIA CUVID");

        // Audio Decoders
        LOGGER.info(IT, "=== Audio Decoders ===");
        // AAC family
        checkDecoder("aac", "AAC (Advanced Audio Coding)");
        checkDecoder("aac_fixed", "AAC Fixed-point");
        checkDecoder("aac_latm", "AAC LATM");
        // MP3/MPEG Audio
        checkDecoder("mp1", "MP1 / MPEG Audio Layer 1");
        checkDecoder("mp1float", "MP1 Float");
        checkDecoder("mp2", "MP2 / MPEG Audio Layer 2");
        checkDecoder("mp2float", "MP2 Float");
        checkDecoder("mp3", "MP3 / MPEG Audio Layer 3");
        checkDecoder("mp3float", "MP3 Float");
        checkDecoder("mp3adu", "MP3 ADU");
        checkDecoder("mp3adufloat", "MP3 ADU Float");
        checkDecoder("mp3on4", "MP3 on MP4");
        checkDecoder("mp3on4float", "MP3 on MP4 Float");
        // Modern lossy
        checkDecoder("vorbis", "Vorbis");
        checkDecoder("opus", "Opus");
        // Dolby/DTS
        checkDecoder("ac3", "AC-3 / Dolby Digital");
        checkDecoder("ac3_fixed", "AC-3 Fixed-point");
        checkDecoder("eac3", "E-AC-3 / Dolby Digital Plus");
        checkDecoder("truehd", "Dolby TrueHD");
        checkDecoder("mlp", "MLP / Dolby Lossless");
        checkDecoder("dolby_e", "Dolby E");
        checkDecoder("dts", "DTS");
        checkDecoder("dca", "DTS Coherent Acoustics");
        // Lossless audio
        checkDecoder("flac", "FLAC");
        checkDecoder("alac", "Apple Lossless / ALAC");
        checkDecoder("ape", "Monkey's Audio / APE");
        checkDecoder("wavpack", "WavPack");
        checkDecoder("tta", "True Audio / TTA");
        checkDecoder("tak", "TAK");
        checkDecoder("shorten", "Shorten");
        checkDecoder("als", "MPEG-4 ALS");
        checkDecoder("wmalossless", "Windows Media Audio Lossless");
        // Windows Media Audio
        checkDecoder("wmav1", "Windows Media Audio 1");
        checkDecoder("wmav2", "Windows Media Audio 2");
        checkDecoder("wmapro", "Windows Media Audio Pro");
        checkDecoder("wmavoice", "Windows Media Audio Voice");
        // RealAudio
        checkDecoder("ra_144", "RealAudio 1.0 / 14.4k");
        checkDecoder("ra_288", "RealAudio 2.0 / 28.8k");
        checkDecoder("ralf", "RealAudio Lossless");
        checkDecoder("cook", "RealAudio Cook / G2");
        // Sony ATRAC
        checkDecoder("atrac1", "Sony ATRAC1");
        checkDecoder("atrac3", "Sony ATRAC3");
        checkDecoder("atrac3p", "Sony ATRAC3+");
        checkDecoder("atrac3al", "Sony ATRAC3 AL");
        checkDecoder("atrac3pal", "Sony ATRAC3+ AL");
        checkDecoder("atrac9", "Sony ATRAC9");
        // Speech codecs
        checkDecoder("speex", "Speex");
        checkDecoder("gsm", "GSM");
        checkDecoder("gsm_ms", "GSM Microsoft");
        checkDecoder("amrnb", "AMR-NB");
        checkDecoder("amrwb", "AMR-WB");
        checkDecoder("libilbc", "iLBC");
        checkDecoder("evrc", "EVRC");
        checkDecoder("qcelp", "QCELP / PureVoice");
        checkDecoder("g723_1", "G.723.1");
        checkDecoder("g729", "G.729");
        checkDecoder("truespeech", "TrueSpeech");
        checkDecoder("dss_sp", "DSS SP");
        checkDecoder("sipr", "SIPRO / RealAudio 4");
        // ADPCM variants
        checkDecoder("adpcm_ima_wav", "ADPCM IMA WAV");
        checkDecoder("adpcm_ima_qt", "ADPCM IMA QuickTime");
        checkDecoder("adpcm_ima_dk3", "ADPCM IMA Duck DK3");
        checkDecoder("adpcm_ima_dk4", "ADPCM IMA Duck DK4");
        checkDecoder("adpcm_ima_ws", "ADPCM IMA Westwood");
        checkDecoder("adpcm_ima_smjpeg", "ADPCM IMA SMJPEG");
        checkDecoder("adpcm_ms", "ADPCM Microsoft");
        checkDecoder("adpcm_4xm", "ADPCM 4X Movie");
        checkDecoder("adpcm_xa", "ADPCM XA");
        checkDecoder("adpcm_adx", "ADPCM ADX");
        checkDecoder("adpcm_ea", "ADPCM Electronic Arts");
        checkDecoder("adpcm_g722", "ADPCM G.722");
        checkDecoder("adpcm_g726", "ADPCM G.726");
        checkDecoder("adpcm_g726le", "ADPCM G.726 LE");
        checkDecoder("adpcm_ct", "ADPCM Creative");
        checkDecoder("adpcm_swf", "ADPCM Shockwave Flash");
        checkDecoder("adpcm_yamaha", "ADPCM Yamaha");
        checkDecoder("adpcm_thp", "ADPCM THP");
        checkDecoder("adpcm_thp_le", "ADPCM THP LE");
        checkDecoder("adpcm_psx", "ADPCM PlayStation");
        checkDecoder("adpcm_aica", "ADPCM Dreamcast AICA");
        checkDecoder("adpcm_afc", "ADPCM Nintendo AFC");
        checkDecoder("adpcm_dtk", "ADPCM Nintendo DTK");
        checkDecoder("adpcm_vima", "ADPCM LucasArts VIMA");
        checkDecoder("adpcm_zork", "ADPCM Zork");
        // PCM formats
        checkDecoder("pcm_s8", "PCM signed 8-bit");
        checkDecoder("pcm_s8_planar", "PCM signed 8-bit planar");
        checkDecoder("pcm_s16be", "PCM signed 16-bit BE");
        checkDecoder("pcm_s16be_planar", "PCM signed 16-bit BE planar");
        checkDecoder("pcm_s16le", "PCM signed 16-bit LE");
        checkDecoder("pcm_s16le_planar", "PCM signed 16-bit LE planar");
        checkDecoder("pcm_s24be", "PCM signed 24-bit BE");
        checkDecoder("pcm_s24le", "PCM signed 24-bit LE");
        checkDecoder("pcm_s24le_planar", "PCM signed 24-bit LE planar");
        checkDecoder("pcm_s24daud", "PCM signed 24-bit D-Cinema");
        checkDecoder("pcm_s32be", "PCM signed 32-bit BE");
        checkDecoder("pcm_s32le", "PCM signed 32-bit LE");
        checkDecoder("pcm_s32le_planar", "PCM signed 32-bit LE planar");
        checkDecoder("pcm_s64be", "PCM signed 64-bit BE");
        checkDecoder("pcm_s64le", "PCM signed 64-bit LE");
        checkDecoder("pcm_u8", "PCM unsigned 8-bit");
        checkDecoder("pcm_u16be", "PCM unsigned 16-bit BE");
        checkDecoder("pcm_u16le", "PCM unsigned 16-bit LE");
        checkDecoder("pcm_u24be", "PCM unsigned 24-bit BE");
        checkDecoder("pcm_u24le", "PCM unsigned 24-bit LE");
        checkDecoder("pcm_u32be", "PCM unsigned 32-bit BE");
        checkDecoder("pcm_u32le", "PCM unsigned 32-bit LE");
        checkDecoder("pcm_f16le", "PCM float 16-bit LE");
        checkDecoder("pcm_f24le", "PCM float 24-bit LE");
        checkDecoder("pcm_f32be", "PCM float 32-bit BE");
        checkDecoder("pcm_f32le", "PCM float 32-bit LE");
        checkDecoder("pcm_f64be", "PCM float 64-bit BE");
        checkDecoder("pcm_f64le", "PCM float 64-bit LE");
        checkDecoder("pcm_alaw", "PCM A-law");
        checkDecoder("pcm_mulaw", "PCM mu-law");
        checkDecoder("pcm_vidc", "PCM Archimedes VIDC");
        checkDecoder("pcm_bluray", "PCM Blu-ray");
        checkDecoder("pcm_dvd", "PCM DVD");
        // Game/multimedia audio
        checkDecoder("binkaudio_rdft", "Bink Audio RDFT");
        checkDecoder("binkaudio_dct", "Bink Audio DCT");
        checkDecoder("smackaud", "Smacker Audio");
        checkDecoder("roq_dpcm", "id RoQ DPCM");
        checkDecoder("interplay_dpcm", "Interplay DPCM");
        checkDecoder("xan_dpcm", "Xan DPCM");
        checkDecoder("sol_dpcm", "Sol DPCM");
        checkDecoder("mpc7", "Musepack SV7");
        checkDecoder("mpc8", "Musepack SV8");
        checkDecoder("qdm2", "QDesign Music Codec 2");
        checkDecoder("qdmc", "QDesign Music Codec");
        checkDecoder("mace3", "MACE 3:1");
        checkDecoder("mace6", "MACE 6:1");
        checkDecoder("hca", "CRI HCA");
        checkDecoder("xma1", "Xbox Media Audio 1");
        checkDecoder("xma2", "Xbox Media Audio 2");
        // Misc
        checkDecoder("comfortnoise", "RFC 3389 Comfort Noise");
        checkDecoder("dvaudio", "DV Audio");
        checkDecoder("s302m", "SMPTE 302M");
        checkDecoder("sbc", "Bluetooth SBC");

        // Hardware acceleration
        LOGGER.info(IT, "=== Hardware Acceleration ===");
        int hwType = avutil.AV_HWDEVICE_TYPE_NONE;
        int hwCount = 0;
        while ((hwType = avutil.av_hwdevice_iterate_types(hwType)) != avutil.AV_HWDEVICE_TYPE_NONE) {
            try {
                final BytePointer hwName = avutil.av_hwdevice_get_type_name(hwType);
                if (hwName != null && !hwName.isNull()) {
                    LOGGER.info(IT, "  [OK] {}", hwName.getString());
                    hwCount++;
                }
            } catch (final Exception e) {
                // Ignorar
            }
        }
        if (hwCount == 0) {
            LOGGER.info(IT, "  None available");
        }

        // Pixel formats
        LOGGER.info(IT, "=== Pixel Formats ===");
        checkPixelFormat(avutil.AV_PIX_FMT_YUV420P, "yuv420p", "Planar YUV 4:2:0");
        checkPixelFormat(avutil.AV_PIX_FMT_YUV422P, "yuv422p", "Planar YUV 4:2:2");
        checkPixelFormat(avutil.AV_PIX_FMT_YUV444P, "yuv444p", "Planar YUV 4:4:4");
        checkPixelFormat(avutil.AV_PIX_FMT_YUV420P10LE, "yuv420p10le", "Planar YUV 4:2:0 10-bit LE");
        checkPixelFormat(avutil.AV_PIX_FMT_YUV422P10LE, "yuv422p10le", "Planar YUV 4:2:2 10-bit LE");
        checkPixelFormat(avutil.AV_PIX_FMT_YUV444P10LE, "yuv444p10le", "Planar YUV 4:4:4 10-bit LE");
        checkPixelFormat(avutil.AV_PIX_FMT_NV12, "nv12", "Semi-planar YUV 4:2:0");
        checkPixelFormat(avutil.AV_PIX_FMT_NV21, "nv21", "Semi-planar YUV 4:2:0 (UV swapped)");
        checkPixelFormat(avutil.AV_PIX_FMT_BGRA, "bgra", "Packed BGRA 8:8:8:8");
        checkPixelFormat(avutil.AV_PIX_FMT_RGBA, "rgba", "Packed RGBA 8:8:8:8");
        checkPixelFormat(avutil.AV_PIX_FMT_ARGB, "argb", "Packed ARGB 8:8:8:8");
        checkPixelFormat(avutil.AV_PIX_FMT_ABGR, "abgr", "Packed ABGR 8:8:8:8");
        checkPixelFormat(avutil.AV_PIX_FMT_RGB24, "rgb24", "Packed RGB 8:8:8");
        checkPixelFormat(avutil.AV_PIX_FMT_BGR24, "bgr24", "Packed BGR 8:8:8");
        checkPixelFormat(avutil.AV_PIX_FMT_GRAY8, "gray8", "Grayscale 8-bit");
        checkPixelFormat(avutil.AV_PIX_FMT_GRAY16LE, "gray16le", "Grayscale 16-bit LE");
    }
    private static void checkDecoder(final String name, final String description) {
        try {
            final AVCodec codec = avcodec.avcodec_find_decoder_by_name(name);
            if (codec != null && !codec.isNull()) {
                LOGGER.info(IT, "  [OK] {} ({})", name, description);
            } else {
                LOGGER.warn(IT, "  [MISSING] {} ({})", name, description);
            }
        } catch (final Exception e) {
            LOGGER.warn(IT, "  [ERROR] {} - {}", name, e.getMessage());
        }
    }

    private static void checkPixelFormat(final int format, final String name, final String description) {
        try {
            final AVPixFmtDescriptor desc = avutil.av_pix_fmt_desc_get(format);
            if (desc != null && !desc.isNull()) {
                LOGGER.info(IT, "  [OK] {} ({})", name, description);
            } else {
                LOGGER.warn(IT, "  [MISSING] {} ({})", name, description);
            }
        } catch (final Exception e) {
            LOGGER.warn(IT, "  [ERROR] {} - {}", name, e.getMessage());
        }
    }

    public static boolean loaded() {
        return LOADED;
    }

    private static final class LogCallback extends Callback_Pointer_int_String_Pointer {
        private static final int LINE_SIZE = 2048;
        private final BytePointer pointer = new BytePointer(LINE_SIZE);
        private final IntPointer prefixPointer = new IntPointer(1);
        private static final LogCallback INSTANCE = new LogCallback();

        public LogCallback() { super(); }

        @Override
        public void call(final Pointer pointer, final int level, final String format, final Pointer variableList) {
            if (format == null || variableList == null || variableList.isNull()) return;

            try {
                final int written = avutil.av_log_format_line2(pointer, level, format, variableList, this.pointer, LINE_SIZE, this.prefixPointer);
                if (written <= 0) return;

                this.pointer.limit(written);
                final byte[] bytes = this.pointer.getStringBytes();
                final String message = new String(bytes, StandardCharsets.UTF_8).trim();

                switch (level) {
                    case AV_LOG_QUIET, AV_LOG_PANIC, AV_LOG_FATAL -> LOGGER.fatal(IT, message);
                    case AV_LOG_ERROR -> LOGGER.error(IT, message);
                    case AV_LOG_WARNING -> LOGGER.warn(IT, message);
                    case AV_LOG_INFO -> LOGGER.info(IT, message);
                    case AV_LOG_DEBUG -> LOGGER.info(IT, message);
                    default -> {
//                        LOGGER.info(IT, message);
                    }
                }
            } catch (final Throwable e) {
                LOGGER.error(IT, "Error processing FFmpeg log, level {} - format '{}'", level, format);
            }
        }
    }
}