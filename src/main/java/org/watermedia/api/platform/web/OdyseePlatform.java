package org.watermedia.api.platform.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.JSONTool;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;
import static org.watermedia.tools.JSONTool.*;

public final class OdyseePlatform implements Platform {
    public static final String NAME = "Odysee";
    private static final Marker IT = MarkerManager.getMarker(OdyseePlatform.class.getSimpleName());
    private static final Pattern JSON_LD_PATTERN = Pattern.compile(
            "<script\\s+type=\"application/ld\\+json\"\\s*>\\s*([\\s\\S]*?)\\s*</script>"
    );
    private static final String[] HOSTS = { "odysee.com", "www.odysee.com" };

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws PlatformException {
        if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS)) return null;

        final String path = uri.getRawPath();
        if (path == null || path.length() < 2) throw PlatformException.unsupported(NAME, uri);

        try {
            final URI embedUri = URI.create("https://odysee.com/%24/embed/" + path.substring(1));
            LOGGER.debug(IT, "Odysee fetching embed page {} for {}", embedUri, uri);
            try (final NetRequest req = NetRequest.create(embedUri).method("GET").accept("text/html").send()) {
                if (req.statusCode() != 200) throw new PlatformException(NAME, "Embed request for " + embedUri + " returned HTTP " + req.statusCode());
                final String html = req.text();

                final Matcher matcher = JSON_LD_PATTERN.matcher(html);
                if (!matcher.find())
                    throw new PlatformException(NAME, "JSON-LD block not found in embed page (geo-blocked, removed, or markup changed): " + uri);

                final JsonElement ld = JsonParser.parseString(matcher.group(1));
                if (!ld.isJsonObject()) throw new PlatformException(NAME, "JSON-LD block is not an object (not a playable video): " + uri);
                final JsonObject video = ld.getAsJsonObject();
                final String ldType = str(video, "@type");
                if (!"VideoObject".equals(ldType))
                    throw new PlatformException(NAME, "JSON-LD is a '" + ldType + "', not a VideoObject (not a playable video): " + uri);

                final String contentUrl = str(video, "contentUrl");
                if (contentUrl == null)
                    throw new PlatformException(NAME, "VideoObject is missing 'contentUrl' (stream not published yet?): " + uri);
                final URI videoUri = URI.create(contentUrl);

                final String title = str(video, "name");
                final String description = str(video, "description");
                final URI thumbnail = JSONTool.uri(video, "thumbnailUrl");
                if (title == null) LOGGER.warn(IT, "Odysee VideoObject has no 'name' (title) for {}", uri);

                Instant publishedAt = null;
                final String uploadDate = str(video, "uploadDate");
                if (uploadDate != null) {
                    try { publishedAt = Instant.parse(uploadDate); }
                    catch (final DateTimeParseException e) { LOGGER.warn(IT, "Odysee unparseable uploadDate '{}' for {}", uploadDate, uri, e); }
                }

                long durationMs = 0;
                final String durationStr = str(video, "duration");
                if (durationStr != null) {
                    try { durationMs = Math.max(0L, Duration.parse(durationStr).toMillis()); }
                    catch (final DateTimeParseException e) { LOGGER.warn(IT, "Odysee unparseable ISO-8601 duration '{}' for {}", durationStr, uri, e); }
                }

                final String author = video.has("author") && video.get("author").isJsonObject() ? str(video.getAsJsonObject("author"), "name") : null;

                LOGGER.debug(IT, "Odysee resolved contentUrl {} (title='{}', durationMs={}) for {}", videoUri, title, durationMs, uri);
                final Metadata metadata = new Metadata(title, description, publishedAt, durationMs, author);
                final var entry = new Source(MediaType.VIDEO, thumbnail, metadata,
                        HttpHeaders.defaults(uri),
                        List.of(new Quality(videoUri, 0, 0)),
                        null, null);

                return new Resolution(null, entry);
            }
        } catch (final PlatformException e) {
            throw e;
        } catch (final IOException | RuntimeException e) {
            throw new PlatformException(NAME, "Failed to resolve " + uri, e);
        }
    }
}
