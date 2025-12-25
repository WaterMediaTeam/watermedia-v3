package org.watermedia.api.decode.formats.png.chunks;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * tRNS - Transparency Chunk
 * Specifies alpha values for palette entries or a single transparent color for non-indexed images
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11tRNS">PNG Specification - tRNS</a>
 */
public record TRNS(int gray, int red, int green, int blue, byte[] alphaPerPalette) {
    public static final int SIGNATURE = 0x74_52_4E_53; // "tRNS"

    /**
     * Creates tRNS for greyscale image (color type 0)
     */
    public TRNS(final int gray) {
        this(gray, -1, -1, -1, new byte[0]);
    }

    /**
     * Creates tRNS for truecolor image (color type 2)
     */
    public TRNS(final int red, final int green, final int blue) {
        this(-1, red, green, blue, new byte[0]);
    }

    /**
     * Creates tRNS for indexed-color image (color type 3)
     */
    public TRNS(final byte[] alphaPerPalette) {
        this(-1, -1, -1, -1, alphaPerPalette);
    }

    /**
     * Reads tRNS chunk data from buffer based on color type
     */
    public static TRNS read(final int colorType, final int size, final ByteBuffer buffer) {
        return switch (colorType) {
            case 0 -> { // GREYSCALE
                final int gray = buffer.getShort() & 0xFFFF;
                yield new TRNS(gray);
            }
            case 2 -> { // TRUECOLOR
                final int red = buffer.getShort() & 0xFFFF;
                final int green = buffer.getShort() & 0xFFFF;
                final int blue = buffer.getShort() & 0xFFFF;
                yield new TRNS(red, green, blue);
            }
            case 3 -> { // INDEXED-COLOR
                final byte[] alphas = new byte[size];
                buffer.get(alphas);
                yield new TRNS(alphas);
            }
            default -> throw new IllegalArgumentException("tRNS not allowed for color type " + colorType);
        };
    }

    /**
     * Converts a generic CHUNK to TRNS based on color type
     */
    public static TRNS convert(final CHUNK chunk, final int colorType) {
        if (chunk.type() != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for tRNS: 0x" + Integer.toHexString(chunk.type()));
        }

        final byte[] data = chunk.data();
        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        return switch (colorType) {
            case 0 -> { // GREYSCALE - 2 BYTES FOR GRAY SAMPLE
                if (data.length != 2) {
                    throw new IllegalArgumentException("tRNS for greyscale must be 2 bytes, got " + data.length);
                }
                final int gray = buffer.getShort() & 0xFFFF;
                yield new TRNS(gray);
            }
            case 2 -> { // TRUECOLOR - 6 BYTES FOR RGB SAMPLES
                if (data.length != 6) {
                    throw new IllegalArgumentException("tRNS for truecolor must be 6 bytes, got " + data.length);
                }
                final int red = buffer.getShort() & 0xFFFF;
                final int green = buffer.getShort() & 0xFFFF;
                final int blue = buffer.getShort() & 0xFFFF;
                yield new TRNS(red, green, blue);
            }
            case 3 -> { // INDEXED-COLOR - 1 BYTE PER PALETTE ENTRY (UP TO 256)
                final byte[] alphas = new byte[data.length];
                System.arraycopy(data, 0, alphas, 0, data.length);
                yield new TRNS(alphas);
            }
            default -> throw new IllegalArgumentException("tRNS not allowed for color type " + colorType);
        };
    }

    /**
     * Returns the alpha value for the given palette index
     * Only valid for indexed-color images (color type 3)
     * @param index The palette index
     * @return Alpha value (0-255), or 255 if no entry exists for this index
     */
    public int getAlpha(final int index) {
        if (this.alphaPerPalette == null || index >= this.alphaPerPalette.length) {
            return 255; // FULLY OPAQUE FOR UNDEFINED ENTRIES
        }
        return this.alphaPerPalette[index] & 0xFF;
    }

    /**
     * Returns whether the given greyscale value should be transparent
     * Only valid for greyscale images (color type 0)
     */
    public boolean isTransparent(final int grayValue) {
        return this.gray >= 0 && grayValue == this.gray;
    }

    /**
     * Returns whether the given RGB value should be transparent
     * Only valid for truecolor images (color type 2)
     */
    public boolean isTransparent(final int r, final int g, final int b) {
        return this.red >= 0 && r == this.red && g == this.green && b == this.blue;
    }

    /**
     * Returns the number of alpha entries in the palette alpha table
     */
    public int alphaTableSize() {
        return this.alphaPerPalette != null ? this.alphaPerPalette.length : 0;
    }
}