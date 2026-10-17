package org.watermedia.api.platform;

import org.watermedia.WaterMedia;
import org.watermedia.api.platform.Platform.Quality;
import org.watermedia.api.platform.Platform.Resolution;
import org.watermedia.api.platform.Platform.Source;
import org.watermedia.api.platform.web.YouTubePlatform;
import org.watermedia.api.platform.web.YtDlpPlatform;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.Track;

import java.net.URI;
import java.util.List;

/**
 * MANUAL verification harness (not a JUnit test): resolves URLs through the actual yt-dlp platforms,
 * exactly as {@code PlatformAPI} would — so host routing, yt-dlp extraction and the resulting
 * {@link Source} are all exercised end-to-end on this machine's own network (useful when a site
 * blocks datacenter IPs). Run with {@code gradle platformSmoke -Purl=<url>}; with no URL it resolves one
 * public sample per platform. Facebook/Instagram public items rot fast and often need a logged-in
 * session, so test those by passing a fresh public link.
 */
public final class PlatformSmokeTest {
    private static final String[] DEFAULTS = {
            "https://www.youtube.com/watch?v=GjEKrr5EjzA",
            "https://soundcloud.com/imaginedragons/believer",
            "https://www.newgrounds.com/portal/view/582151",
    };

    public static void main(final String[] args) throws Exception {
        WaterMedia.start("TEST", null, null, true);

        // SAME HANDLERS PlatformAPI REGISTERS, IN ITS PROBE ORDER (FIRST NON-NULL WINS)
        final List<Platform> platforms = List.of(
                new YtDlpPlatform(),
                new YouTubePlatform());

        final String[] urls = args.length > 0 ? args : DEFAULTS;
        for (final String url: urls) {
            resolve(platforms, url);
        }
        if (args.length == 0) {
            System.out.println("\nTip: test Facebook/Instagram with a public link: gradle platformSmoke -Purl=<url>");
        }
        System.exit(0);
    }

    private static void resolve(final List<Platform> platforms, final String url) {
        System.out.println("\n=== " + url + " ===");
        final URI uri = URI.create(url);
        final long started = System.currentTimeMillis();
        try {
            for (final Platform platform: platforms) {
                final Resolution data = platform.resolve(uri);
                if (data == null) continue; // NOT THIS PLATFORM'S HOST — KEEP PROBING
                System.out.println("platform=" + platform.name() + " sources=" + data.sourceCount()
                        + " expires=" + data.expires() + " (" + (System.currentTimeMillis() - started) + "ms)");
                for (final Source src: data.sources()) {
                    print(src);
                }
                return;
            }
            System.out.println("(no platform handled this URL)");
        } catch (final Exception e) {
            System.out.println("FAILED (" + (System.currentTimeMillis() - started) + "ms): " + e.getMessage());
        }
    }

    private static void print(final Source src) {
        final HttpHeaders h = src.headers();
        System.out.println("  type=" + src.type() + " qualities=" + src.qualities().size()
                + " | title=" + src.metadata().title() + " | author=" + src.metadata().author());
        System.out.println("  UA=" + (h == null ? "(none)" : h.value("User-Agent")));
        for (final Quality q: src.qualities()) {
            final String u = q.uri().toString();
            System.out.println("  " + q.width() + "x" + q.height() + " -> " + u.substring(0, Math.min(90, u.length())));
        }
        if (src.audioTracks() != null) {
            for (final Track a: src.audioTracks()) {
                final String u = a.uri().toString();
                System.out.println("  audio -> " + u.substring(0, Math.min(90, u.length())));
            }
        }
        if (src.subtitleTracks() != null) {
            System.out.println("  subtitles=" + src.subtitleTracks().size());
        }
    }
}
