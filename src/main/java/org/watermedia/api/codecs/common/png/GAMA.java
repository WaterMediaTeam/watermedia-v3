package org.watermedia.api.codecs.common.png;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * gAMA - Image Gamma Chunk
 * Specifies the relationship between the image samples and the desired display output intensity
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11gAMA">PNG Specification - gAMA</a>
 */
public record GAMA(int gamma) {
    public static final int SIGNATURE = 0x67_41_4D_41; // "gAMA"
    public static final int LENGTH = 4;

    /**
     * Reads gAMA chunk from buffer (reads length/type header first)
     */
    public static GAMA read(final ByteBuffer buffer) {
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        if (type != SIGNATURE)
            throw new IllegalArgumentException("Invalid chunk type for gAMA: 0x" + Integer.toHexString(type));
        if (length != LENGTH)
            throw new IllegalArgumentException("gAMA chunk length must be 4, got " + length);

        return new GAMA(buffer.getInt());
    }

    /**
     * Converts a generic CHUNK to GAMA
     */
    public static GAMA convert(final CHUNK chunk) {
        if (chunk.type() != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for gAMA: 0x" + Integer.toHexString(chunk.type()));
        }

        final byte[] data = chunk.data();
        if (data.length != 4) {
            throw new IllegalArgumentException("gAMA data must be 4 bytes, got " + data.length);
        }

        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        return new GAMA(buffer.getInt());
    }

    /**
     * Returns the gamma value as a float (gamma * 100000 is stored)
     */
    public float gammaValue() {
        return this.gamma / 100000.0f;
    }

    /**
     * Returns the decoding exponent (1/gamma)
     */
    public float decodingExponent() {
        return 100000.0f / this.gamma;
    }

    public byte[] toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(LENGTH).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(this.gamma);
        return buf.array();
    }

    public CHUNK toChunk() {
        return CHUNK.create(SIGNATURE, this.toBytes());
    }
}
