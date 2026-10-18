package org.watermedia.api.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the pure helpers in {@link MathUtil} (clamping, sizing, unit conversion and byte
 * formatting) and the easing curves of {@link MathEases}.
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
        @DisplayName("clamp with inverted bounds always returns max")
        void clampInverted() {
            for (final int value: new int[] { Integer.MIN_VALUE, -5, 0, 5, 10, 20, Integer.MAX_VALUE }) {
                assertEquals(0, MathUtil.clamp(value, 10, 0), "value " + value);
            }
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
    @DisplayName("scaled / minutesToMs")
    class SizingTests {

        @Test
        @DisplayName("scaled keeps the native size when nothing reduces it")
        void scaledKeepsNative() {
            assertEquals(1081, MathUtil.scaled(1081, 0, 100));
            assertEquals(1081, MathUtil.scaled(1081, 4096, 100));
        }

        @Test
        @DisplayName("scaled caps, applies the percentage and keeps the result even")
        void scaledCapsAndEvens() {
            assertEquals(720, MathUtil.scaled(1080, 720, 100));
            assertEquals(540, MathUtil.scaled(1080, 0, 50));
            assertEquals(10, MathUtil.scaled(1080, 0, 1));
            assertEquals(2, MathUtil.scaled(100, 0, 1));
        }

        @Test
        @DisplayName("minutesToMs keeps fractional minutes down to the millisecond")
        void minutesToMs() {
            assertEquals(60_000L, MathUtil.minutesToMs(1.0));
            assertEquals(60_030L, MathUtil.minutesToMs(1.0005));
            assertEquals(30_000L, MathUtil.minutesToMs(0.5));
        }
    }

    @Nested
    @DisplayName("byte formatting")
    class ByteTests {

        @Test
        @DisplayName("displayBytes(0) == '0 B'")
        void displayBytesZero() {
            assertEquals("0 B", MathUtil.displayBytes(0L));
        }

        @Test
        @DisplayName("displayBytes scales to KB, MB and GB")
        void displayBytesUnits() {
            assertEquals("1.0 KB", MathUtil.displayBytes(1024L));
            assertEquals("1.5 MB", MathUtil.displayBytes(1024L * 1024L * 3 / 2));
            assertEquals("2.0 GB", MathUtil.displayBytes(2L * 1024L * 1024L * 1024L));
        }

        @Test
        @DisplayName("displayBytes keeps a dot separator under a comma locale")
        void displayBytesIgnoresLocale() {
            final Locale previous = Locale.getDefault();
            try {
                Locale.setDefault(Locale.forLanguageTag("es-ES"));
                assertEquals("1.5 KB", MathUtil.displayBytes(1536L));
            } finally {
                Locale.setDefault(previous);
            }
        }
    }

    @Nested
    @DisplayName("easing boundaries")
    class EasingTests {

        @Test
        @DisplayName("every curve starts at start and ends at end")
        void everyCurveHitsBothEnds() {
            for (final MathEases ease: MathEases.values()) {
                assertEquals(0.0, ease.apply(0.0, 10.0, 0.0), EPSILON, ease.name());
                assertEquals(10.0, ease.apply(0.0, 10.0, 1.0), EPSILON, ease.name());
                assertEquals(10.0, ease.apply(10.0, 30.0, 0.0), EPSILON, ease.name());
                assertEquals(30.0, ease.apply(10.0, 30.0, 1.0), EPSILON, ease.name());
            }
        }

        @Test
        @DisplayName("outside [0, 1] circle curves return NaN while elastic and bounce extrapolate")
        void outsideUnitRange() {
            assertTrue(Double.isNaN(MathEases.EASE_IN_CIRCLE.apply(0.0, 10.0, 1.5)));
            assertTrue(Double.isNaN(MathEases.EASE_OUT_CIRCLE.apply(0.0, 10.0, -0.5)));
            assertTrue(Double.isNaN(MathEases.EASE_IN_OUT_CIRCLE.apply(0.0, 10.0, 2.0)));
            assertTrue(Double.isFinite(MathEases.EASE_OUT_ELASTIC.apply(0.0, 10.0, 1.5)));
            assertTrue(Double.isFinite(MathEases.EASE_IN_OUT_BOUNCE.apply(0.0, 10.0, -0.5)));
        }

        @Test
        @DisplayName("EASE_IN is quadratic and EASE_OUT its mirror")
        void quadraticShapes() {
            assertEquals(2.5, MathEases.EASE_IN.apply(0.0, 10.0, 0.5), EPSILON);
            assertEquals(7.5, MathEases.EASE_OUT.apply(0.0, 10.0, 0.5), EPSILON);
        }

        @Test
        @DisplayName("in-out curves cross the midpoint half-way")
        void inOutMidpoints() {
            assertEquals(5.0, MathEases.EASE_IN_OUT.apply(0.0, 10.0, 0.5), EPSILON);
            assertEquals(5.0, MathEases.EASE_IN_OUT_QUAD.apply(0.0, 10.0, 0.5), EPSILON);
            assertEquals(5.0, MathEases.EASE_IN_OUT_SINE.apply(0.0, 10.0, 0.5), EPSILON);
            assertEquals(5.0, MathEases.EASE_IN_OUT_BOUNCE.apply(0.0, 10.0, 0.5), EPSILON);
        }

        @Test
        @DisplayName("composite curves honour a non-zero start and scale with the range")
        void compositeCurvesHonourRange() {
            assertEquals(11.25, MathEases.EASE_IN_OUT.apply(10.0, 20.0, 0.25), EPSILON);
            assertEquals(15.0, MathEases.EASE_IN_OUT.apply(10.0, 20.0, 0.5), EPSILON);
            assertEquals(13.75, MathEases.EASE_OUT_IN.apply(10.0, 20.0, 0.25), EPSILON);
            assertEquals(15.0, MathEases.EASE_OUT_IN.apply(10.0, 20.0, 0.5), EPSILON);
            for (final MathEases ease: new MathEases[] { MathEases.EASE_IN_OUT, MathEases.EASE_OUT_IN, MathEases.EASE_IN_OUT_ELASTIC }) {
                for (final double t: new double[] { 0.1, 0.25, 0.4, 0.6, 0.75, 0.9 }) {
                    // THE CURVE SCALES WITH THE RANGE AND SHIFTS WITH THE START
                    assertEquals(10.0 * ease.apply(0.0, 1.0, t), ease.apply(0.0, 10.0, t), EPSILON, ease.name() + " at " + t);
                    assertEquals(5.0 + ease.apply(0.0, 10.0, t), ease.apply(5.0, 15.0, t), EPSILON, ease.name() + " at " + t);
                }
            }
        }
    }
}
