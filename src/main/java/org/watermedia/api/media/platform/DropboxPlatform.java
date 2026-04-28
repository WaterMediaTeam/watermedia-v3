package org.watermedia.api.media.platform;

import org.watermedia.api.media.MRL;
import org.watermedia.tools.NetTool;

import java.net.URI;
import java.net.URLConnection;

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
    public Result getSources(final URI uri) throws Exception {
        final URI directUri = new URI(uri.toString().replace("dl=0", "dl=1"));

        final URLConnection conn = NetTool.connectToURI(directUri, "GET", "*/*");
        try {
            conn.getInputStream().close();
            final var sourceBuilder = new MRL.SourceBuilder(MRL.MediaType.of(conn.getContentType())).uri(directUri);
            return new Result(null, sourceBuilder.build());
        } finally {
            if (conn instanceof final java.net.HttpURLConnection http) {
                http.disconnect();
            }
        }
    }
}
