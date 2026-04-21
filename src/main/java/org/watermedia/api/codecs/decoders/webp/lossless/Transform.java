package org.watermedia.api.codecs.decoders.webp.lossless;

public record Transform(
        Type type,
        int bits,          // BLOCK SIZE BITS FOR PREDICTOR/COLOR
        int[] data         // PREDICTOR MODES, COLOR TRANSFORM ELEMENTS, OR COLOR TABLE
) {
    // FOR PREDICTOR AND COLOR TRANSFORMS
    public static Transform block(final Type type, final int bits, final int[] data) {
        return new Transform(type, bits, data);
    }

    // FOR SUBTRACT_GREEN (NO DATA)
    public static Transform subtractGreen() {
        return new Transform(Type.SUBTRACT_GREEN, 0, null);
    }

    // FOR COLOR_INDEXING
    public static Transform colorTable(final int[] table) {
        return new Transform(Type.COLOR_INDEXING, 0, table);
    }

    public int blockSize() {
        return 1 << this.bits;
    }

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
