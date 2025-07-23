package org.watermedia.api.media.player;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avutil.AVChannelLayout;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.swresample.SwrContext;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.watermedia.tools.ThreadTool;

import java.net.URI;
import java.util.concurrent.Executor;

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

    public FFMediaPlayer(URI mrl, Thread renderThread, Executor renderThreadEx, boolean video, boolean audio) {
        super(mrl, renderThread, renderThreadEx, video, audio);
        this.ffmpegInstance = avformat.avformat_alloc_context();
        this.playerThread = ThreadTool.createStarted("FFMediaPlayer-Thread", this::tick);
    }

    private void tick() {
        while (true) {
            if (this.status() == Status.WAITING) {
                try {
                    Thread.sleep(100); // Sleep to avoid busy waiting
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break; // Exit if interrupted
                }
                continue;
            }


            // Read packets, decode frames, etc.
            // This is a placeholder for the actual implementation
        }
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
        return null;
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
