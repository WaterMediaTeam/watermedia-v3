import org.junit.jupiter.api.Test;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageReader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates {@link ImageReader#scan()} for animated and static image formats. Each test opens a
 * single reader per file, asserts the pre-generated {@link ImageData.Scan} summary, and then
 * streams every frame from the same reader, verifying the per-frame delays the streaming codec
 * emits match the delays captured up front by the scanner.
 */
public class ScanTest {

    // 1x1 grayscale static PNG (no acTL/fcTL chunks), produced by ImageIO so the chunk CRCs
    // are guaranteed to be valid.
    private static final byte[] STATIC_PNG_BYTES = buildStaticPng();

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

    @Test
    void staticPngScanIsTheStaticSingleton() throws IOException {
        try (final ImageReader reader = CodecsAPI.decodeImage(ByteBuffer.wrap(STATIC_PNG_BYTES))) {
            assertSame(ImageData.Scan.EMPTY, reader.scan(),
                    "static PNGs should reuse the STATIC scan singleton");
        }
    }

    @Test
    void apngScanMatchesStreaming() throws IOException {
        assertScanMatchesStreaming("png/1.png");
    }

    @Test
    void gifScanMatchesStreaming() throws IOException {
        assertScanMatchesStreaming("gif/1.gif");
    }

    @Test
    void animatedWebpScanMatchesStreaming() throws IOException {
        assertScanMatchesStreaming("webp/animated/1.webp");
    }

    /**
     * Opens a single reader, queries its pre-generated scan, then drives the same reader through
     * every frame, asserting that:
     * <ul>
     *   <li>scan reports a positive duration and more than one frame</li>
     *   <li>scan duration equals the sum of its own per-frame delays</li>
     *   <li>each streamed frame's delay equals the scan's pre-recorded delay at that index</li>
     *   <li>the number of streamed frames equals scan's reported frame count</li>
     * </ul>
     */
    private static void assertScanMatchesStreaming(final String resource) throws IOException {
        final byte[] bytes = Files.readAllBytes(Path.of("src/test/resources/" + resource));
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
}
