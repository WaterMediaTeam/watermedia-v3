package org.watermedia.api.media;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.lwjgl.opengl.GL12;
import org.watermedia.api.decode.DecoderAPI;
import org.watermedia.api.decode.Image;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.NetTool;
import org.watermedia.tools.ThreadTool;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.watermedia.WaterMedia.LOGGER;

public final class PicturePlayer extends MediaPlayer {
    private static final Marker IT = MarkerManager.getMarker(PicturePlayer.class.getSimpleName());
    private static final Thread PLAYER_THREAD = ThreadTool.createStartedLoop("PicturePlayerThread", PicturePlayer::playerLoop);
    private static final ScheduledExecutorService FETCH_EXECUTOR = Executors.newScheduledThreadPool(ThreadTool.minThreads());
    private static final ConcurrentLinkedQueue<PicturePlayer> ACTIVE_PLAYERS = new ConcurrentLinkedQueue<>();

    // PLAYER STATE
    private volatile Image images = null;
    private boolean paused = false;
    private float speed = 1.0f; // Default speed is 1.0x
    private volatile Status status = Status.WAITING;
    private volatile boolean triggerStop = false;
    private volatile boolean triggerResume = false;
    private volatile boolean triggerPause = false;
    private volatile boolean triggerNextFrame = false;
    private volatile boolean triggerPrevFrame = false;

    // TIME
    private volatile long time = 0;
    private int lastFrameIndex = -1; // Last frame index to avoid unnecessary updates
    private static long lastTick = System.currentTimeMillis();

    public PicturePlayer(final URI mrl, final Thread renderThread, final Executor renderThreadEx, final boolean video) {
        super(mrl, renderThread, renderThreadEx, video, false);
    }

    @Override
    public void start() {
        FETCH_EXECUTOR.execute(this::fetchImage);
    }

    @Override
    public void startPaused() {
        this.start();
        this.triggerPause = true;
    }

    private void fetchImage() {
        this.status = Status.LOADING; // Set status to loading
        try (final NetTool.Request request = new NetTool.Request(this.mrl.toURL(), "GET", null)) {
            final String type = request.getContentType();
            if (type == null || !type.startsWith("image/")) {
                throw new IllegalArgumentException("Invalid media type: " + type);
            } else {
                LOGGER.debug(IT, "Fetching image from: {} with content type {}", this.mrl, type);
            }

            final int code = request.getResponseCode();
            final boolean valid = NetTool.validateHTTP200(code, this.mrl);
            if (valid) {
                this.status = Status.BUFFERING;
            }

            LOGGER.debug(IT, "Server Response code: {}, running decoding", code);
            this.images = DecoderAPI.decodeImage(IOTool.readAllBytes(request.getInputStream()));
            this.status = this.triggerPause ? Status.PAUSED : Status.PLAYING;

            if (this.images == null || this.images.frames() == null || this.images.frames().length == 0) {
                throw new IOException("No frames found in the media: " + this.mrl);
            }

            this.setVideoFormat(GL12.GL_BGRA, this.images.width(), this.images.height());

            ACTIVE_PLAYERS.add(this);
            LOGGER.debug(IT, "Successfully fetched image: {} with dimensions {}x{} and delay {}", this.mrl, this.images.width(), this.images.height(), this.images.delay());
        } catch (final Throwable e) {
            LOGGER.error(IT, "Failed to open media: {}", this.mrl, e);
            this.status = Status.ERROR;
        }
    }

    @Override
    public boolean previousFrame() {
        if (!this.paused()) return false;
        this.triggerPrevFrame = true;
        return true;
    }

    @Override
    public boolean nextFrame() {
        if (!this.paused()) return false;
        this.triggerNextFrame = true;
        return true;
    }

    @Override
    public boolean resume() {
        return this.pause(false);
    }

    @Override
    public boolean pause() {
        return this.pause(true);
    }

    @Override
    public boolean pause(final boolean paused) {
        if (paused) {
            this.triggerPause = true;
            this.triggerResume = false;
        } else {
            this.triggerResume = true;
            this.triggerPause = false;
        }
        return true; // Always return true as we can toggle pause
    }

    @Override
    public boolean stop() {
        this.triggerStop = true;
        return true;
    }

    @Override
    public boolean togglePlay() {
        return this.paused() ? this.resume() : this.pause();
    }

