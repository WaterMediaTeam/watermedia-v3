package org.watermedia.api.util;

import org.watermedia.api.media.MRL;
import org.watermedia.tools.DataTool;

import java.util.Locale;
import java.util.Map;

/**
 * Media types supported by {@link MRL.Source}.
 */
public enum MediaType {
    IMAGE,
    VIDEO,
    AUDIO,
    SUBTITLES,
    UNKNOWN;

    /**
     * Extension-to-MIME table for every format WaterMedia consumes (codecs API + FFmpeg).
     * Single source of truth: {@link #ofExtension(String)} classifies through it and
     * {@link NetRequest} extends Java's file-name map with it — the bundled
     * {@code content-types.properties} misses most of these.
     */
    private static final Map<String, String> MIMES = DataTool.arrayMapper(new String[] {
            // IMAGE
            "png",  "image/png",
            "apng", "image/apng",
            "jpg",  "image/jpeg",
            "jpeg", "image/jpeg",
            "jfif", "image/jpeg",
            "gif",  "image/gif",
            "webp", "image/webp",
            "svg",  "image/svg+xml",
            "pbm",  "image/x-portable-bitmap",
            "pgm",  "image/x-portable-graymap",
            "ppm",  "image/x-portable-pixmap",
            "pam",  "image/x-portable-arbitrarymap",
            "bmp",  "image/bmp",
            "tif",  "image/tiff",
            "tiff", "image/tiff",
            "ico",  "image/x-icon",
            "avif", "image/avif",
            "heic", "image/heic",
            "heif", "image/heif",

            // VIDEO
            "mp4",  "video/mp4",
            "m4v",  "video/x-m4v",
            "mkv",  "video/x-matroska",
            "webm", "video/webm",
            "mov",  "video/quicktime",
            "avi",  "video/x-msvideo",
            "wmv",  "video/x-ms-wmv",
            "flv",  "video/x-flv",
            "f4v",  "video/x-f4v",
            "ts",   "video/mp2t",
            "mts",  "video/mp2t",
            "m2ts", "video/mp2t",
            "mpg",  "video/mpeg",
            "mpeg", "video/mpeg",
            "3gp",  "video/3gpp",
            "3g2",  "video/3gpp2",
            "ogv",  "video/ogg",
            "asf",  "video/x-ms-asf",
            "vob",  "video/dvd",
            "rm",   "application/vnd.rn-realmedia",
            "rmvb", "application/vnd.rn-realmedia-vbr",
            "m3u",  "application/vnd.apple.mpegurl",
            "m3u8", "application/vnd.apple.mpegurl",
            "mpd",  "application/dash+xml",

            // AUDIO
            "mp3",  "audio/mpeg",
            "aac",  "audio/aac",
            "m4a",  "audio/mp4",
            "ogg",  "audio/ogg",
            "oga",  "audio/ogg",
            "opus", "audio/opus",
            "flac", "audio/flac",
            "wav",  "audio/wav",
            "weba", "audio/webm",
            "wma",  "audio/x-ms-wma",
            "ac3",  "audio/ac3",
            "dts",  "audio/vnd.dts",
            "amr",  "audio/amr",
            "aiff", "audio/aiff",
            "aif",  "audio/aiff",
            "au",   "audio/basic",

            // SUBTITLES
            "vtt",  "text/vtt",
            "srt",  "application/x-subrip",
            "ass",  "text/x-ssa",
            "ssa",  "text/x-ssa",
            "sub",  "text/x-microdvd",
    });

    /**
     * Parses a MIME type string into MediaType. Streaming manifests and containers hiding
     * behind {@code application/*} (HLS, DASH, RealMedia, SubRip) are classified too.
     */
    public static MediaType of(final String mimeType) {
        if (mimeType == null || mimeType.isEmpty()) return UNKNOWN;

        final String[] type = mimeType.split("/");
        final String subtype = type.length == 2 ? type[1].toLowerCase() : "";
        return switch (type[0].toLowerCase()) {
            case "image" -> IMAGE;
            case "video" -> VIDEO;
            case "audio" -> AUDIO;
            case "text" -> (subtype.equals("vtt") || subtype.equals("srt") || subtype.equals("x-ssa")
                    || subtype.equals("x-microdvd") || subtype.contains("subtitle")) ? SUBTITLES : UNKNOWN;
            case "application" -> switch (subtype) {
                case "vnd.apple.mpegurl", "x-mpegurl", "dash+xml",
                     "vnd.rn-realmedia", "vnd.rn-realmedia-vbr" -> VIDEO;
                case "x-subrip" -> SUBTITLES;
                default -> UNKNOWN;
            };
            default -> UNKNOWN;
        };
    }

    /**
     * Known MIME type for a bare file extension (no dot, any case), or {@code null} when unmapped.
     */
    public static String mime(final String extension) {
        return extension == null ? null : MIMES.get(extension.toLowerCase(Locale.ROOT));
    }

    /**
     * Resolves a MediaType from a file path/URL extension through the shared MIME table. Used
     * as a fallback when the server reports an ambiguous MIME type (e.g.
     * {@code application/octet-stream}), which many CDNs do for files like {@code .webp} thumbnails.
     *
     * <p>Only the file name is inspected (dots in parent directories are ignored) and its
     * extensions are scanned right-to-left, returning the first recognized one. This handles
     * stacked extensions where a platform appends the real container instead of renaming
     * (e.g. {@code video.m3u8.mp4}), and tolerates a trailing non-media suffix masking an
     * earlier media one (e.g. {@code clip.mp4.download}).
     */
    public static MediaType ofExtension(final String path) {
        if (path == null) return UNKNOWN;
        final String name = path.substring(path.lastIndexOf('/') + 1);
        final String[] parts = name.split("\\.");
        for (int i = parts.length - 1; i >= 1; i--) {
            final MediaType type = of(mime(parts[i]));
            if (type != UNKNOWN) return type;
        }
        return UNKNOWN;
    }
}
