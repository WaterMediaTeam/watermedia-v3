package org.watermedia.test.media.engines;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.media.engines.AWTEngine;
import org.watermedia.api.util.PixelFormat;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link AWTEngine} software sink: BGRA/RGBA byte order lands as {@code 0xAARRGGBB}
 * in the {@code BufferedImage}, resize reallocates, and format declines steer the producer to BGRA.
 */
@DisplayName("AWTEngine")
class AWTEngineTest {

    // BUILDS A DIRECT BUFFER AT POSITION 0 FROM THE GIVEN UNSIGNED BYTES
    private static ByteBuffer direct(final int... bytes) {
        final ByteBuffer b = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
        for (final int v: bytes) b.put((byte) v);
        return b.position(0);
    }

    @Test
    @DisplayName("BGRA bytes pack into ARGB pixels")
    void bgraPacking() {
        final AWTEngine engine = new AWTEngine.Builder().build();
        engine.setVideoFormat(PixelFormat.BGRA, 2, 1, 8);
        // PIXEL 0: B=10 G=20 R=30 A=255 ; PIXEL 1: B=40 G=50 R=60 A=128
        engine.upload(direct(10, 20, 30, 255, 40, 50, 60, 128), 0);

        final BufferedImage img = engine.image();
        assertEquals(2, img.getWidth());
        assertEquals(1, img.getHeight());
        assertEquals(0xFF1E140A, img.getRGB(0, 0));
        assertEquals(0x803C3228, img.getRGB(1, 0));
    }

    @Test
    @DisplayName("RGBA is swizzled to the same ARGB result")
    void rgbaSwizzle() {
        final AWTEngine engine = new AWTEngine.Builder().build();
        engine.setVideoFormat(PixelFormat.RGBA, 1, 1, 8);
        // R=30 G=20 B=10 A=255 -> 0xFF1E140A, same pixel as the BGRA case
        engine.upload(direct(30, 20, 10, 255), 0);
        assertEquals(0xFF1E140A, engine.image().getRGB(0, 0));
    }

    @Test
    @DisplayName("onFrame fires and texture() flips to 0 after release")
    void callbackAndLifecycle() {
        final boolean[] fired = {false};
        final AWTEngine engine = new AWTEngine.Builder().onFrame(() -> fired[0] = true).build();
        engine.setVideoFormat(PixelFormat.BGRA, 1, 1, 8);
        assertEquals(1L, engine.texture());

        engine.upload(direct(1, 2, 3, 4), 0);
        assertTrue(fired[0]);

        engine.release();
        assertEquals(0L, engine.texture());
    }

    @Test
    @DisplayName("A resolution change reallocates the image")
    void resizeReallocates() {
        final AWTEngine engine = new AWTEngine.Builder().build();
        engine.setVideoFormat(PixelFormat.BGRA, 2, 2, 8);
        final BufferedImage first = engine.image();
        engine.setVideoFormat(PixelFormat.BGRA, 4, 3, 8);
        final BufferedImage second = engine.image();

        assertEquals(4, second.getWidth());
        assertEquals(3, second.getHeight());
        assertTrue(first != second);
    }

    @Test
    @DisplayName("Only BGRA/RGBA are accepted; planar/RGB decline so the producer converts")
    void formatDeclines() {
        final AWTEngine engine = new AWTEngine.Builder().build();
        assertTrue(engine.supportsFormat(PixelFormat.BGRA));
        assertTrue(engine.supportsFormat(PixelFormat.RGBA));
        assertFalse(engine.supportsFormat(PixelFormat.RGB));
        assertFalse(engine.supportsFormat(PixelFormat.YUV420P));
    }
}
