package org.watermedia.api.platform.web;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.MPEGTools;

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
    private static final String[] HOSTS = { "d.tube", "www.d.tube" };

    @Override
    public String name() { return NAME; }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        if (!DataTool.containsIgnoreCase(uri.getHost(), HOSTS)) return null;

        try (final NetRequest req = NetRequest.create(uri).method("GET").accept("text/html").send()) {
            if (req.statusCode() != 200) throw new PlatformException(DTubePlatform.class, "Page request for " + uri + " returned HTTP " + req.statusCode());
            final String html = req.readAllAsString();

            final String videoUrl = extractRequired(html, VIDEO_URL, "video_url", uri);
            final URI hlsUri = new URI(videoUrl);
            LOGGER.debug(IT, "D.tube extracted video_url {} from {}", hlsUri, uri);

            final String title = extract(html, TITLE);
            final String description = extract(html, DESCRIPTION);
            final String thumbnailUrl = extract(html, THUMBNAIL_URL);
            final URI thumbnail = thumbnailUrl != null ? new URI(thumbnailUrl) : null;
            if (title == null) LOGGER.warn(IT, "D.tube page has no title for {}", uri);

            // METADATA IS BEST-EFFORT: A MALFORMED TIMESTAMP MUST NOT ABORT AN OTHERWISE PLAYABLE VIDEO
            Instant publishedAt = null;
            final String createdAt = extract(html, CREATED_AT);
            if (createdAt != null) {
                try { publishedAt = Instant.parse(createdAt); }
                catch (final Exception e) { LOGGER.warn(IT, "D.tube unparseable created_at '{}' for {}", createdAt, uri); }
            }

            long duration = 0;
            final String durationStr = extract(html, DURATION);
            if (durationStr != null) {
                duration = Long.parseLong(durationStr) * 1000L;
            }

            final String author = extract(html, USERNAME);
            final Metadata metadata = new Metadata(title, description, publishedAt, duration, author);

            final List<DataQuality> variants = new ArrayList<>();
            try {
                // RENDITION URLS COME BACK ALREADY ABSOLUTE (RESOLVED AGAINST hlsUri BY MPEGTools)
                final MPEGTools.Playlist r = fetchHls(hlsUri);
                if (r instanceof final MPEGTools.Master master) {
                    for (final MPEGTools.Variant variant: master.variants()) {
                        variants.add(new DataQuality(variant.uri(), variant.width(), variant.height()));
                    }
                    LOGGER.debug(IT, "D.tube parsed {} HLS rendition(s) for {}", master.variants().size(), uri);
                } else {
                    LOGGER.warn(IT, "D.tube HLS response for {} was not a master playlist — falling back to direct URL", uri);
                }
            } catch (final IOException e) {
                LOGGER.warn(IT, "D.tube failed to parse HLS playlist for {}: {} — falling back to direct URL", uri, e.getMessage());
            }
            if (variants.isEmpty()) {
                variants.add(new DataQuality(hlsUri, 0, 0));
            }

            // D.tube CDN demands a browser UA + d.tube origin/referer
            final RequestHeaders headers = RequestHeaders.defaults(uri)
                    .set("User-Agent", NetRequest.UserAgent.GENERIC.value())
                    .set("Referer", ORIGIN + "/")
                    .set("Origin", ORIGIN);

            LOGGER.info(IT, "D.tube resolved '{}' with {} variant(s) for {}", title, variants.size(), uri);
            final var entry = new DataSource(MediaType.VIDEO, thumbnail, metadata,
                    headers,
                    variants.toArray(DataQuality[]::new),
                    null, null);
            return new PlatformData(Instant.now().plus(30, ChronoUnit.MINUTES), entry);
        }
    }

    // FETCHES THE HLS PLAYLIST WITH D.tube CDN HEADERS (BROWSER UA + d.tube ORIGIN/REFERER).
    // KEPT SEPARATE FROM MPEGTools.fetch BECAUSE THAT HELPER CANNOT CARRY THE Origin/Referer HEADERS THE CDN DEMANDS.
    private static MPEGTools.Playlist fetchHls(final URI uri) throws IOException {
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
            return MPEGTools.parse(req.readAllAsString(), uri);
        }
    }

    private static String extract(final String html, final Pattern pattern) {
        final Matcher m = pattern.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private static String extractRequired(final String html, final Pattern pattern, final String field, final URI uri) throws PlatformException {
        final String value = extract(html, pattern);
        if (value == null) {
            throw new PlatformException(DTubePlatform.class, "No " + field + " found in page: " + uri);
        }
        return value;
    }
}
