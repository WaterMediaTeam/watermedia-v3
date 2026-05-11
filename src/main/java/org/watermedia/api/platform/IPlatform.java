package org.watermedia.api.platform;

import java.net.URI;

/**
 * Contract for a platform handler. Implementations validate a URI and resolve
 * it to raw {@link PlatformData} — direct links, dimensions, metadata.
 * Source/MRL construction lives in {@link org.watermedia.api.media.MRL}, not
 * here.
 */
public interface IPlatform {

    /**
     * Provides the human-readable platform name (e.g. "Kick", "BiliBili").
     */
    String name();

    /**
     * Checks if the URI can be resolved by this platform.
     * @param uri uri to validate
     * @return {@code true} if this platform can fetch data for the URI
     */
    boolean validate(URI uri);

    /**
     * Resolves the URI to one or more media entries with their variants and
     * metadata. The returned structure is platform-agnostic and intentionally
     * decoupled from {@code MRL.Source}: callers (including {@link org.watermedia.api.media.MRL})
     * decide how to consume it.
     *
     * @param uri the URI to resolve
     * @return raw data with available variants and expiration
     * @throws Exception if the lookup fails
     */
    PlatformData getData(URI uri) throws Exception;
}
