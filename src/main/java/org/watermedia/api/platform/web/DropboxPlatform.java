package org.watermedia.api.platform.web;

import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.api.util.NetRequest;

import java.net.URI;

public class DropboxPlatform implements IPlatform {
    public static final String NAME = "Dropbox";

    @Override
    public String name() { return NAME; }

    @Override
    public boolean validate(final URI uri) {
        final String host = uri.getHost();
        final String query = uri.getQuery();
        return host != null && host.contains("dropbox.com") && query != null && query.contains("dl=0");
    }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        final URI directUri = new URI(uri.toString().replace("dl=0", "dl=1"));

        try (final NetRequest req = NetRequest.create(directUri).method("GET").accept("*/*").send()) {
            final var entry = new DataSource(MediaType.of(req.contentType()), null, null,
                    RequestHeaders.defaults(uri),
                    new DataQuality[] {new DataQuality(directUri, 0, 0)},
                    null, null);
            return new PlatformData(null, entry);
        }
    }
}
