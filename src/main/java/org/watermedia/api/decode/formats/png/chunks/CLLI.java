package org.watermedia.api.decode.formats.png.chunks;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * cLLi - Content Light Level Information Chunk
 * HDR metadata describing the light levels of the content
 *
 * @see <a href="https://www.w3.org/TR/png-3/#cLLi-chunk">PNG Specification - cLLi</a>
 */
public record CLLI(long maxContentLightLevel, long maxFrameAverageLightLevel) {
    public static final int SIGNATURE = 0x63_4C_4C_69; // "cLLi"
    public static final int LENGTH = 8;

    /**
     * Reads cLLi chunk from buffer (reads length/type header first)
     */
    public static CLLI read(final ByteBuffer buffer) {
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        if (type != SIGNATURE)
            throw new IllegalArgumentException("Invalid chunk type for cLLi: 0x" + Integer.toHexString(type));
        if (length != LENGTH)
            throw new IllegalArgumentException("cLLi chunk length must be 8, got " + length);

        return new CLLI(
                buffer.getInt() & 0xFFFFFFFFL, // MAX CONTENT LIGHT LEVEL
                buffer.getInt() & 0xFFFFFFFFL  // MAX FRAME-AVERAGE LIGHT LEVEL
        );
    }

    /**
     * Converts a generic CHUNK to CLLI
     */
    public static CLLI convert(final CHUNK chunk) {
        if (chunk.type() != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for cLLi: 0x" + Integer.toHexString(chunk.type()));
        }

        final byte[] data = chunk.data();
        if (data.length != 8) {
            throw new IllegalArgumentException("cLLi data must be 8 bytes, got " + data.length);
        }

        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        return new CLLI(
                buffer.getInt() & 0xFFFFFFFFL, // MAX CONTENT LIGHT LEVEL
                buffer.getInt() & 0xFFFFFFFFL  // MAX FRAME-AVERAGE LIGHT LEVEL
        );
    }

    // VALUES ARE STORED AS 0.0001 CD/M^2 UNITS
    public float maxCLLCdm2() { return this.maxContentLightLevel * 0.0001f; }
    public float maxFALLCdm2() { return this.maxFrameAverageLightLevel * 0.0001f; }
}
