package org.watermedia.api.codecs.common.gif;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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

    public byte[] toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 4);
        int packed = 0;
        packed |= (this.disposalMethod & 0b111) << 2;
        if (this.userInputFlag) packed |= 0b0000_0010;
        if (this.transparentColorFlag) packed |= 0b0000_0001;
        buf.put((byte) packed);
        buf.putShort((short) this.delayTime);
        buf.put((byte) this.transparentColorIndex);
        buf.put((byte) 0);
        return buf.array();
    }
}