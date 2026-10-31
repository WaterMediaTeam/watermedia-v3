package org.watermedia.api.codecs.readers.webp.lossless;

// width IS THE IMAGE WIDTH WHEN THE TRANSFORM WAS READ (libwebp xsize_): COLOR INDEXING PACKS SEVERAL
// INDEXES PER PIXEL, SO EVERY TRANSFORM READ AFTER IT, AND ITS OWN INVERSE INPUT, USE THE PACKED WIDTH
record Transform(
        Type type,
        int width,         // WIDTH THE INVERSE OPERATES ON
        int bits,          // BLOCK SIZE BITS FOR PREDICTOR/COLOR, PIXEL PACKING BITS FOR COLOR_INDEXING
        int[] data         // PREDICTOR MODES, COLOR TRANSFORM ELEMENTS, OR COLOR TABLE
) {
    // MAPS A TWO-BIT WEBP TRANSFORM CODE TO ITS TYPE; AN OUT-OF-RANGE CODE THROWS
    public static Type typeof(final int i) {
        return Type.VALUES[i];
    }

    public enum Type {
        PREDICTOR,       // 0
        COLOR,           // 1
        SUBTRACT_GREEN,  // 2
        COLOR_INDEXING;  // 3

        private static final Type[] VALUES = Type.values();
    }
}
