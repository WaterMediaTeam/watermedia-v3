package org.watermedia.api.platform.web;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;

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
    private static final String[] HOSTS = { "sendvid.com", "www.sendvid.com" };

    @Override
    public String name() { return NAME; }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        if (!DataTool.containsIgnoreCase(uri.getHost(), HOSTS)) return null;

        final String path = uri.getPath();
        if (path == null || path.length() < 2) {
            throw new PlatformException(SendvidPlatform.class, "Invalid URL: " + uri);
        }

        final String videoId = path.substring(1).split("[/#?]")[0];
        final URI statusUri = new URI(String.format(STATUS_API, videoId));
        LOGGER.debug(IT, "Sendvid resolving video '{}'", videoId);

        final StatusResponse status = this.fetchStatus(statusUri);
        if (!"done".equals(status.state)) {
            LOGGER.debug(IT, "Sendvid video '{}' not ready yet (state={}, queue={})", videoId, status.state, status.placeInQueue);
            if (status.placeInQueue > MAX_QUEUE) {
                throw new PlatformException(SendvidPlatform.class, "Video '" + videoId + "' is in queue position " + status.placeInQueue + " (> " + MAX_QUEUE + "), too far to wait");
            }
            this.waitForReady(statusUri, videoId);
        }

        try (final NetRequest req = NetRequest.create(uri).method("GET").accept("text/html").send()) {
            if (req.statusCode() != 200) throw new PlatformException(SendvidPlatform.class, "Page request for video '" + videoId + "' returned HTTP " + req.statusCode());

            final String html = req.readAllAsString();

            final Matcher sourceMatcher = PATTERN_SOURCE.matcher(html);
            if (!sourceMatcher.find()) {
                throw new PlatformException(SendvidPlatform.class, "video_source not found in page for video '" + videoId + "' (removed or markup changed): " + uri);
            }

            final URI videoUri = new URI(sourceMatcher.group(1));

            URI thumbnailUri = null;
            final Matcher posterMatcher = PATTERN_POSTER.matcher(html);
            if (posterMatcher.find()) {
                thumbnailUri = new URI(posterMatcher.group(1));
            } else {
                LOGGER.warn(IT, "Sendvid video '{}' has no video_poster (thumbnail)", videoId);
            }

            // PREFER THE PAGE'S video_duration; FALL BACK TO THE STATUS API VALUE
            long durationMs = (long) (status.duration * 1000);
            final Matcher durationMatcher = PATTERN_DURATION.matcher(html);
            if (durationMatcher.find()) {
                durationMs = (long) (Double.parseDouble(durationMatcher.group(1)) * 1000);
            }

            final Metadata metadata = new Metadata(null, null, null, durationMs, null);

            // THE SIGNED CDN URL CARRIES ITS OWN EXPIRY (validto EPOCH SECONDS); HONOR IT FOR CACHE INVALIDATION
            Instant expires = null;
            final Matcher validtoMatcher = PATTERN_VALIDTO.matcher(videoUri.toString());
            if (validtoMatcher.find()) {
                expires = Instant.ofEpochSecond(Long.parseLong(validtoMatcher.group(1)));
            }

            LOGGER.info(IT, "Sendvid resolved video '{}' (durationMs={}, expires={})", videoId, durationMs, expires);
            final var entry = new DataSource(MediaType.VIDEO, thumbnailUri, metadata,
                    RequestHeaders.defaults(uri),
                    new DataQuality[] {new DataQuality(videoUri, 0, 0)},
                    null, null);
            return new PlatformData(expires, entry);
        }
    }

    private StatusResponse fetchStatus(final URI statusUri) throws Exception {
        try (final NetRequest req = NetRequest.create(statusUri).method("GET").accept("application/json").send()) {
            if (req.statusCode() != 200) throw new PlatformException(SendvidPlatform.class, "Status API returned HTTP " + req.statusCode() + " for " + statusUri);
            final StatusResponse status = req.json(StatusResponse.class);
            if (status == null) throw new PlatformException(SendvidPlatform.class, "Status API returned an empty or non-JSON body for " + statusUri);
            return status;
        }
    }

    private void waitForReady(final URI statusUri, final String videoId) throws Exception {
        for (int i = 0; i < 30; i++) {
            Thread.sleep(2000);
            final StatusResponse status = this.fetchStatus(statusUri);
            LOGGER.debug(IT, "Sendvid {} status: {} ({}%)", videoId, status.state, status.progress);
            if ("done".equals(status.state)) return;
            if (status.placeInQueue > MAX_QUEUE) {
                throw new PlatformException(SendvidPlatform.class, "Video '" + videoId + "' queue position grew to " + status.placeInQueue);
            }
        }
        throw new PlatformException(SendvidPlatform.class, "Video '" + videoId + "' did not become ready after 60 seconds");
    }

    private static class StatusResponse {
        int progress;
        @com.google.gson.annotations.SerializedName("place_in_queue")
        int placeInQueue;
        String state;
        double duration;
    }
}
