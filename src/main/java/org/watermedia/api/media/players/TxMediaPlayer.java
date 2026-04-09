package org.watermedia.api.media.players;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.decode.DecoderAPI;
import org.watermedia.api.decode.Image;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.engines.GFXEngine;
import org.watermedia.tools.NetTool;
import org.watermedia.tools.NetTool.Request;
import org.watermedia.tools.ThreadTool;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Media player for static and animated images (PNG, APNG, GIF, WebP).
 * <p>
 * Single lifecycle thread: LOADING (fetch) → BUFFERING (decode) → upload.
 * <ul>
 *   <li><b>Single-frame:</b> upload once, set PLAYING, thread exits.
 *       Texture persists in GL — no thread waste.</li>
 *   <li><b>Multi-frame:</b> upload first frame, enter animation loop
 *       with per-frame delay and real seek support.</li>
 * </ul>
 * Designed for future streaming decoder (reader → decoder → upload)
 * where the first frame is shown as soon as it's available.
 */
public final class TxMediaPlayer extends MediaPlayer {
    private static final Marker IT = MarkerManager.getMarker(TxMediaPlayer.class.getSimpleName());

    // DECODED DATA
    private volatile Image images = null;
    private volatile boolean animated = false;

    // STATUS
    private volatile Status status = Status.WAITING;
    private volatile float speed = 1.0f;

    // LIFECYCLE THREAD (ONLY ALIVE DURING FETCH + ANIMATION LOOP)
    private volatile Thread lifecycleThread;

    // TRIGGERS (CALLER → LIFECYCLE THREAD)
    private volatile boolean paused = false;
    private volatile boolean triggerStop = false;
    private volatile boolean triggerPause = false;
    private volatile boolean triggerResume = false;
    private volatile boolean triggerNextFrame = false;
    private volatile boolean triggerPrevFrame = false;
    private volatile long seekTarget = -1;

    // TIME (LIFECYCLE THREAD WRITES, CALLER READS)
    private volatile long time = 0;

    public TxMediaPlayer(final MRL.Source source, final GFXEngine gfxEngine) {
        super(source, gfxEngine, null);
    }

    // START / STOP / RELEASE
    @Override
    public void start() {
        if (this.lifecycleThread != null && this.lifecycleThread.isAlive()) {
            this.stop();
        }
        final Thread old = this.lifecycleThread;

        this.triggerStop = false;
        this.seekTarget = -1;
        this.time = 0;

        this.lifecycleThread = new Thread(() -> {
            if (old != null) ThreadTool.join(old);
            this.lifecycle();
        }, "TxPlayer-" + Integer.toHexString(this.source.hashCode()));
        this.lifecycleThread.setDaemon(true);
        this.lifecycleThread.start();
    }

    @Override
    public void startPaused() {
        this.triggerPause = true;
        this.start();
    }

    @Override
    public boolean stop() {
        this.triggerStop = true;
        // INTERRUPT SLEEP IN ANIMATION LOOP FOR IMMEDIATE RESPONSE
        if (this.lifecycleThread != null) this.lifecycleThread.interrupt();
        return true;
    }

    @Override
    public void release() {
        this.triggerStop = true;
        if (this.lifecycleThread != null) {
            this.lifecycleThread.interrupt();
            ThreadTool.join(this.lifecycleThread);
            this.lifecycleThread = null;
        }
        this.images = null;
        super.release();
    }

