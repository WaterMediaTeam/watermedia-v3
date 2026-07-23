package org.watermedia.test.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.util.PixelFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Identity and intrinsic-data coverage for {@link PixelFormat}: every constant survives
 * {@code valueOf}, and the planes/blockBytes/compressed data matches each layout family.
 */
@DisplayName("PixelFormat")
public class PixelFormatTest {

    @Test
    @DisplayName("values() exposes every declared constant")
    void testValuesCount() {
        // KEEP IN SYNC WITH THE ENUM: 15 RAW LAYOUTS ACROSS 4 PLANE FAMILIES + 5 BCn FORMATS
        assertEquals(20, PixelFormat.values().length);
    }

    @Test
    @DisplayName("valueOf round-trips every constant")
    void testValueOfRoundTrip() {
        for (final PixelFormat fmt: PixelFormat.values()) {
            assertSame(fmt, PixelFormat.valueOf(fmt.name()));
        }
    }

    @Test
    @DisplayName("Plane counts match each layout family")
    void testPlanes() {
        assertEquals(1, PixelFormat.BGRA.planes());
        assertEquals(1, PixelFormat.YUYV.planes());
        assertEquals(2, PixelFormat.NV12.planes());
        assertEquals(3, PixelFormat.YUV420P.planes());
        assertEquals(4, PixelFormat.YUVA444P.planes());
        assertEquals(1, PixelFormat.BC7.planes());
    }

    @Test
    @DisplayName("Only BCn formats are compressed, with 8 or 16 block bytes")
    void testCompressed() {
        for (final PixelFormat fmt: PixelFormat.values()) {
            if (fmt.name().startsWith("BC")) {
                assertTrue(fmt.compressed(), fmt + " must be compressed");
                assertEquals(fmt == PixelFormat.BC1 ? 8 : 16, fmt.blockBytes(), fmt + " block bytes");
            } else {
                assertFalse(fmt.compressed(), fmt + " must not be compressed");
                assertEquals(0, fmt.blockBytes(), fmt + " must have no block bytes");
            }
        }
    }
}
