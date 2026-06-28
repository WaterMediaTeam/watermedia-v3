package org.watermedia.api.platform;

import org.watermedia.api.media.MRL;

import java.net.URI;

/**
 * A single hit returned by {@link IPlatform#search(String, int)}. It carries only what a
 * result list needs to be drawn — the originating platform, a display title, a thumbnail and
 * the <i>raw</i> page {@link #url() URL}. The URL is intentionally <b>not</b> resolved to an
 * {@link MRL}: resolution is deferred until the user actually picks a result, at which point the
 * URL is fed back through {@link MRL}/{@link PlatformAPI#fetch(URI)} like any other link.
 *
 * @param platform  the {@link IPlatform#name() name} of the platform that produced this hit
 * @param title     human-readable title (video/stream/gallery name)
 * @param thumbnail preview image URL, or {@code null} when the platform exposes none
 * @param url       the raw page URL to resolve later (e.g. {@code https://www.youtube.com/watch?v=...})
 */
public record PlatformResult(String platform, String title, URI thumbnail, URI url) {
}
