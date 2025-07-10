package org.watermedia.api.media.player;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

public final class PicturePlayer extends MediaPlayer {

    public final int width;
    public final int height;
    public final int[] textures;
    public final long[] delay;
    public final long duration;
    private ByteBuffer[] images;

    public boolean flushed;
    public int remaining;

    public PicturePlayer(URI mrl, Thread renderThread, Executor renderThreadEx, boolean video, boolean audio) {
        super(mrl, renderThread, renderThreadEx, video, audio);

    }

    @Override
    public void previousFrame() {

    }

    @Override
    public void nextFrame() {

    }

    @Override
    public void start() {

    }

    @Override
    public void startPaused() {

    }

    @Override
    public boolean startSync() {
        return false;
    }

    @Override
    public boolean startSyncPaused() {
        return false;
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
    public boolean usable() {
        return false;
    }

    @Override
    public boolean loading() {
        return false;
    }

    @Override
    public boolean buffering() {
        return false;
    }

    @Override
    public boolean ready() {
        return false;
    }

    @Override
    public boolean paused() {
        return false;
    }

    @Override
    public boolean playing() {
        return false;
    }

    @Override
    public boolean stopped() {
        return false;
    }

    @Override
    public boolean ended() {
        return false;
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

    @Override
    public void release() {
        super.release();
    }
}
