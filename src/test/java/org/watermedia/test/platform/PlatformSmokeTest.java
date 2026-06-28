package org.watermedia.test.platform;

import org.watermedia.WaterMedia;
import org.watermedia.api.platform.DataQuality;
import org.watermedia.api.platform.DataSlave;
import org.watermedia.api.platform.DataSource;
import org.watermedia.api.platform.IPlatform;
import org.watermedia.api.platform.PlatformData;
import org.watermedia.api.platform.web.YouTubePlatform;
import org.watermedia.api.platform.web.YtDlpPlatform;
import org.watermedia.api.util.RequestHeaders;

import java.net.URI;
import java.util.List;

/**
 * MANUAL verification harness (not a JUnit test): resolves URLs through the actual yt-dlp platforms,
 * exactly as {@code PlatformAPI} would — so host routing, yt-dlp extraction and the resulting
 * {@link DataSource} are all exercised end-to-end on this machine's own network (useful when a site
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
        final List<IPlatform> platforms = List.of(
                new YtDlpPlatform(),
                new YouTubePlatform());

        final String[] urls = args.length > 0 ? args : DEFAULTS;
        for (final String url : urls) {
            resolve(platforms, url);
        }
        if (args.length == 0) {
            System.out.println("\nTip: test Facebook/Instagram with a public link: gradle platformSmoke -Purl=<url>");
        }
        System.exit(0);
    }

    private static void resolve(final List<IPlatform> platforms, final String url) {
        System.out.println("\n=== " + url + " ===");
        final URI uri = URI.create(url);
        final long started = System.currentTimeMillis();
        try {
            for (final IPlatform platform : platforms) {
                final PlatformData data = platform.getData(uri);
                if (data == null) continue; // NOT THIS PLATFORM'S HOST — KEEP PROBING
                System.out.println("platform=" + platform.name() + " entries=" + data.size()
                        + " expires=" + data.expires() + " (" + (System.currentTimeMillis() - started) + "ms)");
                for (final DataSource src : data.entries()) {
                    print(src);
                }
                return;
            }
            System.out.println("(no platform handled this URL)");
        } catch (final Exception e) {
            System.out.println("FAILED (" + (System.currentTimeMillis() - started) + "ms): " + e.getMessage());
        }
    }

    private static void print(final DataSource src) {
        final RequestHeaders h = src.headers();
        System.out.println("  type=" + src.type() + " variants=" + src.variants().length
                + " | title=" + src.metadata().title() + " | author=" + src.metadata().author());
        System.out.println("  UA=" + (h == null ? "(none)" : h.get("User-Agent")));
        for (final DataQuality q : src.variants()) {
            final String u = q.uri().toString();
            System.out.println("  " + q.width() + "x" + q.height() + " -> " + u.substring(0, Math.min(90, u.length())));
        }
        if (src.audioSlaves() != null) {
            for (final DataSlave a : src.audioSlaves()) {
                final String u = a.uri().toString();
                System.out.println("  audio -> " + u.substring(0, Math.min(90, u.length())));
            }
        }
        if (src.subSlaves() != null) {
            System.out.println("  subtitles=" + src.subSlaves().size());
        }
    }
}
