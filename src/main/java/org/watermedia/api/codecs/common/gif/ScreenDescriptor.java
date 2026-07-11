package org.watermedia.api.codecs.common.gif;

import org.watermedia.api.codecs.XCodecException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public record ScreenDescriptor(
    int width, int height,
    boolean globalColorTableFlag, int colorResolution, boolean sortFlag,
    int globalColorTableSize, int backgroundColorIndex, int pixelAspectRatio) {

    public static final int SIGNATURE_SIZE = 7;

    // VALIDATION LIVES IN read(): A RECORD CANONICAL CONSTRUCTOR CANNOT DECLARE A throws CLAUSE, SO
    // MALFORMED DATA IS REJECTED WITH XCodecException (THE READER-LAYER FAILURE TYPE) AT THE PARSE BOUNDARY
    public static ScreenDescriptor read(final ByteBuffer buffer) throws XCodecException {
        if (buffer.remaining() < SIGNATURE_SIZE) {
            throw new XCodecException("Buffer does not contain enough data for Screen Descriptor");
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

        if (width <= 0 || height <= 0)
            throw new XCodecException("Width and height must be positive integers");

        return new ScreenDescriptor(width, height, globalColorTableFlag, colorResolution, sortFlag,
                globalColorTableSize, backgroundColorIndex, pixelAspectRatio);
    }

    public byte[] toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(SIGNATURE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) this.width);
        buf.putShort((short) this.height);
        int packed = 0;
        if (this.globalColorTableFlag) packed |= 0b1000_0000;
        packed |= ((this.colorResolution - 1) & 0b0111) << 4;
        if (this.sortFlag) packed |= 0b0000_1000;
        packed |= this.globalColorTableSize & 0b0000_0111;
        buf.put((byte) packed);
        buf.put((byte) this.backgroundColorIndex);
        buf.put((byte) this.pixelAspectRatio);
        return buf.array();
    }
}
