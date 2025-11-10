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
    private static final ThreadFactory DEFAULT_THREAD_FACTORY = ThreadTool.createFactory("FFThread", 7);
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
            while (this.running && !this.stopRequested) {
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

                        if (this.repeat()) {
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
            if (avformat.avformat_open_input(this.formatContext, this.sources[this.sourceIndex].sourceQuality().values().toArray(new URI[0])[0].toString(), null, null) < 0) {
                return false;
            }

            if (avformat.avformat_find_stream_info(this.formatContext, (PointerPointer<?>) null) < 0) {
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

            // Initialize video decoder
            if (this.video && this.videoStreamIndex >= 0) {
                final AVCodec videoCodec = avcodec.avcodec_find_decoder(
                        this.formatContext.streams(this.videoStreamIndex).codecpar().codec_id());

                if (videoCodec == null) return false;

                this.videoCodecContext = avcodec.avcodec_alloc_context3(videoCodec);
                if (avcodec.avcodec_parameters_to_context(this.videoCodecContext,
                        this.formatContext.streams(this.videoStreamIndex).codecpar()) < 0) {
                    return false;
                }

                if (avcodec.avcodec_open2(this.videoCodecContext, videoCodec, (PointerPointer<?>) null) < 0) {
                    return false;
                }

                this.setVideoFormat(GL12.GL_BGRA, this.videoCodecContext.width(), this.videoCodecContext.height());

                this.swsContext = swscale.sws_getContext(
                        this.width(), this.height(), this.videoCodecContext.pix_fmt(),
                        this.width(), this.height(), avutil.AV_PIX_FMT_BGRA,
                        swscale.SWS_BILINEAR, null, null, (double[]) null);

                if (this.swsContext == null) return false;

                this.scaledFrame.format(avutil.AV_PIX_FMT_BGRA);
                this.scaledFrame.width(this.width());
                this.scaledFrame.height(this.height());

                if (avutil.av_frame_get_buffer(this.scaledFrame, 32) < 0) return false;
            }

            // Initialize audio decoder
            if (this.audio && this.audioStreamIndex >= 0) {
                final AVStream audioStream = this.formatContext.streams(this.audioStreamIndex);
                final AVCodecParameters codecParams = audioStream.codecpar();
                final AVCodec audioCodec = avcodec.avcodec_find_decoder(codecParams.codec_id());

                if (audioCodec == null) return false;

                this.audioCodecContext = avcodec.avcodec_alloc_context3(audioCodec);
                if (this.audioCodecContext == null) return false;

                if (avcodec.avcodec_parameters_to_context(this.audioCodecContext, codecParams) < 0) {
                    return false;
                }

                if (avcodec.avcodec_open2(this.audioCodecContext, audioCodec, (PointerPointer<?>) null) < 0) {
                    return false;
                }

                // Initialize resampler
                this.swrContext = swresample.swr_alloc();
                if (this.swrContext == null) return false;

                final AVChannelLayout inputLayout = new AVChannelLayout();
                final AVChannelLayout outputLayout = new AVChannelLayout();

                if (codecParams.ch_layout().nb_channels() > 0) {
                    avutil.av_channel_layout_copy(inputLayout, codecParams.ch_layout());
                } else {
                    avutil.av_channel_layout_default(inputLayout, codecParams.ch_layout().nb_channels());
                }

                avutil.av_channel_layout_default(outputLayout, AUDIO_CHANNELS);

                avutil.av_opt_set_chlayout(this.swrContext, "in_chlayout", inputLayout, 0);
                avutil.av_opt_set_int(this.swrContext, "in_sample_rate", codecParams.sample_rate(), 0);
                avutil.av_opt_set_sample_fmt(this.swrContext, "in_sample_fmt", codecParams.format(), 0);
                avutil.av_opt_set_chlayout(this.swrContext, "out_chlayout", outputLayout, 0);
                avutil.av_opt_set_int(this.swrContext, "out_sample_rate", AUDIO_SAMPLE_RATE, 0);
                avutil.av_opt_set_sample_fmt(this.swrContext, "out_sample_fmt", avutil.AV_SAMPLE_FMT_S16, 0);

                if (swresample.swr_init(this.swrContext) < 0) return false;

                this.resampledFrame.format(avutil.AV_SAMPLE_FMT_S16);
                this.resampledFrame.ch_layout().nb_channels(AUDIO_CHANNELS);
                avutil.av_channel_layout_default(this.resampledFrame.ch_layout(), AUDIO_CHANNELS);
                this.resampledFrame.sample_rate(AUDIO_SAMPLE_RATE);
                this.resampledFrame.nb_samples(AUDIO_SAMPLES);

                return avutil.av_frame_get_buffer(this.resampledFrame, 0) >= 0;
            }

            return true;
        } catch (final Exception e) {
            LOGGER.error(IT, "Failed to initialize FFmpeg", e);
            return false;
        }
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
            this.videoBuffer = this.scaledFrame.data(0).asByteBuffer();
            synchronized (this.videoBuffer) {
                swscale.sws_scale(this.swsContext, this.videoFrame.data(), this.videoFrame.linesize(),
                        0, this.height(), this.scaledFrame.data(), this.scaledFrame.linesize());
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
        this.running = false;

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

        LOGGER.info(IT, "Cleanup completed");
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

            avutil.av_log_set_callback(LogCallback.INSTANCE);
            avutil.av_log_set_flags(avutil.AV_LOG_PRINT_LEVEL | avutil.AV_LOG_SKIP_REPEATED);
            avutil.av_log_set_level(LOGGER.isDebugEnabled() ? avutil.AV_LOG_DEBUG : avutil.AV_LOG_INFO);

            LOGGER.info(IT, "FFMPEG started, running version {} under {}", avformat.avformat_version(), avformat.avformat_license().getString());
            return LOADED = true;
        } catch (final Throwable t) {
            LOGGER.error(IT, "Failed to load FFMPEG", t);
            return false;
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
                    default -> LOGGER.debug(IT, message);
                }
            } catch (final Throwable e) {
                LOGGER.error(IT, "Error processing FFmpeg log: {}", format);
            }
        }
    }
}