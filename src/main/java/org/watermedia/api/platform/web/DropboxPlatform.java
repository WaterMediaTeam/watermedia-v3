package org.watermedia.api.platform.web;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;

import java.net.URI;
import java.util.List;

import static org.watermedia.WaterMedia.LOGGER;

public final class DropboxPlatform implements Platform {
    public static final String NAME = "Dropbox";
    private static final Marker IT = MarkerManager.getMarker(DropboxPlatform.class.getSimpleName());
    private static final String[] HOSTS = { "dropbox.com", "www.dropbox.com" };

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws Exception {
        final String query = uri.getRawQuery();
        if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS) || query == null) return null;

        // dl=0 OPENS THE PREVIEW PAGE AND dl=1 SERVES THE RAW FILE THROUGH THE CDN; ONLY THE WHOLE
        // PARAMETER COUNTS, A dl=01 OR xdl=0 LOOKALIKE IS NOT A SHARE LINK
        final String[] params = query.split("&");
        boolean preview = false;
        for (int i = 0; i < params.length; i++) {
            if (!params[i].equals("dl=0")) continue;
            params[i] = "dl=1";
            preview = true;
        }
        if (!preview) return null;
        final URI directUri = URI.create(uri.toString().replace("?" + query, "?" + String.join("&", params)));

        try (final NetRequest req = NetRequest.create(directUri).method("GET").accept(NetRequest.ACCEPT_ANY).send()) {
            if (req.statusCode() != 200)
                throw new PlatformException(NAME, "Direct download for " + directUri + " returned HTTP " + req.statusCode());

            final MediaType type = MediaType.of(req.contentType());
            LOGGER.debug(IT, "Dropbox resolved {} (content-type {}) for {}", directUri, req.contentType(), uri);

            final var entry = new Source(type, null, null,
                    HttpHeaders.defaults(uri),
                    List.of(new Quality(directUri, 0, 0)),
                    null, null);
            return new Resolution(null, entry);
        }
    }
}
