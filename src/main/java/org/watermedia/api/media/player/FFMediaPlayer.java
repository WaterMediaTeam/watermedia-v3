package org.watermedia.api.media.player;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVChannelLayout;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.ffmpeg.swresample.SwrContext;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.lwjgl.openal.AL10;
import org.lwjgl.opengl.GL12;
import org.watermedia.tools.ThreadTool;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16;
import static org.bytedeco.ffmpeg.global.avutil.av_opt_set_chlayout;
import static org.bytedeco.ffmpeg.global.avutil.av_opt_set_int;
import static org.bytedeco.ffmpeg.global.avutil.av_opt_set_sample_fmt;
import static org.bytedeco.ffmpeg.global.swresample.*;
import static org.bytedeco.ffmpeg.global.swresample.swr_convert;
import static org.watermedia.WaterMedia.LOGGER;

public final class FFMediaPlayer extends MediaPlayer {
    private static final Marker IT = MarkerManager.getMarker("FFMPEG-MediaPlayer");

    public final AVFormatContext ffmpegInstance;
    private final Thread playerThread;

    private AVPacket packet;
    private AVFrame frame;
    private AVFrame rgbaFrame;
    private AVFrame audioFrame;
    private SwsContext swsCtx;
    private AVCodecContext codecContext;
    private AVCodecContext audioCtx;

    private int videoStreamIndex = -1;
    private int audioStreamIndex = -1;
    private AVRational videoTimeBase;
    private AVRational audioTimeBase;
    private BytePointer buffer;
    private SwrContext swrCtx;
    private AVChannelLayout outputLayout;

    // TIMING VARIABLES
    private long playbackStartTime;
    private long lastFrameTime = 0;
    private double frameRate = 30.0; // DEFAULT FRAME RATE
    private double frameDuration; // FRAME DURATION IN MS
    private final AtomicLong virtualTime = new AtomicLong(0); // VIRUAL TIME IN MS
    private long lastVideoTimestamp = 0;
    private long droppedFrames = 0;
    private long totalFrames = 0;

    // STATE
    private Status status = Status.WAITING;
    private boolean repeat = true;

    // Frame sync constants
    private static final long FRAME_SYNC_THRESHOLD_MS = 16; // DRIFT BEFORE DROP FRAMES
    private static final long MAX_FRAME_SKIP = 5; // MAX FRAMES TO SKIP

    public FFMediaPlayer(final URI mrl, final Thread renderThread, final Executor renderThreadEx, final boolean video, final boolean audio) {
        super(mrl, renderThread, renderThreadEx, video, audio);
        this.ffmpegInstance = avformat.avformat_alloc_context();
        this.playerThread = ThreadTool.create("FFMediaPlayer-Thread", this::tick);
    }

    @Override
    public boolean previousFrame() {
        // TODO: Implement
        return false;
    }

    @Override
    public boolean nextFrame() {
        // TODO: Implement
        return false;
    }

