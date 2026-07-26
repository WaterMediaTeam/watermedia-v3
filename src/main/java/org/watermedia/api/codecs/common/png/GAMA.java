package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.XCodecException;

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
    // GAMMA 100.0 — ORDERS OF MAGNITUDE ABOVE ANY REAL ENCODER (0.45455 AND 1.0 ARE THE COMMON ONES)
    public static final int MAX_GAMMA = 10_000_000;

    /**
     * Reads gAMA chunk from buffer (reads length/type header first)
     */
    public static GAMA read(final ByteBuffer buffer) throws XCodecException {
        if (buffer.remaining() < 8) throw new XCodecException("Truncated gAMA chunk header");
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        // TYPE AND LENGTH COME STRAIGHT OFF THE WIRE HERE, SO BOTH ARE ATTACKER DATA (UNLIKE convert)
        if (type != SIGNATURE)
            throw new XCodecException("Invalid chunk type for gAMA: 0x" + Integer.toHexString(type));
        if (length != LENGTH)
            throw new XCodecException("gAMA chunk length must be 4, got " + Integer.toUnsignedString(length));
        if (buffer.remaining() < LENGTH) throw new XCodecException("Truncated gAMA chunk");

        return of(buffer.getInt());
    }

    /**
     * Converts a generic CHUNK to GAMA
     */
    public static GAMA convert(final CHUNK chunk) throws XCodecException {
        if (chunk.type() != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for gAMA: 0x" + Integer.toHexString(chunk.type()));
        }

        final byte[] data = chunk.data();
        if (data.length != LENGTH) {
            throw new XCodecException("gAMA data must be 4 bytes, got " + data.length);
        }

        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        return of(buffer.getInt());
    }

    // THE FIELD IS UNSIGNED gamma * 100000: 0x80000000 READS BACK NEGATIVE AND FEEDS A NEGATIVE EXPONENT
    // TO Math.pow, WHOSE +Infinity SATURATES THE DECODER'S GAMMA LUT AND BLEEDS OVER THE ALPHA BYTE
    private static GAMA of(final int gamma) throws XCodecException {
        if (gamma <= 0 || gamma > MAX_GAMMA) {
            throw new XCodecException("Invalid gAMA value: " + Integer.toUnsignedString(gamma));
        }
        return new GAMA(gamma);
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
