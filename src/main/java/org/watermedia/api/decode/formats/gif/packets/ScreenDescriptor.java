package org.watermedia.api.decode.formats.gif.packets;

import java.nio.ByteBuffer;

public record ScreenDescriptor(
    int width, int height,
    boolean globalColorTableFlag, int colorResolution, boolean sortFlag,
    int globalColorTableSize, int backgroundColorIndex, int pixelAspectRatio) {

    public static final int SIGNATURE_SIZE = 7;

    public ScreenDescriptor {
        if (width <= 0 || height <= 0)
            throw new IllegalArgumentException("Width and height must be positive integers");
        if (globalColorTableSize < 0 || globalColorTableSize > 7)
            throw new IllegalArgumentException("Global color table size must be between 0 and 7");
        if (colorResolution < 1 || colorResolution > 8)
            throw new IllegalArgumentException("Color resolution must be between 1 and 8");
        if (pixelAspectRatio < 0 || pixelAspectRatio > 255)
            throw new IllegalArgumentException("Pixel aspect ratio must be between 0 and 255");
    }

    public static ScreenDescriptor read(final ByteBuffer buffer) {
        if (buffer.remaining() < SIGNATURE_SIZE) {
            throw new IllegalArgumentException("Buffer does not contain enough data for Screen Descriptor");
        }

        final int width = Short.toUnsignedInt(buffer.getShort());
        final int height = Short.toUnsignedInt(buffer.getShort());
        final int packedFields = Byte.toUnsignedInt(buffer.get());
        final boolean globalColorTableFlag = (packedFields & 0b1000_0000) != 0;
        final int colorResolution = ((packedFields & 0b0111_0000) >> 4) + 1;
        final boolean sortFlag = (packedFields & 0b0000_1000) != 0;
        final int globalColorTableSize = packedFields & 0b0000_0111;
        final int backgroundColorIndex = Byte.toUnsignedInt(buffer.get());
        final int pixelAspectRatio = Byte.toUnsignedInt(buffer.get());

        return new ScreenDescriptor(width, height, globalColorTableFlag, colorResolution, sortFlag,
                globalColorTableSize, backgroundColorIndex, pixelAspectRatio);
    }


}
