package org.watermedia.api.codecs.common.gif;

import org.watermedia.api.codecs.XCodecException;

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
    /** Full block body: block size + packed fields + delay + transparent index + terminator. */
    public static final int BODY_SIZE = 6;

    // VALIDATION LIVES IN read(): A RECORD CANONICAL CONSTRUCTOR CANNOT DECLARE A throws CLAUSE, SO
    // MALFORMED DATA IS REJECTED WITH XCodecException (THE READER-LAYER FAILURE TYPE) AT THE PARSE BOUNDARY
    public static GraphicExtension read(final ByteBuffer buffer) throws XCodecException {
        if (buffer.remaining() < BODY_SIZE) {
            throw new XCodecException("Buffer does not contain enough data for Graphic Control Extension");
        }

        // BLOCK SIZE AND TERMINATOR ARE FIXED BY THE SPEC. SKIPPING THEM MAKES THIS PARSER ACCEPT FILES
        // A STRICTER ONE REJECTS, AND TWO PARSERS DISAGREEING OVER ONE FILE IS A VALIDATION BYPASS
        final int blockSize = Byte.toUnsignedInt(buffer.get());
        if (blockSize != 4) throw new XCodecException("Invalid GCE block size: " + blockSize + " (must be 4)");

        final int packedFields = Byte.toUnsignedInt(buffer.get());
        final int disposalMethod = (packedFields & 0b00011100) >> 2;
        final boolean userInputFlag = (packedFields & 0b00000010) != 0;
        final boolean transparentColorFlag = (packedFields & 0b00000001) != 0;

        final int delayTime = Short.toUnsignedInt(buffer.getShort());
        final int transparentColorIndex = Byte.toUnsignedInt(buffer.get());

        final int terminator = Byte.toUnsignedInt(buffer.get());
        if (terminator != 0) throw new XCodecException("Invalid GCE block terminator: " + terminator + " (must be 0)");

        return new GraphicExtension(disposalMethod, userInputFlag, transparentColorFlag, delayTime, transparentColorIndex);
    }

    public byte[] toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(BODY_SIZE).order(ByteOrder.LITTLE_ENDIAN);
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