package org.watermedia.api.media.platform;

import org.watermedia.api.media.MRL;
import org.watermedia.tools.NetTool;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;

import static org.watermedia.WaterMedia.LOGGER;

public class DefaultPlatform implements IPlatform {
    @Override
    public String name() {
        return "Default";
    }

    @Override
    public boolean validate(URI uri) {
        return true; // DEFAULT
    }

    @Override
    public Result getSources(final URI uri) throws Exception {
        final URLConnection conn = NetTool.connectToURI(uri, "GET", "*/*");

        conn.getInputStream().close(); // FORCE CONNECTION (to get content type)
        // CHECK IF THE RESPONSE CODE IS 301 OR 302, IF YES, THEN RECONNECT TO THE NEW LOCATION
        if (conn instanceof final HttpURLConnection http) {
            final int responseCode = http.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP) {
                final String newLocation = http.getHeaderField("Location");
                LOGGER.debug("Redirected to {} from {}", newLocation, uri);
                return this.getSources(URI.create(newLocation));
            }
        }

        LOGGER.debug("Connected to {} with content type {}", uri, conn.getContentType());

        final var sourceBuilder = new MRL.SourceBuilder(MRL.MediaType.of(conn.getContentType())).uri(uri);

        if (conn instanceof final HttpURLConnection http) {
            http.disconnect();
        }

        return new Result(null, sourceBuilder.build());
    }
}
