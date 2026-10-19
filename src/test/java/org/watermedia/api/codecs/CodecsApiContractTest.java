package org.watermedia.api.codecs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.test.support.Fixtures;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link CodecsAPI} contract: format detection, header consumption on the
 * caller's buffer, reader-level invariants ({@code width/height/frameCount/delays},
 * {@code readAll}), the overloads and {@link ImageMetadata} normalization for PNG and JPEG.
 */
@DisplayName("CodecsAPI contract")
public class CodecsApiContractTest {

    // SYNTHETIC PNG tEXt METADATA USED BY THE NORMALIZATION TEST
    private static final String PNG_TITLE_VALUE = "WaterMedia";
    private static final String PNG_AUTHOR_VALUE = "J-RAP";

    // ==========================================================================
    // FORMAT DETECTION TESTS
    // ==========================================================================

    @Nested
    @DisplayName("Format Detection")
    class FormatDetectionTests {

        @Test
        @DisplayName("decode(byte[]) throws UnsupportedFormatException for unknown bytes")
        void testDecodeImageByteArrayThrowsUnsupportedFormat() {
            final byte[] invalid = {0x00, 0x11, 0x22, 0x33, 0x44};
            assertThrows(UnsupportedFormatException.class, () -> CodecsAPI.decode(invalid));
        }

        @Test
        @DisplayName("reader(ByteBuffer) throws UnsupportedFormatException for unknown bytes")
        void testDecodeImageByteBufferThrowsUnsupportedFormat() {
            final ByteBuffer invalid = ByteBuffer.wrap(new byte[] {0x01, 0x02, 0x03, 0x04});
            assertThrows(UnsupportedFormatException.class, () -> CodecsAPI.reader(invalid));
        }

        @Test
        @DisplayName("Readers expose their format name without consuming past the header")
        void testReadersExposeFormatNameWithoutConsumingPastHeader() throws Exception {
            record Fixture(String name, Path path, int headerBytes) {}

            for (final Fixture fixture: List.of(
                    new Fixture("PNG", Fixtures.PNG_ANIMATED, 8),
                    new Fixture("GIF", Fixtures.GIF_ANIMATED, 6),
                    new Fixture("WEBP", Fixtures.WEBP_LOSSLESS, 12),
                    new Fixture("NETPBM", Fixtures.NETPBM_PPM, 2),
                    new Fixture("JPEG", Fixtures.JPEG_BASELINE, 2)
            )) {
                final ByteBuffer data = ByteBuffer.wrap(Fixtures.readAll(fixture.path()));

                try (final ImageReader reader = CodecsAPI.reader(data)) {
                    assertEquals(fixture.name(), reader.name());
                    assertEquals(fixture.headerBytes(), data.position(),
                            fixture.name() + " should leave caller buffer after the format header");
                    assertTrue(reader.hasNext(), fixture.name() + " fixture should expose a frame");
                }
            }
        }
    }

    // ==========================================================================
    // READER INVARIANT TESTS
    // ==========================================================================

    @Nested
    @DisplayName("Reader Invariants")
    class ReaderInvariantTests {

        @Test
        @DisplayName("reader(ByteBuffer) returns a reader and skips the header")
        void testDecodeImageByteBufferReturnsReaderAndSkipsHeader() throws Exception {
            final ByteBuffer data = ByteBuffer.wrap(Fixtures.readAll(Fixtures.PNG_ANIMATED));

            try (final ImageReader reader = CodecsAPI.reader(data)) {
                assertEquals(8, data.position(), "PNG header should be consumed by CodecsAPI");
                assertTrue(reader.width() > 0);
                assertTrue(reader.height() > 0);
                assertTrue(reader.frameCount() > 0);
                assertEquals(reader.frameCount(), reader.delays().length);
                assertTrue(reader.hasNext());
                assertTrue(reader.next().isDirect());
            }
        }

        @Test
        @DisplayName("reader.readAll() returns ImageData consistent with the reader")
        void testReaderReadAllReturnsImageData() throws Exception {
            final ByteBuffer data = ByteBuffer.wrap(Fixtures.readAll(Fixtures.PNG_ANIMATED));

            try (final ImageReader reader = CodecsAPI.reader(data)) {
                final ImageData image = reader.readAll();
                assertNotNull(image);
                assertEquals(reader.width(), image.width());
                assertEquals(reader.height(), image.height());
                assertEquals(reader.frameCount(), image.frames().length);
            }
        }

        @Test
        @DisplayName("decode accepts buffers and streams, sniff accepts buffers, and null arguments are rejected")
        void testOverloadsAndArguments() throws Exception {
            final byte[] bytes = Fixtures.readAll(Fixtures.PNG_STATIC);
            final ImageData fromArray = CodecsAPI.decode(bytes);
            final ImageData fromBuffer = CodecsAPI.decode(ByteBuffer.wrap(bytes));
            final ImageData fromStream = CodecsAPI.decode(new ByteArrayInputStream(bytes));
            assertEquals(fromArray.frames().length, fromBuffer.frames().length);
            assertEquals(fromArray.width(), fromStream.width());
            assertEquals(fromArray.format(), fromStream.format());

            final ByteBuffer probe = ByteBuffer.wrap(bytes);
            assertEquals(MediaType.IMAGE, CodecsAPI.sniff(probe));
            assertEquals(0, probe.position(), "sniffing a buffer leaves its position alone");

            assertThrows(IllegalArgumentException.class, () -> CodecsAPI.reader(null));
            assertThrows(IllegalArgumentException.class, () -> CodecsAPI.decode((byte[]) null));
            assertThrows(IllegalArgumentException.class, () -> CodecsAPI.decode((InputStream) null));
            assertThrows(IllegalArgumentException.class, () -> CodecsAPI.sniff((ByteBuffer) null));
            assertThrows(IllegalArgumentException.class, () -> CodecsAPI.reader(ByteBuffer.wrap(bytes), PixelFormat.YUV420P));
        }

