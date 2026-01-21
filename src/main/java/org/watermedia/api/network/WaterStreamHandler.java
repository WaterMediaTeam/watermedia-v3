package org.watermedia.api.network;

import org.watermedia.WaterMediaConfig;

import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;

import static org.watermedia.WaterMedia.LOGGER;
import static org.watermedia.api.network.NetworkAPI.IT;

public class WaterStreamHandler implements URLStreamHandlerFactory {

    @Override
    public URLStreamHandler createURLStreamHandler(final String protocol) {
        return new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(final URL u) {
                final String host = u.getHost();
                if (host != null && host.equals("local")) {
                    // TODO
                }
                if (host != null && host.equals("remote")) {
                    try {
                        String url = WaterMediaConfig.remoteHost;
                        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
                        url += u.getPath();
                        final URL remoteURL = new URL(url);
                        final URLConnection connection = remoteURL.openConnection();
                        connection.addRequestProperty(NetworkAPI.X_WATERMEDIA_TOKEN, WaterMediaConfig.remoteToken);
                        return connection;
                    } catch (final Exception e) {
                        LOGGER.info(IT, "Failed to open remote WaterMedia connection: {}", e.getMessage());
                    }
                }
                if (host != null && host.equals("global")) {
                    // TODO
                }

                return null;
            }
        };
    }
}
