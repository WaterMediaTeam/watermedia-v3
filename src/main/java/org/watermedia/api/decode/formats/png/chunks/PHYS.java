package org.watermedia.api.decode.formats.png.chunks;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * pHYs - Physical Pixel Dimensions Chunk
 * Specifies the intended pixel size or aspect ratio for display
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11pHYs">PNG Specification - pHYs</a>
 */
public record PHYS(long pixelsPerUnitX, long pixelsPerUnitY, int unit) {
    public static final int SIGNATURE = 0x70_48_59_73; // "pHYs"
    public static final int LENGTH = 9;

    // UNIT SPECIFIERS
    public static final int UNIT_UNKNOWN = 0;
    public static final int UNIT_METER = 1;

    /**
     * Reads pHYs chunk from buffer (reads length/type header first)
     */
    public static PHYS read(final ByteBuffer buffer) {
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        if (type != SIGNATURE)
            throw new IllegalArgumentException("Invalid chunk type for pHYs: 0x" + Integer.toHexString(type));
        if (length != LENGTH)
            throw new IllegalArgumentException("pHYs chunk length must be 9, got " + length);

        return new PHYS(
                buffer.getInt() & 0xFFFFFFFFL, // PIXELS PER UNIT X
                buffer.getInt() & 0xFFFFFFFFL, // PIXELS PER UNIT Y
                buffer.get() & 0xFF            // UNIT SPECIFIER
        );
    }

    /**
     * Converts a generic CHUNK to PHYS
     */
    public static PHYS convert(final CHUNK chunk) {
        if (chunk.type() != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for pHYs: 0x" + Integer.toHexString(chunk.type()));
        }

        final byte[] data = chunk.data();
        if (data.length != 9) {
            throw new IllegalArgumentException("pHYs data must be 9 bytes, got " + data.length);
        }

        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        return new PHYS(
                buffer.getInt() & 0xFFFFFFFFL, // PIXELS PER UNIT X
                buffer.getInt() & 0xFFFFFFFFL, // PIXELS PER UNIT Y
                buffer.get() & 0xFF            // UNIT SPECIFIER
        );
    }

    /**
     * Returns whether the unit is meters (allows DPI calculation)
     */
    public boolean isMetric() {
        return this.unit == UNIT_METER;
    }

    /**
     * Returns the aspect ratio (X/Y)
     */
    public double aspectRatio() {
        return (double) this.pixelsPerUnitX / this.pixelsPerUnitY;
    }

    /**
     * Returns DPI for X axis (only valid if unit is meter)
     */
    public double dpiX() {
        if (this.unit != UNIT_METER) return 0;
        return this.pixelsPerUnitX * 0.0254; // METERS TO INCHES
    }

    /**
     * Returns DPI for Y axis (only valid if unit is meter)
     */
    public double dpiY() {
        if (this.unit != UNIT_METER) return 0;
        return this.pixelsPerUnitY * 0.0254;
    }
}
