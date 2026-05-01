package org.watermedia.api.media.platform;

import com.google.gson.Gson;
import org.watermedia.api.media.MRL;
import org.watermedia.tools.NetTool;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

public class SendvidPlatform implements IPlatform {
    public static final String NAME = "Sendvid";
    private static final String STATUS_API = "https://sendvid.com/api/v1/videos/%s/status.json";
    private static final Pattern PATTERN_SOURCE = Pattern.compile("var\\s+video_source\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern PATTERN_POSTER = Pattern.compile("var\\s+video_poster\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern PATTERN_DURATION = Pattern.compile("var\\s+video_duration\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern PATTERN_VALIDTO = Pattern.compile("[?&]validto=(\\d+)");
    private static final int MAX_QUEUE = 2;
    private static final Gson GSON = new Gson();

    @Override
    public String name() { return NAME; }

    @Override
    public boolean validate(final URI uri) {
        final String host = uri.getHost();
        return host != null && (host.equals("sendvid.com") || host.equals("www.sendvid.com"));
    }

    @Override
    public Result getSources(final URI uri) throws Exception {
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

        final HttpURLConnection conn = NetTool.connectToHTTP(uri, "GET", "text/html");
        try {
            NetTool.validateHTTP200(conn.getResponseCode(), uri);

            final String html = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            final Matcher sourceMatcher = PATTERN_SOURCE.matcher(html);
            if (!sourceMatcher.find()) {
                throw new IllegalStateException("No video_source found in Sendvid page: " + uri);
            }

            final URI videoUri = new URI(sourceMatcher.group(1));
            final var sourceBuilder = new MRL.SourceBuilder(MRL.MediaType.VIDEO).uri(videoUri);

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

            sourceBuilder.metadata(new MRL.Metadata(null, null, thumbnailUri, null, durationMs, null));

            Instant expires = null;
            final Matcher validtoMatcher = PATTERN_VALIDTO.matcher(videoUri.toString());
            if (validtoMatcher.find()) {
                expires = Instant.ofEpochSecond(Long.parseLong(validtoMatcher.group(1)));
            }

            return new Result(expires, sourceBuilder.build());
        } finally {
            conn.disconnect();
        }
    }

    private StatusResponse fetchStatus(final URI statusUri) throws Exception {
        final HttpURLConnection conn = NetTool.connectToHTTP(statusUri, "GET", "application/json");
        try {
            NetTool.validateHTTP200(conn.getResponseCode(), statusUri);
            final String json = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return GSON.fromJson(json, StatusResponse.class);
        } finally {
            conn.disconnect();
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
