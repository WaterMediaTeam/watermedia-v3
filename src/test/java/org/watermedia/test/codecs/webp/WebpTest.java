package org.watermedia.test.codecs.webp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.util.PixelFormat;
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
 * WebP decoder verification, consolidating lossless, lossy and animated coverage.
 *
 * <p>Lossless fixtures are compared pixel-perfectly against {@code dwebp}-generated PAMs.
 * Lossy fixtures use relaxed similarity bounds because VP8 implementations legitimately
 * differ in DCT rounding, quantization and loop-filter behavior. Animated fixtures only
 * exercise multi-frame decoding (dwebp doesn't reference-render animated WebP).
 *
 * <p>All tests request {@link PixelFormat#BGRA} so the no-arg overload's native-YUV
 * default for lossy WebP doesn't trip pixel comparisons.
 */
@DisplayName("WebP decoder")
public class WebpTest {

    // LOSSY VP8 DECODE BOUNDS — DECODER VARIANTS ARE NORMAL
    private static final int LOSSY_MAX_DIFF_TOLERANCE = 100;
    private static final double LOSSY_MAX_DIFF_PERCENTAGE = 30.0;
    private static final int LOSSY_SMALL_THRESHOLD = 10;

    // LOSSLESS MUST BE PIXEL-PERFECT
    private static final int LOSSLESS_TOLERANCE = 0;

    @TestFactory
    @DisplayName("Lossless decode and pixel match")
    Iterable<DynamicTest> testLossless() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Path imageFile: listWebp(Fixtures.WEBP_LOSSLESS_DIR)) {
            final String name = imageFile.getFileName().toString();

            tests.add(dynamicTest("LOSSLESS decode [" + name + "]",
                    () -> assertDecodeShape(imageFile, false)));

            tests.add(dynamicTest("LOSSLESS data match [" + name + "]", () -> {
                final ImageData decoded = CodecsAPI.decodeImage(Fixtures.readAll(imageFile), PixelFormat.BGRA);
                assertNotNull(decoded, "WEBP signature not recognized");
                final Path pamPath = Fixtures.WEBP_LOSSLESS_DIR.resolve("raw")
                        .resolve(name.replace(".webp", ".pam"));
                assertTrue(Files.exists(pamPath),
                        "Reference PAM file not found: " + pamPath.toAbsolutePath()
                                + " - Run dwebp to generate reference data");
                final PamImage reference = PamImage.read(pamPath);
                final byte[] actualRgba = PamImage.bgraToRgba(decoded.frames()[0],
                        decoded.width(), decoded.height());

                assertEquals(reference.pixels().length, actualRgba.length,
                        "Pixel data size mismatch - reference: " + reference.pixels().length
                                + ", java: " + actualRgba.length);

                final PixelDiff diff = PixelDiff.compare(reference.pixels(), actualRgba, LOSSLESS_TOLERANCE);
                assertTrue(diff.maxDiff() <= LOSSLESS_TOLERANCE,
                        String.format(
                                "Pixel data mismatch for %s - max diff: %d (tolerance: %d), %.2f%% of values exceed tolerance",
                                name, diff.maxDiff(), LOSSLESS_TOLERANCE, diff.percentAboveSmall()));
            }));
        }
        return tests;
    }

    @TestFactory
    @DisplayName("Lossy decode and similarity")
    Iterable<DynamicTest> testLossy() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Path imageFile: listWebp(Fixtures.WEBP_LOSSY_DIR)) {
            final String name = imageFile.getFileName().toString();

            tests.add(dynamicTest("LOSSY decode [" + name + "]",
                    () -> assertDecodeShape(imageFile, false)));

            tests.add(dynamicTest("LOSSY similarity [" + name + "]", () -> {
                final ImageData decoded = CodecsAPI.decodeImage(Fixtures.readAll(imageFile), PixelFormat.BGRA);
                assertNotNull(decoded, "WEBP signature not recognized");
                final Path pamPath = Fixtures.WEBP_LOSSY_DIR.resolve("raw")
                        .resolve(name.replace(".webp", ".pam"));
                assertTrue(Files.exists(pamPath),
                        "Reference PAM file not found: " + pamPath.toAbsolutePath()
                                + " - Run dwebp to generate reference data");
                final PamImage reference = PamImage.read(pamPath);
                final byte[] actualRgba = PamImage.bgraToRgba(decoded.frames()[0],
                        decoded.width(), decoded.height());

                assertEquals(reference.pixels().length, actualRgba.length,
                        "Pixel data size mismatch - reference: " + reference.pixels().length
                                + ", java: " + actualRgba.length);

                final PixelDiff diff = PixelDiff.compare(reference.pixels(), actualRgba, LOSSY_SMALL_THRESHOLD);
                assertTrue(diff.maxDiff() <= LOSSY_MAX_DIFF_TOLERANCE,
                        String.format(
                                "Lossy %s has extreme pixel difference - max: %d (limit: %d), mean: %.2f, %.2f%% above threshold %d",
                                name, diff.maxDiff(), LOSSY_MAX_DIFF_TOLERANCE,
                                diff.meanDiff(), diff.percentAboveSmall(), LOSSY_SMALL_THRESHOLD));
                assertTrue(diff.percentAboveSmall() <= LOSSY_MAX_DIFF_PERCENTAGE,
                        String.format(
                                "Lossy %s has too many differing pixels - %.2f%% above threshold %d (limit: %.1f%%), max diff: %d, mean diff: %.2f",
                                name, diff.percentAboveSmall(), LOSSY_SMALL_THRESHOLD,
                                LOSSY_MAX_DIFF_PERCENTAGE, diff.maxDiff(), diff.meanDiff()));
            }));
        }
        return tests;
    }

    @TestFactory
    @DisplayName("Animated decode")
    Iterable<DynamicTest> testAnimated() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Path imageFile: listWebp(Fixtures.WEBP_ANIMATED_DIR)) {
            final String name = imageFile.getFileName().toString();
            // ANIMATED-ONLY — dwebp DOES NOT GENERATE A REFERENCE FOR ANIMATED WEBP
            tests.add(dynamicTest("ANIMATED decode [" + name + "]",
                    () -> assertDecodeShape(imageFile, true)));
        }
        return tests;
    }

    @TestFactory
    @DisplayName("reset() replay")
    Iterable<DynamicTest> testReset() {
        final List<DynamicTest> tests = new ArrayList<>();
        // EVERY ANIMATED FIXTURE PLUS ONE STATIC OF EACH CODEC — reset() MUST REPLAY ALL SHAPES
        final List<Path> files = new ArrayList<>(listWebp(Fixtures.WEBP_ANIMATED_DIR));
        files.add(Fixtures.WEBP_LOSSLESS);
        files.add(Fixtures.WEBP_LOSSY_DIR.resolve("1.webp"));
        for (final Path imageFile: files) {
            final String name = imageFile.getParent().getFileName() + "/" + imageFile.getFileName();
            tests.add(dynamicTest("RESET replay [" + name + "]", () -> {
                final ByteBuffer source = ByteBuffer.wrap(Fixtures.readAll(imageFile));
                try (final ImageReader reader = CodecsAPI.decodeImage(source, PixelFormat.BGRA)) {
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

    // SHARED DECODE-SHAPE ASSERTION — REUSED ACROSS THREE FACTORIES, NAMED CLEARLY
    private static void assertDecodeShape(final Path imageFile, final boolean expectAnimated) throws IOException {
        final byte[] bytes = Fixtures.readAll(imageFile);
        final ImageData result = CodecsAPI.decodeImage(bytes, PixelFormat.BGRA);
        assertNotNull(result, "WEBP signature not recognized for: " + imageFile.getFileName());

        assertTrue(result.width() > 0, "Width must be positive");
        assertTrue(result.height() > 0, "Height must be positive");
        assertNotNull(result.frames(), "Frames array is null");
        assertTrue(result.frames().length > 0, "No frames decoded");

        if (expectAnimated) {
            assertTrue(result.frames().length > 1,
                    "Animated WebP should have multiple frames, got: " + result.frames().length);
            assertEquals(result.frames().length, result.delay().length,
                    "Delay array length must match frame count");
            assertTrue(result.duration() > 0, "Animation duration must be positive");
        }

        final int expectedBufferSize = result.width() * result.height() * 4;
        for (int i = 0; i < result.frames().length; i++) {
            final ByteBuffer frame = result.frames()[i];
            assertNotNull(frame, "Frame " + i + " is null");
            assertEquals(expectedBufferSize, frame.capacity(), "Frame " + i + " buffer size mismatch");
        }
    }

    private static List<Path> listWebp(final Path dir) {
        assertTrue(Files.exists(dir), "Test folder does not exist: " + dir);
        try (final Stream<Path> entries = Files.list(dir)) {
            final List<Path> images = entries
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".webp"))
                    .sorted()
                    .toList();
            assertTrue(!images.isEmpty(), "Test folder is empty: " + dir);
            return images;
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to enumerate WebP fixtures in " + dir, e);
        }
    }
}
