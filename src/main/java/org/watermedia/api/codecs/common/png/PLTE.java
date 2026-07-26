package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.XCodecException;

import java.nio.ByteBuffer;

/**
 * PLTE - Palette Chunk
 * Contains 1 to 256 palette entries, stored as packed RGB integers (0xRRGGBB)
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11PLTE">PNG Specification - PLTE</a>
 */
public record PLTE(int[] colors) {
    public static final int SIGNATURE = 0x50_4C_54_45; // "PLTE"
    // THE SPEC CAPS THE PALETTE AT 256 ENTRIES; WITHOUT IT A HUGE PLTE INFLATES TO A 1.33x int[] COPY
    public static final int MAX_LENGTH = 256 * 3;

    /**
     * Reads PLTE chunk from buffer (reads length/type header first)
     */
    public static PLTE read(final ByteBuffer buffer) throws XCodecException {
        if (buffer.remaining() < 8) throw new XCodecException("Truncated PLTE chunk header");
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        // TYPE AND LENGTH COME STRAIGHT OFF THE WIRE HERE, SO BOTH ARE ATTACKER DATA (UNLIKE convert)
        if (type != SIGNATURE)
            throw new XCodecException("Invalid chunk type for PLTE: 0x" + Integer.toHexString(type));
        if (length < 0 || length % 3 != 0)
            throw new XCodecException("PLTE data length must be divisible by 3, got " + Integer.toUnsignedString(length));
        if (length > MAX_LENGTH)
            throw new XCodecException("PLTE holds more than 256 entries: " + (length / 3));
        if (buffer.remaining() < length) throw new XCodecException("Truncated PLTE chunk");

        final int count = length / 3;
        final int[] colors = new int[count];

        for (int i = 0; i < count; i++) {
            final int r = buffer.get() & 0xFF;
            final int g = buffer.get() & 0xFF;
            final int b = buffer.get() & 0xFF;
            colors[i] = (r << 16) | (g << 8) | b;
        }

        return new PLTE(colors);
    }

    /**
     * Converts a generic CHUNK to PLTE
     */
    public static PLTE convert(final CHUNK chunk) throws XCodecException {
        if (chunk.type() != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for PLTE: 0x" + Integer.toHexString(chunk.type()));
        }

        final byte[] data = chunk.data();
        if (data.length % 3 != 0) {
            throw new XCodecException("PLTE data length must be divisible by 3");
        }
        if (data.length > MAX_LENGTH) {
            throw new XCodecException("PLTE holds more than 256 entries: " + (data.length / 3));
        }

        final int count = data.length / 3;
        final int[] colors = new int[count];

        for (int i = 0; i < count; i++) {
            final int offset = i * 3;
            final int r = data[offset] & 0xFF;
            final int g = data[offset + 1] & 0xFF;
            final int b = data[offset + 2] & 0xFF;
            colors[i] = (r << 16) | (g << 8) | b;
        }

        return new PLTE(colors);
    }

    /**
     * Returns the number of palette entries
     */
    public int size() {
        return this.colors.length;
    }

    /**
     * Returns the packed RGB color at the given palette index (0xRRGGBB)
     * @param index The palette index (0-255)
     * @return Packed RGB value
     */
    public int getColor(final int index) {
        return this.colors[index];
    }

    public byte[] toBytes() {
        final byte[] data = new byte[this.colors.length * 3];
        for (int i = 0; i < this.colors.length; i++) {
            final int c = this.colors[i];
            data[i * 3] = (byte) ((c >> 16) & 0xFF);
            data[i * 3 + 1] = (byte) ((c >> 8) & 0xFF);
            data[i * 3 + 2] = (byte) (c & 0xFF);
        }
        return data;
    }

    public CHUNK toChunk() {
        return CHUNK.create(SIGNATURE, this.toBytes());
    }
}