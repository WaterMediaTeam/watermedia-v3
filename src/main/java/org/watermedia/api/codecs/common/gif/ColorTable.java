package org.watermedia.api.codecs.common.gif;

import org.watermedia.api.codecs.XCodecException;

import java.nio.ByteBuffer;

public record ColorTable(int size, int[] colors) {
    public static final int MAX_COLORS = 256;

    // VALIDATION LIVES IN read(): A RECORD CANONICAL CONSTRUCTOR CANNOT DECLARE A throws CLAUSE, SO
    // MALFORMED DATA IS REJECTED WITH XCodecException (THE READER-LAYER FAILURE TYPE) AT THE PARSE BOUNDARY
    public static ColorTable read(int size, ByteBuffer buffer) throws XCodecException {
        if (size < 0 || size > MAX_COLORS) {
            throw new XCodecException("Color table size must be between 0 and " + MAX_COLORS);
        }
        final int byteCount = size * 3;
        if (buffer.remaining() < byteCount) {
            throw new XCodecException("Buffer does not contain enough data for Color Table. " +
                    "Expected " + byteCount + " bytes, but only " + buffer.remaining() + " available");
        }

        final byte[] raw = new byte[byteCount];
        buffer.get(raw);

        final int[] colorTable = new int[size];
        for (int i = 0, p = 0; i < size; i++, p += 3) {
            // BGRA layout when consumed via IntBuffer over little-endian direct buffer
            colorTable[i] = 0xFF000000
                    | ((raw[p]     & 0xFF) << 16)
                    | ((raw[p + 1] & 0xFF) << 8)
                    | (raw[p + 2] & 0xFF);
        }

        return new ColorTable(size, colorTable);
    }

    public byte[] toBytes() {
        final byte[] data = new byte[this.size * 3];
        for (int i = 0; i < this.size; i++) {
            final int c = this.colors[i];
            data[i * 3] = (byte) ((c >> 16) & 0xFF);
            data[i * 3 + 1] = (byte) ((c >> 8) & 0xFF);
            data[i * 3 + 2] = (byte) (c & 0xFF);
        }
        return data;
    }
}