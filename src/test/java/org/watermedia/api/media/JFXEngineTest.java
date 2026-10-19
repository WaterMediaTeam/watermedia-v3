package org.watermedia.api.media;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat.Type;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritablePixelFormat;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.WaterMedia;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.test.support.LogCapture;
import org.watermedia.test.support.MediaBootstrap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link JFXEngine} software sink: BGRA/RGBA frames land premultiplied in the
 * {@code PixelBuffer}-backed image, a resize replaces the image, format declines steer the producer
 * to BGRA and the shared engine lifecycle (refused sizes, release, isolated callbacks) holds. Skips
 * when the JavaFX toolkit cannot start (headless machines).
 */
@DisplayName("JFXEngine")
public class JFXEngineTest {
    private static final long FX_TIMEOUT_MS = 5_000L;

    @BeforeAll
    static void boot() throws InterruptedException {
        // ENGINE CONSTRUCTION IS CLIENT-GATED BY THE GFXEngine BASE — BOOT A CLIENT ENVIRONMENT ONCE
        MediaBootstrap.client();
        // PixelBuffer IMAGES AND Platform.runLater NEED A STARTED TOOLKIT; ANOTHER CLASS MAY HAVE STARTED IT ALREADY
        final CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (final IllegalStateException running) {
            started.countDown();
        } catch (final RuntimeException | Error unavailable) {
            Assumptions.abort("JavaFX toolkit unavailable: " + unavailable);
        }
        Assumptions.assumeTrue(started.await(FX_TIMEOUT_MS, TimeUnit.MILLISECONDS), "JavaFX toolkit did not start");
        Platform.setImplicitExit(false);
    }

    @Nested
    @DisplayName("Frame uploads")
    class Frames {

