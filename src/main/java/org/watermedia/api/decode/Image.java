package org.watermedia.api.decode;

import org.watermedia.tools.DataTool;

import java.nio.ByteBuffer;
import java.util.Arrays;

public record Image(ByteBuffer[] frames, int width, int height, long[] delay, long duration, int repeat) {
    public static final int REPEAT_FOREVER = 0;
    public static final int NO_REPEAT = -1;

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
