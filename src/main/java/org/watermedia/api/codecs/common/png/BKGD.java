package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.decoders.PNG;

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
     * Reads bKGD chunk from buffer based on color type (reads length/type header first)
     */
    public static BKGD read(final ByteBuffer buffer, final int colorType) {
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        if (type != SIGNATURE)
            throw new IllegalArgumentException("Invalid chunk type for bKGD: 0x" + Integer.toHexString(type));

        return switch (PNG.ColorType.of(colorType)) {
            case GREYSCALE, GREYSCALE_ALPHA -> {
                if (length != 2)
                    throw new IllegalArgumentException("bKGD for greyscale must be 2 bytes, got " + length);
                final int gray = buffer.getShort() & 0xFFFF;
                yield new BKGD(gray);
            }
            case TRUECOLOR, TRUECOLOR_ALPHA -> {
                if (length != 6)
                    throw new IllegalArgumentException("bKGD for truecolor must be 6 bytes, got " + length);
                final int red = buffer.getShort() & 0xFFFF;
                final int green = buffer.getShort() & 0xFFFF;
                final int blue = buffer.getShort() & 0xFFFF;
                yield new BKGD(red, green, blue);
            }
            case INDEXED -> {
                if (length != 1)
                    throw new IllegalArgumentException("bKGD for indexed must be 1 byte, got " + length);
                final int index = buffer.get() & 0xFF;
                yield BKGD.forPalette(index);
            }
            case FORBIDDEN_1, FORBIDDEN_5 -> throw new IllegalArgumentException("Forbidden color type: " + colorType);
        };
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

        return switch (PNG.ColorType.of(colorType)) {
            case GREYSCALE, GREYSCALE_ALPHA -> {
                if (data.length != 2) {
                    throw new IllegalArgumentException("bKGD for greyscale must be 2 bytes");
                }
                final int gray = buffer.getShort() & 0xFFFF;
                yield new BKGD(gray);
            }
            case TRUECOLOR, TRUECOLOR_ALPHA -> {
                if (data.length != 6) {
                    throw new IllegalArgumentException("bKGD for truecolor must be 6 bytes");
                }
                final int red = buffer.getShort() & 0xFFFF;
                final int green = buffer.getShort() & 0xFFFF;
                final int blue = buffer.getShort() & 0xFFFF;
                yield new BKGD(red, green, blue);
            }
            case INDEXED -> {
                if (data.length != 1) {
                    throw new IllegalArgumentException("bKGD for indexed must be 1 byte");
                }
                final int index = data[0] & 0xFF;
                yield BKGD.forPalette(index);
            }
            case FORBIDDEN_1, FORBIDDEN_5 -> throw new IllegalArgumentException("Forbidden color type: " + colorType);
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
        return 0xFF000000; // DEFAULT BLACK FOR INDEXED
    }

    public int scaleTo8Bit(final int value, final int depth) {
        if (depth == 8) return value;
        if (depth == 16) return value >> 8;
        if (depth <= 8) return (value * 255) / ((1 << depth) - 1);
        return value;
    }

    public byte[] toBytes() {
        if (this.isGreyscale()) {
            final ByteBuffer buf = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN);
            buf.putShort((short) this.gray);
            return buf.array();
        } else if (this.isTruecolor()) {
            final ByteBuffer buf = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN);
            buf.putShort((short) this.red);
            buf.putShort((short) this.green);
            buf.putShort((short) this.blue);
            return buf.array();
        } else {
            return new byte[] { (byte) this.paletteIndex };
        }
    }

    public CHUNK toChunk() {
        return CHUNK.create(SIGNATURE, this.toBytes());
    }
}