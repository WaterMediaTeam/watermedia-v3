import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.util.PixelFormat;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * JPEG decoder tests backed by raw PAM reference images.
 */
public class JPEGTest {
    private static final Path JPEG_DIR = Path.of("src", "test", "resources", "jpeg");
    private static final int SMALL_DIFF = 8;
    private static final int MAX_DIFF = 80;
    private static final double MAX_MEAN_DIFF = 6.0;
    private static final double MAX_SMALL_DIFF_PERCENT = 10.0;

    private record Fixture(int id, String encoding, PixelFormat nativeFormat, int nativePlanes) {
        Path jpeg() {
            return JPEG_DIR.resolve(this.id + ".jpg");
        }

        Path raw() {
            return JPEG_DIR.resolve("raw").resolve(this.id + ".pam");
        }
    }

    @TestFactory
    Iterable<DynamicTest> testJPEGFixtures() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Fixture fixture: List.of(
                new Fixture(1, "jpeg400jfif", PixelFormat.GRAY, 1),
                new Fixture(2, "jpeg420exif", PixelFormat.YUV420P, 3),
                new Fixture(3, "jpeg422jfif", PixelFormat.YUV422P, 3),
                new Fixture(4, "jpeg444", PixelFormat.YUV444P, 3),
                new Fixture(5, "jpeg444_all_appx_markers", PixelFormat.YUV444P, 3)
        )) {
            tests.add(dynamicTest("JPEG decode [" + fixture.id + " " + fixture.encoding + "]",
                    () -> this.decode(fixture)));
            tests.add(dynamicTest("JPEG native format [" + fixture.id + " " + fixture.encoding + "]",
                    () -> this.nativeFormat(fixture)));
            tests.add(dynamicTest("JPEG pixels [" + fixture.id + " " + fixture.encoding + "]",
                    () -> this.comparePixels(fixture)));
        }
        return tests;
    }

    private void decode(final Fixture fixture) throws Exception {
        final ByteBuffer source = ByteBuffer.wrap(Files.readAllBytes(fixture.jpeg()));
        try (final ImageReader reader = CodecsAPI.decodeImage(source, PixelFormat.BGRA)) {
            assertEquals("JPEG", reader.name());
            assertEquals(2, source.position(), "JPEG SOI should be consumed by CodecsAPI");
            assertTrue(reader.width() > 0);
            assertTrue(reader.height() > 0);
            assertEquals(PixelFormat.BGRA, reader.pixelFormat());
            assertEquals(1, reader.frameCount());
            assertTrue(reader.hasNext());

            final ByteBuffer frame = reader.next();
            assertTrue(frame.isDirect());
            assertEquals(reader.width() * reader.height() * 4, frame.remaining());
        }
    }

    private void nativeFormat(final Fixture fixture) throws Exception {
        final ByteBuffer source = ByteBuffer.wrap(Files.readAllBytes(fixture.jpeg()));
        try (final ImageReader reader = CodecsAPI.decodeImage(source)) {
            assertEquals(fixture.nativeFormat, reader.pixelFormat(), fixture.encoding);
            assertEquals(fixture.nativePlanes, reader.planeCount(), fixture.encoding);
            final ByteBuffer frame = reader.next();
            assertTrue(frame.isDirect(), fixture.encoding);
            assertEquals(nativeBytes(fixture.nativeFormat, reader.width(), reader.height()), frame.remaining(),
                    fixture.encoding);
            for (int i = 0; i < reader.planeCount(); i++) {
                assertNotNull(reader.plane(i), fixture.encoding + " plane " + i);
                assertTrue(reader.planeStride(i) > 0, fixture.encoding + " stride " + i);
            }
        }
    }

    private void comparePixels(final Fixture fixture) throws Exception {
        final ImageData image = CodecsAPI.decodeImage(Files.readAllBytes(fixture.jpeg()), PixelFormat.BGRA);
        final PamImage reference = parsePam(fixture.raw());

        assertEquals(reference.width, image.width(), fixture.encoding);
        assertEquals(reference.height, image.height(), fixture.encoding);
        assertEquals(1, image.frames().length, fixture.encoding);

        final ByteBuffer bgra = image.frames()[0].duplicate();
        int maxDiff = 0;
        long totalDiff = 0L;
        int valuesAboveSmallDiff = 0;
        final int totalValues = reference.width * reference.height * 3;

        for (int i = 0; i < reference.width * reference.height; i++) {
            final int b = bgra.get(i * 4) & 0xFF;
            final int g = bgra.get(i * 4 + 1) & 0xFF;
            final int r = bgra.get(i * 4 + 2) & 0xFF;
            final int refOffset = i * reference.depth;

            final int[] diffs = {
                    Math.abs((reference.pixels[refOffset] & 0xFF) - r),
                    Math.abs((reference.pixels[refOffset + 1] & 0xFF) - g),
                    Math.abs((reference.pixels[refOffset + 2] & 0xFF) - b)
            };
            for (final int diff: diffs) {
                maxDiff = Math.max(maxDiff, diff);
                totalDiff += diff;
                if (diff > SMALL_DIFF) valuesAboveSmallDiff++;
            }
        }

        final double meanDiff = (double) totalDiff / totalValues;
        final double smallDiffPercent = (valuesAboveSmallDiff * 100.0) / totalValues;

        assertTrue(maxDiff <= MAX_DIFF,
                fixture.encoding + " max diff " + maxDiff + " exceeds " + MAX_DIFF);
        assertTrue(meanDiff <= MAX_MEAN_DIFF,
                fixture.encoding + " mean diff " + meanDiff + " exceeds " + MAX_MEAN_DIFF);
        assertTrue(smallDiffPercent <= MAX_SMALL_DIFF_PERCENT,
                fixture.encoding + " has " + smallDiffPercent + "% values above " + SMALL_DIFF);
    }

    private static PamImage parsePam(final Path path) throws Exception {
        final byte[] data = Files.readAllBytes(path);
        int headerEnd = -1;
        for (int i = 0; i <= data.length - 7; i++) {
            if (data[i] == 'E' && data[i + 1] == 'N' && data[i + 2] == 'D' &&
                    data[i + 3] == 'H' && data[i + 4] == 'D' && data[i + 5] == 'R' &&
                    data[i + 6] == '\n') {
                headerEnd = i + 7;
                break;
            }
        }
        assertTrue(headerEnd > 0, "Invalid PAM header: " + path);

        final String header = new String(data, 0, headerEnd, java.nio.charset.StandardCharsets.US_ASCII);
        int width = 0;
        int height = 0;
        int depth = 0;
        for (final String line: header.split("\\R")) {
            if (line.startsWith("WIDTH ")) width = Integer.parseInt(line.substring("WIDTH ".length()));
            else if (line.startsWith("HEIGHT ")) height = Integer.parseInt(line.substring("HEIGHT ".length()));
            else if (line.startsWith("DEPTH ")) depth = Integer.parseInt(line.substring("DEPTH ".length()));
        }
        assertTrue(width > 0 && height > 0 && depth >= 3, "Invalid PAM dimensions: " + path);

        final byte[] pixels = new byte[data.length - headerEnd];
        System.arraycopy(data, headerEnd, pixels, 0, pixels.length);
        assertEquals(width * height * depth, pixels.length, "Invalid PAM payload: " + path);
        return new PamImage(width, height, depth, pixels);
    }

    private static int nativeBytes(final PixelFormat format, final int width, final int height) {
        final int pixels = width * height;
        final int chromaW = (width + 1) >> 1;
        final int chromaH = (height + 1) >> 1;
        return switch (format) {
            case GRAY -> pixels;
            case YUV420P -> pixels + 2 * chromaW * chromaH;
            case YUV422P -> pixels + 2 * chromaW * height;
            case YUV444P -> pixels * 3;
            default -> throw new IllegalArgumentException("Unsupported JPEG native format " + format);
        };
    }

    private record PamImage(int width, int height, int depth, byte[] pixels) {}
}
