package org.watermedia.api.codecs.common.png;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link PngChunk#calculateCRC()} / {@link PngChunk#corrupted()} against reference CRC-32
 * vectors. The CRC gate drives PNG {@code failOnCorruptedData}, so it must match both the canonical
 * PNG IEND value and the JDK's independent CRC-32 implementation over arbitrary chunk bytes.
 */
@DisplayName("PNG chunk CRC")
public class ChunkCrcTest {

    @Nested
    @DisplayName("Reference vectors")
    class ReferenceVectors {

        @Test
        @DisplayName("Matches the canonical IEND CRC-32 vector")
        void testIendReferenceVector() {
            final PngChunk iend = new PngChunk(0, IEND.SIGNATURE, new byte[0], 0xAE426082);
            assertEquals(0xAE426082, iend.calculateCRC(), "IEND CRC must equal the canonical 0xAE426082");
            assertFalse(iend.corrupted(), "a chunk carrying its correct CRC must not be flagged corrupted");
        }

        @Test
        @DisplayName("Matches the JDK CRC-32 over type + data")
        void testMatchesJdkCrc() {
            final byte[] data = { 1, 2, 3, 4, 5, (byte) 0xFF, 0, (byte) 0x80 };
            final CRC32 ref = new CRC32();
            // CRC IS COMPUTED OVER THE 4 TYPE BYTES (BIG-ENDIAN) FOLLOWED BY THE DATA
            ref.update(new byte[] { 'I', 'D', 'A', 'T' });
            ref.update(data);
            final int expected = (int) ref.getValue();

            final PngChunk good = new PngChunk(data.length, IDAT.SIGNATURE, data, expected);
            assertEquals(expected, good.calculateCRC());
            assertFalse(good.corrupted());

            final PngChunk bad = new PngChunk(data.length, IDAT.SIGNATURE, data, expected ^ 0x1);
            assertTrue(bad.corrupted(), "a chunk whose stored CRC is wrong must be flagged corrupted");
        }
    }

    @Nested
    @DisplayName("Envelope reads")
    class EnvelopeReads {

        @Test
        @DisplayName("Keeps the stored CRC so a flipped wire CRC is flagged corrupted")
        void testKeepsWireCrc() throws IOException {
            final TEXT text = new TEXT(TEXT.KEYWORD_TITLE, "WaterMedia");
            assertFalse(PngChunk.read(envelope(text, false)).corrupted());
            assertTrue(PngChunk.read(envelope(text, true)).corrupted());
        }

        @Test
        @DisplayName("Typed reads leave the CRC policy to the caller")
        void testTypedReadIgnoresCrc() throws IOException {
            // PngReader GATES THE CRC THROUGH failOnCorruptedData, SO THE CHUNK PARSER MUST NOT DECIDE IT
            final TEXT text = new TEXT(TEXT.KEYWORD_TITLE, "WaterMedia");
            assertEquals(text, TEXT.read(envelope(text, true)));
        }
    }

    // UTILITY
    private static ByteBuffer envelope(final IChunk value, final boolean flipCrc) {
        final PngChunk chunk = value.toChunk();
        final ByteBuffer buffer = ByteBuffer.allocate(chunk.length() + 12);
        chunk.write(buffer);
        if (flipCrc) buffer.put(buffer.position() - 1, (byte) (buffer.get(buffer.position() - 1) ^ 0x01));
        return buffer.flip();
    }
}
