package org.watermedia.api.util;

import org.watermedia.api.media.MRL;

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
     * Parses a MIME type string into MediaType.
     */
    public static MediaType of(final String mimeType) {
        if (mimeType == null || mimeType.isEmpty()) return UNKNOWN;

        final String[] type = mimeType.split("/");
        return switch (type[0].toLowerCase()) {
            case "image" -> IMAGE;
            case "video" -> VIDEO;
            case "audio" -> AUDIO;
            case "text" -> {
                final String subtype = type.length == 2 ? type[1].toLowerCase() : "";
                yield (subtype.equals("vtt") || subtype.equals("srt") || subtype.contains("subtitle")) ? SUBTITLES : UNKNOWN;
            }
            default -> UNKNOWN;
        };
    }

    /**
     * Resolves a MediaType from a file path/URL extension. Used as a fallback when the
     * server reports an ambiguous MIME type (e.g. {@code application/octet-stream}), which
     * many CDNs do for files like {@code .webp} thumbnails.
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
            final MediaType type = switch (parts[i].toLowerCase()) {
                case "jpg", "jpeg", "jfif", "png", "gif", "webp", "bmp", "tiff", "tif", "avif", "ico", "heic", "heif" -> IMAGE;
                case "mp4", "m4v", "webm", "mkv", "avi", "mov", "flv", "ts", "mpd", "m3u8", "wmv", "3gp", "ogv", "mpg", "mpeg" -> VIDEO;
                case "mp3", "aac", "ogg", "oga", "opus", "wav", "flac", "m4a", "weba", "wma" -> AUDIO;
                case "vtt", "srt", "ass", "ssa", "sub" -> SUBTITLES;
                default -> UNKNOWN;
            };
            if (type != UNKNOWN) return type;
        }
        return UNKNOWN;
    }
}
