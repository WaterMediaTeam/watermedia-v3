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
import org.bytedeco.javacpp.PointerPointer;
import org.lwjgl.opengl.GL12;
import org.watermedia.tools.ThreadTool;

import java.net.URI;
import java.util.concurrent.Executor;

import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16;
import static org.bytedeco.ffmpeg.global.avutil.av_opt_set_int;
import static org.bytedeco.ffmpeg.global.avutil.av_opt_set_sample_fmt;
import static org.bytedeco.ffmpeg.global.swresample.swr_alloc;
import static org.bytedeco.ffmpeg.global.swresample.swr_init;
import static org.watermedia.WaterMedia.LOGGER;

public class FFMediaPlayer extends MediaPlayer {
    private static final Marker IT = MarkerManager.getMarker("FFMPEG-MediaPlayer");

    private final AVFormatContext ffmpegInstance;
    private final Thread playerThread;

    // MEDIA DATA
    private AVPacket packet;
    private AVFrame frame;
    private AVFrame rgbaFrame;
    private AVFrame audioFrame;
    private SwsContext swsCtx;
    private AVCodecContext codecContext;
    private AVCodecContext audioCtx;

    // PLAYER STATE
    private int videoIndex = -1;
    private int audioIndex = -1;
    private AVRational videoTimeBase;
    private AVRational audioTimeBase;
    private BytePointer buffer;
    private SwrContext swrCtx;
    private AVChannelLayout outputLayout;

    // PLAYER FLAGS
    private boolean triggerStart;
    private boolean triggerStop;
    private boolean triggerPause;


    // PLAYER STATUS
    private Status status;
    private boolean loop;
    private boolean seekable;
    private double frameRate;

    public FFMediaPlayer(URI mrl, Thread renderThread, Executor renderThreadEx, boolean video, boolean audio) {
        super(mrl, renderThread, renderThreadEx, video, audio);
        this.ffmpegInstance = avformat.avformat_alloc_context();
        this.playerThread = ThreadTool.createStartedLoop("FFMediaPlayer-Thread", this::tick);
    }

    private void tick() {
        switch (this.status()) {
            case WAITING, ERROR -> {
                ThreadTool.handBreak(500);
                if (!this.triggerStart) break;
                this.triggerStart = false;

                if (avformat.avformat_open_input(this.ffmpegInstance, this.mrl.toString(), null, null) < 0) {
                    LOGGER.error(IT, "Couldn't open video file");
                    this.status = Status.ERROR;
                    break;
                }

                if (avformat.avformat_find_stream_info(this.ffmpegInstance, (PointerPointer<?>) null) < 0) {
                    LOGGER.error(IT, "Couldn't retrieve stream info");
                    this.status = Status.ERROR;
                    break;
                }

                this.videoIndex = -1;
                this.audioIndex = -1;

                for (int i = 0; i < this.ffmpegInstance.nb_streams(); i++) {
                    switch (this.ffmpegInstance.streams(i).codecpar().codec_type()) {
                        case avutil.AVMEDIA_TYPE_VIDEO -> {
                            if (this.videoIndex == -1) {
                                this.videoIndex = i;
                            }
                        }
                        case avutil.AVMEDIA_TYPE_AUDIO -> {
                            if (this.audioIndex == -1) {
                                this.audioIndex = i;
                            }
                        }
                        default -> {
                            // TODO: SUPPORT SUBTITLES
                        }
                    }

                    if (this.videoIndex == -1 || this.audioIndex == -1) {
                        LOGGER.error(IT, "No audio nor video found in file");
                        this.status = Status.ERROR;
                        break;
                    }

                    // LOAD VIDEO, PREPARE THE CANNONS
                    if (this.videoIndex != -1) {
                        final AVStream videoStream = this.ffmpegInstance.streams(this.videoIndex);
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

                    // PROCESS AUDIO
                    if (this.audioIndex != -1) {
                        final AVStream audioStream = this.ffmpegInstance.streams(this.audioIndex);
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
                }
            }
            case PLAYING -> {

            }
            case PAUSED -> {

            }
            case STOPPED -> {
            }
            case ENDED -> {
                if (avformat.av_seek_frame(this.ffmpegInstance, -1, 1000, avformat.AVSEEK_FLAG_BACKWARD) >= 0) {
                    this.loop = false;
                    LOGGER.info(IT, "Was not possible ");

                }
            }
        }


        // Read packets, decode frames, etc.
        // This is a placeholder for the actual implementation
    }

    @Override
    public boolean previousFrame() {
        return false;
    }

    @Override
    public boolean nextFrame() {
        return false;
    }

    @Override
    public void start() {

    }

    @Override
    public void startPaused() {

    }

    @Override
    public boolean resume() {
        return false;
    }

    @Override
    public boolean pause() {
        return false;
    }

    @Override
    public boolean pause(boolean paused) {
        return false;
    }

    @Override
    public boolean stop() {
        return false;
    }

    @Override
    public boolean togglePlay() {
        return false;
    }

    @Override
    public boolean seek(long time) {
        return false;
    }

    @Override
    public boolean skipTime(long time) {
        return false;
    }

    @Override
    public boolean seekQuick(long time) {
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
        return 0;
    }

    @Override
    public boolean speed(float speed) {
        return false;
    }

    @Override
    public Status status() {
        return this.status;
    }

    @Override
    public boolean validSource() {
        return false;
    }

    @Override
    public boolean liveSource() {
        return false;
    }

    @Override
    public boolean canSeek() {
        return false;
    }

    @Override
    public boolean canPause() {
        return false;
    }

    @Override
    public boolean canPlay() {
        return false;
    }

    @Override
    public long duration() {
        return 0;
    }

    @Override
    public long time() {
        return 0;
    }
}
