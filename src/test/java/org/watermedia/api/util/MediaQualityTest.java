package org.watermedia.api.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@link MediaQuality} classification, navigation and lookup helpers.
 */
@DisplayName("MediaQuality")
public class MediaQualityTest {

    @Nested
    @DisplayName("of(width, height)")
    class OfDimensionsTests {

        @Test
        @DisplayName("1920x1080 maps to HIGHER")
        void hd1080() {
            assertSame(MediaQuality.HIGHER, MediaQuality.of(1920, 1080));
        }

        @Test
        @DisplayName("3840x2160 maps to ULTRA")
        void uhd2160() {
            assertSame(MediaQuality.ULTRA, MediaQuality.of(3840, 2160));
        }

        @Test
        @DisplayName("640x360 maps to LOW")
        void sd360() {
            assertSame(MediaQuality.LOW, MediaQuality.of(640, 360));
        }

        @Test
        @DisplayName("0x0 maps to UNKNOWN")
        void allZero() {
            assertSame(MediaQuality.UNKNOWN, MediaQuality.of(0, 0));
            assertSame(MediaQuality.UNKNOWN, MediaQuality.of(-1, -1));
        }

        @Test
        @DisplayName("known sizes below 144 map to LOWEST")
        void tinySizesAreLowest() {
            assertSame(MediaQuality.LOWEST, MediaQuality.of(1, 1));
            assertSame(MediaQuality.LOWEST, MediaQuality.of(256, 143));
            assertSame(MediaQuality.LOWEST, MediaQuality.of(0, 64));
            assertSame(MediaQuality.LOWEST, MediaQuality.of(256, 144));
            assertSame(MediaQuality.LOWER, MediaQuality.of(426, 240));
        }

        @Test
        @DisplayName("negative width is treated as missing dimension")
        void negativeWidthFallsBackToHeight() {
            // WIDTH <= 0 SO HEIGHT IS USED DIRECTLY
            assertSame(MediaQuality.HIGHER, MediaQuality.of(-5, 1080));
        }

        @Test
        @DisplayName("zero height falls back to width")
        void zeroHeightFallsBackToWidth() {
            assertSame(MediaQuality.HIGH, MediaQuality.of(720, 0));
        }

        @Test
        @DisplayName("portrait uses the smaller dimension")
        void portraitUsesSmallerSide() {
            // MATH.MIN(1080, 1920) == 1080 -> HIGHER
            assertSame(MediaQuality.HIGHER, MediaQuality.of(1080, 1920));
        }
    }

    @Nested
    @DisplayName("closest(set, preferred)")
    class ClosestTests {

        @Test
        @DisplayName("returns preferred when available")
        void exactMatch() {
            final Set<MediaQuality> available = EnumSet.of(MediaQuality.MEDIUM, MediaQuality.HIGHER, MediaQuality.HIGH);
            assertSame(MediaQuality.HIGHER, MediaQuality.closest(available, MediaQuality.HIGHER));
        }

        @Test
        @DisplayName("returns closest available when preferred is missing")
        void picksClosestNeighbour() {
            final Set<MediaQuality> available = EnumSet.of(MediaQuality.HIGH, MediaQuality.HIGHEST);
            // PREFERRED HIGHER -> NEIGHBOURS HIGH (LOWER) AND HIGHEST, BOTH ARE 1 STEP AWAY.
            // LOWER WINS BECAUSE TIES BREAK TOWARD THE LOWER QUALITY.
            assertSame(MediaQuality.HIGH, MediaQuality.closest(available, MediaQuality.HIGHER));
        }

        @Test
        @DisplayName("empty set returns null")
        void emptyReturnsNull() {
            assertNull(MediaQuality.closest(EnumSet.noneOf(MediaQuality.class), MediaQuality.HIGHER));
        }

        @Test
        @DisplayName("null set returns null")
        void nullReturnsNull() {
            assertNull(MediaQuality.closest(null, MediaQuality.HIGHER));
        }

        @Test
        @DisplayName("null preferred quality is rejected")
        void nullPreferredRejected() {
            final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> MediaQuality.closest(EnumSet.of(MediaQuality.HIGH), null));
            assertEquals("Preferred quality cannot be null", failure.getMessage());
        }

        @Test
        @DisplayName("walks outward until a match is found")
        void walksOutward() {
            final Set<MediaQuality> available = EnumSet.of(MediaQuality.LOWEST);
            assertSame(MediaQuality.LOWEST, MediaQuality.closest(available, MediaQuality.ULTRA_8K));
        }
    }

    @Test
    @DisplayName("of(int) delegates to of(int, int) with same width/height")
    void singleResolution() {
        assertEquals(MediaQuality.of(1080, 1080), MediaQuality.of(1080));
    }
}
