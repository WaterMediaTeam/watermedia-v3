package org.watermedia.api.platform.web;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

public final class LightshotPlatform implements Platform {
    public static final String NAME = "Lightshot";
    private static final Marker IT = MarkerManager.getMarker(LightshotPlatform.class.getSimpleName());
    private static final Pattern IMG_PATTERN = Pattern.compile("<img[^>]*class=\"no-click screenshot-image\"[^>]*src=\"(https://[^\"]+)\"");
    private static final String[] HOSTS = { "prnt.sc" };

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws PlatformException {
        if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS)) return null;
        if (uri.getPath() == null || uri.getPath().length() < 2) throw PlatformException.unsupported(NAME, uri);

        try (final NetRequest req = NetRequest.create(uri).method("GET").accept("text/html").send()) {
            if (req.statusCode() != 200) throw new PlatformException(NAME, "Page request for " + uri + " returned HTTP " + req.statusCode());

            final String html = req.text();
            final Matcher matcher = IMG_PATTERN.matcher(html);
            if (!matcher.find())
                throw new PlatformException(NAME, "Screenshot image not found in page (expired, removed, or markup changed): " + uri);

            final String imageUrl = matcher.group(1);
            LOGGER.debug(IT, "Lightshot resolved screenshot image {} for {}", imageUrl, uri);

            final var entry = new Source(MediaType.IMAGE, null, null,
                    HttpHeaders.defaults(uri),
                    List.of(new Quality(URI.create(imageUrl), 0, 0)),
                    null, null);
            return new Resolution(null, entry);
        } catch (final PlatformException e) {
            throw e;
        } catch (final IOException | RuntimeException e) {
            throw new PlatformException(NAME, "Failed to resolve " + uri, e);
        }
    }
}
