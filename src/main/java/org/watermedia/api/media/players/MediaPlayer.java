package org.watermedia.api.media.players;

import java.net.URI;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public abstract class MediaPlayer {
    protected static final Executor POOL = Executors.newScheduledThreadPool(2);
    public static final long NO_DURATION = -1;
    public static final long NO_TIME = -1;
    public static final int NO_SIZE = -1;
    public static final int NO_TEXTURE = -1;

    // MEDIA DATA
    protected final URI mrl;
    protected final Executor renderThreadEx;

    // STATE
    protected boolean repeat;

    public MediaPlayer(final URI mrl, final Executor renderThreadEx) {
        this.mrl = mrl;
        this.renderThreadEx = renderThreadEx;
    }

    public int width() {
        return NO_SIZE;
    }

    public int height() {
        return NO_SIZE;
    }

    // CONTROL
    public abstract void start();

    public abstract void startPaused();

    public abstract boolean startSync();

    public abstract boolean startSyncPaused();

    public abstract boolean resume();

    public abstract boolean pause();

    public abstract boolean pause(boolean paused);

    public abstract boolean stop();

    public abstract boolean togglePlay();

    public abstract boolean seek(long time);

    public abstract boolean skipTime(long time);

    public abstract boolean seekQuick(long time);

    public abstract boolean foward();

    public abstract boolean rewind();

    public abstract void nextFrame();

    public abstract void prevFrame();

    public abstract float speed();

    public abstract boolean speed(float speed);

    public abstract boolean repeat();

    public abstract boolean repeat(boolean repeat);

    public abstract void volume(int volume);

    public abstract int volume();

    public abstract void mute(boolean mute);

    public abstract boolean mute();

    public abstract int texture();

    // STATE
    public abstract Status status();

    @Deprecated
    public abstract boolean usable();

    public abstract boolean loading();

    public abstract boolean buffering();

    public abstract boolean ready();

    public abstract boolean paused();

    public abstract boolean playing();

    public abstract boolean stopped();

    public abstract boolean ended();

    public abstract boolean validSource();

    public abstract boolean liveSource();

    public abstract boolean canSeek();

    public abstract boolean canPause();

    public abstract boolean canPlay();

    public abstract long duration();

    public abstract long time();

    public abstract void release();

    public enum Status {
        WAITING,
        LOADING,
        BUFFERING,
        PLAYING,
        PAUSED,
        STOPPED,
        ENDED,
        ERROR;

        public static final Status[] VALUES = values();

        public static Status of(final int value) {
            return VALUES[value];
        }
    }
}
