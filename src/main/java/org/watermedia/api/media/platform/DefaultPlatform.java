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
    public MRL.Source[] getSources(final URI uri) throws Exception {
        final URLConnection conn = NetTool.connectToURI(uri);

        if (conn != null) {
            try {
                conn.connect();
                conn.getInputStream().close();
                LOGGER.info("Connected to {} with content type {}", uri, conn.getContentType());
            } catch (Exception e) {
                LOGGER.warn("Failed to connect to {}: {}", uri, e.getMessage());
            }
        }

        final var sourceBuilder = new MRL.SourceBuilder(conn == null ? MRL.MediaType.UNKNOWN : MRL.MediaType.of(conn.getContentType()))
                .uri(uri);

        if (conn instanceof HttpURLConnection http) {
            http.disconnect();
        }

        return new MRL.Source[] { sourceBuilder.build() };
    }
}
