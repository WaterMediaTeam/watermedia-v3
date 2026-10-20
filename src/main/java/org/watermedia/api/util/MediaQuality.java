package org.watermedia.api.util;

import java.util.Set;

/**
 * Quality levels for media streams.
 */
public enum MediaQuality {
    /**
     * Unknown quality
     */
    UNKNOWN(0),
    /**
     * Anything above 1 and below {@link MediaQuality#LOWER} threshold
     */
    LOWEST(144),
    /**
     * Anything equals or above its threshold and below {@link MediaQuality#LOW} threshold
     */
    LOWER(240),
    /**
     * Anything equals or above its threshold and below {@link MediaQuality#LOWISH} threshold
     */
    LOW(360),
    /**
     * Anything equals or above its threshold and below {@link MediaQuality#MEDIUM} threshold
     */
    LOWISH(480),
    /**
     * Anything equals or above its threshold and below {@link MediaQuality#HIGH} threshold
     */
    MEDIUM(720),
    /**
     * Anything equals or above its threshold and below {@link MediaQuality#HIGHER} threshold
     */
    HIGH(1080),
    /**
     * Anything equals or above its threshold and below {@link MediaQuality#HIGHEST} threshold
     */
    HIGHER(1440),
    /**
     * Anything equals or above its threshold and below {@link MediaQuality#ULTRA_8K} threshold
     */
    HIGHEST(2160),
    /**
     * Anything equals or above its threshold
     */
    ULTRA_8K(4320);

    private static final MediaQuality[] VALUES = values();

    private final int height;

    MediaQuality(final int threshold) {
        this.height = threshold;
    }

    /** Returns the vertical resolution this level names, in pixels; zero for {@link #UNKNOWN}. */
    public int height() { return this.height; }

    /** Determines quality from a single dimension; see {@link #of(int, int)}. */
    public static MediaQuality of(final int resolution) {
        return of(resolution, resolution);
    }

    /**
     * Determines quality from video dimensions.
     * It picks the smallest non-zero one — landscape videos (1920x1080)
     * will use 1080 to determine quality (HIGH). When a single dimension
     * is reported (HLS rendition tags often give height only), the
     * non-zero value is used directly. Sizes between 1 and 143 pixels
     * map to {@link #LOWEST}.
     *
     * @param width  video width (0 if unknown)
     * @param height video height (0 if unknown)
     * @return the matching quality, {@link #LOWEST} for any known size below 144 pixels,
     *         or {@link #UNKNOWN} when both dimensions are unknown
     */
    public static MediaQuality of(final int width, final int height) {
        final int size;
        if (width <= 0 && height <= 0) return UNKNOWN;
        else if (width <= 0) size = height;
        else if (height <= 0) size = width;
        else size = Math.min(width, height);

        // UNKNOWN (INDEX 0) IS NO THRESHOLD: A KNOWN SIZE UNDER EVERY REAL ONE IS STILL THE LOWEST QUALITY
        for (int i = VALUES.length - 1; i > LOWEST.ordinal(); i--) {
            if (size >= VALUES[i].height) {
                return VALUES[i];
            }
        }
        return LOWEST;
    }

    /**
     * Finds the closest available quality from a set.
     *
     * @param available set of available qualities
     * @param preferred the preferred quality
     * @return the closest available quality, or null if none available
     * @throws IllegalArgumentException when {@code preferred} is null
     */
    public static MediaQuality closest(final Set<MediaQuality> available, final MediaQuality preferred) {
        if (preferred == null) throw new IllegalArgumentException("Preferred quality cannot be null");
        if (available == null || available.isEmpty()) return null;

        // LINEAR MIN-DISTANCE SCAN; ASCENDING ORDER BREAKS TIES TOWARD THE LOWER QUALITY
        MediaQuality best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (final MediaQuality quality: VALUES) {
            if (!available.contains(quality)) continue;
            final int distance = Math.abs(quality.ordinal() - preferred.ordinal());
            if (distance < bestDistance) {
                best = quality;
                bestDistance = distance;
            }
        }
        return best;
    }
}
