package org.watermedia.api.platform.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.api.util.NetRequest;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OdyseePlatform implements IPlatform {
    public static final String NAME = "Odysee";
    private static final Pattern JSON_LD_PATTERN = Pattern.compile(
            "<script\\s+type=\"application/ld\\+json\"\\s*>\\s*([\\s\\S]*?)\\s*</script>"
    );

    @Override
    public String name() { return NAME; }

    @Override
    public boolean validate(final URI uri) {
        final String host = uri.getHost();
        return host != null && (host.equals("odysee.com") || host.equals("www.odysee.com"));
    }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        final String path = uri.getRawPath();
        if (path == null || path.length() < 2) {
            throw new IllegalArgumentException("Invalid Odysee URL: " + uri);
        }

        final URI embedUri = new URI("https://odysee.com/%24/embed/" + path.substring(1));
        try (final NetRequest req = NetRequest.create(embedUri).method("GET").accept("text/html").send()) {
            if (req.statusCode() != 200) throw new IOException("HTTP " + req.statusCode() + " for " + embedUri);
            final String html = req.readAllAsString();

            final Matcher matcher = JSON_LD_PATTERN.matcher(html);
            if (!matcher.find()) {
                throw new IllegalStateException("No JSON-LD found in Odysee page: " + uri);
            }

            final JsonObject video = JsonParser.parseString(matcher.group(1)).getAsJsonObject();
            if (!"VideoObject".equals(jsonString(video, "@type"))) {
                throw new IllegalStateException("JSON-LD is not a VideoObject in Odysee page: " + uri);
            }

            final String contentUrl = video.get("contentUrl").getAsString();
            final URI videoUri = new URI(contentUrl);

            final String title = jsonString(video, "name");
            final String description = jsonString(video, "description");
            final String thumbnailUrl = jsonString(video, "thumbnailUrl");
            final URI thumbnail = thumbnailUrl != null ? new URI(thumbnailUrl) : null;

            final String uploadDate = jsonString(video, "uploadDate");
            final Instant publishedAt = uploadDate != null ? Instant.parse(uploadDate) : null;

            long durationMs = 0;
            final String durationStr = jsonString(video, "duration");
            if (durationStr != null) {
                durationMs = Duration.parse(durationStr).toMillis();
            }

            String author = null;
            if (video.has("author") && video.get("author").isJsonObject()) {
                author = jsonString(video.getAsJsonObject("author"), "name");
            }

            final Metadata metadata = new Metadata(title, description, publishedAt, durationMs, author);
            final var entry = new DataSource(MediaType.VIDEO, thumbnail, metadata,
                    RequestHeaders.defaults(uri),
                    new DataQuality[] {new DataQuality(videoUri, 0, 0)},
                    null, null);

            return new PlatformData(null, entry);
        }
    }

    private static String jsonString(final JsonObject obj, final String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }
}
