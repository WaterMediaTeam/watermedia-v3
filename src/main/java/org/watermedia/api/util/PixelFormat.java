package org.watermedia.api.util;

/**
 * Pixel layouts.
 */
public enum PixelFormat {
    // ===============
    // SINGLE PLANE
    // ==============

    /** Single-channel luminance (grayscale). 1 byte/pixel at 8-bit (Y only). No color, no alpha. */
    GRAY,
    /** Packed RGB. 3 bytes/pixel in [R, G, B] order. No alpha channel. */
    RGB,
    /** Packed RGBA. 4 bytes/pixel in [R, G, B, A] order. OpenGL's native upload layout. */
    RGBA,
    /** Packed BGRA. 4 bytes/pixel in [B, G, R, A] order. Same as {@link #RGBA} with R and B swapped — common on Windows/DirectX and Java's BufferedImage. Used as the fallback CPU format. */
    BGRA,
    /** Packed GBRA. 4 bytes/pixel in [G, B, R, A] order. Variant of {@link #RGBA}/{@link #BGRA} with channels reshuffled to put green first. */
    GBRA,
    /** Packed YUV 4:2:2 in [Y0, U, Y1, V] order — 4 bytes per 2 pixels (avg 2 bytes/pixel). Chroma horizontally subsampled, shared between pixel pairs. */
    YUYV,
    /** Packed YUV 4:2:2 in [U, Y0, V, Y1] order (UYVY). Same layout as {@link #YUYV} with luma and chroma bytes swapped. */
    YUYV2,

    // ===============
    // TWO PLANES (Y + interleaved chroma)
    // ==============

    /** Biplanar YUV 4:2:0: full-resolution Y plane + half-width/half-height interleaved [U, V] plane. 1.5 bytes/pixel at 8-bit. */
    NV12,
    /** Biplanar YUV 4:2:0: full-resolution Y plane + half-width/half-height interleaved [V, U] plane. Same as {@link #NV12} with U and V swapped (Android camera default). */
    NV21,

    // ==============
    // THREE PLANES (Y + U + V)
    // ==============

    /** Planar YUV 4:2:0: separate Y, U, V planes; U and V are half-width and half-height. 1.5 bytes/pixel at 8-bit. The most common video format. */
    YUV420P,
    /** Planar YUV 4:2:2: separate Y, U, V planes; U and V are half-width but full height. 2 bytes/pixel at 8-bit. Same plane layout as {@link #YUV420P} with vertical chroma resolution preserved. */
    YUV422P,
    /** Planar YUV 4:4:4: separate Y, U, V planes all at full resolution — no chroma subsampling. 3 bytes/pixel at 8-bit. Same plane layout as {@link #YUV420P}/{@link #YUV422P} with full chroma. */
    YUV444P,

    // ==============
    // FOUR PLANES (Y + U + V + A)
    // ==============

    /** Planar YUV 4:2:0 with alpha: {@link #YUV420P} plus a full-resolution A plane. 2.5 bytes/pixel at 8-bit. */
    YUVA420P,
    /** Planar YUV 4:2:2 with alpha: {@link #YUV422P} plus a full-resolution A plane. 3 bytes/pixel at 8-bit. */
    YUVA422P,
    /** Planar YUV 4:4:4 with alpha: {@link #YUV444P} plus a full-resolution A plane. 4 bytes/pixel at 8-bit. */
    YUVA444P,
}
