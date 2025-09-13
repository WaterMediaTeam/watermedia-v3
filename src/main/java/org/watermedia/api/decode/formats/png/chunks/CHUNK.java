package org.watermedia.api.decode.formats.png.chunks;

import java.nio.ByteBuffer;

public record CHUNK(int length, int type, byte[] data, int crc) {

    private static final int[] CRC_TABLE = new int[256];

    static {
        // Precalcula la tabla CRC (idéntica a la de GZIP/ZIP)
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

    public static CHUNK read(ByteBuffer buffer) {
        final int length = buffer.getInt();
        final int type = buffer.getInt();
        final byte[] data = new byte[length];
        buffer.get(data, 0, length);
        final int crc = buffer.getInt();
        return new CHUNK(length, type, data, crc);
    }

    public boolean corrupted() {
        int crcCalc = 0xFFFFFFFF;

        // Signature
        for (int i = 0; i < 4; i++) {
            int b = (type >> (24 - i * 8)) & 0xFF;
            crcCalc = CRC_TABLE[(crcCalc ^ b) & 0xFF] ^ (crcCalc >>> 8);
        }

        // Data
        for (byte b: data) {
            crcCalc = CRC_TABLE[(crcCalc ^ b) & 0xFF] ^ (crcCalc >>> 8);
        }

        // Flip
        crcCalc = ~crcCalc;

        return crcCalc != crc;
    }
}
