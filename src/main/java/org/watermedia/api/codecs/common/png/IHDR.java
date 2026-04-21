package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.decoders.PNG;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * IHDR - Image Header Chunk
 * The first chunk in a PNG datastream, contains image metadata
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11IHDR">PNG Specification - IHDR</a>
 */
public record IHDR(int width, int height, int depth, int colorType, int compression, int filter, int interlace) {
    public static final int SIGNATURE = 0x49_48_44_52; // "IHDR"
    public static final int LENGTH = 13;

    /**
     * Reads IHDR chunk from buffer (reads length/type header first)
     */
    public static IHDR read(final ByteBuffer buffer) {
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        if (type != SIGNATURE)
            throw new IllegalArgumentException("Invalid chunk type for IHDR: 0x" + Integer.toHexString(type));
        if (length != LENGTH)
            throw new IllegalArgumentException("IHDR chunk length must be 13, got " + length);

        return new IHDR(
                buffer.getInt(),    // WIDTH (4 BYTES)
                buffer.getInt(),    // HEIGHT (4 BYTES)
                buffer.get() & 0xFF, // BIT DEPTH (1 BYTE)
                buffer.get() & 0xFF, // COLOR TYPE (1 BYTE)
                buffer.get() & 0xFF, // COMPRESSION METHOD (1 BYTE)
                buffer.get() & 0xFF, // FILTER METHOD (1 BYTE)
                buffer.get() & 0xFF  // INTERLACE METHOD (1 BYTE)
        );
    }

    /**
     * Converts a generic CHUNK to IHDR
     */
    public static IHDR convert(final CHUNK chunk, final ByteOrder order) {
        if (chunk.type() != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for IHDR: 0x" + Integer.toHexString(chunk.type()));
        }
        final byte[] data = chunk.data();
        if (data.length != 13) {
            throw new IllegalArgumentException("Invalid IHDR data length: " + data.length + " (expected 13)");
        }

        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        return new IHDR(
                buffer.getInt(),    // WIDTH
                buffer.getInt(),    // HEIGHT
                buffer.get() & 0xFF, // BIT DEPTH
                buffer.get() & 0xFF, // COLOR TYPE
                buffer.get() & 0xFF, // COMPRESSION METHOD
                buffer.get() & 0xFF, // FILTER METHOD
                buffer.get() & 0xFF  // INTERLACE METHOD
        );
    }

    public int bytesPerPixel() {
        final int samplesPerPixel = switch (PNG.ColorType.of(this.colorType)) {
            case GREYSCALE -> 1;
            case TRUECOLOR -> 3;
            case INDEXED -> 1;
            case GREYSCALE_ALPHA -> 2;
            case TRUECOLOR_ALPHA -> 4;
            case FORBIDDEN_1, FORBIDDEN_5 -> throw new IllegalStateException("Forbidden color type: " + this.colorType);
        };
        return Math.max(1, (samplesPerPixel * this.depth) / 8);
    }

    public byte[] toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(LENGTH).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(this.width);
        buf.putInt(this.height);
        buf.put((byte) this.depth);
        buf.put((byte) this.colorType);
        buf.put((byte) this.compression);
        buf.put((byte) this.filter);
        buf.put((byte) this.interlace);
        return buf.array();
    }

    public CHUNK toChunk() {
        return CHUNK.create(SIGNATURE, this.toBytes());
    }
}