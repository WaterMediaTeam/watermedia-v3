package org.watermedia.api.media.data;

public enum MediaType {
    IMAGE,
    AUDIO,
    VIDEO,
    SUBTITLES,
    UNKNOWN;

    public static MediaType getByMimetype(final String mimetype) {
        final String[] mm = mimetype.split("/");
        final String type = mm[0];
        final String format = mm.length == 1 ? null : mm[1].toLowerCase();

        return switch (type) {
            case "video" -> VIDEO;
            case "audio" -> AUDIO;
            case "text" -> format != null && (format.equals("str") || format.equals("plain")) ? SUBTITLES : UNKNOWN;
            default -> UNKNOWN;
        };
    }
}
