package org.watermedia.api.platform;

import java.time.Instant;

/**
 * Result of a platform query. May carry multiple {@link DataSource entries}
 * when the source URI is a gallery, album or multi-stream resource.
 *
 * @param expires expiration instant for cached entries (null = never)
 * @param entries one or more media entries
 */
public record PlatformData(Instant expires, DataSource... entries) {
    public int size() {
        return this.entries.length;
    }
}