        @Test
        @DisplayName("A BGRA frame lands premultiplied in the image without touching the caller's buffer")
        void bgraPremultiplied() throws Exception {
            final JFXEngine engine = MediaAPI.jfxEngine(null);
            try {
                assertTrue(engine.format(PixelFormat.BGRA, 2, 1, 8));
                // PIXEL 0 IS OPAQUE; PIXEL 1 (A=128) PREMULTIPLIES TO ROUND(C * 128 / 255)
                final ByteBuffer frame = direct(10, 20, 30, 255, 40, 50, 60, 128);
                engine.upload(new ByteBuffer[] { frame }, new int[] { 0 });

                final Image image = engine.image();
                assertEquals(2, image.getWidth());
                assertEquals(1, image.getHeight());
                assertEquals(Type.BYTE_BGRA_PRE, image.getPixelReader().getPixelFormat().getType(), "PixelBuffer only takes premultiplied BGRA");
                assertArrayEquals(bytes(10, 20, 30, 255, 20, 25, 30, 128), pixels(image));
                assertEquals(0, frame.position(), "the frame keeps its position");
                assertEquals(40, frame.get(4), "the caller's frame is never premultiplied in place");

                engine.upload(new ByteBuffer[] { frame }, new int[] { 0 });
                assertArrayEquals(bytes(10, 20, 30, 255, 20, 25, 30, 128), pixels(image), "a repeated frame is premultiplied once");
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("RGBA is swizzled to the same premultiplied BGRA")
        void rgbaSwizzle() throws Exception {
            final JFXEngine engine = MediaAPI.jfxEngine(null);
            try {
                assertTrue(engine.format(PixelFormat.RGBA, 2, 1, 8));
                engine.upload(new ByteBuffer[] { direct(30, 20, 10, 255, 60, 50, 40, 128) }, new int[] { 0 });
                assertArrayEquals(bytes(10, 20, 30, 255, 20, 25, 30, 128), pixels(engine.image()));
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("A resolution change replaces the image and waits for the next frame")
        void resizeReplacesImage() throws Exception {
            final JFXEngine engine = MediaAPI.jfxEngine(null);
            try {
                assertTrue(engine.format(PixelFormat.BGRA, 2, 2, 8));
                final Image first = engine.image();
                engine.upload(new ByteBuffer[] { direct(new int[16]) }, new int[] { 0 });
                assertNotEquals(0L, engine.texture());

                assertTrue(engine.format(PixelFormat.BGRA, 4, 3, 8));
                final Image second = engine.image();
                assertNotSame(first, second, "re-read image() after a resize");
                assertEquals(4, second.getWidth());
                assertEquals(3, second.getHeight());
                assertEquals(0L, engine.texture(), "the new surface holds no frame yet");

                engine.upload(new ByteBuffer[] { direct(new int[48]) }, new int[] { 0 });
                assertNotEquals(0L, engine.texture());
                assertEquals(48, pixels(second).length);
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("A frame callback that throws is logged once per engine, never thrown at the decoder")
        void callbackFailuresAreIsolated() throws Exception {
            final JFXEngine engine = MediaAPI.jfxEngine(() -> { throw new IllegalStateException("host repaint failed"); });
            try {
                assertTrue(engine.format(PixelFormat.BGRA, 1, 1, 8));
                try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
                    for (int frame = 0; frame < 3; frame++)
                        assertDoesNotThrow(() -> engine.upload(new ByteBuffer[] { direct(1, 2, 3, 255) }, new int[] { 0 }));
                    final long errors = capture.events().stream().filter(event -> event.getLevel() == Level.ERROR).count();
                    assertEquals(1, errors, "a repeated failure is logged once per engine");
                }
                assertNotEquals(0L, engine.texture(), "the frame was published before the callback failed");
                assertArrayEquals(bytes(1, 2, 3, 255), pixels(engine.image()));
            } finally {
                engine.release();
            }
        }
    }

    @Nested
    @DisplayName("Formats and lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("Only 8-bit BGRA/RGBA are accepted; planar, RGB and deep color decline so the producer converts")
        void formatDeclines() {
            final JFXEngine engine = MediaAPI.jfxEngine(null);
            try {
                assertTrue(engine.supports(PixelFormat.BGRA));
                assertTrue(engine.supports(PixelFormat.RGBA));
                assertFalse(engine.supports(PixelFormat.RGB));
                assertFalse(engine.supports(PixelFormat.YUV420P));
                assertFalse(engine.supports(PixelFormat.RGBA, 16), "a software surface copies four bytes per pixel");
                assertFalse(engine.format(PixelFormat.YUV420P, 2, 2, 8));
                assertNull(engine.image(), "a declined format allocates no surface");
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("Refused sizes leave no surface and drop frames; a declined layout keeps the current one")
        void refusedFormats() throws Exception {
            final JFXEngine engine = MediaAPI.jfxEngine(null);
            try {
                // EMPTY SIZES ARE REFUSED BY THE BASE CONTRACT BEFORE ANY BACKEND WORK, SILENTLY
                for (final int[] size: new int[][] { { 0, 4 }, { 4, -1 } }) {
                    try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
                        assertFalse(engine.format(PixelFormat.BGRA, size[0], size[1], 8));
                        assertNull(engine.image());
                        assertTrue(capture.events().isEmpty());
                    }
                    engine.upload(new ByteBuffer[] { direct(1, 2, 3, 4) }, new int[] { 0 });
                    assertEquals(0L, engine.texture());
                }

                // A LAYOUT THE ENGINE DECLINES NEVER REACHES THE BACKEND, SO THE CURRENT SURFACE STAYS ON SCREEN
                assertTrue(engine.format(PixelFormat.BGRA, 1, 1, 8));
                engine.upload(new ByteBuffer[] { direct(1, 2, 3, 255) }, new int[] { 0 });
                final Image current = engine.image();
                assertFalse(engine.format(PixelFormat.NV12, 1, 1, 8));
                assertSame(current, engine.image());
                assertArrayEquals(bytes(1, 2, 3, 255), pixels(current));

                // A SIZE NO SINGLE BUFFER CAN BACK DROPS THE SURFACE WITH A WARNING INSTEAD OF LEAVING A STALE FRAME
                try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
                    assertFalse(engine.format(PixelFormat.BGRA, 50_000, 50_000, 8));
                    assertNull(engine.image());
                    assertEquals(Level.WARN, capture.events().get(0).getLevel());
                    assertTrue(capture.events().get(0).getMessage().getFormattedMessage().contains("50000x50000"));
                }
                engine.upload(new ByteBuffer[] { direct(1, 2, 3, 4) }, new int[] { 0 });
                assertEquals(0L, engine.texture());
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("texture() sentinel is 0 until the first frame, then non-zero, then 0 after release")
        void textureSentinel() {
            final boolean[] fired = { false };
            final JFXEngine engine = MediaAPI.jfxEngine(() -> fired[0] = true);
            assertTrue(engine.format(PixelFormat.BGRA, 1, 1, 8));
            assertEquals(0L, engine.texture(), "no frame yet");

            engine.upload(new ByteBuffer[] { direct(1, 2, 3, 4) }, new int[] { 0 });
            assertTrue(fired[0], "the callback runs after the frame is published");
            assertNotEquals(0L, engine.texture(), "a frame is present");

            engine.release();
            assertEquals(0L, engine.texture(), "a released engine exposes no texture");
        }

        @Test
        @DisplayName("A released engine refuses formats, drops frames and exposes no image")
        void useAfterRelease() {
            final boolean[] fired = { false };
            final JFXEngine engine = MediaAPI.jfxEngine(() -> fired[0] = true);
            assertTrue(engine.format(PixelFormat.BGRA, 1, 1, 8));
            assertNotNull(engine.image());
            engine.release();

            assertTrue(engine.released());
            assertNull(engine.image(), "release drops the surface");
            assertFalse(engine.format(PixelFormat.BGRA, 1, 1, 8), "a released engine refuses formats");
            assertNull(engine.image(), "no surface comes back after release");
            engine.upload(new ByteBuffer[] { direct(1, 2, 3, 4) }, new int[] { 0 });
            assertFalse(fired[0], "uploads are dropped after release");
            assertEquals(0L, engine.texture());
            assertDoesNotThrow(engine::repaint, "repainting a released engine is a no-op");
            engine.release(); // IDEMPOTENT
        }
    }

    // UTILITY

    // BUILDS A DIRECT BUFFER AT POSITION 0 FROM THE GIVEN UNSIGNED BYTES
    private static ByteBuffer direct(final int... values) {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(values.length).order(ByteOrder.nativeOrder());
        for (final int value: values) buffer.put((byte) value);
        return buffer.position(0);
    }

    private static byte[] bytes(final int... values) {
        final byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
    }

    // READS THE IMAGE AS PREMULTIPLIED BGRA ON THE FX THREAD; runLater IS FIFO, SO EVERY updateBuffer POSTED BY present() RAN FIRST
    private static byte[] pixels(final Image image) throws Exception {
        final FutureTask<byte[]> read = new FutureTask<>(() -> {
            final int width = (int) image.getWidth();
            final int height = (int) image.getHeight();
            final byte[] out = new byte[width * height * 4];
            final PixelReader reader = image.getPixelReader();
            reader.getPixels(0, 0, width, height, WritablePixelFormat.getByteBgraPreInstance(), out, 0, width * 4);
            return out;
        });
        Platform.runLater(read);
        return read.get(FX_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }
}
