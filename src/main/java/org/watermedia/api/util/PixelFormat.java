package org.watermedia.api.util;

/**
 * Pixel layouts, including block-compressed (BCn) texture formats.
 * <p>
 * Each constant carries its intrinsic layout data: {@link #planes()} for raw formats and
 * {@link #blockBytes()} for compressed ones, so producers and engines never hardcode either.
 */
public enum PixelFormat {
    // ===============
    // SINGLE PLANE
    // ==============

    /** Single-channel luminance (grayscale). 1 byte/pixel at 8-bit (Y only). No color, no alpha. */
    GRAY(1),
    /** Packed RGB. 3 bytes/pixel in [R, G, B] order. No alpha channel. */
    RGB(1),
    /** Packed RGBA. 4 bytes/pixel in [R, G, B, A] order. OpenGL's native upload layout. */
    RGBA(1),
    /** Packed BGRA. 4 bytes/pixel in [B, G, R, A] order. Same as {@link #RGBA} with R and B swapped — common on Windows/DirectX and Java's BufferedImage. Used as the fallback CPU format. */
    BGRA(1),
    /** Packed GBRA. 4 bytes/pixel in [G, B, R, A] order. Variant of {@link #RGBA}/{@link #BGRA} with channels reshuffled to put green first. */
    GBRA(1),
    /** Packed YUV 4:2:2 in [Y0, U, Y1, V] order — 4 bytes per 2 pixels (avg 2 bytes/pixel). Chroma horizontally subsampled, shared between pixel pairs. */
    YUYV(1),
    /** Packed YUV 4:2:2 in [U, Y0, V, Y1] order (UYVY). Same layout as {@link #YUYV} with luma and chroma bytes swapped. */
    YUYV2(1),

    // ===============
    // TWO PLANES (Y + interleaved chroma)
    // ==============

    /** Biplanar YUV 4:2:0: full-resolution Y plane + half-width/half-height interleaved [U, V] plane. 1.5 bytes/pixel at 8-bit. */
    NV12(2),
    /** Biplanar YUV 4:2:0: full-resolution Y plane + half-width/half-height interleaved [V, U] plane. Same as {@link #NV12} with U and V swapped (Android camera default). */
    NV21(2),

    // ==============
    // THREE PLANES (Y + U + V)
    // ==============

    /** Planar YUV 4:2:0: separate Y, U, V planes; U and V are half-width and half-height. 1.5 bytes/pixel at 8-bit. The most common video format. */
    YUV420P(3),
    /** Planar YUV 4:2:2: separate Y, U, V planes; U and V are half-width but full height. 2 bytes/pixel at 8-bit. Same plane layout as {@link #YUV420P} with vertical chroma resolution preserved. */
    YUV422P(3),
    /** Planar YUV 4:4:4: separate Y, U, V planes all at full resolution — no chroma subsampling. 3 bytes/pixel at 8-bit. Same plane layout as {@link #YUV420P}/{@link #YUV422P} with full chroma. */
    YUV444P(3),

    // ==============
    // FOUR PLANES (Y + U + V + A)
    // ==============

    /** Planar YUV 4:2:0 with alpha: {@link #YUV420P} plus a full-resolution A plane. 2.5 bytes/pixel at 8-bit. */
    YUVA420P(4),
    /** Planar YUV 4:2:2 with alpha: {@link #YUV422P} plus a full-resolution A plane. 3 bytes/pixel at 8-bit. */
    YUVA422P(4),
    /** Planar YUV 4:4:4 with alpha: {@link #YUV444P} plus a full-resolution A plane. 4 bytes/pixel at 8-bit. */
    YUVA444P(4),

    // ==============
    // BLOCK-COMPRESSED (BCn) — GPU SAMPLES THE BLOCKS DIRECTLY, NO SOFTWARE DECODE
    // ==============

    /** BC1 (DXT1): opaque RGB / 1-bit alpha, 8 bytes per 4x4 block — 1/8 the VRAM of RGBA8. */
    BC1(1, 8),
    /** BC2 (DXT3): RGB + explicit 4-bit alpha, 16 bytes per 4x4 block. */
    BC2(1, 16),
    /** BC3 (DXT5): RGB + interpolated alpha, 16 bytes per 4x4 block — 1/4 the VRAM of RGBA8. */
    BC3(1, 16),
    /** BC5: two-channel (RG) interpolated data, 16 bytes per 4x4 block — normal maps and chroma pairs. */
    BC5(1, 16),
    /** BC7: high-quality RGBA, 16 bytes per 4x4 block — the best-looking BCn format. */
    BC7(1, 16);

    private final int planes;
    private final int blockBytes;

    PixelFormat(final int planes) {
        this(planes, 0);
    }

    PixelFormat(final int planes, final int blockBytes) {
        this.planes = planes;
        this.blockBytes = blockBytes;
    }

    /** Number of separate data planes one frame carries (compressed formats are a single block plane). */
    public int planes() { return this.planes; }

    /** Bytes per 4x4 block for {@link #compressed()} formats, or 0 for raw layouts. */
    public int blockBytes() { return this.blockBytes; }

    /** Whether this is a block-compressed (BCn) format the GPU samples without decoding. */
    public boolean compressed() { return this.blockBytes > 0; }
}
