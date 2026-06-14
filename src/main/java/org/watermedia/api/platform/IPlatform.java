package org.watermedia.api.platform;

import java.net.URI;

/**
 * Contract for a platform handler. A single {@link #getData(URI)} call both
 * decides whether the URI belongs to this platform and resolves it to raw
 * {@link PlatformData} — direct links, dimensions, metadata. Source/MRL
 * construction lives in {@link org.watermedia.api.media.MRL}, not here.
 */
public interface IPlatform {

    /**
     * Provides the human-readable platform name (e.g. "Kick", "BiliBili").
     */
    String name();

    /**
     * Resolves the URI to one or more media entries with their variants and
     * metadata. The returned structure is platform-agnostic and intentionally
     * decoupled from {@code MRL.Source}: callers (including {@link org.watermedia.api.media.MRL})
     * decide how to consume it.
     * <p>
     * The contract has three outcomes:
     * <ul>
     *   <li>{@code null} — the URI does not belong to this platform; the caller
     *       must keep probing other handlers.</li>
     *   <li>a thrown exception — the URI belongs to this platform but resolution
     *       failed (parse error, network error, or mature content disabled for
     *       platforms that gate it).</li>
     *   <li>a {@link PlatformData} instance — the URI was resolved successfully.</li>
     * </ul>
     *
     * @param uri the URI to resolve
     * @return raw data with available variants and expiration, or {@code null}
     *         when the URI is not handled by this platform
     * @throws Exception if the URI belongs to this platform but the lookup fails
     */
    PlatformData getData(URI uri) throws Exception;
}
