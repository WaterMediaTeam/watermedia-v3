package org.watermedia.api.codecs;

import org.watermedia.api.codecs.readers.GifReader;
import org.watermedia.api.codecs.readers.JpegReader;
import org.watermedia.api.codecs.readers.NetpbmReader;
import org.watermedia.api.codecs.readers.PngReader;
import org.watermedia.api.codecs.readers.WebPReader;
import org.watermedia.api.codecs.readers.svg.SvgReader;
import org.watermedia.api.codecs.writers.WebMWriter;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.IOTool;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Image codec entry point.
 *
 * <p>{@link #reader(ByteBuffer)} scans the buffer's leading bytes against the supported
 * format headers, identifies the format, advances the buffer position past the matched header,
 * and returns the matching {@link ImageReader}. Readers parse only the format body.
 */
public final class CodecsAPI {
    private CodecsAPI() {}

    // ==========================================================================
    // CODEC IDENTIFIERS
    // ==========================================================================
    // PURE-JAVA IMAGE CODECS — ALWAYS AVAILABLE (SEE THE readers PACKAGE).
    public static final String CODEC_PNG = "PNG";
    public static final String CODEC_JPEG = "JPEG";
    public static final String CODEC_GIF = "GIF";
    public static final String CODEC_WEBP = "WEBP";
    public static final String CODEC_NETPBM = "NETPBM";
    public static final String CODEC_SVG = "SVG";

    // ==========================================================================
    // METADATA KEYS — VALUES ARE JDK TYPES ONLY, SO EVERY HOST CAN READ THEM
    // ==========================================================================
    /** ICC colour profile, decompressed, as {@code byte[]}; published by PNG, JPEG and WebP. */
    public static final String METAKEY_ICC_PROFILE = "icc_profile";
    /** EXIF payload as {@code byte[]} starting at its TIFF header; published by PNG, JPEG and WebP. */
    public static final String METAKEY_EXIF = "exif";
    /** XMP packet as {@code byte[]}; published by PNG, JPEG and WebP. */
    public static final String METAKEY_XMP = "xmp";
    /** EXIF orientation already applied to the delivered pixels, as {@code Integer}; published by JPEG and WebP. */
    public static final String METAKEY_ORIENTATION = "orientation";
    /** Loop count exactly as the container declares it, as {@code Integer}; {@link ImageData#repeat()} carries the normalized plays. */
    public static final String METAKEY_LOOP_COUNT = "loop_count";

    /** PNG tEXt entries as {@code Map<String, List<String>>}, keyword to values. */
    public static final String PNG_METAKEY_TEXT = "png.text";
    /** PNG zTXt entries as {@code Map<String, List<String>>}, keyword to values. */
    public static final String PNG_METAKEY_COMPRESSED_TEXT = "png.compressed_text";
    /** PNG iTXt entries as {@code Map<String, List<String>>}, keyword to values. */
    public static final String PNG_METAKEY_INTERNATIONAL_TEXT = "png.international_text";
    /** PNG gAMA as {@code Double}, the encoding gamma. */
    public static final String PNG_METAKEY_GAMMA = "png.gamma";
    /** PNG cHRM as {@code double[8]}: white point, red, green and blue x/y pairs. */
    public static final String PNG_METAKEY_CHROMATICITIES = "png.chromaticities";
    /** PNG sRGB rendering intent as {@code Integer}. */
    public static final String PNG_METAKEY_SRGB = "png.srgb";
    /** PNG sBIT as {@code int[]}, one entry per channel. */
    public static final String PNG_METAKEY_SIGNIFICANT_BITS = "png.significant_bits";
    /** PNG cICP as {@code int[4]}: colour primaries, transfer function, matrix coefficients, full-range flag. */
    public static final String PNG_METAKEY_CICP = "png.cicp";
    /** PNG mDCV as {@code double[10]}: red, green, blue and white x/y pairs, then maximum and minimum luminance. */
    public static final String PNG_METAKEY_MASTERING_DISPLAY_COLOR_VOLUME = "png.mastering_display_color_volume";
    /** PNG cLLI as {@code double[2]} in cd/m²: maximum content light level and maximum frame-average light level. */
    public static final String PNG_METAKEY_CONTENT_LIGHT_LEVEL = "png.content_light_level";
    /** PNG pHYs as {@code long[3]}: pixels per unit on x and y, then the unit specifier. */
    public static final String PNG_METAKEY_PHYSICAL_PIXEL_DIMENSIONS = "png.physical_pixel_dimensions";
    /** PNG hIST as {@code int[]}, one frequency per palette entry. */
    public static final String PNG_METAKEY_HISTOGRAM = "png.histogram";
    /** PNG sPLT entries as {@code Map<String, int[]>}, palette name to its entries flattened as R, G, B, A, frequency. */
    public static final String PNG_METAKEY_SUGGESTED_PALETTES = "png.suggested_palettes";
    /** PNG tIME as {@code LocalDateTime}. */
    public static final String PNG_METAKEY_LAST_MODIFIED_TIME = "png.last_modified_time";
    /** Unknown PNG ancillary chunks as {@code Map<String, List<byte[]>>}, chunk type to raw payloads. */
    public static final String PNG_METAKEY_ANCILLARY_CHUNK = "png.ancillary_chunk";

    /** GIF application extensions as {@code Map<String, List<byte[]>>}, identifier plus authentication code to payloads. */
    public static final String GIF_METAKEY_APPLICATION_EXTENSION = "gif.application_extension";

    private static final byte[] PNG_HEADER = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
    private static final byte[] JPEG_HEADER = { (byte) 0xFF, (byte) 0xD8 };
    private static final byte[] GIF87_HEADER = { 'G', 'I', 'F', '8', '7', 'a' };
    private static final byte[] GIF89_HEADER = { 'G', 'I', 'F', '8', '9', 'a' };
    private static final byte[] RIFF_HEADER = { 'R', 'I', 'F', 'F' };
    private static final byte[] WEBP_HEADER = { 'W', 'E', 'B', 'P' };

    // ADDITIONAL IMAGE SIGNATURES (USED ONLY BY sniff)
    private static final byte[] BMP_HEADER = { 'B', 'M' };
    private static final byte[] TIFF_LE_HEADER = { 0x49, 0x49, 0x2A, 0x00 };
    private static final byte[] TIFF_BE_HEADER = { 0x4D, 0x4D, 0x00, 0x2A };
    private static final byte[] PSD_HEADER = { '8', 'B', 'P', 'S' };
    private static final byte[] ICO_HEADER = { 0x00, 0x00, 0x01, 0x00 };
    private static final byte[] CUR_HEADER = { 0x00, 0x00, 0x02, 0x00 };
    private static final byte[] JP2_HEADER = { 0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, (byte) 0x87, 0x0A };
    private static final byte[] J2K_HEADER = { (byte) 0xFF, 0x4F, (byte) 0xFF, 0x51 };
    private static final byte[] JXL_CODESTREAM_HEADER = { (byte) 0xFF, 0x0A };
    private static final byte[] JXL_CONTAINER_HEADER = { 0x00, 0x00, 0x00, 0x0C, 'J', 'X', 'L', ' ', 0x0D, 0x0A, (byte) 0x87, 0x0A };
    private static final byte[] DDS_HEADER = { 'D', 'D', 'S', ' ' };
    private static final byte[] ICNS_HEADER = { 'i', 'c', 'n', 's' };
    private static final byte[] QOI_HEADER = { 'q', 'o', 'i', 'f' };
    private static final byte[] FLIF_HEADER = { 'F', 'L', 'I', 'F' };
    private static final byte[] EXR_HEADER = { 0x76, 0x2F, 0x31, 0x01 };
    private static final byte[] XCF_HEADER = { 'g', 'i', 'm', 'p', ' ', 'x', 'c', 'f', ' ' };
    private static final byte[] KTX_HEADER = { (byte) 0xAB, 'K', 'T', 'X', ' ' };

    // VIDEO CONTAINER / RAW-STREAM SIGNATURES
    private static final byte[] FTYP_HEADER = { 'f', 't', 'y', 'p' }; // ISO BMFF, AT OFFSET 4
    private static final byte[] EBML_HEADER = { 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3 }; // MATROSKA / WEBM
    private static final byte[] FLV_HEADER = { 'F', 'L', 'V' };
    private static final byte[] ASF_HEADER = { 0x30, 0x26, (byte) 0xB2, 0x75, (byte) 0x8E, 0x66, (byte) 0xCF, 0x11 }; // WMV / WMA
    private static final byte[] MPEG_PS_HEADER = { 0x00, 0x00, 0x01, (byte) 0xBA }; // MPEG PROGRAM STREAM
    private static final byte[] MPEG_SEQ_HEADER = { 0x00, 0x00, 0x01, (byte) 0xB3 }; // MPEG VIDEO SEQUENCE
    private static final byte[] H264_NAL_HEADER = { 0x00, 0x00, 0x00, 0x01 }; // RAW H.264/H.265 ANNEX B
    private static final byte[] REALMEDIA_HEADER = { 0x2E, 'R', 'M', 'F' };
    private static final byte[] MXF_HEADER = { 0x06, 0x0E, 0x2B, 0x34 };

    // AUDIO SIGNATURES
    private static final byte[] ID3_HEADER = { 'I', 'D', '3' }; // MP3 WITH ID3v2 TAG
    private static final byte[] FLAC_HEADER = { 'f', 'L', 'a', 'C' };
    private static final byte[] MIDI_HEADER = { 'M', 'T', 'h', 'd' };
    private static final byte[] AU_HEADER = { 0x2E, 's', 'n', 'd' };
    private static final byte[] AMR_HEADER = { '#', '!', 'A', 'M', 'R' };
    private static final byte[] CAF_HEADER = { 'c', 'a', 'f', 'f' };
    private static final byte[] DSF_HEADER = { 'D', 'S', 'D', ' ' };
    private static final byte[] MPC_SV8_HEADER = { 'M', 'P', 'C', 'K' };
    private static final byte[] MPC_SV7_HEADER = { 'M', 'P', '+' };
    private static final byte[] WAVPACK_HEADER = { 'w', 'v', 'p', 'k' };
    private static final byte[] APE_HEADER = { 'M', 'A', 'C', ' ' };
    private static final byte[] TTA_HEADER = { 'T', 'T', 'A', '1' };
    private static final byte[] REALAUDIO_HEADER = { 0x2E, 'r', 'a', (byte) 0xFD };
    private static final byte[] AC3_HEADER = { 0x0B, 0x77 };
    private static final byte[] DTS_HEADER = { 0x7F, (byte) 0xFE, (byte) 0x80, 0x01 };

    // RIFF / FORM SUBTYPES (AT OFFSET 8)
    private static final byte[] RIFF_WAVE = { 'W', 'A', 'V', 'E' };
    private static final byte[] RIFF_AVI = { 'A', 'V', 'I', ' ' };
    private static final byte[] FORM_HEADER = { 'F', 'O', 'R', 'M' };
    private static final byte[] AIFF_HEADER = { 'A', 'I', 'F', 'F' };
    private static final byte[] AIFC_HEADER = { 'A', 'I', 'F', 'C' };

    // OGG IS A SHARED CONTAINER; THE CODEC ID IN ITS FIRST PAGE DECIDES THE TYPE
    private static final byte[] OGG_HEADER = { 'O', 'g', 'g', 'S' };

    // TEXT SUBTITLES
    private static final byte[] WEBVTT_HEADER = { 'W', 'E', 'B', 'V', 'T', 'T' };
    private static final byte[] ASS_HEADER = { '[', 'S', 'c', 'r', 'i', 'p', 't', ' ', 'I', 'n', 'f', 'o', ']' };

    // BYTES SCANNED FOR THE SVG ROOT ELEMENT (PROLOG/COMMENTS/DOCTYPE MAY PRECEDE <svg>)
    private static final int SVG_SNIFF_WINDOW_BYTES = 4_096;
    // BYTES READ FROM THE STREAM FOR SNIFFING; COVERS THE MPEG-TS 188-BYTE SYNC CHECK AND MATCHES
    // THE SVG SNIFF WINDOW SO sniff ACCEPTS THE SAME PROLOG-HEAVY SVGS AS reader
    private static final int PROBE_SIZE = SVG_SNIFF_WINDOW_BYTES;

    /**
     * Opens an {@link ImageReader} for the given source. The buffer is left positioned after the
     * consumed format header. The reader returns frames in its own {@link PixelFormat}: BGRA for
     * every format except a grayscale JPEG, which returns GRAY. Use
     * {@link #reader(ByteBuffer, PixelFormat)} to request a specific output layout.
     *
     * @throws UnsupportedFormatException if the leading bytes don't match any supported format
     * @throws IOException                on malformed bitstream
     * @throws IllegalArgumentException   when {@code source} is null
     */
    public static ImageReader reader(final ByteBuffer source) throws IOException {
        return reader(source, null);
    }

    /**
     * Opens an {@link ImageReader} for the given source that delivers frames in the specified
     * {@link PixelFormat}: {@link PixelFormat#BGRA}, {@link PixelFormat#RGBA} or
     * {@link PixelFormat#GRAY}, converted by the reader when it is not its own layout. When
     * {@code requestedFormat} is {@code null} the reader delivers its own layout without converting.
     * The source buffer is shared with the reader, not copied, so its contents must not change until
     * the reader is closed; when the reader refuses the body the buffer position is left where it was.
     *
     * @throws UnsupportedFormatException if the leading bytes don't match any supported format
     * @throws IOException                on malformed bitstream
     * @throws IllegalArgumentException   when {@code source} is null or the matched reader is asked for another layout
     */
    public static ImageReader reader(final ByteBuffer source, final PixelFormat requestedFormat) throws IOException {
        if (source == null) throw new IllegalArgumentException("Source cannot be null");
        final int start = source.position();
        try {
            if (DataTool.startsWith(source, start, PNG_HEADER)) {
                source.position(start + PNG_HEADER.length);
                return new PngReader(source, requestedFormat);
            }
            if (DataTool.startsWith(source, start, JPEG_HEADER)) {
                source.position(start + JPEG_HEADER.length);
                return new JpegReader(source, requestedFormat);
            }
            if (DataTool.startsWith(source, start, GIF87_HEADER) || DataTool.startsWith(source, start, GIF89_HEADER)) {
                source.position(start + GIF89_HEADER.length);
                return new GifReader(source, requestedFormat);
            }
            if (DataTool.startsWith(source, start, RIFF_HEADER) && DataTool.startsWith(source, start + 8, WEBP_HEADER)) {
                // THE RIFF SIZE BOUNDS THE FILE: BYTES BEYOND IT (TRAILING JUNK, A CONCATENATED FILE) ARE NOT CHUNKS
                final long riffBytes = 8L + Integer.toUnsignedLong(Integer.reverseBytes(source.getInt(start + 4)));
                final ByteBuffer webp = source.duplicate();
                webp.limit((int) Math.min(source.limit(), start + riffBytes)).position(Math.min(webp.limit(), start + 12));
                source.position(start + 12);
                return new WebPReader(webp, requestedFormat);
            }
            if (source.limit() - start >= 2 && source.get(start) == 'P') {
                final int version = source.get(start + 1) & 0xFF;
                if (version >= '1' && version <= '7') {
                    source.position(start + 2);
                    return new NetpbmReader(source, version - '0', requestedFormat);
                }
            }
            if (looksLikeSvg(probeText(source, start))) {
                // SVG HAS NO FIXED HEADER — THE WHOLE DOCUMENT IS THE BODY, SO LEAVE THE POSITION AT start
                source.position(start);
                return new SvgReader(source, requestedFormat);
            }
        } catch (final IOException | RuntimeException refused) { // A READER THAT REFUSES ITS BODY LEAVES THE BUFFER UNTOUCHED
            source.position(start);
            throw refused;
        }

        source.position(start);
        throw new UnsupportedFormatException("Unsupported image format at buffer position " + start);
    }

    /**
     * Decodes every frame of an encoded image in the reader's own layout: BGRA for every format
     * except a grayscale JPEG, which stays one luma byte per pixel; {@link ImageData#format()}
     * tells which. Use {@link #decode(byte[], PixelFormat)} when a uniform layout matters.
     *
     * @param data the whole encoded file
     * @return the decoded frames, their layout, delays and repeat count
     * @throws IOException on unsupported format or malformed bitstream
     * @see #reader(ByteBuffer)
     */
    public static ImageData decode(final byte[] data) throws IOException {
        return decode(data, null);
    }

    /**
     * Decodes every frame of an encoded image into the requested pixel layout, closing the
     * underlying reader before returning.
     *
     * @param data            the whole encoded file
     * @param requestedFormat {@link PixelFormat#BGRA}, {@link PixelFormat#RGBA} or {@link PixelFormat#GRAY}, or {@code null} for the reader's own layout
     * @return the decoded frames, their layout, delays and repeat count
     * @throws IOException on unsupported format or malformed bitstream
     * @throws IllegalArgumentException when {@code data} is null or the matched reader is asked for another layout
     * @see #reader(ByteBuffer, PixelFormat)
     */
    public static ImageData decode(final byte[] data, final PixelFormat requestedFormat) throws IOException {
        if (data == null) throw new IllegalArgumentException("Data cannot be null");
        return decode(ByteBuffer.wrap(data), requestedFormat);
    }

    /** Decodes every frame of an encoded image held in a buffer; see {@link #decode(byte[])}. */
    public static ImageData decode(final ByteBuffer source) throws IOException {
        return decode(source, null);
    }

    /** Decodes every frame of an encoded image held in a buffer into the requested layout; see {@link #decode(byte[], PixelFormat)}. */
    public static ImageData decode(final ByteBuffer source, final PixelFormat requestedFormat) throws IOException {
        try (final ImageReader reader = reader(source, requestedFormat)) {
            return reader.readAll();
        }
    }

    /** Decodes every frame of an encoded image read from a stream, which stays open; see {@link #decode(byte[])}. */
    public static ImageData decode(final InputStream in) throws IOException {
        return decode(in, null);
    }

    /**
     * Decodes every frame of an encoded image read from a stream into the requested layout. The
     * stream is read to its end, at most {@link ImageReader#MAX_DECODED_BYTES} encoded bytes, and
     * stays open; see {@link #decode(byte[], PixelFormat)}.
     */
    public static ImageData decode(final InputStream in, final PixelFormat requestedFormat) throws IOException {
        if (in == null) throw new IllegalArgumentException("Stream cannot be null");
        return decode(ByteBuffer.wrap(IOTool.readLimited(in, ImageReader.MAX_DECODED_BYTES, -1L, 0L)), requestedFormat);
    }

    /** Opens an experimental VP9 WebM {@link ImageWriter} at 30 fps and CRF 31. */
    public static ImageWriter writer(final OutputStream out, final int width, final int height, final PixelFormat format) throws IOException {
        return new WebMWriter(out, width, height, format);
    }

    /**
     * Opens an experimental {@link ImageWriter} that encodes frames as VP9 inside a seekable WebM
     * container through FFmpeg. Alpha is flattened; block-compressed formats are rejected.
     *
     * @param fps frame rate used when a frame carries no explicit delay
     * @param crf VP9 constant-quality factor (0-63, lower is better)
     * @throws IllegalArgumentException when {@code fps <= 0}, {@code crf} is out of range or the format cannot feed VP9
     * @throws IOException              when FFmpeg or a VP9 encoder is unavailable, or setup fails
     */
    public static ImageWriter writer(final OutputStream out, final int width, final int height, final PixelFormat format,
                                     final int fps, final int crf) throws IOException {
        return new WebMWriter(out, width, height, format, fps, crf);
    }

    /**
     * Sniffs the {@link MediaType} of a stream by matching its leading bytes against the known
     * container, codec and raw-format signatures across images, video and audio. This is the
     * authoritative classifier when a server reports an ambiguous MIME type (such as
     * {@code application/octet-stream}), where the content type and the URL extension are
     * unreliable.
     *
     * <p>Reads up to {@value #PROBE_SIZE} bytes and never closes {@code in}; the caller keeps
     * ownership of the stream. The probed bytes are consumed, so a caller that also needs the body
     * must buffer or re-open the stream.
     *
     * @param in the stream to inspect; its leading bytes are consumed
     * @return the detected type, or {@link MediaType#UNKNOWN} when nothing matches
     * @throws IOException if reading the stream fails
     * @throws IllegalArgumentException when {@code in} is null
     */
    public static MediaType sniff(final InputStream in) throws IOException {
        if (in == null) throw new IllegalArgumentException("Stream cannot be null");
        return sniff(ByteBuffer.wrap(in.readNBytes(PROBE_SIZE)));
    }

    /**
     * Sniffs the {@link MediaType} of buffered bytes by matching their leading bytes the same way
     * {@link #sniff(InputStream)} does; the buffer position is left untouched.
     *
     * @param source the bytes to inspect, from their position
     * @return the detected type, or {@link MediaType#UNKNOWN} when nothing matches
     * @throws IllegalArgumentException when {@code source} is null
     */
    public static MediaType sniff(final ByteBuffer source) {
        if (source == null) throw new IllegalArgumentException("Source cannot be null");
        final byte[] h = new byte[Math.min(source.remaining(), PROBE_SIZE)];
        source.get(source.position(), h);
        if (h.length < 2) return MediaType.UNKNOWN;
        final ByteBuffer b = ByteBuffer.wrap(h);

        // RIFF FAMILY — THE FORM TYPE AT OFFSET 8 DECIDES (WAVE=AUDIO, AVI=VIDEO, WEBP=IMAGE)
        if (DataTool.startsWith(b, 0, RIFF_HEADER)) {
            if (DataTool.startsWith(b, 8, WEBP_HEADER)) return MediaType.IMAGE;
            if (DataTool.startsWith(b, 8, RIFF_AVI)) return MediaType.VIDEO;
            if (DataTool.startsWith(b, 8, RIFF_WAVE)) return MediaType.AUDIO;
            return MediaType.UNKNOWN;
        }

        // ISO BASE MEDIA (MP4/MOV/HEIF/AVIF/3GP/M4A...) — THE BRAND AT OFFSET 8 DECIDES
        if (DataTool.startsWith(b, 4, FTYP_HEADER)) return ofFtyp(12 > h.length ? "" : new String(h, 8, 4, StandardCharsets.US_ASCII));

        // AIFF / AIFF-C
        if (DataTool.startsWith(b, 0, FORM_HEADER) && (DataTool.startsWith(b, 8, AIFF_HEADER) || DataTool.startsWith(b, 8, AIFC_HEADER)))
            return MediaType.AUDIO;

        // IMAGES — EXACT MAGIC
        if (DataTool.startsWith(b, 0, PNG_HEADER)) return MediaType.IMAGE;
        if (DataTool.startsWith(b, 0, JPEG_HEADER)) return MediaType.IMAGE;
        if (DataTool.startsWith(b, 0, GIF87_HEADER) || DataTool.startsWith(b, 0, GIF89_HEADER)) return MediaType.IMAGE;
        if (DataTool.startsWith(b, 0, BMP_HEADER)) return MediaType.IMAGE;
        if (DataTool.startsWith(b, 0, TIFF_LE_HEADER) || DataTool.startsWith(b, 0, TIFF_BE_HEADER)) return MediaType.IMAGE;
        if (DataTool.startsWith(b, 0, PSD_HEADER)) return MediaType.IMAGE;
        if (DataTool.startsWith(b, 0, JP2_HEADER)) return MediaType.IMAGE;
        if (DataTool.startsWith(b, 0, J2K_HEADER)) return MediaType.IMAGE;
        if (DataTool.startsWith(b, 0, JXL_CONTAINER_HEADER) || DataTool.startsWith(b, 0, JXL_CODESTREAM_HEADER)) return MediaType.IMAGE;
        if (DataTool.startsWith(b, 0, ICO_HEADER) || DataTool.startsWith(b, 0, CUR_HEADER)) return MediaType.IMAGE;
        if (DataTool.startsWith(b, 0, DDS_HEADER)) return MediaType.IMAGE;
        if (DataTool.startsWith(b, 0, ICNS_HEADER)) return MediaType.IMAGE;
        if (DataTool.startsWith(b, 0, QOI_HEADER)) return MediaType.IMAGE;
        if (DataTool.startsWith(b, 0, FLIF_HEADER)) return MediaType.IMAGE;
        if (DataTool.startsWith(b, 0, EXR_HEADER)) return MediaType.IMAGE;
        if (DataTool.startsWith(b, 0, XCF_HEADER)) return MediaType.IMAGE;
        if (DataTool.startsWith(b, 0, KTX_HEADER)) return MediaType.IMAGE;
        if (h[0] == 'P' && h[1] >= '1' && h[1] <= '7') return MediaType.IMAGE; // NETPBM (PBM/PGM/PPM/PAM)
        if (h[0] == '#' && h[1] == '?') return MediaType.IMAGE; // RADIANCE HDR ("#?RADIANCE" / "#?RGBE")
        if (looksLikeSvg(probeText(b, 0))) return MediaType.IMAGE; // SVG (XML, ROOT <svg>)

        // VIDEO — CONTAINERS / RAW STREAMS
        if (DataTool.startsWith(b, 0, EBML_HEADER)) return MediaType.VIDEO;
        if (DataTool.startsWith(b, 0, FLV_HEADER)) return MediaType.VIDEO;
        if (DataTool.startsWith(b, 0, ASF_HEADER)) return MediaType.VIDEO;
        if (DataTool.startsWith(b, 0, MPEG_PS_HEADER) || DataTool.startsWith(b, 0, MPEG_SEQ_HEADER)) return MediaType.VIDEO;
        if (DataTool.startsWith(b, 0, H264_NAL_HEADER)) return MediaType.VIDEO;
        if (DataTool.startsWith(b, 0, REALMEDIA_HEADER)) return MediaType.VIDEO;
        if (DataTool.startsWith(b, 0, MXF_HEADER)) return MediaType.VIDEO;
        // MPEG-TS: THE 0x47 SYNC BYTE REPEATS EVERY 188 BYTES
        if (h.length > 188 && (h[0] & 0xFF) == 0x47 && (h[188] & 0xFF) == 0x47) return MediaType.VIDEO;

        // AUDIO — CONTAINERS / CODECS
        if (DataTool.startsWith(b, 0, ID3_HEADER)) return MediaType.AUDIO;
        if (DataTool.startsWith(b, 0, FLAC_HEADER)) return MediaType.AUDIO;
        if (DataTool.startsWith(b, 0, MIDI_HEADER)) return MediaType.AUDIO;
        if (DataTool.startsWith(b, 0, AU_HEADER)) return MediaType.AUDIO;
        if (DataTool.startsWith(b, 0, AMR_HEADER)) return MediaType.AUDIO;
        if (DataTool.startsWith(b, 0, CAF_HEADER)) return MediaType.AUDIO;
        if (DataTool.startsWith(b, 0, DSF_HEADER)) return MediaType.AUDIO;
        if (DataTool.startsWith(b, 0, MPC_SV8_HEADER) || DataTool.startsWith(b, 0, MPC_SV7_HEADER)) return MediaType.AUDIO;
        if (DataTool.startsWith(b, 0, WAVPACK_HEADER)) return MediaType.AUDIO;
        if (DataTool.startsWith(b, 0, APE_HEADER)) return MediaType.AUDIO;
        if (DataTool.startsWith(b, 0, TTA_HEADER)) return MediaType.AUDIO;
        if (DataTool.startsWith(b, 0, REALAUDIO_HEADER)) return MediaType.AUDIO;
        if (DataTool.startsWith(b, 0, AC3_HEADER)) return MediaType.AUDIO;
        if (DataTool.startsWith(b, 0, DTS_HEADER)) return MediaType.AUDIO;

        // OGG — SCAN THE FIRST PAGE FOR THE CODEC ID; VIDEO CODECS WIN, ELSE AUDIO
        if (DataTool.startsWith(b, 0, OGG_HEADER)) {
            final String head = new String(h, 0, Math.min(h.length, 64), StandardCharsets.ISO_8859_1);
            if (head.contains("theora") || head.contains("OVP80") || head.contains("daala")) return MediaType.VIDEO;
            return MediaType.AUDIO; // VORBIS / OPUS / SPEEX / FLAC / DEFAULT
        }

        // MP3 / AAC-ADTS FRAME SYNC (11 BITS SET). CHECKED LATE SO THE FF-PREFIXED IMAGE
        // FORMATS (JPEG, JPEG2000, JPEG-XL) ARE MATCHED FIRST.
        if ((h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0) return MediaType.AUDIO;

        // TEXT SUBTITLES — TOLERATE A LEADING UTF-8 BOM
        final int t = (h.length >= 3 && (h[0] & 0xFF) == 0xEF && (h[1] & 0xFF) == 0xBB && (h[2] & 0xFF) == 0xBF) ? 3 : 0;
        if (DataTool.startsWith(b, t, WEBVTT_HEADER) || DataTool.startsWith(b, t, ASS_HEADER)) return MediaType.SUBTITLES;

        return MediaType.UNKNOWN;
    }

    // CLASSIFIES AN ISO-BMFF MAJOR BRAND. STILL-IMAGE (HEIF/AVIF) AND AUDIO-ONLY BRANDS ARE
    // ENUMERATED; EVERY OTHER BRAND IS A MOVIE CONTAINER (isom, mp4x, qt, m4v, 3gp, dash, mj2...).
    private static MediaType ofFtyp(final String brand) {
        final String v = brand.trim().toLowerCase(Locale.ROOT);
        if (v.startsWith("avif") || v.startsWith("avis")
                || v.startsWith("heic") || v.startsWith("heix") || v.startsWith("heim") || v.startsWith("heis")
                || v.startsWith("hevc") || v.startsWith("hevx") || v.startsWith("hevm") || v.startsWith("hevs")
                || v.startsWith("mif1") || v.startsWith("msf1"))
            return MediaType.IMAGE;
        if (v.startsWith("m4a") || v.startsWith("m4b") || v.startsWith("m4p")
                || v.startsWith("f4a") || v.startsWith("f4b") || v.startsWith("aac"))
            return MediaType.AUDIO;
        return MediaType.VIDEO;
    }

    // EXTRACTS THE LEADING TEXT FOR SVG SNIFFING (NO BINARY MAGIC EXISTS): SKIPS A UTF BOM,
    // DROPS NUL BYTES SO UTF-16 MARKUP STILL READS AS ASCII, AND CAPS THE SCANNED WINDOW
    private static String probeText(final ByteBuffer src, final int start) {
        final int limit = src.limit();
        int p = start;
        if (limit - p >= 3 && (src.get(p) & 0xFF) == 0xEF && (src.get(p + 1) & 0xFF) == 0xBB && (src.get(p + 2) & 0xFF) == 0xBF) p += 3;
        else if (limit - p >= 2 && (((src.get(p) & 0xFF) == 0xFF && (src.get(p + 1) & 0xFF) == 0xFE) || ((src.get(p) & 0xFF) == 0xFE && (src.get(p + 1) & 0xFF) == 0xFF))) p += 2;
        final int end = Math.min(limit, p + SVG_SNIFF_WINDOW_BYTES);
        final StringBuilder sb = new StringBuilder(Math.max(16, end - p));
        for (int i = p; i < end; i++) {
            final int b = src.get(i) & 0xFF;
            if (b != 0) sb.append((char) b);
        }
        return sb.toString();
    }

    // WALKS THE XML PROLOG (DECLARATION, COMMENTS, DOCTYPE) AND CHECKS THAT THE FIRST ELEMENT IS <svg>
    private static boolean looksLikeSvg(final String s) {
        int i = 0;
        final int n = s.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(s.charAt(i))) i++;
            if (i >= n || s.charAt(i) != '<') return false;
            if (s.startsWith("<?", i)) { final int e = s.indexOf("?>", i); if (e < 0) return false; i = e + 2; continue; }
            if (s.startsWith("<!--", i)) { final int e = s.indexOf("-->", i); if (e < 0) return false; i = e + 3; continue; }
            if (s.startsWith("<!", i)) {
                // DOCTYPE / MARKUP DECL — AN INTERNAL DTD SUBSET [ ... ] MAY CONTAIN '>' (e.g. <!ENTITY ...>)
                // BEFORE THE REAL END, SO SKIP PAST THE MATCHING ']' FIRST WHEN A SUBSET IS PRESENT
                final int gt = s.indexOf('>', i);
                if (gt < 0) return false;
                final int bracket = s.indexOf('[', i);
                if (bracket >= 0 && bracket < gt) {
                    final int endBracket = s.indexOf(']', bracket);
                    final int close = endBracket < 0 ? -1 : s.indexOf('>', endBracket);
                    if (close < 0) return false;
                    i = close + 1;
                } else {
                    i = gt + 1;
                }
                continue;
            }
            int k = i + 1;
            while (k < n && !Character.isWhitespace(s.charAt(k)) && s.charAt(k) != '>' && s.charAt(k) != '/') k++;
            String name = s.substring(i + 1, k);
            final int colon = name.indexOf(':');
            if (colon >= 0) name = name.substring(colon + 1);
            return name.equalsIgnoreCase("svg");
        }
        return false;
    }

    // ==========================================================================
    // CODEC AVAILABILITY
    // ==========================================================================
    /**
     * Reports support for decoding pixels through this API; BC textures are read through {@link BcReader}.
     * @param codec a pixel codec id, case-insensitive
     * @return true when its pure Java pixel decoder is available
     */
    public static boolean available(final String codec) {
        if (codec == null) return false;
        return switch (codec.toUpperCase(Locale.ROOT)) {
            case CODEC_PNG, CODEC_JPEG, "JPG", CODEC_GIF, CODEC_WEBP,
                 CODEC_NETPBM, "PNM", "PBM", "PGM", "PPM", "PAM", CODEC_SVG -> true;
            default -> false;
        };
    }
}
