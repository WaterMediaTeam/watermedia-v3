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

import static org.watermedia.WaterMedia.LOGGER;

public final class DropboxPlatform implements Platform {
    public static final String NAME = "Dropbox";
    private static final Marker IT = MarkerManager.getMarker(DropboxPlatform.class.getSimpleName());
    private static final String[] HOSTS = { "dropbox.com", "www.dropbox.com" };
    // SHARE LINKS: /s/<id>/<name>, /sh/<id>/<key>/<name> AND /scl/fi/<id>/<name>?rlkey=...; FOLDERS AND APP PAGES ARE NOT PLAYABLE
    private static final String[] SHARE_PREFIXES = { "/s/", "/sh/", "/scl/fi/" };

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws PlatformException {
        if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS)) return null;
        final String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (!DataTool.startsWith(path, SHARE_PREFIXES)) throw PlatformException.unsupported(NAME, uri);

        // dl=0 OPENS THE PREVIEW PAGE WHILE dl=1 AND raw=1 SERVE THE FILE THROUGH THE CDN; ONLY A WHOLE PARAMETER
        // COUNTS (dl=01 OR xdl=0 ARE NOT IT) AND A SHARE LINK WITHOUT ONE GETS dl=1 APPENDED
        final String query = uri.getRawQuery();
        final String[] params = query == null || query.isEmpty() ? new String[0] : query.split("&");
        boolean direct = false;
        for (int i = 0; i < params.length; i++) {
            if (params[i].equals("dl=0")) params[i] = "dl=1";
            if (params[i].equals("dl=1") || params[i].equals("raw=1")) direct = true;
        }
        String rebuilt = String.join("&", params);
        if (!direct) rebuilt = rebuilt.isEmpty() ? "dl=1" : rebuilt + "&dl=1";
        final URI directUri = URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + path + "?" + rebuilt);

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
        } catch (final PlatformException e) {
            throw e;
        } catch (final IOException | RuntimeException e) {
            throw new PlatformException(NAME, "Failed to resolve " + uri, e);
        }
    }
}
