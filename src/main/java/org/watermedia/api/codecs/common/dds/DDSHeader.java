package org.watermedia.api.codecs.common.dds;

import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.XCodecException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * DirectDraw Surface (DDS) container with the modern {@code DX10} extended header — the storage
 * format for BC-compressed frames, the same way
 * RIFF is the container for WebP.
 * This class knows the container only; the block codec is independent.
 *
 * <p>An animation is stored as a 2D texture array (one array slice per frame). DDS itself has no
 * field for per-frame delays, so a small WaterMedia footer is appended after the texture data:
 * <pre>
 *   "DDS " (4) + DDS_HEADER (124) + DDS_HEADER_DXT10 (20)     // {@value #BYTES}-byte prefix
 *   frame[0..N) block data, each {@code frameBytes(w,h,codec)} bytes
 *   footer: int magic({@value #FOOTER_MAGIC}) + int version + int frameCount + long[frameCount] delaysMs
 * </pre>
 * The DDS prefix is a valid stand-alone texture; readers that ignore trailing bytes still load it.
 */
public final class DDSHeader {

    /** Byte length of the {@code "DDS "} + {@code DDS_HEADER} + {@code DDS_HEADER_DXT10} prefix. */
    public static final int BYTES = 148;
    // LITTLE-ENDIAN ARRAY SIZE OFFSET IN THE DXT10 HEADER.
    private static final int ARRAYSIZE_OFFSET = 140;
    // WATERMEDIA ANIMATION FOOTER IDENTIFIER.
    private static final int FOOTER_MAGIC = 0x574D5443;
    private static final int FOOTER_VERSION = 1;
    // MAGIC, VERSION AND FRAME COUNT PRECEDE THE DELAY LONGS.
    private static final int FOOTER_HEAD_BYTES = 12;

    // ATTACKER-CONTROLLED DIMENSIONS CAN OVERFLOW BLOCK SIZE MATH BEFORE ALLOCATION CHECKS.
    // THESE CAPS ALSO MATCH THE SIBLING IMAGE READERS.
    private static final int MAX_DIM = 16384;
    private static final int MAX_PIXELS = 1 << 26;
    // ONE SLICE PER ANIMATION FRAME; A DECLARED COUNT COSTS TWO BUFFER OBJECTS AND EIGHT FOOTER BYTES
    private static final int MAX_ARRAY_SIZE = 4096;

    // 'D','D','S',' ' AS A LITTLE-ENDIAN INT
    private static final int MAGIC = 0x20534444;
    // 'D','X','1','0' AS A LITTLE-ENDIAN INT
    private static final int FOURCC_DX10 = 0x30315844;

    private static final int DDPF_FOURCC = 0x4;
    private static final int DDSCAPS_TEXTURE = 0x1000;
    private static final int DX10_RESOURCE_DIMENSION_TEXTURE2D = 3;

    // DXGI_FORMAT VALUES FOR THE BLOCK-COMPRESSED, UNORM VARIANTS
    private static final int DXGI_BC1_UNORM = 71;
    private static final int DXGI_BC3_UNORM = 77;
    private static final int DXGI_BC7_UNORM = 98;

    private DDSHeader() {}

    /**
     * Parses and validates the prefix of a BC-in-DDS file from {@code src.position()}, leaving the
     * position unchanged.
     *
     * @throws XCodecException when the magic, the {@code DX10} header or the DXGI format is not a
     *                         supported BC layout, or the declared dimensions or array size are
     *                         outside the bounds the block arithmetic can represent
     */
    public static Info read(final ByteBuffer src) throws XCodecException {
        final int base = src.position();
        if (src.remaining() < BYTES) throw new XCodecException("Truncated DDS: " + src.remaining() + " bytes");
        final ByteBuffer b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(base) != MAGIC) throw new XCodecException("Not a DDS container");
        if (b.getInt(base + 84) != FOURCC_DX10) throw new XCodecException("DDS is not DX10-extended");
        // THE BLOCK SLICES BELOW REPRESENT ONLY TOP-LEVEL 2D TEXTURES, NEVER MIPS, VOLUMES OR CUBES.
        if (b.getInt(base + 4) != 124 || b.getInt(base + 76) != 32
                || (b.getInt(base + 80) & DDPF_FOURCC) == 0
                || (b.getInt(base + 108) & DDSCAPS_TEXTURE) == 0
                || b.getInt(base + 24) > 1 || b.getInt(base + 24) < 0
                || b.getInt(base + 28) > 1 || b.getInt(base + 28) < 0
                || b.getInt(base + 132) != DX10_RESOURCE_DIMENSION_TEXTURE2D
                || b.getInt(base + 136) != 0) {
            throw new XCodecException("Unsupported DDS texture structure");
        }
        final int height = b.getInt(base + 12);
        final int width = b.getInt(base + 16);
        final int dxgi = b.getInt(base + 128);
        final int arraySize = b.getInt(base + ARRAYSIZE_OFFSET);
        final String codec = codecOf(dxgi);
        if (codec == null) throw new XCodecException("DDS DXGI format is not a supported BC layout: " + dxgi);
        if (width <= 0 || height <= 0 || width > MAX_DIM || height > MAX_DIM) {
            throw new XCodecException("Invalid DDS dimensions: " + width + "x" + height + " (max " + MAX_DIM + ")");
        }
        // A PER-AXIS CAP IS NOT A BUDGET: THE BLOCK COUNT, AND EVERY ALLOCATION DERIVED FROM IT, IS THE PRODUCT
        if ((long) width * height > MAX_PIXELS) {
            throw new XCodecException("DDS texture too big: " + width + "x" + height + " (max " + MAX_PIXELS + " pixels)");
        }
        if (arraySize <= 0 || arraySize > MAX_ARRAY_SIZE) {
            throw new XCodecException("Invalid DDS arraySize: " + arraySize + " (max " + MAX_ARRAY_SIZE + ")");
        }
        return new Info(width, height, codec, blockBytesOf(codec), arraySize);
    }

    /**
     * Reads the per-frame delays from the footer that starts at {@code src.position()}.
     *
     * @throws XCodecException when the footer is truncated or its magic/version/count is wrong
     */
    public static long[] readFooter(final ByteBuffer src, final int expectedCount) throws XCodecException {
        final ByteBuffer b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        // LONG MATH: expectedCount*8 OVERFLOWS int FOR expectedCount > ~268M, BYPASSING THE TRUNCATION
        // CHECK INTO A MULTI-GB new long[count]; ALSO REJECTS A FORGED COUNT THAT CANNOT FIT IN THE BUFFER
        if (expectedCount < 0 || (long) FOOTER_HEAD_BYTES + (long) expectedCount * Long.BYTES > b.remaining()) {
            throw new XCodecException("Truncated DDS footer");
        }
        if (b.getInt() != FOOTER_MAGIC) throw new XCodecException("Bad DDS footer magic");
        if (b.getInt() != FOOTER_VERSION) throw new XCodecException("Unsupported DDS footer version");
        final int count = b.getInt();
        if (count != expectedCount) throw new XCodecException("DDS footer frame count mismatch: " + count + " != " + expectedCount);
        final long[] delays = new long[count];
        for (int i = 0; i < count; i++) delays[i] = b.getLong();
        return delays;
    }

    // ==========================================================================
    // CONTAINER ⇄ CODEC MAPPINGS
    // ==========================================================================
    /** Number of 4x4 blocks in a frame of the given dimensions (edges padded up to the grid). */
    public static long blocksPerFrame(final int width, final int height) {
        // LONG MATH THROUGHOUT: THE HEADER FIELDS ARE UNTRUSTED AND THE int PRODUCT WRAPS LONG BEFORE
        // THEY BECOME IMPLAUSIBLE, HANDING THE CALLER A NEGATIVE — OR EXACTLY ZERO — FRAME SIZE
        return (((long) width + 3) >> 2) * (((long) height + 3) >> 2);
    }

    /** Compressed byte size of one frame for the given codec. */
    public static long frameBytes(final int width, final int height, final String codec) {
        return blocksPerFrame(width, height) * blockBytesOf(codec);
    }

    /**
     * Bytes per 4x4 block for a codec id: {@code 8} for BC1, {@code 16} for BC3/BC7.
     *
     * @throws IllegalArgumentException when the codec is not a known BC version — silently assuming
     *                                  16 turned a typo into a plausible but wrong header
     */
    public static int blockBytesOf(final String codec) {
        return switch (codec == null ? "" : codec.toUpperCase(Locale.ROOT)) {
            case CodecsAPI.CODEC_BC1 -> 8;
            case CodecsAPI.CODEC_BC3, CodecsAPI.CODEC_BC7 -> 16;
            default -> throw new IllegalArgumentException("Unsupported block codec: " + codec);
        };
    }

    private static String codecOf(final int dxgiFormat) {
        return switch (dxgiFormat) {
            case DXGI_BC1_UNORM -> CodecsAPI.CODEC_BC1;
            case DXGI_BC3_UNORM -> CodecsAPI.CODEC_BC3;
            case DXGI_BC7_UNORM -> CodecsAPI.CODEC_BC7;
            default -> null;
        };
    }

    /** Parsed DDS prefix: dimensions, the file's BC codec, its block size, and the frame count. */
    public record Info(int width, int height, String codec, int blockBytes, int arraySize) {}
}
