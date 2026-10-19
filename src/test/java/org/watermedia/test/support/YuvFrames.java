package org.watermedia.test.support;

import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.IntBinaryOperator;

/**
 * Builds raw frame planes for the GPU engine tests and computes the colors they must show, straight from the
 * standard formulas (BT.601/709/2020 matrices, limited and full range, SMPTE ST 2084) so a wrong shader constant
 * cannot hide behind the same wrong constant in the test.
 */
public final class YuvFrames {
    /** Nits of SDR white (BT.2408); HDR at this level shows as full white before the highlight knee. */
    public static final double SDR_WHITE_NITS = 203.0;

    private YuvFrames() {}

    /** A plane of {@code rows} rows of {@code rowBytes} bytes, {@code stride} apart (0 = tight), ending right after its last row. */
    public static ByteBuffer plane(final int stride, final int rowBytes, final int rows, final IntBinaryOperator sample) {
        final int step = stride == 0 ? rowBytes : stride;
        final ByteBuffer buffer = BufferUtils.createByteBuffer((rows - 1) * step + rowBytes);
        for (int row = 0; row < rows; row++)
            for (int col = 0; col < rowBytes; col++) buffer.put(row * step + col, (byte) sample.applyAsInt(row, col));
        return buffer;
    }

    /** A tight plane of little-endian 16-bit samples (10, 12 and 16-bit frames), {@code width} samples per row. */
    public static ByteBuffer plane16(final int width, final int rows, final IntBinaryOperator sample) {
        final ByteBuffer buffer = BufferUtils.createByteBuffer(width * rows * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int row = 0; row < rows; row++)
            for (int col = 0; col < width; col++) buffer.putShort((row * width + col) * 2, (short) sample.applyAsInt(row, col));
        return buffer;
    }

    /**
     * Returns the 8-bit RGB an SDR YCbCr sample shows.
     *
     * @param kr   red luma weight of the matrix (0.299 BT.601, 0.2126 BT.709, 0.2627 BT.2020)
     * @param kb   blue luma weight of the matrix (0.114 BT.601, 0.0722 BT.709, 0.0593 BT.2020)
     * @param full full range codes instead of limited range
     * @param bits sample depth
     * @return red, green and blue in 0..255
     */
    public static int[] rgb(final double kr, final double kb, final boolean full, final int bits,
                            final int y, final int cb, final int cr) {
        final double unit = 1 << (bits - 8);
        final double max = (1 << bits) - 1;
        final double half = full ? 1 << (bits - 1) : 128 * unit;
        final double luma = full ? y / max : (y - 16 * unit) / (219 * unit);
        final double blue = (cb - half) / (full ? max : 224 * unit);
        final double red = (cr - half) / (full ? max : 224 * unit);
        final double r = luma + 2 * (1 - kr) * red;
        final double b = luma + 2 * (1 - kb) * blue;
        // GREEN FOLLOWS FROM THE LUMA DEFINITION Y = Kr R + Kg G + Kb B
        final double g = (luma - kr * r - kb * b) / (1 - kr - kb);
        return new int[] { byteOf(r), byteOf(g), byteOf(b) };
    }

    /** Returns the limited-range 8-bit Y, Cb and Cr codes of a non-linear RGB color in [0, 1] under luma weights kr and kb. */
    public static int[] ycbcr(final double kr, final double kb, final double r, final double g, final double b) {
        final double luma = kr * r + (1 - kr - kb) * g + kb * b;
        return new int[] {
                (int) Math.round(16 + 219 * luma),
                (int) Math.round(128 + 224 * (b - luma) / (2 * (1 - kb))),
                (int) Math.round(128 + 224 * (r - luma) / (2 * (1 - kr)))
        };
    }

    /** Returns the SMPTE ST 2084 (PQ) signal in [0, 1] for an absolute luminance in nits. */
    public static double pq(final double nits) {
        final double y = Math.pow(nits / 10_000.0, 0.1593017578125);
        return Math.pow((0.8359375 + 18.8515625 * y) / (1.0 + 18.6875 * y), 78.84375);
    }

    /** Returns the nits a gray HLG (ARIB STD-B67) signal shows on a 1000-nit display, after the BT.2100 OOTF. */
    public static double hlgNits(final double signal) {
        final double scene = signal <= 0.5
                ? signal * signal / 3.0
                : (Math.exp((signal - 0.55991073) / 0.17883277) + 0.28466892) / 12.0;
        return 1_000.0 * Math.pow(scene, 1.2);
    }

    /** Returns the 8-bit gray an HDR gray of {@code nits} shows below the highlight knee (0.75 of SDR white). */
    public static int hdrGray(final double nits) {
        return byteOf(Math.pow(nits / SDR_WHITE_NITS, 1.0 / 2.4));
    }

    // UTILITY

    private static int byteOf(final double value) {
        return (int) Math.round(Math.max(0.0, Math.min(1.0, value)) * 255.0);
    }
}
