package org.watermedia.api.util;

import org.watermedia.api.media.MRL;
import org.watermedia.api.platform.Platform;

import java.net.URI;

/**
 * A secondary track attached to a media source — an alternate audio stream or a subtitle file.
 * Shared by the platform layer ({@link Platform.Source}) and {@link MRL.Source}.
 *
 * @param name display name for the track (e.g. "English")
 * @param lang BCP-47 language tag (e.g. "en", "es-419")
 * @param uri  direct URL to the track stream/file
 */
public record Track(String name, String lang, URI uri) {
}
