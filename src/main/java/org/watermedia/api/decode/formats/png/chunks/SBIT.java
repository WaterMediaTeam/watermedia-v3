package org.watermedia.api.decode.formats.png.chunks;

import org.watermedia.api.decode.formats.png.PNG;

import java.nio.ByteBuffer;

/**
 * sBIT - Significant Bits Chunk
 * Indicates the original number of significant bits per sample
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11sBIT">PNG Specification - sBIT</a>
 */
public record SBIT(int gray, int red, int green, int blue, int alpha) {
    public static final int SIGNATURE = 0x73_42_49_54; // "sBIT"

    /**
     * Creates sBIT for greyscale (color type 0)
     */
    public SBIT(final int gray) {
        this(gray, -1, -1, -1, -1);
    }

    /**
     * Creates sBIT for greyscale with alpha (color type 4)
     */
    public static SBIT greyscaleAlpha(final int gray, final int alpha) {
        return new SBIT(gray, -1, -1, -1, alpha);
    }

    /**
     * Creates sBIT for truecolor (color type 2)
     */
    public static SBIT truecolor(final int red, final int green, final int blue) {
        return new SBIT(-1, red, green, blue, -1);
    }

    /**
     * Creates sBIT for truecolor with alpha (color type 6)
     */
    public static SBIT truecolorAlpha(final int red, final int green, final int blue, final int alpha) {
        return new SBIT(-1, red, green, blue, alpha);
    }

    /**
     * Creates sBIT for indexed-color (color type 3)
     */
    public static SBIT indexed(final int red, final int green, final int blue) {
        return new SBIT(-1, red, green, blue, -1);
    }

    /**
     * Reads sBIT chunk from buffer based on color type (reads length/type header first)
     */
    public static SBIT read(final ByteBuffer buffer, final int colorType) {
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        if (type != SIGNATURE)
            throw new IllegalArgumentException("Invalid chunk type for sBIT: 0x" + Integer.toHexString(type));

        return switch (PNG.ColorType.of(colorType)) {
            case GREYSCALE -> {
                if (length != 1)
                    throw new IllegalArgumentException("sBIT for greyscale must be 1 byte, got " + length);
                yield new SBIT(buffer.get() & 0xFF);
            }
            case TRUECOLOR, INDEXED -> {
                if (length != 3)
                    throw new IllegalArgumentException("sBIT for truecolor/indexed must be 3 bytes, got " + length);
                yield SBIT.truecolor(
                        buffer.get() & 0xFF,
                        buffer.get() & 0xFF,
                        buffer.get() & 0xFF
                );
            }
            case GREYSCALE_ALPHA -> {
                if (length != 2)
                    throw new IllegalArgumentException("sBIT for greyscale+alpha must be 2 bytes, got " + length);
                yield SBIT.greyscaleAlpha(
                        buffer.get() & 0xFF,
                        buffer.get() & 0xFF
                );
            }
            case TRUECOLOR_ALPHA -> {
                if (length != 4)
                    throw new IllegalArgumentException("sBIT for truecolor+alpha must be 4 bytes, got " + length);
                yield SBIT.truecolorAlpha(
                        buffer.get() & 0xFF,
                        buffer.get() & 0xFF,
                        buffer.get() & 0xFF,
                        buffer.get() & 0xFF
                );
            }
            case FORBIDDEN_1, FORBIDDEN_5 -> throw new IllegalArgumentException("Forbidden color type: " + colorType);
        };
    }

    /**
     * Converts a generic CHUNK to SBIT based on color type
     */
    public static SBIT convert(final CHUNK chunk, final int colorType) {
        if (chunk.type() != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for sBIT: 0x" + Integer.toHexString(chunk.type()));
        }

        final byte[] data = chunk.data();

        return switch (PNG.ColorType.of(colorType)) {
            case GREYSCALE -> {
                if (data.length != 1) {
                    throw new IllegalArgumentException("sBIT for greyscale must be 1 byte");
                }
                yield new SBIT(data[0] & 0xFF);
            }
            case TRUECOLOR, INDEXED -> {
                if (data.length != 3) {
                    throw new IllegalArgumentException("sBIT for truecolor/indexed must be 3 bytes");
                }
                yield SBIT.truecolor(data[0] & 0xFF, data[1] & 0xFF, data[2] & 0xFF);
            }
            case GREYSCALE_ALPHA -> {
                if (data.length != 2) {
                    throw new IllegalArgumentException("sBIT for greyscale+alpha must be 2 bytes");
                }
                yield SBIT.greyscaleAlpha(data[0] & 0xFF, data[1] & 0xFF);
            }
            case TRUECOLOR_ALPHA -> {
                if (data.length != 4) {
                    throw new IllegalArgumentException("sBIT for truecolor+alpha must be 4 bytes");
                }
                yield SBIT.truecolorAlpha(data[0] & 0xFF, data[1] & 0xFF, data[2] & 0xFF, data[3] & 0xFF);
            }
            case FORBIDDEN_1, FORBIDDEN_5 -> throw new IllegalArgumentException("Forbidden color type: " + colorType);
        };
    }
}
