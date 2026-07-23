package org.watermedia.api.platform;

import java.time.Instant;
import java.util.List;

/**
 * Result of a platform query. May carry multiple {@link DataSource entries}
 * when the source URI is a gallery, album or multi-stream resource.
 *
 * @param expires expiration instant for cached entries (null = never)
 * @param entries one or more media entries
 */
public record PlatformData(Instant expires, List<DataSource> entries) {

    public PlatformData {
        // DEFENSIVE COPY: RECORD COMPONENT MUST NOT EXPOSE A MUTABLE LIST SHARED WITH THE PLATFORM
        entries = List.copyOf(entries);
    }

    // CONVENIENCE FOR THE COMMON SINGLE-ENTRY RESULT
    public PlatformData(final Instant expires, final DataSource entry) {
        this(expires, List.of(entry));
    }

    public int size() {
        return this.entries.size();
    }
}
