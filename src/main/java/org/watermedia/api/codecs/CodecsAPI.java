package org.watermedia.api.codecs;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.api.WaterMediaAPI;
import org.watermedia.api.codecs.readers.GIFReader;
import org.watermedia.api.codecs.readers.JPEGReader;
import org.watermedia.api.codecs.readers.NETPBMReader;
import org.watermedia.api.codecs.readers.PNGReader;
import org.watermedia.api.codecs.readers.WEBPReader;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.tools.DataTool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Image codec entry point.
 *
 * <p>{@link #decodeImage(ByteBuffer)} scans the buffer's leading bytes against the supported
 * format headers, identifies the format, advances the buffer position past the matched header,
 * and returns the matching {@link ImageReader}. Readers parse only the format body.
 */
public class CodecsAPI extends WaterMediaAPI {
    private static final Marker IT = MarkerManager.getMarker(CodecsAPI.class.getSimpleName());

    public static final String PNG_METAKEY_TEXT = "png.text";
    public static final String PNG_METAKEY_COMPRESSED_TEXT = "png.compressed_text";
    public static final String PNG_METAKEY_INTERNATIONAL_TEXT = "png.international_text";
    public static final String PNG_METAKEY_GAMMA = "png.gamma";
    public static final String PNG_METAKEY_CHROMATICITIES = "png.chromaticities";
    public static final String PNG_METAKEY_SRGB = "png.srgb";
    public static final String PNG_METAKEY_ICC_PROFILE = "png.icc_profile";
    public static final String PNG_METAKEY_SIGNIFICANT_BITS = "png.significant_bits";
    public static final String PNG_METAKEY_CICP = "png.cicp";
    public static final String PNG_METAKEY_MASTERING_DISPLAY_COLOR_VOLUME = "png.mastering_display_color_volume";
    public static final String PNG_METAKEY_CONTENT_LIGHT_LEVEL = "png.content_light_level";
    public static final String PNG_METAKEY_PHYSICAL_PIXEL_DIMENSIONS = "png.physical_pixel_dimensions";
    public static final String PNG_METAKEY_HISTOGRAM = "png.histogram";
    public static final String PNG_METAKEY_SUGGESTED_PALETTES = "png.suggested_palettes";
    public static final String PNG_METAKEY_EXIF = "png.exif";
    public static final String PNG_METAKEY_LAST_MODIFIED_TIME = "png.last_modified_time";
    public static final String PNG_METAKEY_ANCILLARY_CHUNK = "png.ancillary_chunk";

    public static final String GIF_METAKEY_COMMENT = "gif.comment";
    public static final String GIF_METAKEY_LOOP_COUNT = "gif.loop_count";
    public static final String GIF_METAKEY_APPLICATION_EXTENSION = "gif.application_extension";

    public static final String WEBP_METAKEY_ICC_PROFILE = "webp.icc_profile";
    public static final String WEBP_METAKEY_EXIF = "webp.exif";
    public static final String WEBP_METAKEY_XMP = "webp.xmp";

    private static final byte[] PNG_HEADER = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
    private static final byte[] JPEG_HEADER = { (byte) 0xFF, (byte) 0xD8 };
    private static final byte[] GIF87_HEADER = { 'G', 'I', 'F', '8', '7', 'a' };
    private static final byte[] GIF89_HEADER = { 'G', 'I', 'F', '8', '9', 'a' };
    private static final byte[] RIFF_HEADER = { 'R', 'I', 'F', 'F' };
    private static final byte[] WEBP_HEADER = { 'W', 'E', 'B', 'P' };

    // ADDITIONAL IMAGE SIGNATURES (USED ONLY BY getMediaType)
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

    // BYTES READ FROM THE STREAM FOR SNIFFING; LARGE ENOUGH FOR THE MPEG-TS 188-BYTE SYNC CHECK
    private static final int PROBE_SIZE = 256;

    /**
     * Opens an {@link ImageReader} for the given source. The buffer is left positioned after the
     * consumed format header. The reader returns frames in its native {@link PixelFormat} —
     * whichever layout is cheapest for it to produce. For example, WEBP lossy returns YUV. Use
     * {@link #decodeImage(ByteBuffer, PixelFormat)} to request a specific output layout.
     *
     * @throws UnsupportedFormatException if the leading bytes don't match any supported format
     * @throws IOException                on malformed bitstream
     */
    public static ImageReader decodeImage(final ByteBuffer source) throws IOException {
        return decodeImage(source, null);
    }

    /**
     * Opens an {@link ImageReader} for the given source and asks it to deliver frames in the
     * specified {@link PixelFormat}. When {@code requestedFormat} is {@code null} the reader picks
     * its native format (no conversion). The reader may decline the request when the conversion
     * is not supported, in which case {@link ImageReader#pixelFormat()} reports the actual layout
     * it produced — callers must always consult that method.
     *
     * @throws UnsupportedFormatException if the leading bytes don't match any supported format
     * @throws IOException                on malformed bitstream
     */
    public static ImageReader decodeImage(final ByteBuffer source, final PixelFormat requestedFormat) throws IOException {
        if (source == null) throw new NullPointerException("source");
        final int start = source.position();

        if (DataTool.startsWith(source, start, PNG_HEADER)) {
            source.position(start + PNG_HEADER.length);
            return new PNGReader(source);
        }
        if (DataTool.startsWith(source, start, JPEG_HEADER)) {
            source.position(start + JPEG_HEADER.length);
            return new JPEGReader(source, requestedFormat);
        }
        if (DataTool.startsWith(source, start, GIF87_HEADER) || DataTool.startsWith(source, start, GIF89_HEADER)) {
            source.position(start + GIF89_HEADER.length);
            return new GIFReader(source);
        }
        if (DataTool.startsWith(source, start, RIFF_HEADER) && DataTool.startsWith(source, start + 8, WEBP_HEADER)) {
            source.position(start + 12);
            return new WEBPReader(source, requestedFormat);
        }
        if (source.limit() - start >= 2 && source.get(start) == 'P') {
            final int version = source.get(start + 1) & 0xFF;
            if (version >= '4' && version <= '7') {
                source.position(start + 2);
                return new NETPBMReader(source, version - '0');
            }
        }

        source.position(start);
        throw new UnsupportedFormatException("Unsupported image format at buffer position " + start);
    }

    /**
     * @see #decodeImage(ByteBuffer)
     * @param data buffer data array
     * @return a {@link ImageData} instance with raw decoded image in the reader's native layout
     *         (check {@link ImageReader#pixelFormat()} on the underlying reader to know what it
     *         picked).
     * @throws IOException on unsupported format or malformed bitstream
     */
    public static ImageData decodeImage(final byte[] data) throws IOException {
        return decodeImage(data, null);
    }

    /**
     * @see #decodeImage(ByteBuffer, PixelFormat)
     * @param data            buffer data array
     * @param requestedFormat desired output pixel format, or {@code null} for the reader's native
     *                        layout.
     * @return a {@link ImageData} instance with raw decoded image
     * @throws IOException on unsupported format or malformed bitstream
     */
    public static ImageData decodeImage(final byte[] data, final PixelFormat requestedFormat) throws IOException {
        try (final ImageReader reader = decodeImage(ByteBuffer.wrap(data), requestedFormat)) {
            return reader.readAll();
        }
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
     */
    public static MediaType getMediaType(final InputStream in) throws IOException {
        if (in == null) throw new NullPointerException("in");
        final byte[] h = in.readNBytes(PROBE_SIZE);
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
        if (DataTool.startsWith(b, 4, FTYP_HEADER)) return ofFtyp(ascii(h, 8, 4));

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

    private static String ascii(final byte[] data, final int off, final int len) {
        if (off + len > data.length) return "";
        return new String(data, off, len, StandardCharsets.US_ASCII);
    }

    @Override
    public String name() {
        return CodecsAPI.class.getSimpleName();
    }

    @Override
    public void load(final WaterMedia instance) {
        this.steps = 0;
        this.step = 0;
        this.stepName = "";
    }

    @Override
    public boolean start(final WaterMedia instance) {
        if (!instance.clientSide) {
            LOGGER.warn(IT, "Codecs API refuses to load on server-side");
            return false;
        }
        return true;
    }

    @Override
    public void release(final WaterMedia instance) {
        this.step = 0;
        this.steps = 0;
        this.stepName = "";
    }

}
