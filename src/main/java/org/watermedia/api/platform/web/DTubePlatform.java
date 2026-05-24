package org.watermedia.api.platform.web;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.HlsTool;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

public class DTubePlatform implements IPlatform {
    public static final String NAME = "D.tube";
    private static final Marker IT = MarkerManager.getMarker(DTubePlatform.class.getSimpleName());

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
    public PlatformData getData(final URI uri) throws Exception {
        try (final NetRequest req = NetRequest.create(uri).method("GET").accept("text/html").send()) {
            if (req.statusCode() != 200) throw new IOException("HTTP " + req.statusCode() + " for " + uri);
            final String html = req.readAllAsString();

            final String videoUrl = extractRequired(html, VIDEO_URL, "video_url", uri);
            final URI hlsUri = new URI(videoUrl);

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
            final Metadata metadata = new Metadata(title, description, publishedAt, duration, author);

            final List<DataQuality> variants = new ArrayList<>();
            final var r = fetchHls(hlsUri);
            if (r instanceof final HlsTool.MasterResult master) {
                for (final var variant: master.variants()) {
                    variants.add(new DataQuality(hlsUri.resolve(variant.uri()), variant.width(), variant.height()));
                }
            } else if (r instanceof final HlsTool.ErrorResult error) {
                LOGGER.warn(IT, "Failed to parse D.tube HLS playlist: {}, falling back to direct URL", error.message());
                variants.add(new DataQuality(hlsUri, 0, 0));
            } else {
                variants.add(new DataQuality(hlsUri, 0, 0));
            }

            // D.tube CDN demands a browser UA + d.tube origin/referer
            final RequestHeaders headers = RequestHeaders.defaults(uri)
                    .set("User-Agent", NetRequest.UserAgent.GENERIC.value())
                    .set("Referer", ORIGIN + "/")
                    .set("Origin", ORIGIN);

            final var entry = new DataSource(MediaType.VIDEO, thumbnail, metadata,
                    headers,
                    variants.toArray(DataQuality[]::new),
                    null, null);
            return new PlatformData(Instant.now().plus(30, ChronoUnit.MINUTES), entry);
        }
    }

    private static HlsTool.Result fetchHls(final URI uri) {
        try (final NetRequest req = NetRequest.create(uri)
                .method("GET")
                .accept("application/vnd.apple.mpegurl")
                .userAgent(NetRequest.UserAgent.GENERIC)
                .referer(ORIGIN + "/")
                .header("Origin", ORIGIN)
                .connectTimeout(10_000)
                .readTimeout(10_000)
                .send()) {
            if (req.statusCode() != 200) throw new IOException("HTTP " + req.statusCode() + " for " + uri);
            return HlsTool.parse(req.readAllAsString(), uri.toString());
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
