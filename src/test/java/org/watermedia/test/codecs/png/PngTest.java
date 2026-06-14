package org.watermedia.test.codecs.png;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.PamImage;
import org.watermedia.test.support.PixelDiff;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * PNG decoder verification.
 *
 * <p>For every PNG fixture under {@code src/test/resources/png} two dynamic tests run:
 * the first checks that {@code CodecsAPI.decodeImage} produces a well-formed
 * {@link ImageData} (positive dimensions, frame buffers sized {@code w*h*4}), and the
 * second compares the first decoded frame pixel-for-pixel against a pre-generated PAM
 * reference under {@code png/raw}. PNG is lossless so the tolerance is zero.
 */
public class PngTest {

    // PNG IS LOSSLESS — REFERENCE MATCH MUST BE EXACT
    private static final int LOSSLESS_TOLERANCE = 0;

    @TestFactory
    Iterable<DynamicTest> testPNG() {
        final List<DynamicTest> tests = new ArrayList<>();

        // VERIFY FIXTURE FOLDER POPULATED
        try (final Stream<Path> entries = Files.list(Fixtures.PNG_DIR)) {
            final List<Path> images = entries
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png"))
                    .sorted()
                    .toList();
            assertTrue(!images.isEmpty(), "Test folder is empty: " + Fixtures.PNG_DIR);

            for (final Path imageFile: images) {
                final String name = imageFile.getFileName().toString();

                // TEST 1: DECODING SUCCEEDS WITH SANE BUFFER GEOMETRY
                tests.add(dynamicTest("PNG decode [" + name + "]", () -> {
                    final byte[] bytes = Fixtures.readAll(imageFile);
                    final ImageData result = CodecsAPI.decodeImage(bytes);
                    assertNotNull(result, "PNG signature not recognized for: " + name);
                    assertTrue(result.width() > 0, "Width must be positive");
                    assertTrue(result.height() > 0, "Height must be positive");
                    assertNotNull(result.frames(), "Frames array is null");
                    assertTrue(result.frames().length > 0, "No frames decoded");

                    // EACH FRAME MUST BE w*h*4 BYTES (BGRA)
                    final int expectedBufferSize = result.width() * result.height() * 4;
                    for (int i = 0; i < result.frames().length; i++) {
                        final ByteBuffer frame = result.frames()[i];
                        assertNotNull(frame, "Frame " + i + " is null");
                        assertEquals(expectedBufferSize, frame.capacity(),
                                "Frame " + i + " buffer size mismatch");
                    }

                    if (result.frames().length > 1) {
                        System.out.println("Animated PNG detected: " + name
                                + " - " + result.frames().length + " frames, duration: "
                                + result.duration() + "ms");
                    }
                }));

                // TEST 2: FIRST FRAME MUST MATCH REFERENCE PAM EXACTLY
                tests.add(dynamicTest("PNG data match [" + name + "]", () -> {
                    final ImageData decoded = CodecsAPI.decodeImage(Fixtures.readAll(imageFile));
                    assertNotNull(decoded, "PNG signature not recognized");

                    final Path pamPath = Fixtures.PNG_DIR.resolve("raw")
                            .resolve(name.replace(".png", ".pam"));
                    assertTrue(Files.exists(pamPath),
                            "Reference PAM file not found: " + pamPath.toAbsolutePath()
                                    + " - Generate reference data using: convert input.png -depth 8 output.pam");

                    final PamImage reference = PamImage.read(pamPath);
                    final byte[] actualRgba = PamImage.bgraToRgba(decoded.frames()[0],
                            decoded.width(), decoded.height());

                    assertEquals(reference.pixels().length, actualRgba.length,
                            "Pixel data size mismatch for " + name
                                    + " - reference: " + reference.pixels().length
                                    + ", java: " + actualRgba.length);

                    final PixelDiff diff = PixelDiff.compare(reference.pixels(), actualRgba, LOSSLESS_TOLERANCE);
                    assertEquals(0, diff.maxDiff(),
                            String.format(
                                    "Pixel data mismatch for %s - max diff: %d, %d values differ (%.2f%%)",
                                    name, diff.maxDiff(), diff.valuesAboveSmall(), diff.percentAboveSmall()));
                }));
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to enumerate PNG fixtures", e);
        }

        return tests;
    }

    @TestFactory
    Iterable<DynamicTest> testPNGReset() {
        final List<DynamicTest> tests = new ArrayList<>();
        try (final Stream<Path> entries = Files.list(Fixtures.PNG_DIR)) {
            final List<Path> images = entries
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png"))
                    .sorted()
                    .toList();
            assertTrue(!images.isEmpty(), "Test folder is empty: " + Fixtures.PNG_DIR);

            // COVERS BOTH STATIC PNG AND APNG — reset() MUST REPLAY EITHER ONE IDENTICALLY
            for (final Path imageFile: images) {
                final String name = imageFile.getFileName().toString();
                tests.add(dynamicTest("PNG reset replay [" + name + "]", () -> {
                    try (final ImageReader reader = CodecsAPI.decodeImage(ByteBuffer.wrap(Fixtures.readAll(imageFile)))) {
                        final long[] delays = reader.delays().clone();
                        final List<byte[]> first = decodeFrameHashes(reader);
                        assertTrue(!first.isEmpty(), "No frames decoded for " + name);

                        // FIRST RESET — REPLAY MUST BE BYTE-IDENTICAL AND METADATA MUST SURVIVE
                        assertTrue(reader.reset(), "reset() must be supported for " + name);
                        assertArrayEquals(delays, reader.delays(), "Delays changed after reset for " + name);
                        assertReplayMatches(first, decodeFrameHashes(reader), name);

                        // SECOND RESET — reset() MUST BE REPEATABLE
                        assertTrue(reader.reset(), "Second reset() must be supported for " + name);
                        assertReplayMatches(first, decodeFrameHashes(reader), name);
                    }
                }));
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to enumerate PNG fixtures", e);
        }

        return tests;
    }

    // HASH FRAMES INSTEAD OF COPYING THEM — LARGE FIXTURES TIMES THREE DECODE PASSES WOULD BLOW
    // THE 512MB DIRECT-MEMORY BUDGET OF THE TEST JVM, AND SHA-256 EQUALITY IS BYTE-IDENTITY
    private static List<byte[]> decodeFrameHashes(final ImageReader reader) throws IOException, NoSuchAlgorithmException {
        final MessageDigest sha = MessageDigest.getInstance("SHA-256");
        final List<byte[]> hashes = new ArrayList<>();
        while (reader.hasNext()) {
            sha.update(reader.next().duplicate());
            hashes.add(sha.digest());
        }
        return hashes;
    }

    private static void assertReplayMatches(final List<byte[]> expected, final List<byte[]> replay, final String name) {
        assertEquals(expected.size(), replay.size(), "Frame count changed after reset for " + name);
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(expected.get(i), replay.get(i),
                    "Frame " + i + " not byte-identical after reset for " + name);
        }
    }
}
