package org.watermedia.api.decode.formats.png.chunks;

import org.watermedia.tools.DataTool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * acTL - Animation Control Chunk
 * Indicates that the PNG is an animated image and contains information about the animation
 *
 * @see <a href="https://www.w3.org/TR/png-3/#acTL-chunk">PNG Specification - acTL</a>
 */
public record ACTL(int frameCount, int loopCount) {
    public static final int SIGNATURE = 0x61_63_54_4C; // "acTL"

    /**
     * Reads acTL chunk from buffer (legacy method, reads length/type from buffer)
     */
    public static ACTL read(final ByteBuffer buffer) {
        // CHUNK HEADER: LENGTH (4 BYTES), TYPE (4 BYTES)
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        if (type != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for acTL: 0x" + Integer.toHexString(type));
        }

        final int frameCount = buffer.getInt();
        final int loopCount = buffer.getInt();

        // SKIP CRC
        buffer.getInt();

        return new ACTL(frameCount, loopCount);
    }

    /**
     * Converts a generic CHUNK to ACTL
     */
    public static ACTL convert(final CHUNK chunk, final ByteOrder order) {
        if (chunk.type() != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for acTL: 0x" + Integer.toHexString(chunk.type()));
        }

        final byte[] data = chunk.data();
        if (data.length != 8) {
            throw new IllegalArgumentException("acTL data must be 8 bytes, got " + data.length);
        }

        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        final int frameCount = buffer.getInt();
        final int loopCount = buffer.getInt();

        return new ACTL(frameCount, loopCount);
    }

    /**
     * Returns whether the animation loops indefinitely
     */
    public boolean loopsInfinitely() {
        return this.loopCount == 0;
    }

    /**
     * Returns the actual number of times to play the animation
     * 0 means infinite, 1 means play once, etc.
     */
    public int playCount() {
        return this.loopCount;
    }
}