package org.watermedia.api.media.players;

import org.watermedia.api.media.platforms.ALEngine;
import org.watermedia.api.media.platforms.GLEngine;

import java.net.URI;
import java.util.concurrent.Executor;

public final class ClockMediaPlayer extends MediaPlayer {

    public ClockMediaPlayer(final URI mrl, final Thread renderThread, final Executor renderThreadEx, final GLEngine glEngine, final ALEngine alEngine) {
        super(mrl, renderThread, renderThreadEx, glEngine, alEngine, false, false);
    }

    @Override
    protected void updateMedia() {

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
    public boolean stop() {
        return false;
    }

    @Override
    public boolean togglePlay() {
        return false;
    }

    @Override
    public boolean seek(final long time) {
        return false;
    }

    @Override
    public long time() {
        return 0;
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
        return 0;
    }

    @Override
    public boolean speed(final float speed) {
        return false;
    }

    @Override
    public long duration() {
        return 0;
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
}
