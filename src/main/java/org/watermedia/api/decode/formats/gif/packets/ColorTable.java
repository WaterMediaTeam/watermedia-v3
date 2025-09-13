package org.watermedia.api.decode.formats.gif.packets;

import java.nio.ByteBuffer;

public record ColorTable(int size, int[] colors) {
    public static final int MAX_COLORS = 256;

    public ColorTable {
        if (size < 0 || size > MAX_COLORS) {
            throw new IllegalArgumentException("Size must be between 0 and " + MAX_COLORS);
        }
        if (colors == null) {
            throw new IllegalArgumentException("Colors array cannot be null");
        }
    }

    public static ColorTable read(int size, ByteBuffer buffer) {
        if (buffer.remaining() < size * 3) {
            throw new IllegalArgumentException("Buffer does not contain enough data for Color Table. " +
                    "Expected " + (size * 3) + " bytes, but only " + buffer.remaining() + " available");
        }

        final int[] colorTable = new int[size];
        for (int i = 0; i < size; i++) {
            final int r = Byte.toUnsignedInt(buffer.get());
            final int g = Byte.toUnsignedInt(buffer.get());
            final int b = Byte.toUnsignedInt(buffer.get());

            // BGRA format for little-endian ByteBuffer
            // When written with putInt(), bytes are arranged as: B G R A in memory
            colorTable[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
        }

        return new ColorTable(size, colorTable);
    }
}