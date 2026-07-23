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
        engine.upload(new ByteBuffer[]{direct(10, 20, 30, 255, 40, 50, 60, 128)}, new int[]{0});

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
        engine.upload(new ByteBuffer[]{direct(30, 20, 10, 255)}, new int[]{0});
        assertEquals(0xFF1E140A, engine.image().getRGB(0, 0));
    }

    @Test
    @DisplayName("texture() sentinel is 0 until the first frame, then non-zero, then 0 after release")
    void callbackAndLifecycle() {
        final boolean[] fired = {false};
        final AWTEngine engine = new AWTEngine.Builder().onFrame(() -> fired[0] = true).build();
        engine.setVideoFormat(PixelFormat.BGRA, 1, 1, 8);
        assertEquals(0L, engine.texture()); // NO FRAME YET — SENTINEL STAYS 0 (MATCHES THE BASE CONTRACT)

        engine.upload(new ByteBuffer[]{direct(1, 2, 3, 4)}, new int[]{0});
        assertTrue(fired[0]);
        assertEquals(1L, engine.texture()); // FRAME PRESENT — SENTINEL FLIPS NON-ZERO

        engine.release();
        assertEquals(0L, engine.texture());
    }

    @Test
    @DisplayName("BGRA stride padding beyond w*4 is dropped")
    void bgraStridePadding() {
        final AWTEngine engine = new AWTEngine.Builder().build();
        engine.setVideoFormat(PixelFormat.BGRA, 2, 2, 8);
        // 2x2 BGRA WITH A 12-BYTE STRIDE: 8 REAL BYTES PER ROW + 4 PADDING BYTES THE ENGINE MUST SKIP
        engine.upload(new ByteBuffer[]{direct(
                10, 20, 30, 255,  40, 50, 60, 255,   1, 2, 3, 4,   // ROW 0 + PAD
                70, 80, 90, 255, 100, 110, 120, 255,  5, 6, 7, 8)}, // ROW 1 + PAD
                new int[]{12});
        final BufferedImage img = engine.image();
        assertEquals(0xFF1E140A, img.getRGB(0, 0));
        assertEquals(0xFF3C3228, img.getRGB(1, 0));
        assertEquals(0xFF5A5046, img.getRGB(0, 1));
        assertEquals(0xFF786E64, img.getRGB(1, 1));
    }

    @Test
    @DisplayName("RGBA stride padding beyond w*4 is dropped and swizzled")
    void rgbaStridePadding() {
        final AWTEngine engine = new AWTEngine.Builder().build();
        engine.setVideoFormat(PixelFormat.RGBA, 2, 1, 8);
        // 2x1 RGBA, 12-BYTE STRIDE (8 REAL + 4 PAD); R,G,B,A SWIZZLES TO THE SAME ARGB AS THE BGRA CASE
        engine.upload(new ByteBuffer[]{direct(30, 20, 10, 255,  60, 50, 40, 255,  1, 2, 3, 4)}, new int[]{12});
        final BufferedImage img = engine.image();
        assertEquals(0xFF1E140A, img.getRGB(0, 0));
        assertEquals(0xFF3C3228, img.getRGB(1, 0));
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
