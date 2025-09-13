package org.watermedia.api.decode.formats.gif.packets;

import java.nio.ByteBuffer;

public record ImageDescriptor(
    int left, int top, int width, int height,
    boolean localColorTableFlag, boolean interlacedFlag, boolean sortFlag,
    int localColorTableSize) {

    public static final int LOCAL_COLOR_TABLE_SIZE = 8;

    public ImageDescriptor {
        if (left < 0 || top < 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid dimensions for ImageDescriptor");
        }
        if (localColorTableSize < 0 || localColorTableSize > LOCAL_COLOR_TABLE_SIZE) {
            throw new IllegalArgumentException("Local color table size must be between 0 and " + LOCAL_COLOR_TABLE_SIZE);
        }
    }

    public int getLocalColorTableSize() {
        // Size = 2^(N + 1)
        return 1 << (this.localColorTableSize + 1);
    }

    public static ImageDescriptor read(final ByteBuffer buffer) {
        if (buffer.remaining() < 10) {
            throw new IllegalArgumentException("Buffer does not contain enough data for Image Descriptor");
        }

        final int left = Short.toUnsignedInt(buffer.getShort());
        final int top = Short.toUnsignedInt(buffer.getShort());
        final int width = Short.toUnsignedInt(buffer.getShort());
        final int height = Short.toUnsignedInt(buffer.getShort());
        final int packedFields = Byte.toUnsignedInt(buffer.get());

        final boolean localColorTableFlag = (packedFields & 0b1000_0000) != 0;
        final boolean interlacedFlag = (packedFields & 0b0100_0000) != 0;
        final boolean sortFlag = (packedFields & 0b0010_0000) != 0;
        final int localColorTableSize = packedFields & 0b0000_0111;

        return new ImageDescriptor(left, top, width, height, localColorTableFlag, interlacedFlag, sortFlag, localColorTableSize);
    }
}
