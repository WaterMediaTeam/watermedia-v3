package org.watermedia.api.codecs;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.api.WaterMediaAPI;
import org.watermedia.api.codecs.decoders.GIFReader;
import org.watermedia.api.codecs.decoders.JPEGReader;
import org.watermedia.api.codecs.decoders.NETPBMReader;
import org.watermedia.api.codecs.decoders.PNGReader;
import org.watermedia.api.codecs.decoders.WEBPReader;
import org.watermedia.tools.DataTool;

import java.io.IOException;
import java.nio.ByteBuffer;

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

    /**
     * Opens an {@link ImageReader} for the given source. The buffer is left positioned after the
     * consumed format header.
     *
     * @throws UnsupportedFormatException if the leading bytes don't match any supported format
     * @throws IOException                on malformed bitstream
     */
    public static ImageReader decodeImage(final ByteBuffer source) throws IOException {
        if (source == null) throw new NullPointerException("source");
        final int start = source.position();

        if (DataTool.startsWith(source, start, PNG_HEADER)) {
            source.position(start + PNG_HEADER.length);
            return new PNGReader(source);
        }
        if (DataTool.startsWith(source, start, JPEG_HEADER)) {
            source.position(start + JPEG_HEADER.length);
            return new JPEGReader(source);
        }
        if (DataTool.startsWith(source, start, GIF87_HEADER) || DataTool.startsWith(source, start, GIF89_HEADER)) {
            source.position(start + GIF89_HEADER.length);
            return new GIFReader(source);
        }
        if (DataTool.startsWith(source, start, RIFF_HEADER) && DataTool.startsWith(source, start + 8, WEBP_HEADER)) {
            source.position(start + 12);
            return new WEBPReader(source);
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
     * @return a {@link ImageData} instance with raw decoded image, the resulting buffers are in BGRA
     * @throws IOException on unsupported format or malformed bitstream
     */
    public static ImageData decodeImage(final byte[] data) throws IOException {
        try (final ImageReader reader = decodeImage(ByteBuffer.wrap(data))) {
            return reader.readAll();
        }
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
