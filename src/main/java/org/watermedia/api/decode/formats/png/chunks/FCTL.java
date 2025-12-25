package org.watermedia.api.decode.formats.png.chunks;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * fcTL - Frame Control Chunk
 * Contains information about each frame in an APNG animation
 *
 * @see <a href="https://www.w3.org/TR/png-3/#fcTL-chunk">PNG Specification - fcTL</a>
 */
public record FCTL(int seq, int width, int height, int xOffset, int yOffset,
                   short delay, short delay_den, byte dispose, byte blend) {

    public static final int SIGNATURE = 0x66_63_54_4C; // "fcTL"

    // DISPOSE OPERATIONS
    public static final byte DISPOSE_OP_NONE = 0;       // LEAVE OUTPUT BUFFER AS IS
    public static final byte DISPOSE_OP_BACKGROUND = 1; // CLEAR OUTPUT BUFFER TO FULLY TRANSPARENT BLACK
    public static final byte DISPOSE_OP_PREVIOUS = 2;   // RESTORE OUTPUT BUFFER TO PREVIOUS CONTENT

    // BLEND OPERATIONS
    public static final byte BLEND_OP_SOURCE = 0;       // REPLACE ALL COMPONENTS INCLUDING ALPHA
    public static final byte BLEND_OP_OVER = 1;         // ALPHA COMPOSITE (PORTER-DUFF "OVER")

    /**
     * Reads fcTL chunk from buffer (legacy method, reads length/type from buffer)
     */
    public static FCTL read(final ByteBuffer buffer) {
        // CHUNK HEADER: LENGTH (4 BYTES), TYPE (4 BYTES)
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        if (type != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for fcTL: 0x" + Integer.toHexString(type));
        }

        return new FCTL(
                buffer.getInt(),     // SEQUENCE NUMBER
                buffer.getInt(),     // WIDTH
                buffer.getInt(),     // HEIGHT
                buffer.getInt(),     // X OFFSET
                buffer.getInt(),     // Y OFFSET
                buffer.getShort(),   // DELAY NUMERATOR
                buffer.getShort(),   // DELAY DENOMINATOR
                buffer.get(),        // DISPOSE OP
                buffer.get()         // BLEND OP
        );
    }

    /**
     * Converts a generic CHUNK to FCTL
     */
    public static FCTL convert(final CHUNK chunk, final ByteOrder order) {
        if (chunk.type() != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for fcTL: 0x" + Integer.toHexString(chunk.type()));
        }

        final byte[] data = chunk.data();
        if (data.length != 26) {
            throw new IllegalArgumentException("fcTL data must be 26 bytes, got " + data.length);
        }

        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        return new FCTL(
                buffer.getInt(),     // SEQUENCE NUMBER (4 BYTES)
                buffer.getInt(),     // WIDTH (4 BYTES)
                buffer.getInt(),     // HEIGHT (4 BYTES)
                buffer.getInt(),     // X OFFSET (4 BYTES)
                buffer.getInt(),     // Y OFFSET (4 BYTES)
                buffer.getShort(),   // DELAY NUMERATOR (2 BYTES)
                buffer.getShort(),   // DELAY DENOMINATOR (2 BYTES)
                buffer.get(),        // DISPOSE OP (1 BYTE)
                buffer.get()         // BLEND OP (1 BYTE)
        );
    }

    /**
     * Returns the frame delay in milliseconds
     */
    public int delayMillis() {
        final int num = this.delay & 0xFFFF;
        int den = this.delay_den & 0xFFFF;
        if (den == 0) den = 100; // DEFAULT TO 100 IF 0
        return (num * 1000) / den;
    }

    /**
     * Validates frame bounds against canvas dimensions
     */
    public void validate(final int canvasWidth, final int canvasHeight) {
        if (this.width <= 0 || this.height <= 0) {
            throw new IllegalStateException("Invalid frame dimensions: " + this.width + "x" + this.height);
        }
        if (this.xOffset < 0 || this.yOffset < 0) {
            throw new IllegalStateException("Invalid frame offset: " + this.xOffset + "," + this.yOffset);
        }
        if (this.xOffset + this.width > canvasWidth || this.yOffset + this.height > canvasHeight) {
            throw new IllegalStateException("Frame extends beyond canvas bounds");
        }
    }
}