package org.watermedia.api.media;

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
import org.bytedeco.javacpp.PointerPointer;
import org.lwjgl.openal.AL10;
import org.lwjgl.opengl.GL12;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.watermedia.WaterMedia.LOGGER;

public class FFMediaPlayer extends MediaPlayer {
    private static final Marker IT = MarkerManager.getMarker(FFMediaPlayer.class.getSimpleName());

    // Constants
    private static final int MAX_VIDEO_QUEUE_SIZE = 30;
    private static final int MAX_AUDIO_QUEUE_SIZE = 100;
    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final int AUDIO_CHANNELS = 2;
    private static final int AUDIO_SAMPLES = 1024;
    private static final FrameData POISON_PILL = new FrameData(-1, null);

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
    private Thread videoThread;
    private Thread audioThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // State management
    private final AtomicReference<Status> currentStatus = new AtomicReference<>(Status.WAITING);
    private final AtomicBoolean pauseRequested = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final BlockingQueue<Long> seekQueue = new LinkedBlockingQueue<>();
    private volatile boolean seekInProgress = false;
    private final AtomicReference<Float> speedFactor = new AtomicReference<>(1.0f);

    // Timing control
    private final AtomicLong currentPts = new AtomicLong(0);
    private final AtomicBoolean isSearching = new AtomicBoolean(false);
    private final Object seekLock = new Object();
    private final Object pauseLock = new Object();
    private long playbackStartTime = -1;
    private volatile double audioClock = 0.0;
    private volatile boolean isPaused = false;
    private long pausedAt = 0;
    private long totalPauseTime = 0;
    private final Object playbackLock = new Object();
    private final AtomicBoolean throttleNotified = new AtomicBoolean(false);

    // Memory management - reusable buffers
    private AVPacket packet;
    private AVFrame videoFrame;
    private AVFrame audioFrame;
    private AVFrame scaledFrame;
    private AVFrame resampledFrame;
    private ByteBuffer videoBuffer;

    // Frame queues
    private final BlockingQueue<FrameData> videoQueue = new LinkedBlockingQueue<>(MAX_VIDEO_QUEUE_SIZE);
    private final BlockingQueue<FrameData> audioQueue = new LinkedBlockingQueue<>(MAX_AUDIO_QUEUE_SIZE);

    // Time bases
    private double videoTimeBase;
    private double audioTimeBase;

    public FFMediaPlayer(final URI mrl, final Thread renderThread, final Executor renderThreadEx, final boolean video, final boolean audio) {
        super(mrl, renderThread, renderThreadEx, video, audio);
    }

    // ===========================================
    // LIFECYCLE METHODS
    // ===========================================

    @Override
    public void start() {
        if (this.running.get()) return;

        this.currentStatus.set(Status.LOADING);
        this.running.set(true);

        this.playerThread = new Thread(this::playerLoop, "FFmpeg-Player");
        this.playerThread.setDaemon(true);
        this.playerThread.start();
    }

    @Override
    public void startPaused() {
        this.start();
        this.pause(true);
    }

    @Override
    public void release() {
        this.stop();
        this.cleanup();
        super.release();
    }

    // ===========================================
    // CONTROL METHODS
    // ===========================================

    @Override
    public boolean resume() {
        synchronized (this.pauseLock) {
            if (!this.isPaused) return false;

            final long pauseDuration = System.nanoTime() - this.pausedAt;
            this.totalPauseTime += pauseDuration;

            LOGGER.debug(IT, "Resuming after {}ms pause. Total pause time: {}ms",
                    pauseDuration / 1_000_000, this.totalPauseTime / 1_000_000);

            this.isPaused = false;
            this.pauseRequested.set(false);
            this.currentStatus.set(Status.PLAYING);

            this.pauseLock.notifyAll();

            return true;
        }
    }

    @Override
    public boolean pause() {
        return this.pause(true);
    }

    @Override
    public boolean pause(final boolean paused) {
        synchronized (this.pauseLock) {
            if (paused && !this.isPaused) {
                this.isPaused = true;
                this.pauseRequested.set(true);
                this.pausedAt = System.nanoTime();
                this.currentStatus.set(Status.PAUSED);
                LOGGER.debug(IT, "Paused at {}ms", this.time());
                return true;
            } else if (!paused && this.isPaused) {
                return this.resume();
            }
            return false;
        }
    }

