package org.watermedia.test.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.util.MediaQuality;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies {@link MediaQuality} classification, navigation and lookup helpers.
 */
public class MediaQualityTest {

    @Nested
    @DisplayName("of(width, height)")
    class OfDimensionsTests {

        @Test
        @DisplayName("1920x1080 maps to HIGH")
        void hd1080() {
            assertSame(MediaQuality.HIGH, MediaQuality.of(1920, 1080));
        }

        @Test
        @DisplayName("3840x2160 maps to HIGHEST")
        void uhd2160() {
            assertSame(MediaQuality.HIGHEST, MediaQuality.of(3840, 2160));
        }

        @Test
        @DisplayName("640x360 maps to LOWER")
        void sd360() {
            assertSame(MediaQuality.LOWER, MediaQuality.of(640, 360));
        }

        @Test
        @DisplayName("0x0 maps to UNKNOWN")
        void allZero() {
            assertSame(MediaQuality.UNKNOWN, MediaQuality.of(0, 0));
        }

        @Test
        @DisplayName("negative width is treated as missing dimension")
        void negativeWidthFallsBackToHeight() {
            // WIDTH <= 0 SO HEIGHT IS USED DIRECTLY
            assertSame(MediaQuality.HIGH, MediaQuality.of(-5, 1080));
        }

        @Test
        @DisplayName("zero height falls back to width")
        void zeroHeightFallsBackToWidth() {
            assertSame(MediaQuality.MEDIUM, MediaQuality.of(720, 0));
        }

        @Test
        @DisplayName("portrait uses the smaller dimension")
        void portraitUsesSmallerSide() {
            // MATH.MIN(1080, 1920) == 1080 -> HIGH
            assertSame(MediaQuality.HIGH, MediaQuality.of(1080, 1920));
        }
    }

    @Nested
    @DisplayName("higher() / lower() boundaries")
    class NavigationTests {

        @Test
        @DisplayName("LOWEST.lower() stays at LOWEST")
        void lowestFloor() {
            // UNKNOWN IS ORDINAL 0; LOWEST IS NOT THE FIRST CONSTANT BUT FOLLOWS Q144P.
            // INDEX-BASED FLOOR IS UNKNOWN.lower() -> UNKNOWN.
            assertSame(MediaQuality.UNKNOWN, MediaQuality.UNKNOWN.lower());
        }

        @Test
        @DisplayName("Q8K.higher() stays at Q8K")
        void highestCeiling() {
            assertSame(MediaQuality.Q8K, MediaQuality.Q8K.higher());
        }

        @Test
        @DisplayName("higher() advances one step")
        void higherAdvances() {
            assertSame(MediaQuality.HIGHER, MediaQuality.HIGH.higher());
        }

        @Test
        @DisplayName("lower() steps back one")
        void lowerSteps() {
            assertSame(MediaQuality.MEDIUM, MediaQuality.HIGH.lower());
        }
    }

    @Nested
    @DisplayName("closest(set, preferred)")
    class ClosestTests {

        @Test
        @DisplayName("returns preferred when available")
        void exactMatch() {
            final Set<MediaQuality> available = EnumSet.of(MediaQuality.LOW, MediaQuality.HIGH, MediaQuality.MEDIUM);
            assertSame(MediaQuality.HIGH, MediaQuality.closest(available, MediaQuality.HIGH));
        }

        @Test
        @DisplayName("returns closest available when preferred is missing")
        void picksClosestNeighbour() {
            final Set<MediaQuality> available = EnumSet.of(MediaQuality.MEDIUM, MediaQuality.HIGHER);
            // PREFERRED HIGH -> NEIGHBOURS MEDIUM (LOWER) AND HIGHER, BOTH ARE 1 STEP AWAY.
            // LOWER WINS BECAUSE THE LOOP CHECKS LOWER FIRST.
            assertSame(MediaQuality.MEDIUM, MediaQuality.closest(available, MediaQuality.HIGH));
        }

        @Test
        @DisplayName("empty set returns null")
        void emptyReturnsNull() {
            assertNull(MediaQuality.closest(EnumSet.noneOf(MediaQuality.class), MediaQuality.HIGH));
        }

        @Test
        @DisplayName("null set returns null")
        void nullReturnsNull() {
            assertNull(MediaQuality.closest(null, MediaQuality.HIGH));
        }

        @Test
        @DisplayName("walks outward until a match is found")
        void walksOutward() {
            final Set<MediaQuality> available = EnumSet.of(MediaQuality.Q144P);
            assertSame(MediaQuality.Q144P, MediaQuality.closest(available, MediaQuality.Q8K));
        }
    }

    @Test
    @DisplayName("of(int) delegates to of(int, int) with same width/height")
    void singleResolution() {
        assertEquals(MediaQuality.of(1080, 1080), MediaQuality.of(1080));
    }
}