    @Override
    public boolean seek(final long time) {
        if (this.images == null || this.images.frames() == null || this.images.frames().length == 0) {
            LOGGER.warn(IT, "Cannot seek, no frames available for: {}", this.mrl);
            return false;
        }
        if (time < 0 || time > this.images.duration()) {
            LOGGER.warn(IT, "Seek time out of bounds for: {}. Time: {}, Duration: {}", this.mrl, time, this.images.duration());
            return false;
        }
        this.time = time;
        this.lastFrameIndex = -1; // Reset last frame index to force update on next
        return true;
    }

    @Override
    public boolean seekQuick(final long time) {
        return this.seek(time);
    }

    @Override
    public boolean skipTime(final long time) {
        return this.seek(this.duration() + time);
    }

    @Override
    public boolean foward() {
        return this.seek(this.duration() + 1000);
    }

    @Override
    public boolean rewind() {
        return this.seek(this.duration() - 1000);
    }

    @Override
    public float speed() {
        return this.speed;
    }

    @Override
    public boolean speed(final float speed) {
        this.speed = speed;
        return true;
    }

    @Override
    public Status status() {
        return this.status;
    }

    @Override
    public boolean validSource() {
        return this.images != null && this.images.frames() != null && this.images.frames().length > 0;
    }

    @Override
    public boolean liveSource() {
        return false; // always false for picture players
    }

    @Override
    public boolean canSeek() {
        return this.duration() > 1000; // can seek if the duration is greater than 1 second
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
        return this.images.duration();
    }

    @Override
    public long time() {
        return this.time;
    }

    @Override
    public void release() {
        ACTIVE_PLAYERS.remove(this);
        this.images = null;
        super.release();
    }

    private static void playerLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            ThreadTool.sleep(10); // 100 FPS CAP


            for (final PicturePlayer player: ACTIVE_PLAYERS) {
                if (player.triggerPause && !player.paused) {
                    player.paused = true;
                    player.triggerPause = false;
                    player.status = Status.PAUSED;
                }

                if (player.triggerResume && player.paused) {
                    player.paused = false;
                    player.triggerResume = false;
                    player.status = Status.PLAYING;
                }

                if (player.triggerStop) {
                    player.release();
                    continue;
                }

                final boolean detained = player.stopped() || player.ended() || player.paused() || player.error();
                if (detained && !player.triggerNextFrame && !player.triggerPrevFrame)
                    continue;

                if (player.images == null) {
                    continue; // IGNORE PLAYER WITH NO IMAGES (released)
                }

                // PREPARE TICK
                final long now = System.currentTimeMillis();
                final long delta = now - lastTick;
                final long currentTime = detained ? player.time : player.time + (long) (delta * player.speed);

                // PLAYER TIME
                if (currentTime >= player.images.duration()) {
                    if (player.repeat()) {
                        player.time = 0; // reset time
                    } else {
                        player.time = player.images.duration(); // set to end
                        player.status = Status.ENDED;
                        continue; // skip tick
                    }
                }

                // UPDATE TEXTURE
                long frameTime = currentTime;
                final long[] delays = player.images.delay();
                for (int i = 0; i < delays.length; i++) {
                    frameTime -= delays[i]; // CALCULATE CURRENT INDEX BASED SUBSTRACTING DELAYS
                    if (frameTime <= 0) {

                        // FRAME SKIPPER
                        if (detained && player.triggerNextFrame) { // IF NEXT FRAME AND STILL MATCHES CURRENT, SKIP NEW ONE
                            player.triggerNextFrame = false;
                            player.triggerPrevFrame = false; // JUST IN CASE

                            if (i + 1 >= delays.length) break; // DO NO GO FUTHER
                            i++;
                        }

                        // FRAME SKIPPER
                        if (detained && player.triggerPrevFrame) { // IF NEXT FRAME AND STILL MATCHES CURRENT, SKIP NEW ONE
                            player.triggerNextFrame = false;
                            player.triggerPrevFrame = false; // JUST IN CASE

                            if (i - 1 <= 0) break; // DO NO GO FUTHER
                            i--;
                        }

                        // FRAME UPDATER
                        if (player.lastFrameIndex != i) { // only update if the frame has changed
                            player.lastFrameIndex = i;
                            player.uploadVideoFrame(player.images.frames()[i], 0);
                        }
                        player.time = currentTime; // update time
                        break;
                    }
                }
            }

            lastTick = System.currentTimeMillis();
        }
    }
}