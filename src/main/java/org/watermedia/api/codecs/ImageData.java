package org.watermedia.api.codecs;

import org.jetbrains.annotations.NotNull;
import org.watermedia.tools.DataTool;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Decoded image container, animated or static.
 *
 * @param frames   decoded frames in BGRA
 * @param width    frame width; the widest frame when frames differ in size
 * @param height   frame height; the tallest frame when frames differ in size
 * @param delay    per-frame delay in milliseconds; {@code 0} advances to the next frame immediately
 * @param duration single-cycle playback time, the sum of every entry in {@code delay}; use
 *                 {@link #duration()} for the repeat-aware value
 * @param repeat   repeat count: {@link #NO_REPEAT} to play once, {@link #REPEAT_FOREVER} to loop
 *                 forever, or a positive number of extra loops
 */
public record ImageData(ByteBuffer[] frames, int width, int height, long[] delay, long duration, int repeat) {
    public static final int REPEAT_FOREVER = 0;
    public static final int NO_REPEAT = -1;
    public static final long[] NO_DELAY = new long[] { 0L };

    /**
     * Lightweight scan result produced by an {@link ImageReader} at construction time.
     * Carries the animation shape (frame count, per-frame delays, total duration, loop count)
     * without holding any decoded pixel data.
     */
    public record Scan(int frameCount, long[] delays, long duration, int loopCount) {
        public static final Scan EMPTY = new Scan(1, NO_DELAY, 0L, NO_REPEAT);
    }

    public ImageData {
        if (frames == null || frames.length == 0) {
            throw new IllegalArgumentException("At least one image is required");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Width and height must be positive");
        }
        if (delay == null || delay.length != frames.length) {
            throw new IllegalArgumentException("Delay array must match the number of frames");
        }
        if (duration < 0) {
            throw new IllegalArgumentException("Total duration must not be negative");
        }
        if (repeat < NO_REPEAT) {
            throw new IllegalArgumentException("Repeat must be NO_REPEAT (-1) or REPEAT_FOREVER (0) or a positive integer");
        }
    }

    public ImageData(final ByteBuffer[] images, final int width, final int height, final long[] delay, final int repeat) {
        this(images, width, height, delay, DataTool.sumArray(delay), repeat);
    }

    @Override
    public long duration() {
        return this.duration(false);
    }

    /** Repeat-aware total playback time: one cycle multiplied by the loop count (minimum one cycle). */
    public long duration(final boolean loopAware) {
        return this.duration * (loopAware ? Math.max(1, this.repeat) : 1);
    }

    @NotNull
    @Override
    public String toString() {
        return "Image{" +
                "frames=" + Arrays.toString(this.frames) +
                ", width=" + this.width +
                ", height=" + this.height +
                ", delay=" + Arrays.toString(this.delay) +
                ", duration=" + this.duration +
                ", repeat=" + this.repeat +
                '}';
    }
}
