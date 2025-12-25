package org.watermedia.api.decode.formats.png.chunks;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * fdAT - Frame Data Chunk
 * Contains compressed image data for animation frames (same as IDAT but with sequence number)
 *
 * @see <a href="https://www.w3.org/TR/png-3/#fdAT-chunk">PNG Specification - fdAT</a>
 */
public record FDAT(int sequence, byte[] data) {
    public static final int SIGNATURE = 0x66_64_41_54; // "fdAT"

    /**
     * Converts a generic CHUNK to FDAT
     */
    public static FDAT convert(final CHUNK chunk) {
        if (chunk.type() != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for fdAT: 0x" + Integer.toHexString(chunk.type()));
        }

        final byte[] rawData = chunk.data();
        if (rawData.length < 4) {
            throw new IllegalArgumentException("fdAT data must be at least 4 bytes");
        }

        // FIRST 4 BYTES ARE SEQUENCE NUMBER
        final ByteBuffer buffer = ByteBuffer.wrap(rawData, 0, 4).order(ByteOrder.BIG_ENDIAN);
        final int sequence = buffer.getInt();

        // REMAINING BYTES ARE COMPRESSED FRAME DATA
        final byte[] frameData = new byte[rawData.length - 4];
        System.arraycopy(rawData, 4, frameData, 0, frameData.length);

        return new FDAT(sequence, frameData);
    }
}