    @Override
    public void start() {
        if (this.status != Status.WAITING) {
            if (this.status == Status.PLAYING) {
                LOGGER.warn(IT, "Player is already playing, Ignoring start request.");
                return;
            } else if (this.status == Status.PAUSED) {
                this.resume();
                return;
            } else if (this.status == Status.STOPPED) {
                // Reset state for a new playback
                this.virtualTime.set(0);
                this.playbackStartTime = 0;
                this.droppedFrames = 0;
                this.totalFrames = 0;
                this.status = Status.WAITING; // Reset to waiting state
            } else {
                LOGGER.warn(IT, "Player is already started or in progress, Ignoring start request.");
                return;
            }
        }


        if (avformat.avformat_open_input(this.ffmpegInstance, this.mrl.toString(), null, null) < 0)
            throw new RuntimeException("Could not open video file");

        if (avformat.avformat_find_stream_info(this.ffmpegInstance, (PointerPointer<?>) null) < 0)
            throw new RuntimeException("Could not retrieve stream info");

        this.videoStreamIndex = -1;
        this.audioStreamIndex = -1;

        for (int i = 0; i < this.ffmpegInstance.nb_streams(); i++) {
            switch (this.ffmpegInstance.streams(i).codecpar().codec_type()) {
                case avutil.AVMEDIA_TYPE_VIDEO -> {
                    if (this.videoStreamIndex == -1) {
                        this.videoStreamIndex = i;
                    }
                }
                case avutil.AVMEDIA_TYPE_AUDIO -> {
                    if (this.audioStreamIndex == -1) {
                        this.audioStreamIndex = i;
                    }
                }
                default -> {
                    // Ignore other stream types
                }
            }
        }

        if (this.videoStreamIndex == -1 && this.audioStreamIndex == -1)
            throw new RuntimeException("No video or audio stream found.");

        // Initialize video decoding
        if (this.videoStreamIndex != -1) {
            this.initializeVideo();
        }

        // Initialize audio decoding
        if (this.audioStreamIndex != -1) {
            this.initializeAudio();
        }

        // Initialize timing
        this.playbackStartTime = System.currentTimeMillis();
        this.virtualTime.set(0);
        this.frameDuration = 1000.0 / this.frameRate; // Duration in milliseconds
        this.lastVideoTimestamp = 0;
        this.droppedFrames = 0;
        this.totalFrames = 0;

        this.status = Status.PLAYING;
    }

    private void initializeVideo() {
        final AVStream videoStream = this.ffmpegInstance.streams(this.videoStreamIndex);
        final AVCodecParameters codecpar = videoStream.codecpar();
        final AVCodec codec = avcodec.avcodec_find_decoder(codecpar.codec_id());

        if (codec == null) {
            throw new RuntimeException("Video codec not found");
        }

        this.videoTimeBase = videoStream.time_base();
        this.codecContext = avcodec.avcodec_alloc_context3(codec);

        if (avcodec.avcodec_parameters_to_context(this.codecContext, codecpar) < 0) {
            throw new RuntimeException("Could not copy codec context");
        }

        if (avcodec.avcodec_open2(this.codecContext, codec, (PointerPointer<?>) null) < 0) {
            throw new RuntimeException("Could not open video codec");
        }

        // Calculate frame rate
        final AVRational frameRate = videoStream.r_frame_rate();
        if (frameRate.num() > 0 && frameRate.den() > 0) {
            this.frameRate = (double) frameRate.num() / frameRate.den();
        } else {
            final AVRational avgFrameRate = videoStream.avg_frame_rate();
            if (avgFrameRate.num() > 0 && avgFrameRate.den() > 0) {
                this.frameRate = (double) avgFrameRate.num() / avgFrameRate.den();
            }
        }

        this.setVideoFormat(GL12.GL_BGRA, this.codecContext.width(), this.codecContext.height());

        this.packet = avcodec.av_packet_alloc();
        this.frame = avutil.av_frame_alloc();
        this.rgbaFrame = avutil.av_frame_alloc();

        final int bufferSize = avutil.av_image_get_buffer_size(avutil.AV_PIX_FMT_BGRA, this.width(), this.height(), 32);
        this.buffer = new BytePointer(avutil.av_malloc(bufferSize));
        avutil.av_image_fill_arrays(this.rgbaFrame.data(), this.rgbaFrame.linesize(), this.buffer, avutil.AV_PIX_FMT_BGRA, this.width(), this.height(), 32);

        this.swsCtx = swscale.sws_getContext(
                this.width(), this.height(), this.codecContext.pix_fmt(),
                this.width(), this.height(), avutil.AV_PIX_FMT_BGRA,
                swscale.SWS_BILINEAR, null, null, (DoublePointer) null);

        if (this.swsCtx == null) {
            throw new RuntimeException("Could not initialize video converter");
        }
    }

