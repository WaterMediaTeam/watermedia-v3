package org.watermedia.api.media.player;

import org.watermedia.tools.ThreadTool;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public final class PicturePlayer extends MediaPlayer {
    // OFF-THREAD TASKS
    private static final Thread PLAYER_THREAD = ThreadTool.createStarted("PicturePlayerThread", PicturePlayer::tick);
    private static final Executor FETCH_EXECUTOR = Executors.newScheduledThreadPool(ThreadTool.minThreads());

    // REGISTRY
    private static final Set<PicturePlayer> ACTIVE_PLAYERS = new HashSet<>();

    // TICKING
    private static long lastTick = System.currentTimeMillis();

    // INITIALIZE THE PLAYER THREAD
    static {
        PLAYER_THREAD.start();
    }

    // MEDIA DATA
    private final long duration = 12;
    private final long[] delay = null;
    private ByteBuffer[] images;

    // PLAYER STATE
    private long time = 0;
    private int lastFrameIndex = -1; // Last frame index to avoid unnecessary updates
    private boolean repeat = false;
    private Status status = Status.WAITING;


    public boolean flushed;

    public PicturePlayer(URI mrl, Thread renderThread, Executor renderThreadEx, boolean video) {
        super(mrl, renderThread, renderThreadEx, video, false);
        ACTIVE_PLAYERS.add(this);

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
    public boolean loading() {
        return false;
    }

    @Override
    public boolean buffering() {
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
        return this.time >= this.duration;
    }

    @Override
    public boolean validSource() {
        return false;
    }

    @Override
    public boolean liveSource() {
        return false; // always false for picture players
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
        return this.duration;
    }

    @Override
    public long time() {
        return this.time;
    }

    @Override
    public void release() {
        ACTIVE_PLAYERS.remove(this);
        super.release();
    }

    private static void tick() {
        while (!Thread.currentThread().isInterrupted()) {
            ThreadTool.handBreak(1000 / 60); // 60 FPS CAP

            for (final PicturePlayer player: ACTIVE_PLAYERS) {
                if (!player.flushed)
                    continue;

                // PREPARE TICK
                final long now = System.currentTimeMillis();
                final long delta = now - lastTick;
                final long deltaPlayer = player.time + delta;

                // PLAYER TIME
                if (deltaPlayer >= player.duration) {
                    if (player.repeat()) {
                        player.time = 0; // reset time
                    } else {
                        player.time = player.duration; // set to end
                        continue; // skip tick
                    }
                }

                // UPDATE TEXTURE
                long frameTime = deltaPlayer;
                for (int i = 0; i < player.delay.length; i++) {
                    frameTime -= player.delay[i];
                    if (frameTime <= 0) {
                        if (player.lastFrameIndex != i) { // only update if the frame has changed
                            player.lastFrameIndex = i;
                            player.time = deltaPlayer; // update time
                            player.uploadVideoFrame(player.images[i]);
                            break;
                        }
                    }
                }
            }

            lastTick = System.currentTimeMillis();
        }
    }
}
