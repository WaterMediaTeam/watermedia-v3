package org.watermedia.api.decode.formats.webp.lossless;

import org.watermedia.api.decode.DecoderException;
import org.watermedia.api.decode.formats.webp.common.BitReader;

public final class LZ77 {

    // DISTANCE CODE TO (xi, yi) OFFSET MAPPING FOR CODES 1-120
    // From the WebP spec: (xi, yi) pairs in order
    private static final int[][] DISTANCE_MAP = {
            {0, 1}, {1, 0}, {1, 1}, {-1, 1}, {0, 2}, {2, 0}, {1, 2}, {-1, 2},
            {2, 1}, {-2, 1}, {2, 2}, {-2, 2}, {0, 3}, {3, 0}, {1, 3}, {-1, 3},
            {3, 1}, {-3, 1}, {2, 3}, {-2, 3}, {3, 2}, {-3, 2}, {0, 4}, {4, 0},
            {1, 4}, {-1, 4}, {4, 1}, {-4, 1}, {3, 3}, {-3, 3}, {2, 4}, {-2, 4},
            {4, 2}, {-4, 2}, {0, 5}, {3, 4}, {-3, 4}, {4, 3}, {-4, 3}, {5, 0},
            {1, 5}, {-1, 5}, {5, 1}, {-5, 1}, {2, 5}, {-2, 5}, {5, 2}, {-5, 2},
            {4, 4}, {-4, 4}, {3, 5}, {-3, 5}, {5, 3}, {-5, 3}, {0, 6}, {6, 0},
            {1, 6}, {-1, 6}, {6, 1}, {-6, 1}, {2, 6}, {-2, 6}, {6, 2}, {-6, 2},
            {4, 5}, {-4, 5}, {5, 4}, {-5, 4}, {3, 6}, {-3, 6}, {6, 3}, {-6, 3},
            {0, 7}, {7, 0}, {1, 7}, {-1, 7}, {5, 5}, {-5, 5}, {7, 1}, {-7, 1},
            {4, 6}, {-4, 6}, {6, 4}, {-6, 4}, {2, 7}, {-2, 7}, {7, 2}, {-7, 2},
            {3, 7}, {-3, 7}, {7, 3}, {-7, 3}, {5, 6}, {-5, 6}, {6, 5}, {-6, 5},
            {8, 0}, {4, 7}, {-4, 7}, {7, 4}, {-7, 4}, {8, 1}, {8, 2}, {6, 6},
            {-6, 6}, {8, 3}, {5, 7}, {-5, 7}, {7, 5}, {-7, 5}, {8, 4}, {6, 7},
            {-6, 7}, {7, 6}, {-7, 6}, {8, 5}, {7, 7}, {-7, 7}, {8, 6}, {8, 7}
    };

    private LZ77() {
    }

    // DECODE PREFIX CODE TO VALUE (LENGTH OR DISTANCE PREFIX)
    public static int prefixToValue(final int prefixCode, final BitReader reader) throws DecoderException {
        if (prefixCode < 4) {
            return prefixCode + 1;
        }
        final int extraBits = (prefixCode - 2) >> 1;
        final int offset = (2 + (prefixCode & 1)) << extraBits;
        return offset + reader.read(extraBits) + 1;
    }

    // CONVERT DISTANCE CODE TO PIXEL OFFSET
    public static int distanceToOffset(final int distanceCode, final int width) {
        if (distanceCode > 120) {
            // LINEAR DISTANCE (OFFSET BY 120)
            return distanceCode - 120;
        }

        // SPECIAL NEIGHBORHOOD MAPPING
        final int[] offset = DISTANCE_MAP[distanceCode - 1];
        final int xi = offset[0];
        final int yi = offset[1];

        final int dist = xi + yi * width;
        return Math.max(1, dist);
    }

    // COPY PIXELS FROM BACKWARD REFERENCE
    public static void copyPixels(final int[] pixels, final int pos, final int dist, final int length) {
        final int src = pos - dist;
        for (int i = 0; i < length; i++) {
            pixels[pos + i] = pixels[src + i];
        }
    }
}