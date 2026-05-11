package org.watermedia.api.platform;

import java.net.URI;

/**
 * Mirror of {@code MRL.SlaveEntry} kept inside the platform layer so the
 * raw data shape returned by an {@link DataSource} stays decoupled from
 * MRL's domain types. MRL converts these to its own {@code SlaveEntry}
 * when it builds the player-facing {@code Source}.
 *
 * @param name display name for the track (e.g. "English")
 * @param lang BCP-47 language tag (e.g. "en", "es-419")
 * @param uri  direct URL to the slave stream/file
 */
public record DataSlave(String name, String lang, URI uri) {
}
