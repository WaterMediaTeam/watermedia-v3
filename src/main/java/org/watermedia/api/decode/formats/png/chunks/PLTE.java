package org.watermedia.api.decode.formats.png.chunks;

import java.nio.ByteBuffer;

/**
 * PLTE - Palette Chunk
 * Contains 1 to 256 palette entries, stored as packed RGB integers (0xRRGGBB)
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11PLTE">PNG Specification - PLTE</a>
 */
public record PLTE(int[] colors) {
    public static final int SIGNATURE = 0x50_4C_54_45; // "PLTE"

    /**
     * Reads PLTE chunk data from buffer
     */
    public static PLTE read(final ByteBuffer buffer, final int dataLength) {
        if (dataLength % 3 != 0) {
            throw new IllegalArgumentException("PLTE data length must be divisible by 3");
        }

        final int count = dataLength / 3;
        final int[] colors = new int[count];

        for (int i = 0; i < count; i++) {
            final int r = buffer.get() & 0xFF;
            final int g = buffer.get() & 0xFF;
            final int b = buffer.get() & 0xFF;
            colors[i] = (r << 16) | (g << 8) | b;
        }

        return new PLTE(colors);
    }

    /**
     * Converts a generic CHUNK to PLTE
     */
    public static PLTE convert(final CHUNK chunk) {
        if (chunk.type() != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for PLTE: 0x" + Integer.toHexString(chunk.type()));
        }

        final byte[] data = chunk.data();
        if (data.length % 3 != 0) {
            throw new IllegalArgumentException("PLTE data length must be divisible by 3");
        }

        final int count = data.length / 3;
        final int[] colors = new int[count];

        for (int i = 0; i < count; i++) {
            final int offset = i * 3;
            final int r = data[offset] & 0xFF;
            final int g = data[offset + 1] & 0xFF;
            final int b = data[offset + 2] & 0xFF;
            colors[i] = (r << 16) | (g << 8) | b;
        }

        return new PLTE(colors);
    }

    /**
     * Returns the number of palette entries
     */
    public int size() {
        return this.colors.length;
    }

    /**
     * Returns the packed RGB color at the given palette index (0xRRGGBB)
     * @param index The palette index (0-255)
     * @return Packed RGB value
     */
    public int getColor(final int index) {
        return this.colors[index];
    }
}