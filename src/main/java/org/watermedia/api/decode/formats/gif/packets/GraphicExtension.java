package org.watermedia.api.decode.formats.gif.packets;

import java.nio.ByteBuffer;

/**
 * Represents the Graphic Control Extension block.
 * Used for animation and transparency.
 * Section 23 of the GIF89a Specification.
 */
public record GraphicExtension(
    int disposalMethod,
    boolean userInputFlag,
    boolean transparentColorFlag,
    int delayTime,
    int transparentColorIndex) {

    public static final int GCE_LABEL = 0xF9;

    public static GraphicExtension read(final ByteBuffer buffer) {
        buffer.get(); // Block Size (must be 4)
        
        final int packedFields = Byte.toUnsignedInt(buffer.get());
        final int disposalMethod = (packedFields & 0b00011100) >> 2;
        final boolean userInputFlag = (packedFields & 0b00000010) != 0;
        final boolean transparentColorFlag = (packedFields & 0b00000001) != 0;
        
        final int delayTime = Short.toUnsignedInt(buffer.getShort());
        final int transparentColorIndex = Byte.toUnsignedInt(buffer.get());
        
        buffer.get(); // Block Terminator (0x00)

        return new GraphicExtension(disposalMethod, userInputFlag, transparentColorFlag, delayTime, transparentColorIndex);
    }
}