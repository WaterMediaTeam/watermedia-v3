import org.junit.jupiter.api.Test;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageMetadata;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.UnsupportedFormatException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CodecsAPIContractTest {

    @Test
    void decodeImageByteArrayThrowsUnsupportedFormat() {
        final byte[] invalid = {0x00, 0x11, 0x22, 0x33, 0x44};
        assertThrows(UnsupportedFormatException.class, () -> CodecsAPI.decodeImage(invalid));
    }

    @Test
    void decodeImageByteBufferThrowsUnsupportedFormat() {
        final ByteBuffer invalid = ByteBuffer.wrap(new byte[] {0x01, 0x02, 0x03, 0x04});
        assertThrows(UnsupportedFormatException.class, () -> CodecsAPI.decodeImage(invalid));
    }

    @Test
    void decodeImageByteBufferReturnsReaderAndSkipsHeader() throws Exception {
        final ByteBuffer data = ByteBuffer.wrap(Files.readAllBytes(Path.of("src/test/resources/png/1.png")));

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
    void readerReadAllReturnsImageData() throws Exception {
        final ByteBuffer data = ByteBuffer.wrap(Files.readAllBytes(Path.of("src/test/resources/png/1.png")));

        try (final ImageReader reader = CodecsAPI.decodeImage(data)) {
            final ImageData image = reader.readAll();
            assertNotNull(image);
            assertEquals(reader.width(), image.width());
            assertEquals(reader.height(), image.height());
            assertEquals(reader.frameCount(), image.frames().length);
        }
    }

    @Test
    void gifQuickReadExposesDurationFrameCountAndDelays() throws Exception {
        final ByteBuffer data = ByteBuffer.wrap(Files.readAllBytes(Path.of("src/test/resources/gif/1.gif")));

        try (final ImageReader reader = CodecsAPI.decodeImage(data)) {
            assertEquals(6, data.position(), "GIF header should be consumed by CodecsAPI");
            assertTrue(reader.frameCount() > 1, "GIF fixture should be animated");
            assertEquals(reader.frameCount(), reader.delays().length);
            assertTrue(reader.duration() > 0L, "Animated GIF duration should be known after construction");
        }
    }

    @Test
    void readersExposeFormatNameWithoutConsumingPastHeader() throws Exception {
        record Fixture(String name, Path path, int headerBytes) {}

        for (final Fixture fixture: List.of(
                new Fixture("PNG", Path.of("src/test/resources/png/1.png"), 8),
                new Fixture("GIF", Path.of("src/test/resources/gif/1.gif"), 6),
                new Fixture("WEBP", Path.of("src/test/resources/webp/lossless/1.webp"), 12),
                new Fixture("NETPBM", Path.of("src/test/resources/netpbm/test.ppm"), 2),
                new Fixture("JPEG", Path.of("src/test/resources/netpbm/reference.jpg"), 2)
        )) {
            final ByteBuffer data = ByteBuffer.wrap(Files.readAllBytes(fixture.path()));

            try (final ImageReader reader = CodecsAPI.decodeImage(data)) {
                assertEquals(fixture.name(), reader.name());
                assertEquals(fixture.headerBytes(), data.position(),
                        fixture.name() + " should leave caller buffer after the format header");
                assertTrue(reader.hasNext(), fixture.name() + " fixture should expose a frame");
            }
        }
    }

    @Test
    void pngTextMetadataIsNormalizedAndExposedInGenericMap() throws Exception {
        try (final ImageReader reader = CodecsAPI.decodeImage(ByteBuffer.wrap(pngWithTextMetadata()))) {
            final ImageMetadata metadata = reader.metadata();

            assertEquals("WaterMedia", metadata.title());
            assertEquals(List.of("J-RAP"), metadata.authors());
            assertNull(metadata.description());
            assertNotNull(metadata.values());
            assertTrue(metadata.value(CodecsAPI.PNG_METAKEY_TEXT) instanceof Map<?, ?>);
        }
    }

    @Test
    void emptyMetadataFieldsReturnNull() throws Exception {
        final byte[] jpeg = Files.readAllBytes(Path.of("src/test/resources/netpbm/reference.jpg"));
        try (final ImageReader reader = CodecsAPI.decodeImage(ByteBuffer.wrap(jpeg))) {
            final ImageMetadata metadata = reader.metadata();

            assertNull(metadata.title());
            assertNull(metadata.authors());
            assertNull(metadata.values());
        }
    }

    private static byte[] pngWithTextMetadata() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A });
        writePngChunk(out, "IHDR", new byte[] {
                0x00, 0x00, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x01,
                0x08, 0x00, 0x00, 0x00, 0x00
        });
        writePngChunk(out, "tEXt", pngText("Title", "WaterMedia"));
        writePngChunk(out, "tEXt", pngText("Author", "J-RAP"));
        writePngChunk(out, "IDAT", new byte[] {
                0x78, (byte) 0x9C, 0x63, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01
        });
        writePngChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static byte[] pngText(final String key, final String value) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(key.getBytes(StandardCharsets.ISO_8859_1));
        out.write(0);
        out.write(value.getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    private static void writePngChunk(final ByteArrayOutputStream out, final String type, final byte[] data) throws Exception {
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
