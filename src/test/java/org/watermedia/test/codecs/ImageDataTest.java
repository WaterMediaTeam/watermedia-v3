package org.watermedia.test.codecs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.codecs.ImageData;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the {@link ImageData} duration contract: {@code duration()} is the transparent single-cycle
 * sum (equal to the stored component, so a rebuild from accessors does not compound), while
 * {@code totalDuration()} carries the repeat-aware total.
 */
@DisplayName("ImageData duration")
public class ImageDataTest {

    private static ImageData single(final long duration, final int repeat) {
        final ByteBuffer[] frames = { ByteBuffer.allocate(4) };
        return new ImageData(frames, 1, 1, new long[] { duration }, duration, repeat);
    }

    @Test
    @DisplayName("duration() is transparent and survives a round-trip")
    void testDurationTransparent() {
        final ImageData data = single(100L, 3);
        assertEquals(100L, data.duration(), "duration() must return the stored single-cycle sum");

        // REBUILDING FROM ACCESSORS MUST NOT COMPOUND duration BY repeat
        final ImageData copy = new ImageData(data.frames(), data.width(), data.height(),
                data.delay(), data.duration(), data.repeat());
        assertEquals(100L, copy.duration());
    }

    @Test
    @DisplayName("totalDuration() is repeat-aware with a one-cycle floor")
    void testDuration() {
        assertEquals(300L, single(100L, 3).duration(), "positive repeat multiplies the cycle");
        assertEquals(100L, single(100L, ImageData.REPEAT_FOREVER).duration(), "loop-forever floors at one cycle");
        assertEquals(100L, single(100L, ImageData.NO_REPEAT).duration(), "play-once floors at one cycle");
    }

    @Test
    @DisplayName("Convenience constructor sums the per-frame delays")
    void testConvenienceCtorSumsDelays() {
        final ByteBuffer[] frames = { ByteBuffer.allocate(4), ByteBuffer.allocate(4) };
        final ImageData data = new ImageData(frames, 1, 1, new long[] { 40L, 60L }, ImageData.NO_REPEAT);
        assertEquals(100L, data.duration(), "duration must be the sum of the per-frame delays");
    }
}
