package org.watermedia.api.codecs.common.dds;

import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.XCodecException;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

/**
 * DirectDraw Surface (DDS) container with the modern {@code DX10} extended header — the storage
 * format for {@link org.watermedia.api.codecs.common.bc.BCCodec BC}-compressed frames, the same way
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
    /** Little-endian offset of the {@code arraySize} field inside the DXT10 header. */
    public static final int ARRAYSIZE_OFFSET = 140;
    /** Footer magic: {@code 'W','M','T','C'} (WaterMedia Texture Cache). */
    public static final int FOOTER_MAGIC = 0x574D5443;
    public static final int FOOTER_VERSION = 1;
    /** Byte length of the footer header (magic + version + frameCount), before the delay longs. */
    public static final int FOOTER_HEAD_BYTES = 12;

    // DIMENSION CAPS, MATCHING EVERY SIBLING READER. THE HEADER FIELDS ARE ATTACKER-CONTROLLED AND
    // SIZE THE BLOCK ARITHMETIC: 65536x65536 IS 2^31 BC1 BYTES (WRAPS NEGATIVE) AND 65535x65535 IS
    // 2^32 BC7 BYTES (COLLAPSES TO ZERO), BOTH OF WHICH SLIP PAST EVERY DOWNSTREAM SIZE GUARD
    private static final int MAX_DIM = 16384;
    private static final int MAX_PIXELS = 1 << 26;
    // ONE SLICE PER ANIMATION FRAME; A DECLARED COUNT COSTS TWO BUFFER OBJECTS AND EIGHT FOOTER BYTES
    private static final int MAX_ARRAY_SIZE = 4096;

    // 'D','D','S',' ' AS A LITTLE-ENDIAN INT
    private static final int MAGIC = 0x20534444;
    // 'D','X','1','0' AS A LITTLE-ENDIAN INT
    private static final int FOURCC_DX10 = 0x30315844;

    // DDS_HEADER.dwFlags: CAPS | HEIGHT | WIDTH | PIXELFORMAT | LINEARSIZE
    private static final int DDSD_FLAGS = 0x1 | 0x2 | 0x4 | 0x1000 | 0x80000;
    private static final int DDPF_FOURCC = 0x4;
    private static final int DDSCAPS_TEXTURE = 0x1000;
    private static final int DX10_RESOURCE_DIMENSION_TEXTURE2D = 3;

    // DXGI_FORMAT VALUES FOR THE BLOCK-COMPRESSED, UNORM VARIANTS
    private static final int DXGI_BC1_UNORM = 71;
    private static final int DXGI_BC3_UNORM = 77;
    private static final int DXGI_BC7_UNORM = 98;

    private DDSHeader() {}

    /**
     * Builds the {@value #BYTES}-byte prefix for a BC texture array. {@code arraySize} may be left
     * at {@code 0} and patched later with {@link #patchArraySize(Path, int)} when the frame count
     * is only known after streaming.
     *
     * @throws IllegalArgumentException when the codec is unknown or the frame does not fit the
     *                                  container's 32-bit size field
     */
    public static byte[] write(final int width, final int height, final String codec, final int arraySize) {
        // dwPitchOrLinearSize IS A DWORD: A FRAME BEYOND int WOULD BE SILENTLY TRUNCATED INTO A
        // HEADER THAT NO READER — INCLUDING read() BELOW — COULD EVER TRUST
        final long frameBytes = frameBytes(width, height, codec);
        if (frameBytes <= 0 || frameBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("BC frame does not fit the DDS size field: "
                    + width + "x" + height + " is " + frameBytes + " bytes");
        }
        final ByteBuffer b = ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(MAGIC);
        b.putInt(124);                  // DDS_HEADER.dwSize
        b.putInt(DDSD_FLAGS);           // dwFlags
        b.putInt(height);               // dwHeight
        b.putInt(width);                // dwWidth
        b.putInt((int) frameBytes);     // dwPitchOrLinearSize (top-level frame size)
        b.putInt(0);                    // dwDepth
        b.putInt(1);                    // dwMipMapCount
        b.position(b.position() + 44);  // dwReserved1[11]
        b.putInt(32);                   // ddspf.dwSize
        b.putInt(DDPF_FOURCC);          // ddspf.dwFlags
        b.putInt(FOURCC_DX10);          // ddspf.dwFourCC
        b.position(b.position() + 20);  // dwRGBBitCount + RGBA bit masks (5 dwords, all zero)
        b.putInt(DDSCAPS_TEXTURE);      // dwCaps
        b.position(b.position() + 16);  // dwCaps2/3/4 + dwReserved2
        b.putInt(dxgiOf(codec));        // DXT10.dxgiFormat
        b.putInt(DX10_RESOURCE_DIMENSION_TEXTURE2D); // resourceDimension
        b.putInt(0);                    // miscFlag
        b.putInt(arraySize);            // arraySize
        b.putInt(0);                    // miscFlags2 (alpha mode unknown)
        return b.array();
    }

    /** Serializes the trailing footer carrying the per-frame delays DDS itself cannot hold. */
    public static byte[] writeFooter(final long[] delays, final int count) {
        final ByteBuffer b = ByteBuffer.allocate(FOOTER_HEAD_BYTES + count * Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(FOOTER_MAGIC);
        b.putInt(FOOTER_VERSION);
        b.putInt(count);
        for (int i = 0; i < count; i++) b.putLong(delays[i]);
        return b.array();
    }

    /** Overwrites the {@code arraySize} field of an already-written DDS file. */
    public static void patchArraySize(final Path file, final int arraySize) throws IOException {
        try (final RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.seek(ARRAYSIZE_OFFSET);
            raf.write(arraySize & 0xFF);
            raf.write((arraySize >>> 8) & 0xFF);
            raf.write((arraySize >>> 16) & 0xFF);
            raf.write((arraySize >>> 24) & 0xFF);
        }
    }

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
        return switch (codec == null ? "" : codec.toUpperCase(java.util.Locale.ROOT)) {
            case CodecsAPI.CODEC_BC1 -> 8;
            case CodecsAPI.CODEC_BC3, CodecsAPI.CODEC_BC7 -> 16;
            default -> throw new IllegalArgumentException("Unsupported block codec: " + codec);
        };
    }

    private static int dxgiOf(final String codec) {
        return switch (codec.toUpperCase(java.util.Locale.ROOT)) {
            case CodecsAPI.CODEC_BC1 -> DXGI_BC1_UNORM;
            case CodecsAPI.CODEC_BC3 -> DXGI_BC3_UNORM;
            case CodecsAPI.CODEC_BC7 -> DXGI_BC7_UNORM;
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
