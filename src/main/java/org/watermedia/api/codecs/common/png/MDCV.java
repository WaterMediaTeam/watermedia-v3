package org.watermedia.api.codecs.common.png;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * mDCv - Mastering Display Color Volume Chunk
 * HDR metadata describing the color volume of the mastering display
 *
 * @see <a href="https://www.w3.org/TR/png-3/#mDCv-chunk">PNG Specification - mDCv</a>
 */
public record MDCV(
        int redX, int redY,
        int greenX, int greenY,
        int blueX, int blueY,
        int whiteX, int whiteY,
        long maxLuminance,
        long minLuminance
) {
    public static final int SIGNATURE = 0x6D_44_43_76; // "mDCv"
    public static final int LENGTH = 24;

    /**
     * Reads mDCv chunk from buffer (reads length/type header first)
     */
    public static MDCV read(final ByteBuffer buffer) {
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        if (type != SIGNATURE)
            throw new IllegalArgumentException("Invalid chunk type for mDCv: 0x" + Integer.toHexString(type));
        if (length != LENGTH)
            throw new IllegalArgumentException("mDCv chunk length must be 24, got " + length);

        return new MDCV(
                buffer.getShort() & 0xFFFF, // RED X
                buffer.getShort() & 0xFFFF, // RED Y
                buffer.getShort() & 0xFFFF, // GREEN X
                buffer.getShort() & 0xFFFF, // GREEN Y
                buffer.getShort() & 0xFFFF, // BLUE X
                buffer.getShort() & 0xFFFF, // BLUE Y
                buffer.getShort() & 0xFFFF, // WHITE X
                buffer.getShort() & 0xFFFF, // WHITE Y
                buffer.getInt() & 0xFFFFFFFFL, // MAX LUMINANCE
                buffer.getInt() & 0xFFFFFFFFL  // MIN LUMINANCE
        );
    }

    /**
     * Converts a generic CHUNK to MDCV
     */
    public static MDCV convert(final CHUNK chunk) {
        if (chunk.type() != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for mDCv: 0x" + Integer.toHexString(chunk.type()));
        }

        final byte[] data = chunk.data();
        if (data.length != 24) {
            throw new IllegalArgumentException("mDCv data must be 24 bytes, got " + data.length);
        }

        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        return new MDCV(
                buffer.getShort() & 0xFFFF, // RED X
                buffer.getShort() & 0xFFFF, // RED Y
                buffer.getShort() & 0xFFFF, // GREEN X
                buffer.getShort() & 0xFFFF, // GREEN Y
                buffer.getShort() & 0xFFFF, // BLUE X
                buffer.getShort() & 0xFFFF, // BLUE Y
                buffer.getShort() & 0xFFFF, // WHITE X
                buffer.getShort() & 0xFFFF, // WHITE Y
                buffer.getInt() & 0xFFFFFFFFL, // MAX LUMINANCE
                buffer.getInt() & 0xFFFFFFFFL  // MIN LUMINANCE
        );
    }

    // CHROMATICITY VALUES ARE STORED AS 0.00002 UNITS
    public float redPrimaryX() { return this.redX * 0.00002f; }
    public float redPrimaryY() { return this.redY * 0.00002f; }
    public float greenPrimaryX() { return this.greenX * 0.00002f; }
    public float greenPrimaryY() { return this.greenY * 0.00002f; }
    public float bluePrimaryX() { return this.blueX * 0.00002f; }
    public float bluePrimaryY() { return this.blueY * 0.00002f; }
    public float whitePointX() { return this.whiteX * 0.00002f; }
    public float whitePointY() { return this.whiteY * 0.00002f; }

    // LUMINANCE VALUES ARE STORED AS 0.0001 CD/M^2 UNITS
    public float maxLuminanceCdm2() { return this.maxLuminance * 0.0001f; }
    public float minLuminanceCdm2() { return this.minLuminance * 0.0001f; }

    public byte[] toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(LENGTH).order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) this.redX);
        buf.putShort((short) this.redY);
        buf.putShort((short) this.greenX);
        buf.putShort((short) this.greenY);
        buf.putShort((short) this.blueX);
        buf.putShort((short) this.blueY);
        buf.putShort((short) this.whiteX);
        buf.putShort((short) this.whiteY);
        buf.putInt((int) this.maxLuminance);
        buf.putInt((int) this.minLuminance);
        return buf.array();
    }

    public CHUNK toChunk() {
        return CHUNK.create(SIGNATURE, this.toBytes());
    }
}