    @Override
    public boolean stop() {
        this.seekQueue.clear();
        this.stopRequested.set(true);
        this.running.set(false);
        this.currentStatus.set(Status.STOPPED);
        return true;
    }

    @Override
    public boolean togglePlay() {
        return this.pause(!this.pauseRequested.get());
    }

    // ===========================================
    // SEEKING METHODS
    // ===========================================

    @Override
    public boolean seek(long timeMs) {
        if (!this.canSeek()) {
            LOGGER.warn(IT, "Cannot seek - either invalid source or live stream");
            return false;
        }


        timeMs = this.clampSeekTime(timeMs);

        if (this.seekQueue.size() > 5) {
            this.seekQueue.clear();
            LOGGER.debug(IT, "Cleared seek queue due to multiple pending seeks");
        }

        this.seekQueue.offer(timeMs * 1000); // Convertir a microsegundos
        LOGGER.debug(IT, "Seek queued to {}ms. Queue size: {}", timeMs, this.seekQueue.size());

        return true;
    }

    @Override
    public boolean seekQuick(final long timeMs) {
        if (!this.canSeek()) return false;

        synchronized (this.seekLock) {
            final long timeUs = timeMs * 1000;
            final long ffmpegTimestamp = (timeUs / 1_000_000L) * avutil.AV_TIME_BASE;

            final int result = avformat.av_seek_frame(this.formatContext, -1, ffmpegTimestamp,
                    avformat.AVSEEK_FLAG_BACKWARD);

            if (result >= 0) {
                this.clearQueues();
                this.flushCodecs();
                this.resetTiming(timeUs);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean skipTime(final long timeMs) {
        final long currentTime = this.time();
        final long targetTime = this.clampSeekTime(currentTime + timeMs);

        LOGGER.debug(IT, "Skip time: {}ms -> {}ms (delta: {}ms)", currentTime, targetTime, timeMs);
        return this.seek(targetTime);
    }

    @Override
    public boolean previousFrame() {
        if (!this.canSeek()) return false;

        final long frameTimeMs = this.calculateFrameTime();
        final long currentTime = this.time();
        final long targetTime = Math.max(0, currentTime - frameTimeMs);

        LOGGER.debug(IT, "Previous frame: {}ms -> {}ms (frame time: {}ms)", currentTime, targetTime, frameTimeMs);
        return this.seek(targetTime);
    }

    @Override
    public boolean nextFrame() {
        if (!this.canSeek()) return false;

        final long frameTimeMs = this.calculateFrameTime();
        final long currentTime = this.time();
        final long targetTime = this.clampSeekTime(currentTime + frameTimeMs);

        LOGGER.debug(IT, "Next frame: {}ms -> {}ms (frame time: {}ms)", currentTime, targetTime, frameTimeMs);
        return this.seek(targetTime);
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
        return this.speedFactor.get();
    }

    @Override
    public boolean speed(final float speed) {
        if (speed <= 0 || speed > 4.0f) return false;
        this.speedFactor.set(speed);
        return true;
    }

    // ===========================================
    // STATE METHODS
    // ===========================================

    @Override
    public Status status() {
        return this.currentStatus.get();
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
        // During seek, return a stable value
        if (this.seekInProgress || this.isSearching.get()) {
            return this.currentPts.get() / 1000;
        }

        // Use audio clock if available and valid
        if (this.audio && this.audioClock > 0) {
            return (long)(this.audioClock * 1000);
        }

        // Video-only or fallback
        if (this.playbackStartTime <= 0) {
            return this.currentPts.get() / 1000;
        }

        synchronized (this.pauseLock) {
            if (this.isPaused) {
                return (this.pausedAt - this.playbackStartTime - this.totalPauseTime) / 1_000_000;
            } else {
                // Calculate elapsed time but never go backwards
                final long elapsed = (System.nanoTime() - this.playbackStartTime - this.totalPauseTime) / 1_000_000;
                final long lastKnown = this.currentPts.get() / 1000;

                // Return the maximum to prevent time regression
                return Math.max(elapsed, lastKnown);
            }
        }
    }
    // ===========================================
    // PRIVATE IMPLEMENTATION
    // ===========================================

    private void playerLoop() {
        try {
            if (!this.initializeFFmpeg()) {
                this.currentStatus.set(Status.ERROR);
                return;
            }

            this.startVideoThread();
            this.startAudioThread();
            this.currentStatus.set(Status.PLAYING);
            this.playbackStartTime = System.nanoTime();

            while (this.running.get() && !this.stopRequested.get()) {
                if (!this.seekInProgress && !this.seekQueue.isEmpty()) {
                    Long targetTimeUs = null;

                    // Get all pending seeks but only execute the last one
                    while (!this.seekQueue.isEmpty()) {
                        targetTimeUs = this.seekQueue.poll();
                    }

                    if (targetTimeUs != null) {
                        LOGGER.info(IT, "Processing seek to {}ms", targetTimeUs / 1000);
                        this.performSeek(targetTimeUs);
                    }
                }

                if (this.isPaused) {
                    synchronized (this.pauseLock) {
                        while (this.isPaused && this.running.get() && !this.stopRequested.get()) {
                            // Usar wait() en lugar de Thread.sleep()
                            // Será despertado por resume() via notify()
                            this.pauseLock.wait();
                        }
                    }
                    continue;
                }

                if (this.shouldThrottleReading()) {
                    // Estrategia híbrida: wait con timeout
                    final long startTime = System.currentTimeMillis();

                    while (this.shouldThrottleReading() && this.running.get() && !this.stopRequested.get()) {
                        synchronized (this.playbackLock) {
                            // Esperar máximo 10ms o hasta que se libere espacio
                            this.playbackLock.wait(10);

                            // Si llevamos más de 50ms esperando, verificar estado
                            if (System.currentTimeMillis() - startTime > 50) {
                                break;
                            }
                        }
                    }
                    continue;
                }

                if (!this.readAndQueueFrame()) {
                    try {
                        LOGGER.info(IT, "Handling end of stream...");

                        final long startWait = System.currentTimeMillis();
                        while ((!this.videoQueue.isEmpty() || !this.audioQueue.isEmpty()) &&
                                (System.currentTimeMillis() - startWait) < 5000) {
                            Thread.sleep(100);
                        }

                        if (this.repeat()) {
                            LOGGER.info(IT, "Repeating playback...");
                            synchronized (this.pauseLock) {
                                this.totalPauseTime = 0;
                                this.playbackStartTime = -1;
                                this.audioClock = 0.0;
                            }
                            this.seek(0);
                        } else {
                            LOGGER.info(IT, "Setting ENDED status");
                            this.currentStatus.set(Status.ENDED);
                        }
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    break;
                }
            }
        } catch (final Exception e) {
            LOGGER.error(IT, "Error in player loop", e);
            this.currentStatus.set(Status.ERROR);
        } finally {
            this.cleanup();
        }
    }

    private boolean initializeFFmpeg() {
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
            if (avformat.avformat_open_input(this.formatContext, this.mrl.toString(), null, null) < 0) {
                return false;
            }

            if (avformat.avformat_find_stream_info(this.formatContext, (PointerPointer<?>) null) < 0) {
                return false;
            }

            this.findStreams();

            if (this.video && this.videoStreamIndex >= 0 && !this.initializeVideoDecoder()) {
                return false;
            }

            return !this.audio || this.audioStreamIndex < 0 || this.initializeAudioDecoder();
        } catch (final Exception e) {
            LOGGER.error(IT, "Failed to initialize FFmpeg", e);
            return false;
        }
    }

    private void findStreams() {
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
    }

    private boolean initializeVideoDecoder() {
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

        return true;
    }

    private boolean initializeAudioDecoder() {
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

    private void startVideoThread() {
        if (!this.video || this.videoStreamIndex < 0) return;

        this.videoThread = new Thread(() -> {
            try {
                while (this.running.get() && !this.stopRequested.get()) {
                    final FrameData frameData = this.videoQueue.take();
                    if (frameData == POISON_PILL) break;
                    this.processVideoFrame(frameData);
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "FFmpeg-Video");

        this.videoThread.setDaemon(true);
        this.videoThread.start();
    }

    private void startAudioThread() {
        if (!this.audio || this.audioStreamIndex < 0) return;

        this.audioThread = new Thread(() -> {
            try {
                while (this.running.get() && !this.stopRequested.get()) {
                    final FrameData frameData = this.audioQueue.take();
                    if (frameData == POISON_PILL) break;
                    this.processAudioFrame(frameData);
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "FFmpeg-Audio");

        this.audioThread.setDaemon(true);
        this.audioThread.start();
    }

    private void notifyQueueSpaceAvailable() {
        if (this.shouldThrottleReading()) {
            return; // Aún no hay suficiente espacio
        }

        synchronized (this.playbackLock) {
            this.playbackLock.notifyAll();
        }
    }

    private boolean shouldThrottleReading() {
        final int videoSize = this.videoQueue.size();
        final int audioSize = this.audioQueue.size();

        // Usar histéresis para evitar oscilaciones
        if (this.throttleNotified.get()) {
            // Ya estamos en throttling, usar umbrales más bajos para salir
            return videoSize > 15 || audioSize > 60;
        } else {
            // No estamos en throttling, usar umbrales normales para entrar
            final boolean shouldThrottle = videoSize > 20 || audioSize > 80;
            this.throttleNotified.set(shouldThrottle);
            return shouldThrottle;
        }
    }

    private boolean readAndQueueFrame() {
        final int result = avformat.av_read_frame(this.formatContext, this.packet);
        if (result < 0) {
            LOGGER.info(IT, "End of stream detected");
            return false;
        }

        try {
            if (this.packet.stream_index() == this.videoStreamIndex && this.video) {
                this.decodeAndQueue(this.packet, this.videoCodecContext, this.videoQueue);
            } else if (this.packet.stream_index() == this.audioStreamIndex && this.audio) {
                this.decodeAndQueue(this.packet, this.audioCodecContext, this.audioQueue);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            avcodec.av_packet_unref(this.packet);
        }
        return true;
    }

    private void decodeAndQueue(final AVPacket packet, final AVCodecContext context,
                                final BlockingQueue<FrameData> queue) throws InterruptedException {
        final AVFrame targetFrame = (context == this.videoCodecContext) ? this.videoFrame : this.audioFrame;

        if (avcodec.avcodec_send_packet(context, packet) >= 0) {
            while (avcodec.avcodec_receive_frame(context, targetFrame) >= 0) {
                final AVFrame frameCopy = avutil.av_frame_alloc();
                if (frameCopy != null && avutil.av_frame_ref(frameCopy, targetFrame) >= 0) {
                    queue.put(new FrameData(targetFrame.pts(), frameCopy));
                }
            }
        }
    }

    private void processVideoFrame(final FrameData frameData) {
        try {
            if (this.isSearching.get()) return;

            this.waitForPlaybackResume();
            if (!this.running.get() || this.stopRequested.get() || this.isSearching.get()) return;

            final double ptsInSeconds = frameData.pts * this.videoTimeBase;

            // Initialize playback timer on very first frame only
            if (this.playbackStartTime <= 0) {
                this.playbackStartTime = System.nanoTime() - (long)(ptsInSeconds * 1_000_000_000L);
            }

            this.handleVideoFrameTiming(ptsInSeconds);

            swscale.sws_scale(this.swsContext, frameData.frame.data(), frameData.frame.linesize(),
                    0, this.height(), this.scaledFrame.data(), this.scaledFrame.linesize());

            final int stride = this.scaledFrame.linesize(0);
            if (this.videoBuffer == null) {
                this.videoBuffer = this.scaledFrame.data(0).asByteBuffer();
            }
            this.uploadVideoFrame(this.videoBuffer, stride / 4);

            // Update PTS only if it's ahead of current
            if (frameData.pts > this.currentPts.get() / 1000) {
                this.currentPts.set(frameData.pts);
            }

        } finally {
            if (frameData.frame != null) {
                avutil.av_frame_free(frameData.frame);
            }
            this.notifyQueueSpaceAvailable();
        }
    }

    private void processAudioFrame(final FrameData frameData) {
        try {
            if (this.isSearching.get()) return;

            this.waitForPlaybackResume();
            if (!this.running.get() || this.stopRequested.get() || this.isSearching.get()) return;

            final int samplesConverted = swresample.swr_convert(this.swrContext, this.resampledFrame.data(),
                    this.resampledFrame.nb_samples(), frameData.frame.data(), frameData.frame.nb_samples());

            if (samplesConverted > 0) {
                final int dataSize = samplesConverted * AUDIO_CHANNELS * 2;
                this.uploadAudioBuffer(this.resampledFrame.data(0).limit(dataSize).asBuffer().clear(),
                        AL10.AL_FORMAT_STEREO16, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS);

                // Update audio clock naturally
                this.audioClock = (frameData.pts * this.audioTimeBase) +
                        ((double)samplesConverted / AUDIO_SAMPLE_RATE);
            }
        } finally {
            if (frameData.frame != null) {
                avutil.av_frame_free(frameData.frame);
            }
            this.notifyQueueSpaceAvailable();
        }
    }


    private void waitForPlaybackResume() {
        synchronized (this.pauseLock) {
            while (this.isPaused && this.running.get() && !this.stopRequested.get()) {
                try {
                    this.pauseLock.wait();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void handleVideoFrameTiming(final double ptsInSeconds) {
        if (this.audio) {
            // Audio-video synchronization
            final double timeDiff = ptsInSeconds - this.audioClock;

            // Larger tolerance window (80ms) to prevent constant corrections
            if (Math.abs(timeDiff) < 0.08) {
                return; // Close enough, no correction needed
            }

            // Video is ahead of audio - wait
            if (timeDiff > 0) {
                // Gradual sync: don't wait the full difference at once
                final long sleepMs = Math.min(20, (long)(timeDiff * 500)); // Max 20ms wait
                if (sleepMs > 0) {
                    try {
                        Thread.sleep(sleepMs);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            // If video is behind by more than 150ms, skip timing
            else if (timeDiff < -0.15) {
                // Video is too far behind, let it catch up
                return;
            }

        } else {
            // Video-only timing
            if (this.playbackStartTime <= 0) {
                this.playbackStartTime = System.nanoTime();
            }

            final long currentTime = System.nanoTime();
            final long elapsedTime = currentTime - this.playbackStartTime - this.totalPauseTime;
            final long targetTime = (long)(ptsInSeconds * 1_000_000_000L / this.speedFactor.get());

            final long sleepTime = targetTime - elapsedTime;
            if (sleepTime > 0 && sleepTime < 500_000_000L) {
                try {
                    Thread.sleep(sleepTime / 1_000_000L);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void performSeek(final long timeUs) {
        if (this.formatContext == null || !this.canSeek()) {
            LOGGER.warn(IT, "Cannot seek - invalid format context or live stream");
            return;
        }

        synchronized (this.seekLock) {
            this.seekInProgress = true;
            this.isSearching.set(true);

            try {
                LOGGER.info(IT, "Starting seek to {}ms", timeUs / 1000);

                final boolean wasPlaying = !this.isPaused;
                if (wasPlaying) {
                    synchronized (this.pauseLock) {
                        this.isPaused = true;
                    }
                }

                // Clear queues and flush codecs
                this.clearQueues();
                this.flushCodecs();

                // Perform the seek
                final long ffmpegTimestamp = (timeUs / 1_000_000L) * avutil.AV_TIME_BASE;
                final int result = avformat.av_seek_frame(this.formatContext, -1, ffmpegTimestamp,
                        avformat.AVSEEK_FLAG_BACKWARD);

                if (result < 0) {
                    LOGGER.error(IT, "av_seek_frame failed with error: {}", result);
                    return;
                }

                // Set timing to the TARGET time, not the actual frame time
                // This prevents time regression
                synchronized (this.pauseLock) {
                    // Set clocks to the seek target
                    this.audioClock = timeUs / 1_000_000.0;
                    this.currentPts.set(timeUs / 1000);

                    // Calculate playback start so time() returns the seek target
                    this.playbackStartTime = System.nanoTime() - (timeUs * 1000L);
                    this.totalPauseTime = 0;
                    this.pausedAt = 0;

                    LOGGER.debug(IT, "Timing set to seek target: {}ms", timeUs / 1000);
                }

                if (wasPlaying) {
                    synchronized (this.pauseLock) {
                        this.isPaused = false;
                        this.pauseLock.notifyAll();
                    }
                }

                LOGGER.info(IT, "Seek completed successfully to {}ms", timeUs / 1000);

            } catch (final Exception e) {
                LOGGER.error(IT, "Error during seek operation", e);
            } finally {
                this.seekInProgress = false;
                this.isSearching.set(false);
            }
        }
    }

    private void clearQueues() {
        LOGGER.debug(IT, "Clearing queues - Video: {}, Audio: {}", this.videoQueue.size(), this.audioQueue.size());

        FrameData frame;
        while ((frame = this.videoQueue.poll()) != null && frame.frame() != null) {
            avutil.av_frame_free(frame.frame());
        }

        while ((frame = this.audioQueue.poll()) != null && frame.frame() != null) {
            avutil.av_frame_free(frame.frame());
        }

        LOGGER.debug(IT, "Queues cleared");
    }

    private void flushCodecs() {
        try {
            if (this.videoCodecContext != null) {
                avcodec.avcodec_flush_buffers(this.videoCodecContext);
                LOGGER.debug(IT, "Video codec buffers flushed");
            }

            if (this.audioCodecContext != null) {
                avcodec.avcodec_flush_buffers(this.audioCodecContext);
                LOGGER.debug(IT, "Audio codec buffers flushed");
            }
        } catch (final Exception e) {
            LOGGER.error(IT, "Error flushing codec buffers", e);
        }
    }

    private void resetTiming(final long seekTimeUs) {
        synchronized (this.pauseLock) {
            this.playbackStartTime = System.nanoTime();
            this.totalPauseTime = 0;
            this.pausedAt = 0;
            this.audioClock = seekTimeUs / 1_000_000.0;
            this.currentPts.set(seekTimeUs / 1000);

            LOGGER.debug(IT, "Timing reset - playbackStartTime: {}, audioClock: {}s",
                    this.playbackStartTime, this.audioClock);
        }
    }

    private void cleanup() {
        this.running.set(false);
        this.stopRequested.set(true);

        // Interrupt threads
        if (this.playerThread != null) this.playerThread.interrupt();
        if (this.videoThread != null) this.videoThread.interrupt();
        if (this.audioThread != null) this.audioThread.interrupt();

        // Clear queues and free frames
        this.clearQueues();

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

        this.currentStatus.set(Status.STOPPED);
        LOGGER.info(IT, "Cleanup completed");
    }

    // ===========================================
    // UTILITY METHODS
    // ===========================================

    private long clampSeekTime(final long timeMs) {
        final long durationMs = this.duration();

        if (timeMs < 0) {
            return 0;
        }

        if (durationMs > 0 && timeMs > durationMs) {
            LOGGER.warn(IT, "Seek time {}ms exceeds duration {}ms", timeMs, durationMs);
            return durationMs;
        }

        return timeMs;
    }

    private long calculateFrameTime() {
        long frameTimeMs = 33; // Default ~30fps

        if (this.videoStreamIndex >= 0) {
            final AVStream videoStream = this.formatContext.streams(this.videoStreamIndex);
            final AVRational frameRate = videoStream.avg_frame_rate();
            if (frameRate.num() > 0 && frameRate.den() > 0) {
                frameTimeMs = (long)(1000.0 * frameRate.den() / frameRate.num());
            }
        }

        return frameTimeMs;
    }

    private static double av_q2d(final AVRational a) {
        return (double) a.num() / a.den();
    }

    // ===========================================
    // INNER CLASSES
    // ===========================================

    private record FrameData(long pts, AVFrame frame) {}
}