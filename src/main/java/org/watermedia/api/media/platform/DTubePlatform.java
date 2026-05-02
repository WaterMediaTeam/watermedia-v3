package org.watermedia.api.media.platform;

import org.watermedia.api.media.MRL;
import org.watermedia.tools.HlsTool;
import org.watermedia.tools.NetTool;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

public class DTubePlatform implements IPlatform {
    public static final String NAME = "D.tube";

    private static final String ORIGIN = "https://d.tube";
    private static final Pattern VIDEO_URL = Pattern.compile("video_url:\\s*\"([^\"]+)\"");
    private static final Pattern THUMBNAIL_URL = Pattern.compile("thumbnail_url:\\s*\"([^\"]+)\"");
    private static final Pattern TITLE = Pattern.compile("title:\\s*\"([^\"]+)\"");
    private static final Pattern DESCRIPTION = Pattern.compile("description:\\s*\"([^\"]*)\"");
    private static final Pattern DURATION = Pattern.compile("duration:\\s*(\\d+)");
    private static final Pattern CREATED_AT = Pattern.compile("created_at:\\s*\"([^\"]+)\"");
    private static final Pattern USERNAME = Pattern.compile("username:\\s*\"([^\"]+)\"");

    @Override
    public String name() { return NAME; }

    @Override
    public boolean validate(final URI uri) {
        final String host = uri.getHost();
        return host != null && (host.equals("d.tube") || host.equals("www.d.tube"));
    }

    @Override
    public Result getSources(final URI uri) throws Exception {
        final HttpURLConnection conn = NetTool.connectToHTTP(uri, "GET", "text/html");
        try {
            NetTool.validateHTTP200(conn.getResponseCode(), uri);
            final String html = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            final String videoUrl = extractRequired(html, VIDEO_URL, "video_url", uri);
            final URI hlsUri = new URI(videoUrl);

            final var sourceBuilder = new MRL.SourceBuilder(MRL.MediaType.VIDEO);

            final String title = extract(html, TITLE);
            final String description = extract(html, DESCRIPTION);
            final String thumbnailUrl = extract(html, THUMBNAIL_URL);
            final URI thumbnail = thumbnailUrl != null ? new URI(thumbnailUrl) : null;

            final String createdAt = extract(html, CREATED_AT);
            final Instant publishedAt = createdAt != null ? Instant.parse(createdAt) : null;

            long duration = 0;
            final String durationStr = extract(html, DURATION);
            if (durationStr != null) {
                duration = Long.parseLong(durationStr) * 1000L;
            }

            final String author = extract(html, USERNAME);

            sourceBuilder.metadata(new MRL.Metadata(title, description, thumbnail, publishedAt, duration, author));

            final var r = fetchHls(hlsUri);
            if (r instanceof final HlsTool.MasterResult master) {
                for (final var variant : master.variants()) {
                    sourceBuilder.quality(MRL.Quality.of(variant.width(), variant.height()), hlsUri.resolve(variant.uri()));
                }
            } else if (r instanceof final HlsTool.ErrorResult error) {
                LOGGER.warn(IT, "Failed to parse D.tube HLS playlist: {}, falling back to direct URL", error.message());
                sourceBuilder.quality(MRL.Quality.MEDIUM, hlsUri);
            } else {
                sourceBuilder.quality(MRL.Quality.MEDIUM, hlsUri);
            }

            return new Result(Instant.now().plus(30, ChronoUnit.MINUTES), sourceBuilder.build());
        } finally {
            conn.disconnect();
        }
    }

    private static HlsTool.Result fetchHls(final URI uri) {
        try {
            final var http = NetTool.connectToHTTP(uri, "GET", "application/vnd.apple.mpegurl");
            http.setRequestProperty("User-Agent", NetTool.BROWSER_UA);
            http.setRequestProperty("Referer", ORIGIN + "/");
            http.setRequestProperty("Origin", ORIGIN);
            http.setConnectTimeout(10_000);
            http.setReadTimeout(10_000);
            http.setInstanceFollowRedirects(true);

            try {
                NetTool.validateHTTP200(http.getResponseCode(), uri);
                final var response = http.getInputStream().readAllBytes();
                return HlsTool.parse(new String(response, StandardCharsets.UTF_8), uri.toString());
            } finally {
                http.disconnect();
            }
        } catch (final Exception e) {
            return new HlsTool.ErrorResult("Fetch error: " + e.getMessage(), e);
        }
    }

    private static String extract(final String html, final Pattern pattern) {
        final Matcher m = pattern.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private static String extractRequired(final String html, final Pattern pattern, final String field, final URI uri) {
        final String value = extract(html, pattern);
        if (value == null) {
            throw new IllegalStateException("No " + field + " found in D.tube page: " + uri);
        }
        return value;
    }
}