    // LIFECYCLE
    private void lifecycle() {
        try {
            // LOADING: FETCH + DECODE
            this.status = Status.LOADING;
            final Image decoded = this.fetch();
            if (decoded == null) return;

            // BUFFERING: PREPARE GFX
            this.status = Status.BUFFERING;
            this.images = decoded;
            this.animated = decoded.frames().length > 1;
            this.gfx.setVideoFormat(GFXEngine.ColorSpace.BGRA, decoded.width(), decoded.height());

            // UPLOAD FIRST FRAME IMMEDIATELY
            this.gfx.upload(decoded.frames()[0], 0);

            // RESOLVE INITIAL STATE
            if (this.triggerPause) {
                this.paused = true;
                this.triggerPause = false;
                this.status = Status.PAUSED;
            } else {
                this.status = Status.PLAYING;
            }

            LOGGER.debug(IT, "Loaded: {} ({}x{}, {} frames)", this.source,
                    decoded.width(), decoded.height(), decoded.frames().length);

            // SINGLE FRAME: DONE — THREAD EXITS, TEXTURE PERSISTS IN GL
            if (!this.animated) return;

            // MULTI-FRAME: ANIMATION LOOP
            this.animationLoop();

        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            if (this.triggerStop) {
                this.status = Status.STOPPED;
            }
        } catch (final Exception e) {
            if (!Thread.currentThread().isInterrupted()) {
                LOGGER.error(IT, "Lifecycle error: {}", this.source, e);
            }
            this.status = Status.ERROR;
        }
    }

    // ANIMATION LOOP (MULTI-FRAME ONLY)
    private void animationLoop() throws InterruptedException {
        final long[] delays = this.images.delay();
        final int frameCount = this.images.frames().length;
        int frameIndex = 0;

        while (!Thread.currentThread().isInterrupted()) {
            // STOP
            if (this.triggerStop) {
                this.triggerStop = false;
                this.status = Status.STOPPED;
                return;
            }

            // PAUSE / RESUME
            if (this.triggerPause && !this.paused) {
                this.paused = true;
                this.triggerPause = false;
                this.status = Status.PAUSED;
            }
            if (this.triggerResume && this.paused) {
                this.paused = false;
                this.triggerResume = false;
                this.status = Status.PLAYING;
            }

            // SEEK (WORKS BOTH PAUSED AND PLAYING)
            final long target = this.seekTarget;
            if (target >= 0) {
                this.seekTarget = -1;
                frameIndex = frameIndexAt(delays, target);
                this.time = timeAtFrame(delays, frameIndex);
                this.gfx.upload(this.images.frames()[frameIndex], 0);
                if (this.paused) {
                    ThreadTool.sleep(10);
                    continue;
                }
            }

            // PAUSED: FRAME STEPPING
            if (this.paused) {
                if (this.triggerNextFrame) {
                    this.triggerNextFrame = false;
                    if (frameIndex + 1 < frameCount) {
                        frameIndex++;
                        this.time = timeAtFrame(delays, frameIndex);
                        this.gfx.upload(this.images.frames()[frameIndex], 0);
                    }
                }
                if (this.triggerPrevFrame) {
                    this.triggerPrevFrame = false;
                    if (frameIndex > 0) {
                        frameIndex--;
                        this.time = timeAtFrame(delays, frameIndex);
                        this.gfx.upload(this.images.frames()[frameIndex], 0);
                    }
                }
                ThreadTool.sleep(10);
                continue;
            }

            // SLEEP FOR CURRENT FRAME DELAY
            if (this.images == null) return;
            final long delayMs = Math.max(1, (long) (delays[frameIndex] / this.speed));
            ThreadTool.sleep(delayMs);

            // RE-CHECK AFTER SLEEP (SEEK/STOP MIGHT HAVE ARRIVED)
            if (this.seekTarget >= 0 || this.triggerStop) continue;

            // ADVANCE
            this.time += delays[frameIndex];
            frameIndex++;

            if (frameIndex >= frameCount) {
                if (this.repeat()) {
                    frameIndex = 0;
                    this.time = 0;
                } else {
                    this.status = Status.ENDED;
                    return;
                }
            }

            // UPLOAD NEXT FRAME
            if (this.images != null) {
                this.gfx.upload(this.images.frames()[frameIndex], 0);
            }
        }
    }

    // SEEK HELPERS
    // RETURNS THE FRAME INDEX VISIBLE AT THE GIVEN TIME. WALKS THE DELAY ARRAY: FRAME I IS VISIBLE FROM SUM(0..I-1) TO SUM(0..I).
    private static int frameIndexAt(final long[] delays, final long timeMs) {
        long accumulated = 0;
        for (int i = 0; i < delays.length; i++) {
            accumulated += delays[i];
            if (timeMs < accumulated) return i;
        }
        return delays.length - 1;
    }

