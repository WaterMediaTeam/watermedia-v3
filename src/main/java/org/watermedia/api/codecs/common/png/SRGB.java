package org.watermedia.api.codecs.common.png;

import java.nio.ByteBuffer;

/**
 * sRGB - Standard RGB Color Space Chunk
 * Indicates the image uses the sRGB color space with specified rendering intent
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11sRGB">PNG Specification - sRGB</a>
 */
public record SRGB(int renderingIntent) {
    public static final int SIGNATURE = 0x73_52_47_42; // "sRGB"
    public static final int LENGTH = 1;

    // RENDERING INTENTS
    public static final int PERCEPTUAL = 0;
    public static final int RELATIVE_COLORIMETRIC = 1;
    public static final int SATURATION = 2;
    public static final int ABSOLUTE_COLORIMETRIC = 3;

    /**
     * Reads sRGB chunk from buffer (reads length/type header first)
     */
    public static SRGB read(final ByteBuffer buffer) {
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        if (type != SIGNATURE)
            throw new IllegalArgumentException("Invalid chunk type for sRGB: 0x" + Integer.toHexString(type));
        if (length != LENGTH)
            throw new IllegalArgumentException("sRGB chunk length must be 1, got " + length);

        return new SRGB(buffer.get() & 0xFF);
    }

    /**
     * Converts a generic CHUNK to SRGB
     */
    public static SRGB convert(final CHUNK chunk) {
        if (chunk.type() != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for sRGB: 0x" + Integer.toHexString(chunk.type()));
        }

        final byte[] data = chunk.data();
        if (data.length != 1) {
            throw new IllegalArgumentException("sRGB data must be 1 byte, got " + data.length);
        }

        return new SRGB(data[0] & 0xFF);
    }

    /**
     * Returns whether perceptual rendering intent is used
     */
    public boolean isPerceptual() {
        return this.renderingIntent == PERCEPTUAL;
    }

    /**
     * Returns the rendering intent name
     */
    public String renderingIntentName() {
        return switch (this.renderingIntent) {
            case PERCEPTUAL -> "Perceptual";
            case RELATIVE_COLORIMETRIC -> "Relative colorimetric";
            case SATURATION -> "Saturation";
            case ABSOLUTE_COLORIMETRIC -> "Absolute colorimetric";
            default -> "Unknown (" + this.renderingIntent + ")";
        };
    }

    public byte[] toBytes() {
        return new byte[] { (byte) this.renderingIntent };
    }

    public CHUNK toChunk() {
        return CHUNK.create(SIGNATURE, this.toBytes());
    }
}
