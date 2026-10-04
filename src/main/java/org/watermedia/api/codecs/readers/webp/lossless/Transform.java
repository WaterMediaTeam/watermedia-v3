package org.watermedia.api.codecs.readers.webp.lossless;

public record Transform(
        Type type,
        int bits,          // BLOCK SIZE BITS FOR PREDICTOR/COLOR
        int[] data         // PREDICTOR MODES, COLOR TRANSFORM ELEMENTS, OR COLOR TABLE
) {
    /** Stores a transform type, its block-size bits and its decoded transform data. */
    public static Transform block(final Type type, final int bits, final int[] data) {
        return new Transform(type, bits, data);
    }

    /** Creates the subtract-green transform, which carries no block data. */
    public static Transform subtractGreen() {
        return new Transform(Type.SUBTRACT_GREEN, 0, null);
    }

    /** Creates a color-indexing transform backed by the supplied ARGB table. */
    public static Transform colorTable(final int[] table) {
        return new Transform(Type.COLOR_INDEXING, 0, table);
    }

    /** Maps a two-bit WebP transform code to its type; an out-of-range code throws. */
    public static Type typeof(int i) {
        return Type.VALUES[i];
    }

    public enum Type {
        PREDICTOR,       // 0
        COLOR,           // 1
        SUBTRACT_GREEN,  // 2
        COLOR_INDEXING;   // 3

        private static final Type[] VALUES = Type.values();
    }
}
