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
}
