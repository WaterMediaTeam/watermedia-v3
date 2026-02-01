package org.watermedia.api.decode.formats.png.chunks;

import java.nio.ByteBuffer;

/**
 * eXIf - Exchangeable Image File Format Chunk
 * Contains EXIF metadata from cameras and image editing software
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11eXIf">PNG Specification - eXIf</a>
 */
public record EXIF(byte[] data) {
    public static final int SIGNATURE = 0x65_58_49_66; // "eXIf"

    // TIFF/EXIF BYTE ORDER MARKERS
    public static final short BIG_ENDIAN_MARKER = 0x4D4D;    // "MM"
    public static final short LITTLE_ENDIAN_MARKER = 0x4949; // "II"

    /**
     * Reads eXIf chunk from buffer (reads length/type header first)
     */
    public static EXIF read(final ByteBuffer buffer) {
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        if (type != SIGNATURE)
            throw new IllegalArgumentException("Invalid chunk type for eXIf: 0x" + Integer.toHexString(type));

        final byte[] data = new byte[length];
        buffer.get(data);
        return new EXIF(data);
    }

    /**
     * Converts a generic CHUNK to EXIF
     */
    public static EXIF convert(final CHUNK chunk) {
        if (chunk.type() != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for eXIf: 0x" + Integer.toHexString(chunk.type()));
        }

        final byte[] data = chunk.data();

        // VALIDATE MINIMUM SIZE (AT LEAST TIFF HEADER)
        if (data.length < 8) {
            throw new IllegalArgumentException("eXIf data too short: " + data.length + " bytes");
        }

        // VALIDATE BYTE ORDER MARKER
        final short byteOrder = (short) ((data[0] << 8) | (data[1] & 0xFF));
        if (byteOrder != BIG_ENDIAN_MARKER && byteOrder != LITTLE_ENDIAN_MARKER) {
            // TRY LITTLE ENDIAN ORDER
            final short leByteOrder = (short) ((data[1] << 8) | (data[0] & 0xFF));
            if (leByteOrder != BIG_ENDIAN_MARKER && leByteOrder != LITTLE_ENDIAN_MARKER) {
                throw new IllegalArgumentException("Invalid EXIF byte order marker");
            }
        }

        return new EXIF(data.clone());
    }

    /**
     * Returns whether the EXIF data uses big-endian byte order
     */
    public boolean isBigEndian() {
        return (this.data[0] == 'M' && this.data[1] == 'M');
    }

    /**
     * Returns the raw EXIF data for external parsing
     */
    public byte[] getRawData() {
        return this.data.clone();
    }

    /**
     * Returns the size of the EXIF data
     */
    public int size() {
        return this.data.length;
    }
}
