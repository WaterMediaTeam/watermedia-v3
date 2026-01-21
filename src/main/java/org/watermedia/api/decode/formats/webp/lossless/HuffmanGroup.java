package org.watermedia.api.decode.formats.webp.lossless;

public record HuffmanGroup(
        HuffmanTable green,    // GREEN + LENGTH + COLOR CACHE
        HuffmanTable red,      // RED
        HuffmanTable blue,     // BLUE
        HuffmanTable alpha,    // ALPHA
        HuffmanTable dist      // DISTANCE
) {
    public static final int GREEN = 0;
    public static final int RED = 1;
    public static final int BLUE = 2;
    public static final int ALPHA = 3;
    public static final int DIST = 4;

    public HuffmanTable get(final int index) {
        return switch (index) {
            case GREEN -> this.green;
            case RED -> this.red;
            case BLUE -> this.blue;
            case ALPHA -> this.alpha;
            case DIST -> this.dist;
            default -> throw new IllegalArgumentException("Invalid huffman index: " + index);
        };
    }
}
