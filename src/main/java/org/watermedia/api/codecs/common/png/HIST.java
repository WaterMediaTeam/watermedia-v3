package org.watermedia.api.codecs.common.png;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * hIST - Palette Histogram Chunk
 * Gives the approximate usage frequency of each color in the palette
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11hIST">PNG Specification - hIST</a>
 */
public record HIST(int[] frequencies) {
    public static final int SIGNATURE = 0x68_49_53_54; // "hIST"

    /**
     * Reads hIST chunk from buffer (reads length/type header first)
     */
    public static HIST read(final ByteBuffer buffer) {
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        if (type != SIGNATURE)
            throw new IllegalArgumentException("Invalid chunk type for hIST: 0x" + Integer.toHexString(type));

        // LENGTH IS 2 BYTES PER PALETTE ENTRY
        final int paletteSize = length / 2;
        final int[] frequencies = new int[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            frequencies[i] = buffer.getShort() & 0xFFFF;
        }
        return new HIST(frequencies);
    }

    /**
     * Converts a generic CHUNK to HIST
     */
    public static HIST convert(final CHUNK chunk, final int paletteSize) {
        if (chunk.type() != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for hIST: 0x" + Integer.toHexString(chunk.type()));
        }

        final byte[] data = chunk.data();
        if (data.length != paletteSize * 2) {
            throw new IllegalArgumentException("hIST data must have " + paletteSize + " entries (" +
                    (paletteSize * 2) + " bytes), got " + data.length + " bytes");
        }

        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        final int[] frequencies = new int[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            frequencies[i] = buffer.getShort() & 0xFFFF;
        }
        return new HIST(frequencies);
    }

    /**
     * Returns the frequency for a specific palette index
     */
    public int getFrequency(final int index) {
        return this.frequencies[index];
    }

    /**
     * Returns the number of entries
     */
    public int size() {
        return this.frequencies.length;
    }

    public byte[] toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(this.frequencies.length * 2).order(ByteOrder.BIG_ENDIAN);
        for (final int freq: this.frequencies) {
            buf.putShort((short) freq);
        }
        return buf.array();
    }

    public CHUNK toChunk() {
        return CHUNK.create(SIGNATURE, this.toBytes());
    }
}
