package org.watermedia.api.codecs.common.gif;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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

    public byte[] toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) this.left);
        buf.putShort((short) this.top);
        buf.putShort((short) this.width);
        buf.putShort((short) this.height);
        int packed = 0;
        if (this.localColorTableFlag) packed |= 0b1000_0000;
        if (this.interlacedFlag) packed |= 0b0100_0000;
        if (this.sortFlag) packed |= 0b0010_0000;
        packed |= this.localColorTableSize & 0b0000_0111;
        buf.put((byte) packed);
        return buf.array();
    }
}
