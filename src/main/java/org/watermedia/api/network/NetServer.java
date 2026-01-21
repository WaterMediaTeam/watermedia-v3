package org.watermedia.api.network;

import com.sun.net.httpserver.HttpServer;
import org.watermedia.WaterMedia;

import java.net.InetSocketAddress;

import static org.watermedia.WaterMedia.LOGGER;
import static org.watermedia.api.network.NetworkAPI.*;

public class NetServer {

    private HttpServer server;

    public static void start(final int port, final WaterMedia instance) {
        try {
            final HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", httpExchange -> {
                final String response = WaterMedia.NAME + " v" + WaterMedia.VERSION + " is running.";
                httpExchange.sendResponseHeaders(200, response.length());
                try (final var os = httpExchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            });
            server.createContext("/upload", httpExchange -> {
                if (!httpExchange.getRequestMethod().equalsIgnoreCase("POST")
                        || httpExchange.getRequestBody() == null
                        || httpExchange.getRequestHeaders().getFirst("Content-Length") == null
                        || Integer.parseInt(httpExchange.getRequestHeaders().getFirst("Content-Length")) <= 0
                        || httpExchange.getRequestHeaders().getFirst(X_WATERMEDIA_TOKEN) == null
                        || httpExchange.getRequestHeaders().getFirst(X_WATERMEDIA_ID) == null
                ) {
                    httpExchange.sendResponseHeaders(400, -1);
                    return;
                }




                final String response = "WaterMedia Server is running.";
                httpExchange.sendResponseHeaders(200, response.length());
                try (final var os = httpExchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            });
            server.createContext("/download", httpExchange -> {
                final String response = "WaterMedia Server is running.";
                httpExchange.sendResponseHeaders(200, response.length());
                try (final var os = httpExchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            });
            server.setExecutor(null); // creates a default executor
            server.start();
            LOGGER.info(IT, "NetServer started on port " + port);
        } catch (final Exception e) {
            LOGGER.error(IT, "Failed to start NetServer: " + e.getMessage());
        }
    }
}
