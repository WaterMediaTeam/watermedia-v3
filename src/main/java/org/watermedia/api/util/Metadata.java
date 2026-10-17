package org.watermedia.api.util;

import org.watermedia.api.media.MRL;

import java.time.Instant;

/**
 * Metadata a platform reports for a media {@link MRL.Source}. Every component is optional:
 * {@code null} text or instant and a {@code durationMs} of zero mean the platform did not say.
 *
 * @param title       display title of the media
 * @param description longer description or caption
 * @param postedAt    publication time
 * @param durationMs  running time in milliseconds, or zero when unknown or live
 * @param author      uploader, channel or streamer name
 */
public record Metadata(String title, String description, Instant postedAt, long durationMs, String author) {
}
