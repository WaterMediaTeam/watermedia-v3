package org.watermedia.api.platform.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;
import static org.watermedia.tools.JsonTool.*;

public class OdyseePlatform implements IPlatform {
    public static final String NAME = "Odysee";
    private static final Marker IT = MarkerManager.getMarker(OdyseePlatform.class.getSimpleName());
    private static final Pattern JSON_LD_PATTERN = Pattern.compile(
            "<script\\s+type=\"application/ld\\+json\"\\s*>\\s*([\\s\\S]*?)\\s*</script>"
    );
    private static final String[] HOSTS = { "odysee.com", "www.odysee.com" };

    @Override
    public String name() { return NAME; }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS)) return null;

        final String path = uri.getRawPath();
        if (path == null || path.length() < 2) {
            throw new PlatformException(OdyseePlatform.class, "Invalid URL: " + uri);
        }

        final URI embedUri = new URI("https://odysee.com/%24/embed/" + path.substring(1));
        LOGGER.debug(IT, "Odysee fetching embed page {} for {}", embedUri, uri);
        try (final NetRequest req = NetRequest.create(embedUri).method("GET").accept("text/html").send()) {
            if (req.statusCode() != 200) throw new PlatformException(OdyseePlatform.class, "Embed request for " + embedUri + " returned HTTP " + req.statusCode());
            final String html = req.readAllAsString();

            final Matcher matcher = JSON_LD_PATTERN.matcher(html);
            if (!matcher.find()) {
                throw new PlatformException(OdyseePlatform.class, "JSON-LD block not found in embed page (geo-blocked, removed, or markup changed): " + uri);
            }

            final JsonObject video = JsonParser.parseString(matcher.group(1)).getAsJsonObject();
            final String ldType = str(video, "@type");
            if (!"VideoObject".equals(ldType)) {
                throw new PlatformException(OdyseePlatform.class, "JSON-LD is a '" + ldType + "', not a VideoObject (not a playable video): " + uri);
            }

            final String contentUrl = str(video, "contentUrl");
            if (contentUrl == null)
                throw new PlatformException(OdyseePlatform.class, "VideoObject is missing 'contentUrl' (stream not published yet?): " + uri);
            final URI videoUri = new URI(contentUrl);

            final String title = str(video, "name");
            final String description = str(video, "description");
            final String thumbnailUrl = str(video, "thumbnailUrl");
            final URI thumbnail = thumbnailUrl != null ? new URI(thumbnailUrl) : null;
            if (title == null) LOGGER.warn(IT, "Odysee VideoObject has no 'name' (title) for {}", uri);

            Instant publishedAt = null;
            final String uploadDate = str(video, "uploadDate");
            if (uploadDate != null) {
                try { publishedAt = Instant.parse(uploadDate); }
                catch (final DateTimeParseException e) { LOGGER.warn(IT, "Odysee unparseable uploadDate '{}' for {}", uploadDate, uri); }
            }

            long durationMs = 0;
            final String durationStr = str(video, "duration");
            if (durationStr != null) {
                try { durationMs = Duration.parse(durationStr).toMillis(); }
                catch (final DateTimeParseException e) { LOGGER.warn(IT, "Odysee unparseable ISO-8601 duration '{}' for {}", durationStr, uri); }
            }

            String author = null;
            if (video.has("author") && video.get("author").isJsonObject()) {
                author = str(video.getAsJsonObject("author"), "name");
            }

            LOGGER.debug(IT, "Odysee resolved contentUrl {} (title='{}', durationMs={}) for {}", videoUri, title, durationMs, uri);
            final Metadata metadata = new Metadata(title, description, publishedAt, durationMs, author);
            final var entry = new DataSource(MediaType.VIDEO, thumbnail, metadata,
                    RequestHeaders.defaults(uri),
                    new DataQuality[] {new DataQuality(videoUri, 0, 0)},
                    null, null);

            return new PlatformData(null, entry);
        }
    }
}
