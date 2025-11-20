package org.watermedia.api.media;

import org.watermedia.WaterMedia;

import java.net.URI;
import java.util.Date;
import java.util.Map;

public record MRL(MediaType mediaType, Map<MediaQuality, URI> sourceQuality) {

    /**
     * Find URI for the given quality, or pick the closest available quality
     * @param quality
     * @return
     */
    public URI getURI(final MediaQuality quality) {
        if (this.sourceQuality.containsKey(quality)) {
            return this.sourceQuality.get(quality);
        } else {
            // find closest quality
            MediaQuality checkQuality = quality;
            while (true) {
                checkQuality = checkQuality.getBack();
                WaterMedia.LOGGER.info("Checking quality {}", checkQuality);
                if (this.sourceQuality.containsKey(checkQuality)) {
                    return this.sourceQuality.get(checkQuality);
                }
                if (checkQuality == quality) break; // full loop
            }
        }
        throw new IllegalStateException("MRL has no available qualities");
    }

    public String getURIString(final MediaQuality quality) {
        final URI uri = this.getURI(quality);
        if ("file".equals(uri.getScheme())) {
            return uri.getPath();
        } else {
            return uri.toString();
        }
    }

    public enum MediaQuality {
        /**
         * Qualities same or below 240p threshold
         */
        LOWEST(240),

        /**
         * Qualities same or below 480p threshold
         */
        LOWER(480),

        /**
         * Qualities below 540p threshold
         */
        LOW(540),

        /**
         * Qualities same or below 720p threshold
         */
        AVERAGE(720),

        /**
         * Qualities same or below 1080p threshold
         */
        HIGH(1080),

        /**
         * Qualities same or below 2K threshold
         */
        HIGHER(1440),

        /**
         * Qualities same or below 4K threshold
         */
        HIGHEST(2160);

        private final int threshold;

        MediaQuality(final int threshold) { this.threshold = threshold; }

        public static final MediaQuality[] VALUES = values();

        public static MediaQuality of(final int width) { // TODO: evaluate height for tiktok reels
            if (width >= LOWEST.threshold && width < LOWER.threshold) return LOWEST;
            else if (width >= LOWER.threshold && width < LOW.threshold) return LOWER;
            else if (width >= LOW.threshold && width < AVERAGE.threshold) return LOW;
            else if (width >= AVERAGE.threshold && width < HIGH.threshold) return AVERAGE;
            else if (width >= HIGH.threshold && width < HIGHER.threshold) return HIGH;
            else if (width >= HIGHER.threshold && width < HIGHEST.threshold) return HIGHER;
            else return HIGHEST;
        }

        public MediaQuality getNext() {
            final var ordinal = this.ordinal() + 1;
            if (ordinal >= VALUES.length) return VALUES[0];
            return VALUES[ordinal];
        }

        public MediaQuality getBack() {
            final var ordinal = this.ordinal() - 1;
            if (ordinal <= 0) return VALUES[VALUES.length - 1];
            return VALUES[ordinal];
        }
    }

    public enum MediaType {
        IMAGE,
        AUDIO,
        VIDEO,
        SUBTITLES,
        UNKNOWN;

        public static MediaType ofMimetype(final String mimetype) {
            final String[] mm = mimetype.split("/");
            final String type = mm[0];
            final String format = mm.length == 1 ? null : mm[1].toLowerCase();

            return switch (type) {
                case "image" -> IMAGE;
                case "video" -> VIDEO;
                case "audio" -> AUDIO;
                case "text" -> format != null && (format.equals("str") || format.equals("plain")) ? SUBTITLES : UNKNOWN;
                default -> UNKNOWN;
            };
        }
    }

    public record Metadata(URI thumbnail, String title, String description, Date date, long duration) {

    }
}
