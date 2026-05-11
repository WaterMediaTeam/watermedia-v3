package org.watermedia.api.util;

import org.watermedia.api.media.MRL;

import java.time.Instant;

/**
 * Metadata for a media {@link MRL.Source}.
 */
public record Metadata(String title, String desc, Instant postedAt, long duration, String author) {
}
