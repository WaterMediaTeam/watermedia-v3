package org.watermedia.api.codecs.readers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
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
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * <p>Shape checks use the no-arg overload, proving every WebP shape natively delivers
 * {@link PixelFormat#BGRA}; pixel comparisons request it explicitly.
 */
@DisplayName("WebP decoder")
public class WebpTest {

    // LOSSY VP8 DECODE BOUNDS — DECODER VARIANTS ARE NORMAL
    private static final int LOSSY_MAX_DIFF_TOLERANCE = 100;
    private static final double LOSSY_MAX_DIFF_PERCENTAGE = 30.0;
    private static final int LOSSY_SMALL_THRESHOLD = 10;

    // LOSSLESS MUST BE PIXEL-PERFECT
    private static final int LOSSLESS_TOLERANCE = 0;

    @Test
    @DisplayName("EXIF orientation 6 rotates static and animated pictures 90 degrees clockwise and swaps their size")
    void orientationRotates() throws IOException {
        // A LITTLE-ENDIAN TIFF WHOSE IFD0 HOLDS ONE ENTRY: ORIENTATION (0x0112), SHORT, COUNT 1, VALUE 6
        final byte[] exif = { 'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0 };
        for (final Path fixture: List.of(Fixtures.WEBP_LOSSLESS, Fixtures.WEBP_ANIMATED)) {
            final byte[] plain = Fixtures.readAll(fixture);
            final ImageData upright = CodecsAPI.decode(plain, PixelFormat.BGRA);
            final int w = upright.width(), h = upright.height();
            final byte[] tagged = withExif(plain, exif, w, h);
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(tagged))) {
                assertEquals(6, reader.metadata().value(CodecsAPI.WEBP_METAKEY_ORIENTATION), fixture + " orientation key");
            }
            final ImageData turned = CodecsAPI.decode(tagged, PixelFormat.BGRA);
            assertEquals(h, turned.width(), fixture + " rotated width");
            assertEquals(w, turned.height(), fixture + " rotated height");
            assertEquals(upright.frames().length, turned.frames().length, fixture + " frames");
            for (int f = 0; f < upright.frames().length; f++) {
                final IntBuffer a = upright.frames()[f].duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                final IntBuffer b = turned.frames()[f].duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                // OUTPUT PIXEL (x, y) COMES FROM SOURCE PIXEL (y, h - 1 - x)
                for (final int[] p: new int[][] { { 0, 0 }, { h - 1, 0 }, { 0, w - 1 }, { h / 2, w / 3 }, { h - 1, w - 1 } }) {
                    final int x = p[0], y = p[1];
                    assertEquals(a.get((h - 1 - x) * w + y), b.get(y * h + x), fixture + " frame " + f + " pixel " + x + "," + y);
                }
            }
        }
    }

    // APPENDS AN EXIF CHUNK: A SIMPLE FILE IS FIRST WRAPPED INTO A VP8X CONTAINER, AN EXTENDED ONE JUST GAINS THE EXIF FLAG
    private static byte[] withExif(final byte[] webp, final byte[] exif, final int width, final int height) {
        final boolean extended = webp[12] == 'V' && webp[13] == 'P' && webp[14] == '8' && webp[15] == 'X';
        final ByteBuffer out = ByteBuffer.allocate(webp.length + 8 + exif.length + (exif.length & 1) + (extended ? 0 : 18))
                .order(ByteOrder.LITTLE_ENDIAN);
        out.put(webp, 0, 12);
        if (!extended) {
            out.put((byte) 'V').put((byte) 'P').put((byte) '8').put((byte) 'X').putInt(10)
                    .put((byte) 0x08).put((byte) 0).put((byte) 0).put((byte) 0)
                    .put((byte) (width - 1)).put((byte) ((width - 1) >> 8)).put((byte) ((width - 1) >> 16))
                    .put((byte) (height - 1)).put((byte) ((height - 1) >> 8)).put((byte) ((height - 1) >> 16));
        }
        out.put(webp, 12, webp.length - 12);
        if (extended) out.put(20, (byte) (webp[20] | 0x08));
        out.put((byte) 'E').put((byte) 'X').put((byte) 'I').put((byte) 'F').putInt(exif.length).put(exif);
        if ((exif.length & 1) == 1) out.put((byte) 0);
        out.putInt(4, out.position() - 8);
        return Arrays.copyOf(out.array(), out.position());
    }

    @TestFactory
    @DisplayName("Lossless decode and pixel match")
    Iterable<DynamicTest> testLossless() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Path imageFile: listWebp(Fixtures.WEBP_LOSSLESS_DIR)) {
            final String name = imageFile.getFileName().toString();

            tests.add(dynamicTest("LOSSLESS decode [" + name + "]",
                    () -> assertDecodeShape(imageFile, false)));

            tests.add(dynamicTest("LOSSLESS data match [" + name + "]", () -> {
                final ImageData decoded = CodecsAPI.decode(Fixtures.readAll(imageFile), PixelFormat.BGRA);
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
                final ImageData decoded = CodecsAPI.decode(Fixtures.readAll(imageFile), PixelFormat.BGRA);
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
                try (final ImageReader reader = CodecsAPI.reader(source, PixelFormat.BGRA)) {
                    final long[] delays = reader.delays().clone();
                    final List<byte[]> first = decodeFrameHashes(reader);
                    assertFalse(first.isEmpty(), "No frames decoded for " + name);

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

    // SHARED DECODE-SHAPE ASSERTION — REUSED ACROSS THREE FACTORIES; THE NATIVE LAYOUT MUST BE BGRA-SIZED
    private static void assertDecodeShape(final Path imageFile, final boolean expectAnimated) throws IOException {
        final byte[] bytes = Fixtures.readAll(imageFile);
        final ImageData result = CodecsAPI.decode(bytes);
        assertNotNull(result, "WEBP signature not recognized for: " + imageFile.getFileName());

        assertTrue(result.width() > 0, "Width must be positive");
        assertTrue(result.height() > 0, "Height must be positive");
        assertNotNull(result.frames(), "Frames array is null");
        assertTrue(result.frames().length > 0, "No frames decoded");

        if (expectAnimated) {
            assertTrue(result.frames().length > 1,
                    "Animated WebP should have multiple frames, got: " + result.frames().length);
            assertEquals(result.frames().length, result.delays().length,
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
            assertFalse(images.isEmpty(), "Test folder is empty: " + dir);
            return images;
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to enumerate WebP fixtures in " + dir, e);
        }
    }
}
