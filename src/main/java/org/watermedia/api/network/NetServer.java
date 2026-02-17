package org.watermedia.api.network;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

import static org.watermedia.WaterMedia.LOGGER;
import static org.watermedia.api.network.NetworkAPI.*;

public class NetServer {
    private static final String ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int ID_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static Path storageDir;

    public static void start(final int port, final WaterMedia instance) {
        try {
            storageDir = instance.cwd.resolve("watermedia").resolve("files");
            Files.createDirectories(storageDir);

            final HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

            server.createContext("/upload", NetServer::handleUpload);
            server.createContext("/", NetServer::handleRoot);

            server.setExecutor(null);
            server.start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.stop(0);
                LOGGER.info(IT, "NetServer stopped");
            }));

            LOGGER.info(IT, "NetServer started on port {} - storage: {}", port, storageDir);
        } catch (final Exception e) {
            LOGGER.error(IT, "Failed to start NetServer", e);
        }
    }

    private static String nextId() {
        final StringBuilder sb = new StringBuilder(ID_LENGTH);
        String id;
        do {
            sb.setLength(0);
            for (int i = 0; i < ID_LENGTH; i++) {
                sb.append(ID_CHARS.charAt(RANDOM.nextInt(ID_CHARS.length())));
            }
            id = sb.toString();
        } while (Files.exists(storageDir.resolve(id)));
        return id;
    }

    // POST /upload
    // Headers: X-WaterMedia-Token (required), X-WaterMedia-Filename (required)
    // Body: raw file bytes
    // Response: 200 + generated short ID as plain text
    private static void handleUpload(final HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            final String token = exchange.getRequestHeaders().getFirst(X_WATERMEDIA_TOKEN);
            if (token == null || !token.equals(WaterMediaConfig.network.token)) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }

            final String filename = exchange.getRequestHeaders().getFirst(X_WATERMEDIA_FILENAME);
            if (filename == null || filename.isBlank()) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            final byte[] data;
            try (final var is = exchange.getRequestBody()) {
                data = is.readAllBytes();
            }

            if (data.length == 0) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            final String id = nextId();
            final Path idDir = storageDir.resolve(id);
            Files.createDirectory(idDir);
            Files.write(idDir.resolve(filename), data);

            final byte[] response = id.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, response.length);
            try (final var os = exchange.getResponseBody()) {
                os.write(response);
            }

            LOGGER.info(IT, "Stored '{}' as ID '{}' ({} bytes)", filename, id, data.length);
        }
    }

    // GET  /<id>  → download file with Content-Disposition and correct Content-Type
    // HEAD /<id>  → check existence (200 or 404)
    // GET  /      → server info
    private static void handleRoot(final HttpExchange exchange) throws IOException {
        try (exchange) {
            final String path = exchange.getRequestURI().getPath();

            if ("/".equals(path)) {
                final String info = WaterMedia.NAME + " v" + WaterMedia.VERSION;
                exchange.sendResponseHeaders(200, info.length());
                try (final var os = exchange.getResponseBody()) {
                    os.write(info.getBytes());
                }
                return;
            }

            final String id = path.substring(1);

            // Only allow alphanumeric to prevent path traversal
            if (!id.matches("[A-Za-z0-9]+")) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            final Path idDir = storageDir.resolve(id);
            if (!Files.isDirectory(idDir)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            final String method = exchange.getRequestMethod().toUpperCase();

            // HEAD: existence check only
            if ("HEAD".equals(method)) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            if (!"GET".equals(method)) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            // Find the stored file inside the ID directory
            final Path file;
            try (final var listing = Files.list(idDir)) {
                file = listing.findFirst().orElse(null);
            }

            if (file == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            final String filename = file.getFileName().toString();
            String contentType = URLConnection.guessContentTypeFromName(filename);
            if (contentType == null) contentType = "application/octet-stream";

            final byte[] data = Files.readAllBytes(file);

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename.replace("\"", "_") + "\"");
            exchange.sendResponseHeaders(200, data.length);
            try (final var os = exchange.getResponseBody()) {
                os.write(data);
            }
        }
    }
}
