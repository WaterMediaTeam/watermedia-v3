package org.watermedia.api.util;

import java.util.Set;

/**
 * Quality levels for media streams.
 */
public enum MediaQuality {
    UNKNOWN(0),
    Q144P(144),
    LOWEST(240),
    LOWER(360),
    LOW(480),
    MEDIUM(720),
    HIGH(1080),
    HIGHER(1440),
    HIGHEST(2160),
    Q8K(4320);

    private static final MediaQuality[] VALUES = values();

    public final int threshold;

    MediaQuality(final int threshold) {
        this.threshold = threshold;
    }

    public static MediaQuality of(final int resolution) {
        return of(resolution, resolution);
    }

    /**
     * Determines quality from video dimensions.
     * It picks the smallest non-zero one — landscape videos (1920x1080)
     * will use 1080 to determine quality (HIGH). When a single dimension
     * is reported (HLS rendition tags often give height only), the
     * non-zero value is used directly.
     *
     * @param width  video width (0 if unknown)
     * @param height video height (0 if unknown)
     * @return the appropriate quality level
     */
    public static MediaQuality of(final int width, final int height) {
        final int size;
        if (width <= 0 && height <= 0) return UNKNOWN;
        else if (width <= 0) size = height;
        else if (height <= 0) size = width;
        else size = Math.min(width, height);

        for (int i = VALUES.length - 1; i >= 0; i--) {
            if (size >= VALUES[i].threshold) {
                return VALUES[i];
            }
        }
        return Q144P;
    }

    /**
     * Gets the next higher quality level.
     */
    public MediaQuality higher() {
        final int next = this.ordinal() + 1;
        return next < VALUES.length ? VALUES[next] : this;
    }

    /**
     * Gets the next lower quality level.
     */
    public MediaQuality lower() {
        final int prev = this.ordinal() - 1;
        return prev >= 0 ? VALUES[prev] : this;
    }

    /**
     * Finds the closest available quality from a set.
     *
     * @param available set of available qualities
     * @param preferred the preferred quality
     * @return the closest available quality, or null if none available
     */
    public static MediaQuality closest(final Set<MediaQuality> available, final MediaQuality preferred) {
        if (available == null || available.isEmpty()) return null;
        if (available.contains(preferred)) return preferred;

        // SEARCH OUTWARD FROM PREFERRED
        MediaQuality lower = preferred.lower();
        MediaQuality higher = preferred.higher();

        while (lower != preferred || higher != preferred) {
            if (lower != preferred) {
                if (available.contains(lower)) return lower;
                lower = lower.lower();
            }
            if (higher != preferred) {
                if (available.contains(higher)) return higher;
                higher = higher.higher();
            }
            // PREVENT INFINITE LOOP
            if (lower.ordinal() == 0 && higher.ordinal() == VALUES.length - 1) break;
        }

        // FALLBACK: RETURN ANY AVAILABLE
        return available.iterator().next();
    }
}
