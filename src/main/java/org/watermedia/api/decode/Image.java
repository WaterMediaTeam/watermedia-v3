package org.watermedia.api.decode;

import org.watermedia.tools.DataTool;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Decoded image container. it can be animated or not
 * @param frames decoded frames in BGRA
 * @param width with of the frames, if frames varies in size always picks the widest size
 * @param height height of the frames, if frames varies in size always picks the highest size
 * @param delay the i delay of the i frame, when its 0 means that it automatically has to jump into the next frame, otherwise has to wait x time in milliseconds
 * @param duration total duration of the animation, its the sum of all the delays
 * @param repeat number of repeat times, {@link #NO_REPEAT} when the animation must be shown once, and {@link #REPEAT_FOREVER} when should be in loop
 */
public record Image(ByteBuffer[] frames, int width, int height, long[] delay, long duration, int repeat) {
    public static final int REPEAT_FOREVER = 0;
    public static final int NO_REPEAT = -1;
    public static final long[] NO_DELAY = new long[] { 1L };

    public Image {
        if (frames == null || frames.length == 0) {
            throw new IllegalArgumentException("At least one image is required");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Width and height must be positive");
        }
        if (delay == null || delay.length != frames.length) {
            throw new IllegalArgumentException("Delay array must match the number of frames");
        }
        if (duration <= 0) {
            throw new IllegalArgumentException("Total duration must be positive");
        }
        if (repeat < NO_REPEAT) {
            throw new IllegalArgumentException("Repeat must be NO_REPEAT (-1) or REPEAT_FOREVER (0) or a positive integer");
        }
    }

    public Image(final ByteBuffer[] images, final int width, final int height, final long[] delay, final int repeat) {
        this(images, width, height, delay, DataTool.sumArray(delay), repeat);
    }

    @Override
    public long duration() {
        if (this.repeat() > 0) {
            return this.duration * this.repeat();
        }
        return this.duration;
    }

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
