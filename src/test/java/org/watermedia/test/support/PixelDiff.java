package org.watermedia.test.support;

/**
 * Channel-wise difference report between two equally-sized byte arrays.
 * Returned by {@link #compare(byte[], byte[])} as an immutable record.
 *
 * <p>Tests with strict tolerances use this struct directly so assertions can
 * read each metric (max, mean, percent-over-threshold) without recomputing.
 */
public record PixelDiff(int maxDiff, double meanDiff, int valuesAboveSmall, int totalValues) {

    /** Compares two byte arrays as unsigned values; arrays must have equal length. */
    public static PixelDiff compare(final byte[] reference, final byte[] actual) {
        if (reference.length != actual.length) {
            throw new IllegalArgumentException("Length mismatch: ref=" + reference.length + " actual=" + actual.length);
        }
        return compare(reference, actual, 8);
    }

    /**
     * Compares with an explicit "small-diff" threshold (used by lossy codecs
     * to report the fraction of bytes that drifted noticeably).
     */
    public static PixelDiff compare(final byte[] reference, final byte[] actual, final int smallDiffThreshold) {
        int maxDiff = 0;
        long total = 0L;
        int aboveSmall = 0;
        for (int i = 0; i < reference.length; i++) {
            final int diff = Math.abs((reference[i] & 0xFF) - (actual[i] & 0xFF));
            if (diff > maxDiff) maxDiff = diff;
            total += diff;
            if (diff > smallDiffThreshold) aboveSmall++;
        }
        final double mean = reference.length == 0 ? 0.0 : (double) total / reference.length;
        return new PixelDiff(maxDiff, mean, aboveSmall, reference.length);
    }

    /** Percentage of byte values whose absolute difference exceeded the small-diff threshold. */
    public double percentAboveSmall() {
        return this.totalValues == 0 ? 0.0 : (this.valuesAboveSmall * 100.0) / this.totalValues;
    }
}
