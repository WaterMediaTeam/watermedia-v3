package org.watermedia.api.network;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;

import java.net.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.watermedia.WaterMedia.LOGGER;

public class NetworkAPI {
    static final Marker IT = MarkerManager.getMarker(NetworkAPI.class.getSimpleName());
    static String TOKEN = null;
    public static final String PROTOCOL_WATER = "water";
    public static final String X_WATERMEDIA_ID = "X-WaterMedia-Id";
    public static final String X_WATERMEDIA_TOKEN = "X-WaterMedia-Token";
    public static final String X_WATERMEDIA_FILENAME = "X-WaterMedia-Filename";

    static final String WM_SERVER_HOST = "watermedia.server.host";
    static final String WM_SERVER_PORT = "watermedia.server.port";
    static final String WM_SERVER_SSL = "watermedia.server.ssl";
    static final String WM_SERVER_TOKEN = "watermedia.server.token";

    public static final String PATH_UPLOAD = "/upload";
    public static final String PATH_DOWNLOAD = "/download";

    /**
     * Sets the remote WaterMedia server connection properties
     * @param host The host address
     * @param port The port number, for example 80 for HTTP or 443 for HTTPS
     * @param useSSL Whether to use SSL or not
     * @param token The authentication token
     */
    public static void setRemoteServer(final String host, final int port, final boolean useSSL, final String token) {
        System.setProperty(WM_SERVER_HOST, host);
        System.setProperty(WM_SERVER_PORT, String.valueOf(port));
        System.setProperty(WM_SERVER_SSL, String.valueOf(useSSL));
        System.setProperty(WM_SERVER_TOKEN, token);
    }

    public static boolean start(final WaterMedia instance) {
        return true; // TODO: nothing to fail, nothing to load
    }
}
