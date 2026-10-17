package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.CodecException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * acTL - Animation Control Chunk
 * Indicates that the PNG is an animated image and contains information about the animation
 *
 * @see <a href="https://www.w3.org/TR/png-3/#acTL-chunk">PNG Specification - acTL</a>
 */
public record ACTL(int frameCount, int repeat) implements IChunk {
    public static final int SIGNATURE = 0x61_63_54_4C; // "acTL"
    public static final int LENGTH = 8;

    /**
     * Reads acTL chunk from buffer (reads length/type header first)
     */
    public static ACTL read(final ByteBuffer buffer) throws CodecException {
        if (buffer.remaining() < 8) throw new CodecException("Truncated acTL chunk header");
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        // TYPE AND LENGTH COME STRAIGHT OFF THE WIRE HERE, SO BOTH ARE ATTACKER DATA (UNLIKE convert)
        if (type != SIGNATURE)
            throw new CodecException("Invalid chunk type for acTL: 0x" + Integer.toHexString(type));
        if (length != LENGTH)
            throw new CodecException("acTL chunk length must be 8, got " + Integer.toUnsignedString(length));
        if (buffer.remaining() < LENGTH) throw new CodecException("Truncated acTL chunk");

        return new ACTL(
                buffer.getInt(),  // FRAME COUNT
                buffer.getInt()   // LOOP COUNT
        );
    }

    /**
     * Converts a generic PngChunk to ACTL
     */
    public static ACTL convert(final PngChunk chunk) throws CodecException {
        if (chunk.type() != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for acTL: 0x" + Integer.toHexString(chunk.type()));
        }

        final byte[] data = chunk.data();
        if (data.length != LENGTH) {
            throw new CodecException("acTL data must be 8 bytes, got " + data.length);
        }

        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        final int frameCount = buffer.getInt();
        final int repeat = buffer.getInt();

        return new ACTL(frameCount, repeat);
    }

    /**
     * Returns whether the animation repeats forever
     */
    public boolean repeatsForever() {
        return this.repeat == 0;
    }

    @Override
    public byte[] toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(LENGTH).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(this.frameCount);
        buf.putInt(this.repeat);
        return buf.array();
    }

    @Override
    public PngChunk toChunk() {
        return PngChunk.create(SIGNATURE, this.toBytes());
    }
}