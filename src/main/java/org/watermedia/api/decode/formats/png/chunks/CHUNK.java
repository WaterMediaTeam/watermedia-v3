package org.watermedia.api.decode.formats.png.chunks;

import java.nio.ByteBuffer;

/**
 * Generic PNG Chunk
 * All PNG chunks follow the same structure: length, type, data, CRC
 *
 * @see <a href="https://www.w3.org/TR/png-3/#5Chunk-layout">PNG Specification - Chunk Layout</a>
 */
public record CHUNK(int length, int type, byte[] data, int crc) {

    // PRECALCULATED CRC TABLE (POLYNOMIAL: 0xEDB88320)
    private static final int[] CRC_TABLE = new int[256];

    static {
        // PRECALCULATE CRC TABLE (IDENTICAL TO GZIP/ZIP)
        for (int n = 0; n < 256; n++) {
            int c = n;
            for (int k = 0; k < 8; k++) {
                if ((c & 1) != 0) {
                    c = 0xEDB88320 ^ (c >>> 1);
                } else {
                    c = c >>> 1;
                }
            }
            CRC_TABLE[n] = c;
        }
    }

    /**
     * Reads a complete chunk from the buffer
     */
    public static CHUNK read(final ByteBuffer buffer) {
        final int length = buffer.getInt();

        // VALIDATE LENGTH
        if (length < 0) {
            throw new IllegalArgumentException("Invalid chunk length: " + length);
        }

        final int type = buffer.getInt();
        final byte[] data = new byte[length];
        buffer.get(data, 0, length);
        final int crc = buffer.getInt();

        return new CHUNK(length, type, data, crc);
    }

    /**
     * Checks if the chunk data is corrupted by verifying CRC
     */
    public boolean corrupted() {
        return this.crc != this.calculateCRC();
    }

    /**
     * Calculates the CRC-32 of the chunk type and data
     */
    public int calculateCRC() {
        int crcCalc = 0xFFFFFFFF;

        // CRC OVER TYPE (4 BYTES)
        for (int i = 0; i < 4; i++) {
            final int b = (this.type >> (24 - i * 8)) & 0xFF;
            crcCalc = CRC_TABLE[(crcCalc ^ b) & 0xFF] ^ (crcCalc >>> 8);
        }

        // CRC OVER DATA
        for (final byte b : this.data) {
            crcCalc = CRC_TABLE[(crcCalc ^ b) & 0xFF] ^ (crcCalc >>> 8);
        }

        // FINAL XOR
        return ~crcCalc;
    }

    /**
     * Returns the chunk type as a 4-character string
     */
    public String typeName() {
        return String.valueOf(new char[]{
                (char) ((this.type >> 24) & 0xFF),
                (char) ((this.type >> 16) & 0xFF),
                (char) ((this.type >> 8) & 0xFF),
                (char) (this.type & 0xFF)
        });
    }

    /**
     * Returns whether this is a critical chunk (first byte uppercase)
     */
    public boolean isCritical() {
        return ((this.type >> 24) & 0x20) == 0;
    }

    /**
     * Returns whether this is a public chunk (second byte uppercase)
     */
    public boolean isPublic() {
        return ((this.type >> 16) & 0x20) == 0;
    }

    /**
     * Returns whether this chunk is safe to copy (fourth byte lowercase)
     */
    public boolean isSafeToCopy() {
        return (this.type & 0x20) != 0;
    }

    /**
     * Calculates CRC-32 for arbitrary data (static utility method)
     */
    public static int crc32(final byte[] data, final int offset, final int length) {
        int crc = 0xFFFFFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc = CRC_TABLE[(crc ^ data[i]) & 0xFF] ^ (crc >>> 8);
        }
        return ~crc;
    }
}