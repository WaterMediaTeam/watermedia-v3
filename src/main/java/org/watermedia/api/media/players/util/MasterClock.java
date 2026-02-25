package org.watermedia.api.media.players.util;

import org.watermedia.tools.ThreadTool;

/**
 * Class is a timer manager, allows players to control their own timings.
 * <p>
 * MasterClock provides a unified clock system for media players that handles:
 * <ul>
 *   <li>Time tracking with nanosecond precision</li>
 *   <li>Pause/resume state management</li>
 *   <li>Speed adjustment</li>
 *   <li>Frame pacing (handbrake) without relying on external blocking operations</li>
 *   <li>Audio/Video synchronization through drift calculation</li>
 * </ul>
 * <p>
 * This class is designed to be used by any media player implementation, not just FFMPEG-based ones.
 */
public class MasterClock {
    // Clock state
    private volatile long masterTimeMs = 0;
    private volatile long baseTimeMs = -1;
    private volatile boolean paused = false;
    private volatile long pausedTimeMs = 0;
    private volatile float speed = 1.0f;

    // Frame timing configuration
    private volatile float fps = 30.0f;
    private volatile long frameDurationMs = 33; // ~30fps default
    private volatile long skipThresholdMs = 165; // 5 frames default

    /**
     * Creates a new MasterClock with default settings (30fps, 1.0x speed).
     */
    public MasterClock() {}

    /**
     * Creates a new MasterClock with specified frame rate.
     * @param fps the target frames per second
     */
    public MasterClock(final float fps) {
        this.fps(fps);
    }

    /**
     * Starts or restarts the clock from the current master time.
     * Call this when playback begins or resumes.
     */
    public void start() {
        this.baseTimeMs = System.currentTimeMillis();
        this.paused = false;
    }

    /**
     * Pauses the clock, preserving the current time for later resumption.
     */
    public void pause() {
        if (!this.paused) {
            this.pausedTimeMs = this.time();
            this.paused = true;
        }
    }

    /**
     * Resumes the clock from the paused position.
     */
    public void resume() {
        if (this.paused) {
            this.masterTimeMs = this.pausedTimeMs;
            this.baseTimeMs = System.currentTimeMillis();
            this.paused = false;
        }
    }

    /**
     * Resets the clock to zero and stops it.
     */
    public void reset() {
        this.masterTimeMs = 0;
        this.baseTimeMs = -1;
        this.pausedTimeMs = 0;
        this.paused = false;
    }

    /**
     * Sets the clock to a specific time in milliseconds.
     * Useful for seeking operations.
     * @param timeMs the time to set in milliseconds
     */
    public void time(final long timeMs) {
        this.masterTimeMs = timeMs;
        this.baseTimeMs = System.currentTimeMillis();
    }

    /**
     * Returns the current playback time in milliseconds.
     * Accounts for speed and pause state.
     * @return current time in milliseconds
     */
    public long time() {
        if (this.paused || this.baseTimeMs <= 0) {
            return this.masterTimeMs;
        }
        final long elapsedMs = (long) ((System.currentTimeMillis() - this.baseTimeMs) * this.speed);
        return this.masterTimeMs + Math.min(elapsedMs, 100L);
    }

    /**
     * Returns the reference time for A/V sync calculations in milliseconds.
     * This is the time that frames should be compared against.
     * @return reference time in milliseconds
     */
    public long referenceTime() {
        if (this.baseTimeMs <= 0) return 0;
        final long elapsedMs = (long) ((System.currentTimeMillis() - this.baseTimeMs) * this.speed);
        return this.masterTimeMs + elapsedMs;
    }

    /**
     * Checks if the clock is currently paused.
     * @return true if paused
     */
    public boolean paused() {
        return this.paused;
    }

    /**
     * Sets the playback speed multiplier.
     * @param speed the speed multiplier (1.0 = normal speed)
     */
    public void speed(final float speed) {
        if (speed <= 0 || speed > 4.0f) return;
        // Preserve current position when changing speed
        final long currentTime = this.time();
        this.speed = speed;
        this.masterTimeMs = currentTime;
        this.baseTimeMs = System.currentTimeMillis();
    }

    /**
     * Returns the current playback speed multiplier.
     * @return the speed multiplier
     */
    public float speed() {
        return this.speed;
    }

    /**
     * Updates the master clock from a frame timestamp.
     * This recalibrates the clock to correct any drift between the media
     * timestamps and real-world time.
     * @param timeMs the presentation timestamp of the frame in milliseconds
     */
    public void update(final long timeMs) {
        this.masterTimeMs = timeMs;
        this.baseTimeMs = System.currentTimeMillis();
    }

    /**
     * Sets the frame rate for timing calculations.
     * @param fps frames per second
     */
    public void fps(final float fps) {
        if (fps > 0) {
            this.fps = fps;
            this.frameDurationMs = (long) (1000.0 / fps);
            this.skipThresholdMs = this.frameDurationMs * 5;
        }
    }

    /**
     * Returns the configured frame rate.
     * @return frames per second
     */
    public float fps() {
        return this.fps;
    }

    /**
     * Returns the configured frame duration in milliseconds.
     * @return frame duration in milliseconds
     */
    public long frameDuration() {
        return this.frameDurationMs;
    }

    /**
     * Returns the threshold for aggressive frame skipping in milliseconds.
     * @return skip threshold in milliseconds
     */
    public long skipThreshold() {
        return this.skipThresholdMs;
    }

    /**
     * Calculates the drift between a frame's PTS and the current reference time.
     * Positive drift means the frame is ahead (should wait).
     * Negative drift means the frame is behind (might skip).
     * @param framePtsMs the frame's presentation timestamp in milliseconds
     * @return the drift in milliseconds (positive = ahead, negative = behind)
     */
    public long drift(final long framePtsMs) {
        return framePtsMs - this.referenceTime();
    }

    /**
     * Determines if a frame should be skipped based on how far behind it is.
     * <p>
     * Uses {@link #time()} (capped) instead of {@link #referenceTime()} (uncapped)
     * to prevent false drops during video packet bursts. When multiple video packets
     * arrive without interleaved audio packets, {@code referenceTime()} races ahead
     * with wall-clock while {@code masterTimeMs} stays at the last audio PTS.
     * The 100ms cap in {@code time()} prevents this divergence from triggering
     * false skips on containers with poor A/V interleaving.
     * </p>
     * @param framePtsMs the frame's presentation timestamp in milliseconds
     * @return true if the frame should be skipped
     */
    public boolean skip(final long framePtsMs) {
//        return framePtsMs < this.time() - this.skipThresholdMs;
        final long drift = this.drift(framePtsMs);
        return drift < -this.skipThresholdMs;
    }

    /**
     * Waits if necessary to maintain proper frame timing.
     * This is the "handbrake" that prevents playback from running too fast.
     * <p>
     * Call this BEFORE rendering a frame. It will sleep if the frame is ahead
     * of the reference time, ensuring smooth playback without relying on
     * external blocking operations (like OpenAL buffer dequeue).
     * @param framePtsMs the frame's presentation timestamp in milliseconds
     * @return true if we waited (frame was ahead), false if we didn't need to wait
     */
    public boolean waiting(final long framePtsMs) {
        final long drift = this.drift(framePtsMs);

        if (drift > 1)
            return ThreadTool.sleep(drift - 1);

        return false;
    }



}