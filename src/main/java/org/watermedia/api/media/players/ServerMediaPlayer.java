package org.watermedia.api.media.players;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.tools.ThreadTool;

import java.util.Set;
import java.util.concurrent.*;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * A headless media player that acts as a wall clock for time synchronization.
 * <p>
 * This player does not produce any audio or video output. It calculates time
 * progression purely from the system clock, allowing multiple clients to
 * synchronize their playback position against a single authoritative source.
 * <p>
 * All instances share a single daemon thread that periodically checks for
 * end-of-media conditions (repeat or transition to {@link Status#ENDED}).
 */
public final class ServerMediaPlayer extends MediaPlayer {
    private static final Marker IT = MarkerManager.getMarker(ServerMediaPlayer.class.getSimpleName());
    private static final long TICK_MS = 50;
    private static final ScheduledExecutorService TICKER = Executors.newSingleThreadScheduledExecutor(
            ThreadTool.createFactory("ServerMediaPlayer", Thread.NORM_PRIORITY - 2)
    );
    private static final Set<ServerMediaPlayer> ACTIVE = ConcurrentHashMap.newKeySet();

    static {
        TICKER.scheduleAtFixedRate(ServerMediaPlayer::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    // TIME TRACKING
    private volatile long duration;
    private volatile long accumulatedMs;      // time accumulated before the current play segment
    private volatile long segmentStartNanos;  // System.nanoTime() when current segment began
    private volatile Status status = Status.WAITING;

    public ServerMediaPlayer() {
        super();
    }

    // --- CLIENT SYNC ---

    /**
     * Synchronizes the total media duration reported by a client.
     * The last call wins when multiple clients report different values.
     * @param durationMs total duration in milliseconds (clamped to >= 0)
     */
    public void syncDuration(final long durationMs) {
        this.duration = Math.max(0, durationMs);
    }

    /**
     * Synchronizes the current playback position reported by a client.
     * If the player is currently playing, time continues advancing from this position.
     * @param timeMs the corrected playback position in milliseconds (clamped to >= 0)
     */
    public void syncTime(final long timeMs) {
        this.accumulatedMs = Math.max(0, timeMs);
        if (this.status == Status.PLAYING) {
            this.segmentStartNanos = System.nanoTime();
        }
    }

    // --- PLAYBACK CONTROLS ---

    @Override
    public void start() {
        this.accumulatedMs = 0;
        this.segmentStartNanos = System.nanoTime();
        this.status = Status.PLAYING;
        ACTIVE.add(this);
    }

    @Override
    public void startPaused() {
        this.accumulatedMs = 0;
        this.status = Status.PAUSED;
        ACTIVE.add(this);
    }

    @Override
    public boolean pause(final boolean paused) {
        if (paused) {
            if (this.status != Status.PLAYING) return false;
            this.accumulatedMs = computeTime();
            this.status = Status.PAUSED;
            return true;
        } else {
            if (this.status != Status.PAUSED) return false;
            this.segmentStartNanos = System.nanoTime();
            this.status = Status.PLAYING;
            return true;
        }
    }

    @Override
    public boolean stop() {
        if (this.status == Status.WAITING || this.status == Status.STOPPED) return false;
        this.accumulatedMs = 0;
        this.status = Status.STOPPED;
        ACTIVE.remove(this);
        return true;
    }

    @Override
    public boolean togglePlay() {
        return switch (this.status) {
            case PLAYING -> pause();
            case PAUSED  -> resume();
            case STOPPED, ENDED -> { start(); yield true; }
            default -> false;
        };
    }

    @Override
    public boolean seek(final long time) {
        if (this.status == Status.WAITING) return false;
        syncTime(time);
        return true;
    }

    @Override
    public long time() {
        return switch (this.status) {
            case PLAYING -> computeTime();
            case PAUSED, STOPPED, ENDED -> this.accumulatedMs;
            default -> 0;
        };
    }

    @Override
    public boolean skipTime(final long time) {
        return seek(time() + time);
    }

    @Override
    public boolean seekQuick(final long time) {
        return seek(time);
    }

    @Override
    public boolean foward() {
        return skipTime(5000);
    }

    @Override
    public boolean rewind() {
        return skipTime(-5000);
    }

    @Override
    public boolean speed(final float speed) {
        if (this.status == Status.PLAYING) {
            this.accumulatedMs = computeTime();
            this.segmentStartNanos = System.nanoTime();
        }
        return super.speed(speed);
    }

    @Override
    public float fps() {
        return 0;
    }

    @Override
    public long duration() {
        return this.duration;
    }

    @Override
    public Status status() {
        return this.status;
    }

    @Override
    public boolean liveSource() {
        return this.duration <= 0;
    }

    @Override
    public boolean canSeek() {
        return this.duration > 0;
    }

    @Override
    public boolean canPlay() {
        return this.status != Status.ERROR;
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
    public void release() {
        ACTIVE.remove(this);
        this.status = Status.STOPPED;
    }

    // --- INTERNAL ---

    private long computeTime() {
        final long elapsedNanos = System.nanoTime() - this.segmentStartNanos;
        final long elapsedMs = (long) (TimeUnit.NANOSECONDS.toMillis(elapsedNanos) * speed());
        return this.accumulatedMs + elapsedMs;
    }

    private static void tick() {
        for (final ServerMediaPlayer player: ACTIVE) {
            try {
                player.update();
            } catch (final Throwable t) {
                LOGGER.error(IT, "Error updating ServerMediaPlayer", t);
            }
        }
    }

    private void update() {
        if (this.status != Status.PLAYING) return;

        final long d = this.duration;
        if (d > 0 && computeTime() >= d) {
            if (repeat()) {
                this.accumulatedMs = 0;
                this.segmentStartNanos = System.nanoTime();
            } else {
                this.accumulatedMs = d;
                this.status = Status.ENDED;
                ACTIVE.remove(this);
            }
        }
    }
}
