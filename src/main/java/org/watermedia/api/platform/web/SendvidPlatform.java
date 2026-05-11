package org.watermedia.api.platform.web;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.api.util.NetRequest;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

public class SendvidPlatform implements IPlatform {
    public static final String NAME = "Sendvid";
    private static final Marker IT = MarkerManager.getMarker(SendvidPlatform.class.getSimpleName());
    private static final String STATUS_API = "https://sendvid.com/api/v1/videos/%s/status.json";
    private static final Pattern PATTERN_SOURCE = Pattern.compile("var\\s+video_source\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern PATTERN_POSTER = Pattern.compile("var\\s+video_poster\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern PATTERN_DURATION = Pattern.compile("var\\s+video_duration\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern PATTERN_VALIDTO = Pattern.compile("[?&]validto=(\\d+)");
    private static final int MAX_QUEUE = 2;

    @Override
    public String name() { return NAME; }

    @Override
    public boolean validate(final URI uri) {
        final String host = uri.getHost();
        return host != null && (host.equals("sendvid.com") || host.equals("www.sendvid.com"));
    }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        final String path = uri.getPath();
        if (path == null || path.length() < 2) {
            throw new IllegalArgumentException("Invalid Sendvid URL: " + uri);
        }

        final String videoId = path.substring(1).split("[/#?]")[0];
        final URI statusUri = new URI(String.format(STATUS_API, videoId));

        final StatusResponse status = this.fetchStatus(statusUri);
        if (!"done".equals(status.state)) {
            if (status.placeInQueue > MAX_QUEUE) {
                throw new IllegalStateException("Sendvid video " + videoId + " is in queue position " + status.placeInQueue + ", too far to wait");
            }
            this.waitForReady(statusUri, videoId);
        }

        try (final NetRequest req = NetRequest.create(uri).method("GET").accept("text/html").send()) {
            if (req.statusCode() != 200) throw new IOException("HTTP " + req.statusCode() + " for " + uri);

            final String html = req.readAllAsString();

            final Matcher sourceMatcher = PATTERN_SOURCE.matcher(html);
            if (!sourceMatcher.find()) {
                throw new IllegalStateException("No video_source found in Sendvid page: " + uri);
            }

            final URI videoUri = new URI(sourceMatcher.group(1));

            URI thumbnailUri = null;
            final Matcher posterMatcher = PATTERN_POSTER.matcher(html);
            if (posterMatcher.find()) {
                thumbnailUri = new URI(posterMatcher.group(1));
            }

            long durationMs = (long) (status.duration * 1000);
            final Matcher durationMatcher = PATTERN_DURATION.matcher(html);
            if (durationMatcher.find()) {
                durationMs = (long) (Double.parseDouble(durationMatcher.group(1)) * 1000);
            }

            final Metadata metadata = new Metadata(null, null, null, durationMs, null);

            Instant expires = null;
            final Matcher validtoMatcher = PATTERN_VALIDTO.matcher(videoUri.toString());
            if (validtoMatcher.find()) {
                expires = Instant.ofEpochSecond(Long.parseLong(validtoMatcher.group(1)));
            }

            final var entry = new DataSource(MediaType.VIDEO, thumbnailUri, metadata,
                    RequestHeaders.defaults(uri),
                    new DataQuality[] {new DataQuality(videoUri, 0, 0)},
                    null, null);
            return new PlatformData(expires, entry);
        }
    }

    private StatusResponse fetchStatus(final URI statusUri) throws Exception {
        try (final NetRequest req = NetRequest.create(statusUri).method("GET").accept("application/json").send()) {
            if (req.statusCode() != 200) throw new IOException("HTTP " + req.statusCode() + " for " + statusUri);
            return req.json(StatusResponse.class);
        }
    }

    private void waitForReady(final URI statusUri, final String videoId) throws Exception {
        for (int i = 0; i < 30; i++) {
            Thread.sleep(2000);
            final StatusResponse status = this.fetchStatus(statusUri);
            LOGGER.debug(IT, "Sendvid {} status: {} ({}%)", videoId, status.state, status.progress);
            if ("done".equals(status.state)) return;
            if (status.placeInQueue > MAX_QUEUE) {
                throw new IllegalStateException("Sendvid video " + videoId + " queue position grew to " + status.placeInQueue);
            }
        }
        throw new IllegalStateException("Sendvid video " + videoId + " did not become ready after 60 seconds");
    }

    private static class StatusResponse {
        int progress;
        @com.google.gson.annotations.SerializedName("place_in_queue")
        int placeInQueue;
        String state;
        double duration;
    }
}