    private void initializeAudio() {
        final AVStream audioStream = this.ffmpegInstance.streams(this.audioStreamIndex);
        final AVCodecParameters audioParams = audioStream.codecpar();
        final AVCodec audioCodec = avcodec.avcodec_find_decoder(audioParams.codec_id());

        if (audioCodec == null) {
            throw new RuntimeException("Audio codec not found");
        }

        this.audioTimeBase = audioStream.time_base();
        this.audioCtx = avcodec.avcodec_alloc_context3(audioCodec);

        if (avcodec.avcodec_parameters_to_context(this.audioCtx, audioParams) < 0) {
            throw new RuntimeException("Could not copy audio codec context");
        }

        if (avcodec.avcodec_open2(this.audioCtx, audioCodec, (PointerPointer<?>) null) < 0) {
            throw new RuntimeException("Could not open audio codec");
        }

        this.audioFrame = avutil.av_frame_alloc();

        // Setup channel layout
        final AVChannelLayout inputLayout = this.audioCtx.ch_layout();
        this.outputLayout = new AVChannelLayout();

        if (inputLayout.nb_channels() == 2) {
            av_channel_layout_default(this.outputLayout, 2);  // Stereo
        } else {
            av_channel_layout_default(this.outputLayout, 1);  // Mono
        }

        // Initialize resampler
        this.swrCtx = swr_alloc();
        if (this.swrCtx == null) {
            throw new RuntimeException("Could not allocate resampler");
        }

        av_opt_set_chlayout(this.swrCtx, "in_chlayout", inputLayout, 0);
        av_opt_set_chlayout(this.swrCtx, "out_chlayout", this.outputLayout, 0);
        av_opt_set_int(this.swrCtx, "in_sample_rate", this.audioCtx.sample_rate(), 0);
        av_opt_set_int(this.swrCtx, "out_sample_rate", this.audioCtx.sample_rate(), 0);
        av_opt_set_sample_fmt(this.swrCtx, "in_sample_fmt", this.audioCtx.sample_fmt(), 0);
        av_opt_set_sample_fmt(this.swrCtx, "out_sample_fmt", AV_SAMPLE_FMT_S16, 0);

        if (swr_init(this.swrCtx) < 0) {
            throw new RuntimeException("Could not initialize resampler");
        }
    }

    @Override
    public void startPaused() {
        // TODO: Implement
    }

    @Override
    public boolean resume() {
        if (this.status == Status.PAUSED) {
            // Reset timing when resuming
            this.playbackStartTime = System.currentTimeMillis() - this.virtualTime.get();
            this.status = Status.PLAYING;
        }
        return true;
    }

    @Override
    public boolean pause() {
        if (this.status == Status.PLAYING) {
            // Update virtual time when pausing
            this.virtualTime.set(System.currentTimeMillis() - this.playbackStartTime);
            this.status = Status.PAUSED;
        }
        return true;
    }

    @Override
    public boolean pause(final boolean paused) {
        if (paused) {
            return this.pause();
        } else {
            return this.resume();
        }
    }

    @Override
    public boolean stop() {
        this.status = Status.STOPPED;
        this.virtualTime.set(0);
        this.playbackStartTime = 0;
        this.droppedFrames = 0;
        this.totalFrames = 0;
        return true;
    }

    @Override
    public boolean togglePlay() {
        if (this.playing()) {
            return this.pause();
        } else if (this.paused()) {
            return this.resume();
        }
        return false; // If neither playing nor paused, do nothing
    }

    @Override
    public boolean seek(final long time) {
        return false;
    }

    @Override
    public boolean skipTime(final long time) {
        return false;
    }

    @Override
    public boolean seekQuick(final long time) {
        return false;
    }

    @Override
    public boolean foward() {
        return false;
    }

    @Override
    public boolean rewind() {
        return false;
    }

    @Override
    public float speed() {
        return 1.0f;
    }

    @Override
    public boolean speed(final float speed) {
        return false;
    }

    @Override
    public Status status() {
        return this.status;
    }

    @Override
    public boolean validSource() {
        return this.ffmpegInstance != null;
    }

    @Override
    public boolean liveSource() {
        return false;
    }

    @Override
    public boolean canSeek() {
        return true;
    }

    @Override
    public boolean canPause() {
        return true;
    }

    @Override
    public boolean canPlay() {
        return true;
    }

    @Override
    public long duration() {
        if (this.ffmpegInstance != null) {
            return this.ffmpegInstance.duration() / 1000; // Convert to milliseconds
        }
        return 0;
    }