        @Test
        @DisplayName("A requested RGBA layout survives reset() on every reader")
        void testRequestedLayoutSurvivesReset() throws Exception {
            for (final Path fixture: List.of(Fixtures.PNG_STATIC, Fixtures.GIF_ANIMATED, Fixtures.WEBP_LOSSLESS, Fixtures.JPEG_BASELINE, Fixtures.NETPBM_PPM)) {
                final byte[] bytes = Fixtures.readAll(fixture);
                final ByteBuffer bgra;
                try (final ImageReader reference = CodecsAPI.reader(ByteBuffer.wrap(bytes), PixelFormat.BGRA)) { bgra = copy(reference.next()); }
                try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(bytes), PixelFormat.RGBA)) {
                    assertEquals(PixelFormat.RGBA, reader.pixelFormat(), fixture.toString());
                    final ByteBuffer first = copy(reader.next());
                    assertTrue(reader.reset(), fixture.toString());
                    assertEquals(first, copy(reader.next()), fixture + " must deliver the same RGBA bytes after reset()");
                    // THE SWIZZLE MUST HAVE HAPPENED: RED AND BLUE SWAP PLACES AGAINST THE BGRA DECODE
                    assertEquals(bgra.get(0), first.get(2), fixture.toString());
                    assertEquals(bgra.get(2), first.get(0), fixture.toString());
                }
            }
        }

        @Test
        @DisplayName("A reader that refuses its body leaves the buffer position untouched")
        void testRefusedBodyRestoresPosition() {
            // A PNG SIGNATURE FOLLOWED BY GARBAGE: THE HEADER MATCHES, THE BODY DOES NOT
            final ByteBuffer source = ByteBuffer.wrap(new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4 });
            source.position(0);
            assertThrows(IOException.class, () -> CodecsAPI.reader(source));
            assertEquals(0, source.position());
        }

        @Test
        @DisplayName("GIF quick read exposes duration, frame count and delays")
        void testGifQuickReadExposesDurationFrameCountAndDelays() throws Exception {
            final ByteBuffer data = ByteBuffer.wrap(Fixtures.readAll(Fixtures.GIF_ANIMATED));

            try (final ImageReader reader = CodecsAPI.reader(data)) {
                assertEquals(6, data.position(), "GIF header should be consumed by CodecsAPI");
                assertTrue(reader.frameCount() > 1, "GIF fixture should be animated");
                assertEquals(reader.frameCount(), reader.delays().length);
                assertTrue(reader.duration() > 0L, "Animated GIF duration should be known after construction");
            }
        }
    }

    // ==========================================================================
    // METADATA TESTS
    // ==========================================================================

    @Nested
    @DisplayName("Metadata Normalization")
    class MetadataTests {

        @Test
        @DisplayName("PNG tEXt metadata is normalized and exposed in the generic map")
        void testPngTextMetadataIsNormalizedAndExposedInGenericMap() throws Exception {
            // BUILD A SYNTHETIC 1x1 PNG WITH tEXt CHUNKS — VALIDATES METADATA NORMALIZATION
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A });
            writePngChunk(out, "IHDR", new byte[] {
                    0x00, 0x00, 0x00, 0x01,
                    0x00, 0x00, 0x00, 0x01,
                    0x08, 0x00, 0x00, 0x00, 0x00
            });
            writePngChunk(out, "tEXt", pngText("Title", PNG_TITLE_VALUE));
            writePngChunk(out, "tEXt", pngText("Author", PNG_AUTHOR_VALUE));
            writePngChunk(out, "IDAT", new byte[] {
                    0x78, (byte) 0x9C, 0x63, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01
            });
            writePngChunk(out, "IEND", new byte[0]);

            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(out.toByteArray()))) {
                final ImageMetadata metadata = reader.metadata();

                assertEquals(PNG_TITLE_VALUE, metadata.title());
                assertEquals(List.of(PNG_AUTHOR_VALUE), metadata.authors());
                assertNull(metadata.description());
                assertNotNull(metadata.values());
                assertInstanceOf(Map.class, metadata.value(CodecsAPI.PNG_METAKEY_TEXT));
            }
        }

        @Test
        @DisplayName("Empty metadata: scalar fields null, collection fields empty (not null)")
        void testEmptyMetadataFields() throws Exception {
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(Fixtures.readAll(Fixtures.JPEG_BASELINE)))) {
                final ImageMetadata metadata = reader.metadata();
                assertNull(metadata.title());
                assertTrue(metadata.authors().isEmpty());
                assertTrue(metadata.values().isEmpty());
            }
        }
    }

    private static ByteBuffer copy(final ByteBuffer frame) {
        final ByteBuffer heap = ByteBuffer.allocate(frame.remaining());
        heap.put(frame.duplicate()).flip();
        return heap;
    }

    private static byte[] pngText(final String key, final String value) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(key.getBytes(StandardCharsets.ISO_8859_1));
        out.write(0);
        out.write(value.getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    private static void writePngChunk(final ByteArrayOutputStream out, final String type, final byte[] data) throws IOException {
        writeInt(out, data.length);
        final byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.write(typeBytes);
        out.write(data);

        final CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    private static void writeInt(final ByteArrayOutputStream out, final int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}
