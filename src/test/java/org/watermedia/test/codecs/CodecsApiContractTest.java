package org.watermedia.test.codecs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageMetadata;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.UnsupportedFormatException;
import org.watermedia.test.support.Fixtures;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for {@link CodecsAPI}: format detection, header consumption on the
 * caller's buffer, reader-level invariants ({@code width/height/frameCount/delays},
 * {@code readAll}), and {@link ImageMetadata} normalization for PNG and JPEG.
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
        @DisplayName("decodeImage(byte[]) throws UnsupportedFormatException for unknown bytes")
        void testDecodeImageByteArrayThrowsUnsupportedFormat() {
            final byte[] invalid = {0x00, 0x11, 0x22, 0x33, 0x44};
            assertThrows(UnsupportedFormatException.class, () -> CodecsAPI.decodeImage(invalid));
        }

        @Test
        @DisplayName("decodeImage(ByteBuffer) throws UnsupportedFormatException for unknown bytes")
        void testDecodeImageByteBufferThrowsUnsupportedFormat() {
            final ByteBuffer invalid = ByteBuffer.wrap(new byte[] {0x01, 0x02, 0x03, 0x04});
            assertThrows(UnsupportedFormatException.class, () -> CodecsAPI.decodeImage(invalid));
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

                try (final ImageReader reader = CodecsAPI.decodeImage(data)) {
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
        @DisplayName("decodeImage(ByteBuffer) returns a reader and skips the header")
        void testDecodeImageByteBufferReturnsReaderAndSkipsHeader() throws Exception {
            final ByteBuffer data = ByteBuffer.wrap(Fixtures.readAll(Fixtures.PNG_ANIMATED));

            try (final ImageReader reader = CodecsAPI.decodeImage(data)) {
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

            try (final ImageReader reader = CodecsAPI.decodeImage(data)) {
                final ImageData image = reader.readAll();
                assertNotNull(image);
                assertEquals(reader.width(), image.width());
                assertEquals(reader.height(), image.height());
                assertEquals(reader.frameCount(), image.frames().length);
            }
        }

        @Test
        @DisplayName("GIF quick read exposes duration, frame count and delays")
        void testGifQuickReadExposesDurationFrameCountAndDelays() throws Exception {
            final ByteBuffer data = ByteBuffer.wrap(Fixtures.readAll(Fixtures.GIF_ANIMATED));

            try (final ImageReader reader = CodecsAPI.decodeImage(data)) {
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

            try (final ImageReader reader = CodecsAPI.decodeImage(ByteBuffer.wrap(out.toByteArray()))) {
                final ImageMetadata metadata = reader.metadata();

                assertEquals(PNG_TITLE_VALUE, metadata.title());
                assertEquals(List.of(PNG_AUTHOR_VALUE), metadata.authors());
                assertNull(metadata.description());
                assertNotNull(metadata.values());
                assertTrue(metadata.value(CodecsAPI.PNG_METAKEY_TEXT) instanceof Map<?, ?>);
            }
        }

        @Test
        @DisplayName("Empty metadata fields return null")
        void testEmptyMetadataFieldsReturnNull() throws Exception {
            try (final ImageReader reader = CodecsAPI.decodeImage(ByteBuffer.wrap(Fixtures.readAll(Fixtures.JPEG_BASELINE)))) {
                final ImageMetadata metadata = reader.metadata();
                assertNull(metadata.title());
                assertNull(metadata.authors());
                assertNull(metadata.values());
            }
        }
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
