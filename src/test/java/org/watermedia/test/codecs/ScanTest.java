package org.watermedia.test.codecs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.test.support.Fixtures;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates {@link ImageReader#scan()} for animated and static image formats. Each test
 * opens a single reader per file, asserts the pre-generated {@link ImageData.Scan}
 * summary, and then streams every frame from the same reader, verifying the per-frame
 * delays the streaming codec emits match the delays captured up front by the scanner.
 */
@DisplayName("ImageReader.scan()")
public class ScanTest {

    // 1x1 GRAYSCALE STATIC PNG (NO acTL/fcTL) — IMAGEIO-PRODUCED FOR VALID CHUNK CRCs
    private static final byte[] STATIC_PNG_BYTES = buildStaticPng();

    @Test
    @DisplayName("Static PNG scan reuses the EMPTY singleton")
    void testStaticPngScanIsTheStaticSingleton() throws IOException {
        try (final ImageReader reader = CodecsAPI.decodeImage(ByteBuffer.wrap(STATIC_PNG_BYTES))) {
            assertSame(ImageData.Scan.EMPTY, reader.scan(),
                    "static PNGs should reuse the STATIC scan singleton");
        }
    }

    @Test
    @DisplayName("APNG scan matches streaming")
    void testApngScanMatchesStreaming() throws IOException {
        assertScanMatchesStreaming(Fixtures.PNG_ANIMATED);
    }

    @Test
    @DisplayName("GIF scan matches streaming")
    void testGifScanMatchesStreaming() throws IOException {
        assertScanMatchesStreaming(Fixtures.GIF_ANIMATED);
    }

    @Test
    @DisplayName("Animated WebP scan matches streaming")
    void testAnimatedWebpScanMatchesStreaming() throws IOException {
        assertScanMatchesStreaming(Fixtures.WEBP_ANIMATED);
    }

    // OPENS A SINGLE READER, QUERIES ITS PRE-GENERATED SCAN, THEN DRIVES THE SAME READER
    // THROUGH EVERY FRAME, ASSERTING THAT THE SCAN REPORTS A POSITIVE DURATION AND >1 FRAME,
    // ITS DURATION EQUALS THE SUM OF ITS OWN PER-FRAME DELAYS, EACH STREAMED FRAME'S DELAY
    // MATCHES THE SCAN'S PRE-RECORDED DELAY AT THAT INDEX, AND THE STREAMED FRAME COUNT
    // EQUALS THE SCAN'S REPORTED FRAME COUNT.
    private static void assertScanMatchesStreaming(final Path resource) throws IOException {
        final byte[] bytes = Fixtures.readAll(resource);
        try (final ImageReader reader = CodecsAPI.decodeImage(ByteBuffer.wrap(bytes))) {
            final ImageData.Scan scan = reader.scan();
            assertNotNull(scan, resource + ": scan must not be null");
            assertTrue(scan.duration() > 0L, resource + ": scan duration must be positive");
            assertTrue(scan.frameCount() > 1, resource + ": animated file must report >1 frames");
            assertEquals(scan.duration(), Arrays.stream(scan.delays()).sum(),
                    resource + ": scan duration must equal the sum of its per-frame delays");

            final ImageData image = reader.readAll();
            for (int frameIndex = 0; frameIndex < image.delay().length; frameIndex++) {
                assertEquals(scan.delays()[frameIndex], image.delay()[frameIndex],
                        resource + ": decoded delay for frame " + frameIndex + " must match scan");
            }
            assertEquals(scan.frameCount(), image.frames().length,
                    resource + ": streamed frame count must match scan");
        }
    }

    private static byte[] buildStaticPng() {
        try {
            final BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_GRAY);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", out);
            return out.toByteArray();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
