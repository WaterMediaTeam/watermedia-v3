package org.watermedia.api.platform.web;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

public class LightshotPlatform implements IPlatform {
    public static final String NAME = "Lightshot";
    private static final Marker IT = MarkerManager.getMarker(LightshotPlatform.class.getSimpleName());
    private static final Pattern IMG_PATTERN = Pattern.compile("<img[^>]*class=\"no-click screenshot-image\"[^>]*src=\"(https://[^\"]+)\"");
    private static final String[] HOSTS = { "prnt.sc" };

    @Override
    public String name() { return NAME; }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS)) return null;

        try (final NetRequest req = NetRequest.create(uri).method("GET").accept("text/html").send()) {
            if (req.statusCode() != 200) throw new PlatformException(LightshotPlatform.class, "Page request for " + uri + " returned HTTP " + req.statusCode());

            final String html = req.readAllAsString();
            final Matcher matcher = IMG_PATTERN.matcher(html);
            if (!matcher.find())
                throw new PlatformException(LightshotPlatform.class, "Screenshot image not found in page (expired, removed, or markup changed): " + uri);

            final String imageUrl = matcher.group(1);
            LOGGER.debug(IT, "Lightshot resolved screenshot image {} for {}", imageUrl, uri);

            final var entry = new DataSource(MediaType.IMAGE, null, null,
                    RequestHeaders.defaults(uri),
                    new DataQuality[] {new DataQuality(new URI(imageUrl), 0, 0)},
                    null, null);
            return new PlatformData(null, entry);
        }
    }
}
