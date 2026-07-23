package org.watermedia.test.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.util.MathEases;
import org.watermedia.api.util.MathUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the pure helpers in {@link MathUtil}: clamping, unit conversions,
 * byte formatting and easing.
 */
@DisplayName("MathUtil")
public class MathUtilTest {

    private static final double EPSILON = 1.0e-3;

    @Nested
    @DisplayName("clamp / clip255")
    class ClampTests {

        @Test
        @DisplayName("clamp returns the value when inside range")
        void clampInside() {
            assertEquals(5, MathUtil.clamp(5, 0, 10));
        }

        @Test
        @DisplayName("clamp returns lower bound when below")
        void clampLower() {
            assertEquals(0, MathUtil.clamp(-3, 0, 10));
        }

        @Test
        @DisplayName("clamp returns upper bound when above")
        void clampUpper() {
            assertEquals(10, MathUtil.clamp(20, 0, 10));
        }

        @Test
        @DisplayName("clip255 caps at 255")
        void clip255Upper() {
            assertEquals(255, MathUtil.clip255(300));
        }

        @Test
        @DisplayName("clip255 floors at 0")
        void clip255Lower() {
            assertEquals(0, MathUtil.clip255(-1));
        }
    }

    @Nested
    @DisplayName("tick / millisecond / seconds conversions")
    class TimeConversionTests {

        @Test
        @DisplayName("tickToMs(20) == 1000")
        void tickToMs() {
            assertEquals(1000L, MathUtil.tickToMs(20));
        }

        @Test
        @DisplayName("msToTick(1000) == 20")
        void msToTick() {
            assertEquals(20, MathUtil.msToTick(1000L));
        }

        @Test
        @DisplayName("secondsToMs multiplies before truncating, preserving fractional seconds")
        void secondsToMsKeepsFraction() {
            // 2.5 * 1000.0 == 2500.0; (long) 2500.0 == 2500
            assertEquals(2500L, MathUtil.secondsToMs(2.5));
        }

        @Test
        @DisplayName("msToSeconds(1500) == 1.5")
        void msToSeconds() {
            assertEquals(1.5d, MathUtil.msToSeconds(1500L), EPSILON);
        }
    }

    @Nested
    @DisplayName("argb packing")
    class ArgbTests {

        @Test
        @DisplayName("opaque red == 0xFFFF0000")
        void opaqueRed() {
            assertEquals(0xFFFF0000, MathUtil.argb(255, 255, 0, 0));
        }
    }

    @Nested
    @DisplayName("parseLong fallback")
    class ParseLongTests {

        @Test
        @DisplayName("parseLong of a numeric string")
        void numeric() {
            assertEquals(42L, MathUtil.parseLong("42"));
        }

        @Test
        @DisplayName("parseLong of garbage returns 0")
        void invalid() {
            assertEquals(0L, MathUtil.parseLong("abc"));
        }

        @Test
        @DisplayName("parseLong of null returns 0")
        void nullInput() {
            assertEquals(0L, MathUtil.parseLong(null));
        }
    }

    @Nested
    @DisplayName("byte conversions")
    class ByteTests {

        @Test
        @DisplayName("bytesToKB(2048) == 2.0")
        void bytesToKB() {
            assertEquals(2.0, MathUtil.bytesToKB(2048L), EPSILON);
        }

        @Test
        @DisplayName("displayBytes(0) == '0 B'")
        void displayBytesZero() {
            assertEquals("0 B", MathUtil.displayBytes(0L));
        }

        @Test
        @DisplayName("displayBytes(1024) renders 1.0 KB (locale aware decimal)")
        void displayBytesOneKB() {
            // STRING.FORMAT IS LOCALE-AWARE; ACCEPT EITHER '.' OR ',' AS THE FRACTION SEPARATOR
            final String s = MathUtil.displayBytes(1024L);
            assertTrue(s.matches("1[.,]0 KB"), "Unexpected format: " + s);
        }

