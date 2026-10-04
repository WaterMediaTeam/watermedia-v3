package org.watermedia.api.codecs.common.gif;

import org.watermedia.api.codecs.XCodecException;

import java.nio.ByteBuffer;

/** A GIF palette of ARGB values; reading supplies opaque alpha and serialization emits only RGB triples. */
public record ColorTable(int size, int[] colors) implements IChunk {
    public static final int MAX_COLORS = 256;

    /** Reads {@code size} RGB triples (up to {@link #MAX_COLORS}) as opaque ARGB colors. */
    public static ColorTable read(int size, ByteBuffer buffer) throws XCodecException {
        // VALIDATION LIVES HERE: A RECORD CANONICAL CONSTRUCTOR CANNOT THROW XCodecException
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
            // BGRA LAYOUT WHEN CONSUMED VIA IntBuffer OVER A LITTLE-ENDIAN DIRECT BUFFER
            colorTable[i] = 0xFF000000
                    | ((raw[p]     & 0xFF) << 16)
                    | ((raw[p + 1] & 0xFF) << 8)
                    | (raw[p + 2] & 0xFF);
        }

        return new ColorTable(size, colorTable);
    }

    @Override
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
