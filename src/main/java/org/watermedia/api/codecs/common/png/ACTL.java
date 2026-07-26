package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.XCodecException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * acTL - Animation Control Chunk
 * Indicates that the PNG is an animated image and contains information about the animation
 *
 * @see <a href="https://www.w3.org/TR/png-3/#acTL-chunk">PNG Specification - acTL</a>
 */
public record ACTL(int frameCount, int loopCount) {
    public static final int SIGNATURE = 0x61_63_54_4C; // "acTL"
    public static final int LENGTH = 8;

    /**
     * Reads acTL chunk from buffer (reads length/type header first)
     */
    public static ACTL read(final ByteBuffer buffer) throws XCodecException {
        if (buffer.remaining() < 8) throw new XCodecException("Truncated acTL chunk header");
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        // TYPE AND LENGTH COME STRAIGHT OFF THE WIRE HERE, SO BOTH ARE ATTACKER DATA (UNLIKE convert)
        if (type != SIGNATURE)
            throw new XCodecException("Invalid chunk type for acTL: 0x" + Integer.toHexString(type));
        if (length != LENGTH)
            throw new XCodecException("acTL chunk length must be 8, got " + Integer.toUnsignedString(length));
        if (buffer.remaining() < LENGTH) throw new XCodecException("Truncated acTL chunk");

        return new ACTL(
                buffer.getInt(),  // FRAME COUNT
                buffer.getInt()   // LOOP COUNT
        );
    }

    /**
     * Converts a generic CHUNK to ACTL
     */
    public static ACTL convert(final CHUNK chunk, final ByteOrder order) throws XCodecException {
        if (chunk.type() != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for acTL: 0x" + Integer.toHexString(chunk.type()));
        }

        final byte[] data = chunk.data();
        if (data.length != LENGTH) {
            throw new XCodecException("acTL data must be 8 bytes, got " + data.length);
        }

        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        final int frameCount = buffer.getInt();
        final int loopCount = buffer.getInt();

        return new ACTL(frameCount, loopCount);
    }

    /**
     * Returns whether the animation loops indefinitely
     */
    public boolean loopsInfinitely() {
        return this.loopCount == 0;
    }

    /**
     * Returns the actual number of times to play the animation
     * 0 means infinite, 1 means play once, etc.
     */
    public int playCount() {
        return this.loopCount;
    }

    public byte[] toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(LENGTH).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(this.frameCount);
        buf.putInt(this.loopCount);
        return buf.array();
    }

    public CHUNK toChunk() {
        return CHUNK.create(SIGNATURE, this.toBytes());
    }
}