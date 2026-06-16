package org.watermedia.test.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.util.PixelFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Identity coverage for {@link PixelFormat}. The enum is a pure tag so the only
 * meaningful contract is "every declared constant survives {@code valueOf}".
 */
@DisplayName("PixelFormat")
public class PixelFormatTest {

    @Test
    @DisplayName("values() exposes every declared constant")
    void testValuesCount() {
        // KEEP IN SYNC WITH THE ENUM: 15 LAYOUTS ACROSS 4 PLANE FAMILIES
        assertEquals(15, PixelFormat.values().length);
    }

    @Test
    @DisplayName("valueOf round-trips every constant")
    void testValueOfRoundTrip() {
        for (final PixelFormat fmt: PixelFormat.values()) {
            assertSame(fmt, PixelFormat.valueOf(fmt.name()));
        }
    }
}
