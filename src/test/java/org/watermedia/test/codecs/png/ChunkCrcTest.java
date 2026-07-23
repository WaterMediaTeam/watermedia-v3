package org.watermedia.test.codecs.png;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.codecs.common.png.CHUNK;

import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link CHUNK#calculateCRC()} / {@link CHUNK#corrupted()} against reference CRC-32
 * vectors. The CRC gate drives PNG {@code failOnCorruptedData}, so it must match both the canonical
 * PNG IEND value and the JDK's independent CRC-32 implementation over arbitrary chunk bytes.
 */
@DisplayName("PNG chunk CRC")
public class ChunkCrcTest {

    // "IEND" AS A BIG-ENDIAN INT; CANONICAL EMPTY-DATA IEND CRC IS 0xAE426082
    private static final int IEND_TYPE = 0x49_45_4E_44;
    // "IDAT" AS A BIG-ENDIAN INT
    private static final int IDAT_TYPE = 0x49_44_41_54;

    @Test
    @DisplayName("Matches the canonical IEND CRC-32 vector")
    void testIendReferenceVector() {
        final CHUNK iend = new CHUNK(0, IEND_TYPE, new byte[0], 0xAE426082);
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

        final CHUNK good = new CHUNK(data.length, IDAT_TYPE, data, expected);
        assertEquals(expected, good.calculateCRC());
        assertFalse(good.corrupted());

        final CHUNK bad = new CHUNK(data.length, IDAT_TYPE, data, expected ^ 0x1);
        assertTrue(bad.corrupted(), "a chunk whose stored CRC is wrong must be flagged corrupted");
    }
}
