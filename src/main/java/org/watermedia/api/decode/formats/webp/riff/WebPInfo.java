package org.watermedia.api.decode.formats.webp.riff;

public record WebPInfo(
        Type type,
        int width,
        int height,
        boolean hasAlpha,
        boolean hasAnimation,
        boolean hasICC,
        boolean hasExif,
        boolean hasXMP,
        RiffChunk bitstreamChunk,  // VP8 or VP8L
        RiffChunk alphaChunk,      // ALPH (may be null)
        RiffChunk animChunk,       // ANIM (may be null)
        int fileSize
) {
    public enum Type {
        LOSSY,      // VP8 simple
        LOSSLESS,   // VP8L simple
        EXTENDED    // VP8X with features
    }

    // CONVENIENCE CONSTRUCTORS
    public static WebPInfo lossy(final int width, final int height, final RiffChunk vp8Chunk, final int fileSize) {
        return new WebPInfo(Type.LOSSY, width, height, false, false, false, false, false, vp8Chunk, null, null, fileSize);
    }

    public static WebPInfo lossless(final int width, final int height, final boolean hasAlpha, final RiffChunk vp8lChunk, final int fileSize) {
        return new WebPInfo(Type.LOSSLESS, width, height, hasAlpha, false, false, false, false, vp8lChunk, null, null, fileSize);
    }
}
