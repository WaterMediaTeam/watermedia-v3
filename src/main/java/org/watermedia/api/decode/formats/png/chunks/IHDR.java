package org.watermedia.api.decode.formats.png.chunks;

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

    /**
     * Reads IHDR chunk data from buffer (legacy method, reads length/type from buffer)
     */
    public static IHDR read(final ByteBuffer buffer) {
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

    /**
     * Returns the number of samples per pixel based on color type
     */
    public int samplesPerPixel() {
        return switch (this.colorType) {
            case 0 -> 1;  // GREYSCALE
            case 2 -> 3;  // TRUECOLOR (RGB)
            case 3 -> 1;  // INDEXED
            case 4 -> 2;  // GREYSCALE + ALPHA
            case 6 -> 4;  // TRUECOLOR + ALPHA (RGBA)
            default -> throw new IllegalStateException("Unknown color type: " + this.colorType);
        };
    }

    /**
     * Returns whether this image has an alpha channel
     */
    public boolean hasAlpha() {
        return this.colorType == 4 || this.colorType == 6;
    }

    /**
     * Returns whether this image uses a palette
     */
    public boolean isIndexed() {
        return this.colorType == 3;
    }

    /**
     * Returns whether this image is greyscale
     */
    public boolean isGreyscale() {
        return this.colorType == 0 || this.colorType == 4;
    }

    /**
     * Returns whether this image uses truecolor
     */
    public boolean isTruecolor() {
        return this.colorType == 2 || this.colorType == 6;
    }
}