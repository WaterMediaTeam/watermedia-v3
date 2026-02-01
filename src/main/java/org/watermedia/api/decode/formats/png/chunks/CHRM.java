package org.watermedia.api.decode.formats.png.chunks;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * cHRM - Primary Chromaticities Chunk
 * Specifies the 1931 CIE x,y chromaticities of the RGB primaries and white point
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11cHRM">PNG Specification - cHRM</a>
 */
public record CHRM(int whiteX, int whiteY, int redX, int redY, int greenX, int greenY, int blueX, int blueY) {
    public static final int SIGNATURE = 0x63_48_52_4D; // "cHRM"
    public static final int LENGTH = 32;

    /**
     * Reads cHRM chunk from buffer (reads length/type header first)
     */
    public static CHRM read(final ByteBuffer buffer) {
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        if (type != SIGNATURE)
            throw new IllegalArgumentException("Invalid chunk type for cHRM: 0x" + Integer.toHexString(type));
        if (length != LENGTH)
            throw new IllegalArgumentException("cHRM chunk length must be 32, got " + length);

        return new CHRM(
                buffer.getInt(), // WHITE POINT X
                buffer.getInt(), // WHITE POINT Y
                buffer.getInt(), // RED X
                buffer.getInt(), // RED Y
                buffer.getInt(), // GREEN X
                buffer.getInt(), // GREEN Y
                buffer.getInt(), // BLUE X
                buffer.getInt()  // BLUE Y
        );
    }

    /**
     * Converts a generic CHUNK to CHRM
     */
    public static CHRM convert(final CHUNK chunk) {
        if (chunk.type() != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for cHRM: 0x" + Integer.toHexString(chunk.type()));
        }

        final byte[] data = chunk.data();
        if (data.length != 32) {
            throw new IllegalArgumentException("cHRM data must be 32 bytes, got " + data.length);
        }

        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        return new CHRM(
                buffer.getInt(), // WHITE POINT X
                buffer.getInt(), // WHITE POINT Y
                buffer.getInt(), // RED X
                buffer.getInt(), // RED Y
                buffer.getInt(), // GREEN X
                buffer.getInt(), // GREEN Y
                buffer.getInt(), // BLUE X
                buffer.getInt()  // BLUE Y
        );
    }

    // VALUES ARE STORED AS UNSIGNED INTEGERS * 100000
    public float whitePointX() { return this.whiteX / 100000.0f; }
    public float whitePointY() { return this.whiteY / 100000.0f; }
    public float redPrimaryX() { return this.redX / 100000.0f; }
    public float redPrimaryY() { return this.redY / 100000.0f; }
    public float greenPrimaryX() { return this.greenX / 100000.0f; }
    public float greenPrimaryY() { return this.greenY / 100000.0f; }
    public float bluePrimaryX() { return this.blueX / 100000.0f; }
    public float bluePrimaryY() { return this.blueY / 100000.0f; }
}