    @Override
    public long time() {
        if (this.status == Status.PLAYING || this.status == Status.PAUSED) {
            return System.currentTimeMillis() - this.playbackStartTime;
        } else if (this.status == Status.STOPPED) {
            return this.duration();
        } else {
            return this.virtualTime.get();
        }
    }

    @Override
    public void release() {
        this.status = Status.ENDED;

        if (this.playerThread != null && this.playerThread.isAlive()) {
            this.playerThread.interrupt();
            try {
                this.playerThread.join(1000); // Wait up to 1 second
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Log frame drop statistics
        if (this.totalFrames > 0) {
            final double dropRate = (double) this.droppedFrames / this.totalFrames * 100;
            LOGGER.debug("Frame drop statistics: {}/{} frames dropped ({}%)", this.droppedFrames, this.totalFrames, dropRate);
        }

        // Clean up resources
        if (this.frame != null) {
            avutil.av_frame_free(this.frame);
        }
        if (this.rgbaFrame != null) {
            avutil.av_frame_free(this.rgbaFrame);
        }
        if (this.audioFrame != null) {
            avutil.av_frame_free(this.audioFrame);
        }
        if (this.buffer != null) {
            avutil.av_free(this.buffer);
        }
        if (this.swsCtx != null) {
            swscale.sws_freeContext(this.swsCtx);
        }
        if (this.swrCtx != null) {
            swr_free(this.swrCtx);
        }
        if (this.codecContext != null) {
            avcodec.avcodec_free_context(this.codecContext);
        }
        if (this.audioCtx != null) {
            avcodec.avcodec_free_context(this.audioCtx);
        }
        if (this.outputLayout != null) {
            this.outputLayout.close();
        }
        if (this.ffmpegInstance != null) {
            avformat.avformat_close_input(this.ffmpegInstance);
        }
        super.release();
    }

    private void tick() {
        long consecutiveSkips = 0;

        while (!Thread.currentThread().isInterrupted()) {
            if (this.status == Status.PLAYING) {
                if (avformat.av_read_frame(this.ffmpegInstance, this.packet) >= 0) {
                    try {
                        if (this.packet.stream_index() == this.videoStreamIndex) {
                            // Check if we need to skip this frame for sync
                            if (this.shouldSkipFrame()) {
                                this.droppedFrames++;
                                consecutiveSkips++;

                                // Prevent skipping too many consecutive frames
                                if (consecutiveSkips >= MAX_FRAME_SKIP) {
                                    this.processVideoFrame();
                                    consecutiveSkips = 0;
                                }
                            } else {
                                this.processVideoFrame();
                                consecutiveSkips = 0;
                            }
                        } else if (this.packet.stream_index() == this.audioStreamIndex) {
                            this.processAudioFrame();
                        }
                    } finally {
                        avcodec.av_packet_unref(this.packet);
                    }
                } else {
                    // End of stream reached
                    if (this.repeat) {
                        if (avformat.av_seek_frame(this.ffmpegInstance, -1, 0, avformat.AVSEEK_FLAG_BACKWARD) < 0) {
                            LOGGER.error(IT, "Failed to seek to the beginning of the stream");
                            this.status = Status.STOPPED;
                        }
                    } else {
                        this.status = Status.STOPPED;
                    }
                }
            } else {
                // Paused - sleep briefly to avoid busy waiting
                try {
                    Thread.sleep(10);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private boolean shouldSkipFrame() {
        final long currentTime = System.currentTimeMillis();
        final long playbackTime = currentTime - this.playbackStartTime;

        // Convert frame timestamp to milliseconds
        final long frameTimestamp = this.packet.pts() * this.videoTimeBase.num() * 1000L / this.videoTimeBase.den();

        // Calculate how much we're ahead or behind
        final long timeDrift = frameTimestamp - playbackTime;

        // Skip frame if we're significantly behind schedule
        return timeDrift < -FRAME_SYNC_THRESHOLD_MS;
    }

    private void processVideoFrame() {
        if (avcodec.avcodec_send_packet(this.codecContext, this.packet) != 0) {
            return;
        }

        while (avcodec.avcodec_receive_frame(this.codecContext, this.frame) == 0) {
            this.totalFrames++;
            this.lastFrameTime = System.currentTimeMillis();

            // Convert frame timestamp to milliseconds for timing
            final long frameTimestamp = this.frame.pts() * this.videoTimeBase.num() * 1000L / this.videoTimeBase.den();
            this.lastVideoTimestamp = frameTimestamp;

            // Convert frame to BGRA
            if (swscale.sws_scale(this.swsCtx, this.frame.data(), this.frame.linesize(),
                    0, this.height(), this.rgbaFrame.data(), this.rgbaFrame.linesize()) < 0) {
                continue;
            }

            // Get the actual line size for proper stride calculation
            final int lineSize = this.rgbaFrame.linesize(0);
            final int expectedLineSize = this.width() * 4; // 4 bytes per pixel (BGRA)

            // Create properly sized buffer
            final ByteBuffer frameBuffer;
            if (lineSize == expectedLineSize) {
                // No padding, direct copy
                frameBuffer = this.rgbaFrame.data(0).limit((long) this.height() * lineSize).asBuffer();
            } else {
                // Handle padding by copying line by line
                frameBuffer = ByteBuffer.allocateDirect(this.height() * expectedLineSize);
                final BytePointer sourceData = this.rgbaFrame.data(0);

                for (int y = 0; y < this.height(); y++) {
                    sourceData.position((long) y * lineSize);
                    final ByteBuffer line = sourceData.limit(sourceData.position() + expectedLineSize).asBuffer();
                    frameBuffer.put(line);
                }
                frameBuffer.rewind();
            }

            // Create a copy for thread safety
            final ByteBuffer frameBufferCopy = ByteBuffer.allocateDirect(frameBuffer.remaining());
            frameBuffer.rewind();
            frameBufferCopy.put(frameBuffer);
            frameBufferCopy.rewind();

            // Upload frame to OpenGL
            this.renderThreadEx.execute(() -> this.uploadVideoFrame(new ByteBuffer[] { frameBufferCopy }));
        }
    }

    private void processAudioFrame() {
        if (avcodec.avcodec_send_packet(this.audioCtx, this.packet) != 0) {
            return;
        }

        while (avcodec.avcodec_receive_frame(this.audioCtx, this.audioFrame) == 0) {
            // Calculate output buffer size more accurately
            final long maxOutputSamples = av_rescale_rnd(
                    swr_get_delay(this.swrCtx, this.audioCtx.sample_rate()) + this.audioFrame.nb_samples(),
                    this.audioCtx.sample_rate(), this.audioCtx.sample_rate(), AV_ROUND_UP);

            if (maxOutputSamples <= 0) {
                continue;
            }

            final int bytesPerSample = 2; // S16 = 2 bytes per sample
            final int channels = this.outputLayout.nb_channels();
            final long bufferSize = maxOutputSamples * channels * bytesPerSample;

            if (bufferSize > Integer.MAX_VALUE) {
                continue; // Skip if buffer is too large
            }

            // Allocate output buffer
            final BytePointer outputBuffer = new BytePointer(av_malloc(bufferSize));

            try {
                final PointerPointer<Pointer> outputData = new PointerPointer<>(1);
                outputData.put(0, outputBuffer);

                final int samplesConverted = swr_convert(
                        this.swrCtx,
                        outputData,
                        (int) maxOutputSamples,
                        this.audioFrame.data(),
                        this.audioFrame.nb_samples()
                );

                if (samplesConverted > 0) {
                    final int actualBufferSize = samplesConverted * channels * bytesPerSample;

                    // Create a Java ByteBuffer and copy the data
                    final ByteBuffer audioBuffer = ByteBuffer.allocateDirect(actualBufferSize);
                    outputBuffer.position(0).limit(actualBufferSize);
                    audioBuffer.put(outputBuffer.asByteBuffer());
                    audioBuffer.rewind();

                    // Upload to OpenAL
                    final int format = (channels == 1) ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
                    this.uploadAudioBuffer(audioBuffer, format, this.audioCtx.sample_rate(), channels);
                }
            } finally {
                // Free the allocated buffer
                av_free(outputBuffer);
            }
        }
    }
}