    // RETURNS THE START TIME (MS) OF THE GIVEN FRAME INDEX.
    private static long timeAtFrame(final long[] delays, final int frameIndex) {
        long t = 0;
        for (int i = 0; i < frameIndex; i++) {
            t += delays[i];
        }
        return t;
    }

    // FETCH
    private Image fetch() {
        var uri = this.source.uri(this.quality);
        Request request = null;

        for (int redirects = 0; redirects < 6; redirects++) {
            try {
                request = new Request(uri, "GET", null);
                final int code = request.getResponseCode();
                final String type = request.getContentType();

                if (code >= 300 && code < 400) {
                    final String location = request.getHeader("Location");
                    request.close();
                    LOGGER.warn(IT, "Redirect: {} → {}", uri, location);
                    uri = URI.create(location);
                    if (redirects == 5) throw new IOException("Too many redirects");
                    continue;
                }

                final InputStream in = request.getInputStream();
                if (in == null) {
                    request.close();
                    throw new IOException("No input stream (code " + code + ")");
                }

                NetTool.validateHTTP200(code, uri);
                if (type == null || !type.startsWith("image/")) {
                    request.close();
                    throw new IllegalArgumentException("Invalid content type: " + type);
                }

                final Image result = DecoderAPI.decodeImage(in.readAllBytes());
                request.close();

                if (result == null || result.frames() == null || result.frames().length == 0) {
                    throw new IOException("No frames decoded from: " + this.source);
                }
                return result;
            } catch (final Exception e) {
                LOGGER.error(IT, "Fetch failed: {}", this.source, e);
                if (request != null) request.close();
                this.status = Status.ERROR;
                return null;
            }
        }

        this.status = Status.ERROR;
        return null;
    }

    // CONTROLS
    @Override
    public boolean pause() { return this.pause(true); }

    @Override
    public boolean resume() { return this.pause(false); }

    @Override
    public boolean pause(final boolean paused) {
        // SINGLE-FRAME: JUST FLIP STATUS (NO THREAD NEEDED)
        if (!this.animated) {
            this.paused = paused;
            this.status = paused ? Status.PAUSED : Status.PLAYING;
            return true;
        }
        if (paused) { this.triggerPause = true; this.triggerResume = false; }
        else { this.triggerResume = true; this.triggerPause = false; }
        return true;
    }

    @Override
    public boolean togglePlay() {
        return this.paused() ? this.resume() : this.pause();
    }

    @Override
    public boolean seek(final long time) {
        if (this.images == null || this.images.frames() == null) return false;
        final long duration = this.images.duration();
        if (duration <= 0) return false;
        this.seekTarget = Math.max(0, Math.min(time, duration - 1));
        return true;
    }

    @Override
    public boolean seekQuick(final long time) { return this.seek(time); }

    @Override
    public boolean skipTime(final long time) { return this.seek(this.time + time); }

    @Override
    public boolean foward() { return this.skipTime(1000); }

    @Override
    public boolean rewind() { return this.skipTime(-1000); }

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

    // STATE QUERIES
    @Override public Status status() { return this.status; }
    @Override public long time() { return this.time; }
    @Override public float fps() { return this.images.delay()[0]; }
    @Override public float speed() { return this.speed; }
    @Override public boolean liveSource() { return false; }
    @Override public boolean canSeek() { return this.animated && this.duration() > 0; }

    @Override
    public boolean canPlay() {
        return this.images != null && this.images.frames() != null && this.images.frames().length > 0;
    }

    @Override
    public long duration() {
        return this.images == null ? 0 : this.images.duration();
    }

    @Override
    public boolean speed(final float speed) {
        if (speed <= 0 || speed > 4.0f) return false;
        this.speed = speed;
        return true;
    }
}