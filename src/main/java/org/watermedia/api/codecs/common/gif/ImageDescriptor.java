package org.watermedia.api.codecs.common.gif;

import org.watermedia.api.codecs.XCodecException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** A GIF image's position, dimensions and packed flags, without the image separator or palette. */
public record ImageDescriptor(
    int left, int top, int width, int height,
    boolean localColorTableFlag, boolean interlacedFlag, boolean sortFlag,
    int localColorTableSize) implements IChunk {

    public static final int LOCAL_COLOR_TABLE_SIZE = 8;

    /** Returns the local palette color count, {@code 2^(localColorTableSize + 1)}, even when no palette is present. */
    public int getLocalColorTableSize() {
        return 1 << (this.localColorTableSize + 1);
    }

    /** Rejects negative offsets, empty dimensions and palette-size codes outside the three-bit range. */
    public void validate() throws XCodecException {
        // A RECORD CANONICAL CONSTRUCTOR CANNOT THROW XCodecException, SO read() AND
        // GIFReader.readImageDescriptor CALL THIS AT THE PARSE BOUNDARY
        if (this.left < 0 || this.top < 0 || this.width <= 0 || this.height <= 0) {
            throw new XCodecException("Invalid dimensions for ImageDescriptor");
        }
        if (this.localColorTableSize < 0 || this.localColorTableSize >= LOCAL_COLOR_TABLE_SIZE) {
            throw new XCodecException("Local color table size must be between 0 and " + (LOCAL_COLOR_TABLE_SIZE - 1));
        }
    }

    /** Reads and validates the nine-byte descriptor after the image separator, without its local palette. */
    public static ImageDescriptor read(final ByteBuffer buffer) throws XCodecException {
        if (buffer.remaining() < 9) {
            throw new XCodecException("Buffer does not contain enough data for Image Descriptor");
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

        final ImageDescriptor id = new ImageDescriptor(left, top, width, height, localColorTableFlag, interlacedFlag, sortFlag, localColorTableSize);
        id.validate();
        return id;
    }

    @Override
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
