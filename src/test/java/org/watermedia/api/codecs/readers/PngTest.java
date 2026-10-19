package org.watermedia.api.codecs.readers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageMetadata;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.UnsupportedFormatException;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.PamImage;
import org.watermedia.test.support.PixelDiff;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Verifies the PNG decoder against the fixtures and the reader contract with synthetic streams.
 *
 * <p>For every PNG fixture under {@code src/test/resources/png} two dynamic tests run:
 * the first checks that {@code CodecsAPI.decode} produces a well-formed
 * {@link ImageData} (positive dimensions, frame buffers sized {@code w*h*4}), and the
 * second compares the first decoded frame pixel-for-pixel against a pre-generated PAM
 * reference under {@code png/raw}. PNG is lossless so the tolerance is zero.
 */
@DisplayName("PNG decoder")
public class PngTest {

    // PNG IS LOSSLESS — REFERENCE MATCH MUST BE EXACT
    private static final int LOSSLESS_TOLERANCE = 0;

    @TestFactory
    @DisplayName("Decode shape and pixel match")
    Iterable<DynamicTest> testPNG() {
        final List<DynamicTest> tests = new ArrayList<>();

        // VERIFY FIXTURE FOLDER POPULATED
        try (final Stream<Path> entries = Files.list(Fixtures.PNG_DIR)) {
            final List<Path> images = entries
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png"))
                    .sorted()
                    .toList();
            assertFalse(images.isEmpty(), "Test folder is empty: " + Fixtures.PNG_DIR);

            for (final Path imageFile: images) {
                final String name = imageFile.getFileName().toString();

                // TEST 1: DECODING SUCCEEDS WITH SANE BUFFER GEOMETRY
                tests.add(dynamicTest("PNG decode [" + name + "]", () -> {
                    final byte[] bytes = Fixtures.readAll(imageFile);
                    final ImageData result = CodecsAPI.decode(bytes);
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
                    final ImageData decoded = CodecsAPI.decode(Fixtures.readAll(imageFile));
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
    @DisplayName("reset() replay")
    Iterable<DynamicTest> testPNGReset() {
        final List<DynamicTest> tests = new ArrayList<>();
        try (final Stream<Path> entries = Files.list(Fixtures.PNG_DIR)) {
            final List<Path> images = entries
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png"))
                    .sorted()
                    .toList();
            assertFalse(images.isEmpty(), "Test folder is empty: " + Fixtures.PNG_DIR);

            // COVERS BOTH STATIC PNG AND APNG — reset() MUST REPLAY EITHER ONE IDENTICALLY
            for (final Path imageFile: images) {
                final String name = imageFile.getFileName().toString();
                tests.add(dynamicTest("PNG reset replay [" + name + "]", () -> {
                    try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(Fixtures.readAll(imageFile)))) {
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
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to enumerate PNG fixtures", e);
        }

        return tests;
    }

    @Test
    @DisplayName("useBKGDChunk presents the picture against its bKGD colour")
    void bkgdPresentation() throws IOException {
        // 2x1 RGBA: A HALF-COVERING RED PIXEL AND A FULLY TRANSPARENT GREEN ONE, OVER A BLUE bKGD
        final ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.writeBytes(new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' });
        chunk(png, "IHDR", new byte[] { 0, 0, 0, 2, 0, 0, 0, 1, 8, 6, 0, 0, 0 });
        chunk(png, "bKGD", new byte[] { 0, 0, 0, 0, 0, (byte) 255 });
        final Deflater deflater = new Deflater();
        deflater.setInput(new byte[] { 0, (byte) 255, 0, 0, (byte) 128, 0, (byte) 255, 0, 0 });
        deflater.finish();
        final byte[] idat = new byte[64];
        final int compressed = deflater.deflate(idat);
        deflater.end();
        chunk(png, "IDAT", Arrays.copyOf(idat, compressed));
        chunk(png, "IEND", new byte[0]);
        final byte[] bytes = png.toByteArray();

        final boolean previous = WaterMediaConfig.decoders.png.useBKGDChunk;
        try {
            WaterMediaConfig.decoders.png.useBKGDChunk = false;
            assertArrayEquals(new int[] { 0x80FF0000, 0x0000FF00 }, pixels(CodecsAPI.decode(bytes)), "transparency kept");
            WaterMediaConfig.decoders.png.useBKGDChunk = true;
            assertArrayEquals(new int[] { 0xFF80007F, 0xFF0000FF }, pixels(CodecsAPI.decode(bytes)), "flattened onto bKGD");
        } finally {
            WaterMediaConfig.decoders.png.useBKGDChunk = previous;
        }
    }

    @Nested
    @DisplayName("Reader contract")
    class ContractTests {
        @Test
        @DisplayName("An APNG default image ahead of the first fcTL is not a frame")
        void defaultImageIsNotAFrame() throws IOException {
            // 1x1 RGBA: A RED DEFAULT IMAGE, THEN TWO ANIMATION FRAMES, GREEN AND BLUE, OF 100 MS EACH
            final ByteArrayOutputStream png = new ByteArrayOutputStream();
            png.writeBytes(SIGNATURE);
            chunk(png, "IHDR", new byte[] { 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0 });
            chunk(png, "acTL", new byte[] { 0, 0, 0, 2, 0, 0, 0, 0 });
            chunk(png, "IDAT", deflate(new byte[] { 0, (byte) 255, 0, 0, (byte) 255 }));
            chunk(png, "fcTL", fctl(0));
            chunk(png, "fdAT", fdat(1, new byte[] { 0, 0, (byte) 255, 0, (byte) 255 }));
            chunk(png, "fcTL", fctl(2));
            chunk(png, "fdAT", fdat(3, new byte[] { 0, 0, 0, (byte) 255, (byte) 255 }));
            chunk(png, "IEND", new byte[0]);

            final ImageData image = CodecsAPI.decode(png.toByteArray());
            assertEquals(2, image.frames().length, "the default image is not part of the animation");
            assertArrayEquals(new long[] { 100L, 100L }, image.delays());
            assertEquals(ImageData.REPEAT_FOREVER, image.repeat());
            assertEquals(0xFF00FF00, pixels(image)[0], "the first frame is the green animation frame, not the red default image");
            assertEquals(0xFF0000FF, image.frames()[1].duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(0));
        }

        @Test
        @DisplayName("Metadata met between frames is stored once, however many replays")
        void metadataIsStableAcrossReplays() throws IOException {
            final ByteArrayOutputStream png = new ByteArrayOutputStream();
            png.writeBytes(SIGNATURE);
            chunk(png, "IHDR", new byte[] { 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0 });
            chunk(png, "acTL", new byte[] { 0, 0, 0, 2, 0, 0, 0, 1 });
            chunk(png, "fcTL", fctl(0));
            chunk(png, "IDAT", deflate(new byte[] { 0, (byte) 255, 0, 0, (byte) 255 }));
            chunk(png, "tEXt", "Comment\0between frames".getBytes(StandardCharsets.ISO_8859_1));
            chunk(png, "fcTL", fctl(1));
            chunk(png, "fdAT", fdat(2, new byte[] { 0, 0, (byte) 255, 0, (byte) 255 }));
            chunk(png, "IEND", new byte[0]);

            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(png.toByteArray()))) {
                final ImageMetadata early = reader.metadata();
                for (int pass = 0; pass < 3; pass++) {
                    while (reader.hasNext()) reader.next();
                    assertEquals(List.of("between frames"), reader.metadata().comments(), "pass " + pass);
                    assertTrue(reader.reset());
                }
                assertTrue(early.comments().isEmpty(), "a snapshot never changes under the consumer");
                assertEquals(1, reader.repeat(), "one declared play");
                assertThrows(UnsupportedOperationException.class, () -> reader.metadata().comment("x"), "snapshots are read-only");
            }
        }

        @Test
        @DisplayName("A one-frame animation still skips its default image and compressed text obeys a byte budget")
        void singleFrameDefaultImageAndTextBudget() throws IOException {
            final ByteArrayOutputStream png = new ByteArrayOutputStream();
            png.writeBytes(SIGNATURE);
            chunk(png, "IHDR", new byte[] { 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0 });
            chunk(png, "acTL", new byte[] { 0, 0, 0, 1, 0, 0, 0, 0 });
            // THREE zTXt CHUNKS OF 2 MiB EACH: THE 4 MiB TEXT BUDGET KEEPS TWO AND SKIPS THE THIRD WITHOUT FAILING
            final byte[] bomb = deflate(new byte[2 << 20]);
            for (int i = 0; i < 3; i++) {
                final ByteArrayOutputStream ztxt = new ByteArrayOutputStream();
                ztxt.writeBytes("Comment".getBytes(StandardCharsets.ISO_8859_1));
                ztxt.write(0);
                ztxt.write(0);
                ztxt.writeBytes(bomb);
                chunk(png, "zTXt", ztxt.toByteArray());
            }
            chunk(png, "IDAT", deflate(new byte[] { 0, (byte) 255, 0, 0, (byte) 255 }));
            chunk(png, "fcTL", fctl(0));
            chunk(png, "fdAT", fdat(1, new byte[] { 0, 0, (byte) 255, 0, (byte) 255 }));
            chunk(png, "IEND", new byte[0]);

            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(png.toByteArray()))) {
                assertEquals(1, reader.frameCount());
                final ImageData image = reader.readAll();
                assertEquals(1, image.frames().length, "the default image is not a frame even with one fcTL");
                assertEquals(0xFF00FF00, pixels(image)[0]);
                final Map<?, ?> texts = (Map<?, ?>) reader.metadata().value(CodecsAPI.PNG_METAKEY_COMPRESSED_TEXT);
                assertEquals(2, ((List<?>) texts.get("Comment")).size(), "the third 2 MiB text exceeds the budget and is skipped");
            }
        }

        @Test
        @DisplayName("Malformed ancillary chunks are skipped and an unknown critical chunk is unsupported")
        void ancillaryPolicy() throws IOException {
            final ByteArrayOutputStream png = new ByteArrayOutputStream();
            png.writeBytes(SIGNATURE);
            chunk(png, "IHDR", new byte[] { 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0 });
            chunk(png, "tEXt", new byte[] { 0, 'x' }); // EMPTY KEYWORD
            chunk(png, "gAMA", new byte[] { 0, 1, (byte) 0x86, (byte) 0xA0 }); // 100000 = GAMMA 1.0
            chunk(png, "tIME", new byte[] { 7, (byte) 0xE9, 10, 18, 12, 30, 45 });
            chunk(png, "sRGB", new byte[] { 1 });
            chunk(png, "IDAT", deflate(new byte[] { 0, (byte) 255, 0, 0, (byte) 255 }));
            chunk(png, "IEND", new byte[0]);

            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(png.toByteArray()))) {
                final ImageMetadata metadata = reader.metadata();
                assertNull(metadata.value(CodecsAPI.PNG_METAKEY_TEXT), "the malformed tEXt is skipped");
                assertEquals(1.0, metadata.value(CodecsAPI.PNG_METAKEY_GAMMA));
                assertEquals(LocalDateTime.of(2025, 10, 18, 12, 30, 45), metadata.value(CodecsAPI.PNG_METAKEY_LAST_MODIFIED_TIME));
                assertEquals(1, metadata.value(CodecsAPI.PNG_METAKEY_SRGB));
                assertTrue(reader.hasNext(), "a skipped chunk never fails the decode");
            }

            final ByteArrayOutputStream critical = new ByteArrayOutputStream();
            critical.writeBytes(SIGNATURE);
            chunk(critical, "IHDR", new byte[] { 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0 });
            chunk(critical, "ZZZZ", new byte[] { 1 });
            chunk(critical, "IDAT", deflate(new byte[] { 0, (byte) 255, 0, 0, (byte) 255 }));
            chunk(critical, "IEND", new byte[0]);
            final ByteBuffer source = ByteBuffer.wrap(critical.toByteArray());
            assertThrows(UnsupportedFormatException.class, () -> CodecsAPI.reader(source));
            assertEquals(0, source.position(), "a refused body leaves the buffer where it was");
        }

        @Test
        @DisplayName("The requested layout is honoured and the iterator contract holds")
        void requestedLayoutAndIteration() throws IOException {
            final ByteArrayOutputStream png = new ByteArrayOutputStream();
            png.writeBytes(SIGNATURE);
            chunk(png, "IHDR", new byte[] { 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0 });
            chunk(png, "IDAT", deflate(new byte[] { 0, (byte) 255, 0, 0, (byte) 255 }));
            chunk(png, "IEND", new byte[0]);
            final byte[] bytes = png.toByteArray();

            final ImageData rgba = CodecsAPI.decode(bytes, PixelFormat.RGBA);
            assertEquals(PixelFormat.RGBA, rgba.format());
            assertEquals(0xFF0000FF, pixels(rgba)[0], "red lands in the first byte under RGBA");
            final ImageData gray = CodecsAPI.decode(bytes, PixelFormat.GRAY);
            assertEquals(PixelFormat.GRAY, gray.format());
            assertEquals(1, gray.frames()[0].remaining());
            assertEquals(77, gray.frames()[0].get(0) & 0xFF, "BT.601 luma of pure red");

            final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(bytes));
            assertEquals(0L, reader.delay());
            reader.next();
            assertFalse(reader.hasNext());
            assertThrows(NoSuchElementException.class, reader::next);
            reader.close();
            reader.close();
            assertThrows(IllegalStateException.class, reader::hasNext);
            assertThrows(IllegalStateException.class, reader::reset);
            assertThrows(IllegalArgumentException.class, () -> CodecsAPI.decode(bytes, PixelFormat.YUV420P));
        }
    }

    private static final byte[] SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

    // fcTL FOR A 1x1 FRAME AT THE ORIGIN WITH A 1/10 S DELAY, DISPOSE NONE AND BLEND SOURCE
    private static byte[] fctl(final int sequence) {
        return ByteBuffer.allocate(26).putInt(sequence).putInt(1).putInt(1).putInt(0).putInt(0)
                .putShort((short) 1).putShort((short) 10).put((byte) 0).put((byte) 0).array();
    }

    private static byte[] fdat(final int sequence, final byte[] scanline) {
        final byte[] compressed = deflate(scanline);
        return ByteBuffer.allocate(4 + compressed.length).putInt(sequence).put(compressed).array();
    }

    private static byte[] deflate(final byte[] raw) {
        final Deflater deflater = new Deflater();
        deflater.setInput(raw);
        deflater.finish();
        final byte[] out = new byte[64 + raw.length];
        final int length = deflater.deflate(out);
        deflater.end();
        return Arrays.copyOf(out, length);
    }

    private static void chunk(final ByteArrayOutputStream out, final String type, final byte[] data) {
        final byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        final CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(12 + data.length).putInt(data.length).put(typeBytes).put(data).putInt((int) crc.getValue()).array());
    }

    private static int[] pixels(final ImageData image) {
        final int[] pixels = new int[image.width() * image.height()];
        image.frames()[0].duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels);
        return pixels;
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
