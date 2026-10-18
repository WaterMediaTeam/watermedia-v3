package org.watermedia.api.platform;

import org.watermedia.api.media.MRL;

import java.net.URI;

/**
 * A single hit returned by {@link Platform#search(String, int)}. It carries only what a
 * result list needs to be drawn — the originating platform, a display title, a thumbnail and
 * the <i>raw</i> page {@link #uri() URI}. The URI is intentionally <b>not</b> resolved to an
 * {@link MRL}: resolution is deferred until the user actually picks a result, at which point the
 * URI is fed back through {@link MRL}/{@link PlatformAPI#fetch(URI)} like any other link.
 *
 * @param platform  the platform that produced this hit, never null
 * @param title     human-readable title (video/stream/gallery name)
 * @param thumbnail preview image URI, or {@code null} when the platform exposes none
 * @param uri       the raw page URI to resolve later (e.g. {@code https://www.youtube.com/watch?v=...}), never null
 */
public record PlatformResult(Platform platform, String title, URI thumbnail, URI uri) {

    public PlatformResult {
        if (platform == null || uri == null) throw new IllegalArgumentException("PlatformResult requires a platform and a URI");
    }
}
