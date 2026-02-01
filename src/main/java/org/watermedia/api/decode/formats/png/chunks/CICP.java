package org.watermedia.api.decode.formats.png.chunks;

import java.nio.ByteBuffer;

/**
 * cICP - Coding-Independent Code Points Chunk
 * Specifies video signal type identification for HDR images
 *
 * @see <a href="https://www.w3.org/TR/png-3/#cICP-chunk">PNG Specification - cICP</a>
 */
public record CICP(int colorPrimaries, int transferFunction, int matrixCoefficients, int videoFullRangeFlag) {
    public static final int SIGNATURE = 0x63_49_43_50; // "cICP"
    public static final int LENGTH = 4;

    // COMMON COLOR PRIMARIES (ITU-T H.273)
    public static final int PRIMARIES_BT709 = 1;
    public static final int PRIMARIES_BT2020 = 9;
    public static final int PRIMARIES_P3 = 12;

    // COMMON TRANSFER FUNCTIONS
    public static final int TRANSFER_BT709 = 1;
    public static final int TRANSFER_SRGB = 13;
    public static final int TRANSFER_PQ = 16;    // HDR10
    public static final int TRANSFER_HLG = 18;   // HLG HDR

    /**
     * Reads cICP chunk from buffer (reads length/type header first)
     */
    public static CICP read(final ByteBuffer buffer) {
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        if (type != SIGNATURE)
            throw new IllegalArgumentException("Invalid chunk type for cICP: 0x" + Integer.toHexString(type));
        if (length != LENGTH)
            throw new IllegalArgumentException("cICP chunk length must be 4, got " + length);

        return new CICP(
                buffer.get() & 0xFF, // COLOR PRIMARIES
                buffer.get() & 0xFF, // TRANSFER FUNCTION
                buffer.get() & 0xFF, // MATRIX COEFFICIENTS
                buffer.get() & 0xFF  // VIDEO FULL RANGE FLAG
        );
    }

    /**
     * Converts a generic CHUNK to CICP
     */
    public static CICP convert(final CHUNK chunk) {
        if (chunk.type() != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for cICP: 0x" + Integer.toHexString(chunk.type()));
        }

        final byte[] data = chunk.data();
        if (data.length != 4) {
            throw new IllegalArgumentException("cICP data must be 4 bytes, got " + data.length);
        }

        return new CICP(
                data[0] & 0xFF,
                data[1] & 0xFF,
                data[2] & 0xFF,
                data[3] & 0xFF
        );
    }

    /**
     * Returns whether the image uses full range (0-255) or limited range (16-235)
     */
    public boolean isFullRange() {
        return this.videoFullRangeFlag == 1;
    }

    /**
     * Returns whether this indicates an HDR image (PQ or HLG transfer)
     */
    public boolean isHDR() {
        return this.transferFunction == TRANSFER_PQ || this.transferFunction == TRANSFER_HLG;
    }

    /**
     * Returns whether this uses sRGB transfer function
     */
    public boolean isSRGB() {
        return this.transferFunction == TRANSFER_SRGB;
    }
}