        @Test
        @DisplayName("displayBytes(1MiB) renders 1.0 MB (locale aware decimal)")
        void displayBytesOneMB() {
            final String s = MathUtil.displayBytes(1024L * 1024L);
            assertTrue(s.matches("1[.,]0 MB"), "Unexpected format: " + s);
        }
    }

    @Nested
    @DisplayName("easing boundaries")
    class EasingTests {

        @Test
        @DisplayName("easeIn at t=1 reaches end")
        void easeInEnd() {
            assertEquals(10.0, MathUtil.easeIn(0.0, 10.0, 1.0), EPSILON);
        }

        @Test
        @DisplayName("easeOut at t=0 stays at start")
        void easeOutStart() {
            assertEquals(0.0, MathUtil.easeOut(0.0, 10.0, 0.0), EPSILON);
        }

        @Test
        @DisplayName("easeInOut midpoint is half-way")
        void easeInOutMid() {
            assertEquals(5.0, MathUtil.easeInOut(0.0, 10.0, 0.5), EPSILON);
        }
    }

    @Nested
    @DisplayName("sin/cos lookup table")
    class TrigTests {

        @Test
        @DisplayName("sin(0) ~ 0")
        void sinZero() {
            assertEquals(0.0f, MathUtil.sin(0f), 0.01f);
        }

        @Test
        @DisplayName("cos(0) ~ 1")
        void cosZero() {
            assertEquals(1.0f, MathUtil.cos(0f), 0.01f);
        }
    }

    @Nested
    @DisplayName("scaleTempo / scaleDesTempo contract")
    class ScaleTempoTests {

        @Test
        @DisplayName("scaleTempo(long) in-range returns the plain ratio")
        void scaleTempoLongInRange() {
            assertEquals(0.5d, MathUtil.scaleTempo(0L, 10L, 5L), EPSILON);
        }

        @Test
        @DisplayName("scaleTempo(long) out-of-range wraps to zero via modulo")
        void scaleTempoLongWraps() {
            assertEquals(0.5d, MathUtil.scaleTempo(0L, 10L, 15L), EPSILON);
        }

        @Test
        @DisplayName("scaleTempo(double) in-range returns the plain ratio")
        void scaleTempoDoubleInRange() {
            assertEquals(0.5d, MathUtil.scaleTempo(0.0, 10.0, 5.0), EPSILON);
        }

        @Test
        @DisplayName("scaleTempo(double) out-of-range wraps to zero via modulo")
        void scaleTempoDoubleWraps() {
            assertEquals(0.5d, MathUtil.scaleTempo(0.0, 10.0, 15.0), EPSILON);
        }

        @Test
        @DisplayName("scaleDesTempo in-range matches scaleTempo")
        void scaleDesTempoInRange() {
            assertEquals(0.5d, MathUtil.scaleDesTempo(0L, 10L, 5L), EPSILON);
        }

        @Test
        @DisplayName("scaleDesTempo out-of-range over-scales without wrapping")
        void scaleDesTempoOverScales() {
            assertEquals(1.5d, MathUtil.scaleDesTempo(0L, 10L, 15L), EPSILON);
        }

        @Test
        @DisplayName("scaleDesTempoTick over-scales while scaleTempoTick wraps for the same out-of-range tick")
        void tickVariantsDiffer() {
            // 20t = 1s SO end:10t=500ms, time:15t=750ms; WRAP GIVES 0.5, OVER-SCALE GIVES 1.5
            assertEquals(0.5d, MathUtil.scaleTempoTick(0, 10, 15), EPSILON);
            assertEquals(1.5d, MathUtil.scaleDesTempoTick(0, 10, 15), EPSILON);
        }
    }

    @Test
    @DisplayName("MathEases.EASE_IN.apply matches MathUtil.easeIn")
    void enumDelegation() {
        final double expected = MathUtil.easeIn(0.0, 10.0, 0.5);
        assertEquals(expected, MathEases.EASE_IN.apply(0.0, 10.0, 0.5), EPSILON);
    }
}
