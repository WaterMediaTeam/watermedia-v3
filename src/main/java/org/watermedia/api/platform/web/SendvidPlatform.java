package org.watermedia.api.platform.web;

import com.google.gson.annotations.SerializedName;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

public final class SendvidPlatform implements Platform {
    public static final String NAME = "Sendvid";
    private static final Marker IT = MarkerManager.getMarker(SendvidPlatform.class.getSimpleName());
    private static final String STATUS_API = "https://sendvid.com/api/v1/videos/%s/status.json";
    private static final Pattern PATTERN_SOURCE = Pattern.compile("var\\s+video_source\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern PATTERN_POSTER = Pattern.compile("var\\s+video_poster\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern PATTERN_DURATION = Pattern.compile("var\\s+video_duration\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern PATTERN_VALIDTO = Pattern.compile("[?&]validto=(\\d+)");
    private static final int MAX_QUEUE = 2;
    // A QUEUED UPLOAD IS POLLED UNTIL IT IS READY OR THE ATTEMPTS RUN OUT
    private static final int MAX_POLLS = 30;
    private static final long POLL_DELAY_MS = 2_000L;
    private static final String[] HOSTS = { "sendvid.com", "www.sendvid.com" };

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws Exception {
        if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS)) return null;

        final String path = uri.getPath();
        if (path == null || path.length() < 2) {
            throw new PlatformException(NAME, "Invalid URL: " + uri);
        }

        final String videoId = path.substring(1).split("[/#?]")[0];
        final URI statusUri = new URI(String.format(STATUS_API, videoId));
        LOGGER.debug(IT, "Sendvid resolving video '{}'", videoId);

        final StatusResponse status = NetRequest.fetchJson(NAME, statusUri, StatusResponse.class);
        if (!"done".equals(status.state())) {
            LOGGER.debug(IT, "Sendvid video '{}' not ready yet (state={}, queue={})", videoId, status.state(), status.placeInQueue());
            if (status.placeInQueue() > MAX_QUEUE) {
                throw new PlatformException(NAME, "Video '" + videoId + "' is in queue position " + status.placeInQueue() + " (> " + MAX_QUEUE + "), too far to wait");
            }
            this.waitForReady(statusUri, videoId);
        }

        try (final NetRequest req = NetRequest.create(uri).method("GET").accept("text/html").send()) {
            if (req.statusCode() != 200) throw new PlatformException(NAME, "Page request for video '" + videoId + "' returned HTTP " + req.statusCode());

            final String html = req.text();

            final Matcher sourceMatcher = PATTERN_SOURCE.matcher(html);
            if (!sourceMatcher.find()) {
                throw new PlatformException(NAME, "video_source not found in page for video '" + videoId + "' (removed or markup changed): " + uri);
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
            long durationMs = (long) (status.duration() * 1000);
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
            final var entry = new Source(MediaType.VIDEO, thumbnailUri, metadata,
                    HttpHeaders.defaults(uri),
                    List.of(new Quality(videoUri, 0, 0)),
                    null, null);
            return new Resolution(expires, entry);
        }
    }

    private void waitForReady(final URI statusUri, final String videoId) throws Exception {
        for (int i = 0; i < MAX_POLLS; i++) {
            Thread.sleep(POLL_DELAY_MS);
            final StatusResponse status = NetRequest.fetchJson(NAME, statusUri, StatusResponse.class);
            LOGGER.debug(IT, "Sendvid {} status: {} ({}%)", videoId, status.state(), status.progress());
            if ("done".equals(status.state())) return;
            if (status.placeInQueue() > MAX_QUEUE) {
                throw new PlatformException(NAME, "Video '" + videoId + "' queue position grew to " + status.placeInQueue());
            }
        }
        throw new PlatformException(NAME, "Video '" + videoId + "' did not become ready after 60 seconds");
    }

    private record StatusResponse(int progress, @SerializedName("place_in_queue") int placeInQueue, String state, double duration) {
    }
}
