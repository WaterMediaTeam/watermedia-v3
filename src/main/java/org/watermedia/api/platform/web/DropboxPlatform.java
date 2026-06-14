package org.watermedia.api.platform.web;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;

import java.net.URI;

import static org.watermedia.WaterMedia.LOGGER;

public class DropboxPlatform implements IPlatform {
    public static final String NAME = "Dropbox";
    private static final Marker IT = MarkerManager.getMarker(DropboxPlatform.class.getSimpleName());
    private static final String[] HOSTS = { "dropbox.com", "www.dropbox.com" };

    @Override
    public String name() { return NAME; }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        if (!DataTool.containsIgnoreCase(uri.getHost(), HOSTS) || !DataTool.contains(uri.getQuery(), "dl=0"))
            return null;

        // dl=0 OPENS THE PREVIEW PAGE; dl=1 SERVES THE RAW FILE THROUGH THE CDN
        final URI directUri = new URI(uri.toString().replace("dl=0", "dl=1"));

        try (final NetRequest req = NetRequest.create(directUri).method("GET").accept("*/*").send()) {
            if (req.statusCode() != 200)
                throw new PlatformException(DropboxPlatform.class, "Direct download for " + directUri + " returned HTTP " + req.statusCode());

            final MediaType type = MediaType.of(req.contentType());
            LOGGER.debug(IT, "Dropbox resolved {} (content-type {}) for {}", directUri, req.contentType(), uri);

            final var entry = new DataSource(type, null, null,
                    RequestHeaders.defaults(uri),
                    new DataQuality[] {new DataQuality(directUri, 0, 0)},
                    null, null);
            return new PlatformData(null, entry);
        }
    }
}
