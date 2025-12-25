package org.watermedia.api.decode.formats.png.chunks;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * bKGD - Background Color Chunk
 * Specifies a default background color to present the image against
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11bKGD">PNG Specification - bKGD</a>
 */
public record BKGD(int gray, int red, int green, int blue, int paletteIndex) {
    public static final int SIGNATURE = 0x62_4B_47_44; // "bKGD"

    /**
     * Creates bKGD for greyscale image (color type 0 or 4)
     */
    public BKGD(final int gray) {
        this(gray, -1, -1, -1, -1);
    }

    /**
     * Creates bKGD for truecolor image (color type 2 or 6)
     */
    public BKGD(final int red, final int green, final int blue) {
        this(-1, red, green, blue, -1);
    }

    /**
     * Creates bKGD for indexed-color image (color type 3)
     */
    public static BKGD forPalette(final int paletteIndex) {
        return new BKGD(-1, -1, -1, -1, paletteIndex);
    }

    /**
     * Converts a generic CHUNK to BKGD based on color type and bit depth
     */
    public static BKGD convert(final CHUNK chunk, final int colorType, final int bitDepth) {
        if (chunk.type() != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for bKGD: 0x" + Integer.toHexString(chunk.type()));
        }

        final byte[] data = chunk.data();
        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        return switch (colorType) {
            case 0, 4 -> { // GREYSCALE OR GREYSCALE+ALPHA
                if (data.length != 2) {
                    throw new IllegalArgumentException("bKGD for greyscale must be 2 bytes");
                }
                final int gray = buffer.getShort() & 0xFFFF;
                yield new BKGD(gray);
            }
            case 2, 6 -> { // TRUECOLOR OR TRUECOLOR+ALPHA
                if (data.length != 6) {
                    throw new IllegalArgumentException("bKGD for truecolor must be 6 bytes");
                }
                final int red = buffer.getShort() & 0xFFFF;
                final int green = buffer.getShort() & 0xFFFF;
                final int blue = buffer.getShort() & 0xFFFF;
                yield new BKGD(red, green, blue);
            }
            case 3 -> { // INDEXED-COLOR
                if (data.length != 1) {
                    throw new IllegalArgumentException("bKGD for indexed must be 1 byte");
                }
                final int index = data[0] & 0xFF;
                yield BKGD.forPalette(index);
            }
            default -> throw new IllegalArgumentException("Unknown color type: " + colorType);
        };
    }

    /**
     * Returns whether this background is for greyscale
     */
    public boolean isGreyscale() {
        return this.gray >= 0;
    }

    /**
     * Returns whether this background is for truecolor
     */
    public boolean isTruecolor() {
        return this.red >= 0;
    }

    /**
     * Returns whether this background is for indexed-color
     */
    public boolean isIndexed() {
        return this.paletteIndex >= 0;
    }

    /**
     * Returns the background as a packed RGB value (scaled to 8-bit)
     */
    public int toRGB8(final int bitDepth) {
        if (this.isGreyscale()) {
            final int g = this.scaleTo8Bit(this.gray, bitDepth);
            return (g << 16) | (g << 8) | g;
        } else if (this.isTruecolor()) {
            final int r = this.scaleTo8Bit(this.red, bitDepth);
            final int g = this.scaleTo8Bit(this.green, bitDepth);
            final int b = this.scaleTo8Bit(this.blue, bitDepth);
            return (r << 16) | (g << 8) | b;
        }
        return 0;
    }

    private int scaleTo8Bit(final int value, final int depth) {
        if (depth == 8) return value;
        if (depth == 16) return value >> 8;
        if (depth <= 8) return (value * 255) / ((1 << depth) - 1);
        return value;
    }
}