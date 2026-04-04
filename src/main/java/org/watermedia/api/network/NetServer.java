package org.watermedia.api.network;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.concurrent.Executors;

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

            server.setExecutor(Executors.newCachedThreadPool());
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
    // HEADERS: X-WaterMedia-Token (REQUIRED), X-WaterMedia-Filename (REQUIRED)
    // BODY: RAW FILE BYTES
    // RESPONSE: 200 + GENERATED SHORT ID AS PLAIN TEXT
    private static void handleUpload(final HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_METHOD, -1);
                LOGGER.error(IT, "Received non-POST request to /upload: {}", exchange.getRequestMethod());
                return;
            }

            final String token = exchange.getRequestHeaders().getFirst(X_WATERMEDIA_TOKEN);
            if (token == null || !token.equals(WaterMediaConfig.network.token)) {
                LOGGER.error(IT, "Unauthorized upload attempt with token: {}", token);
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_UNAUTHORIZED, -1);
                return;
            }

            final String filename = exchange.getRequestHeaders().getFirst(X_WATERMEDIA_FILENAME);
            if (filename == null || filename.isBlank()) {
                LOGGER.error(IT, "Bad upload attempt with file name: {}", filename);
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_REQUEST, -1);
                return;
            }

            final long maxBytes = WaterMediaConfig.network.maxUploadSizeMB * 1024L * 1024L;
            final long contentLength = Long.parseLong(exchange.getRequestHeaders().getFirst("Content-Length"));

            if (contentLength <= 0) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_REQUEST, -1);
                return;
            }

            if (contentLength > maxBytes) {
                LOGGER.warn(IT, "Upload rejected: {} bytes exceeds max size of {} MB", contentLength, WaterMediaConfig.network.maxUploadSizeMB);
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_ENTITY_TOO_LARGE, -1);
                return;
            }

            final String id = nextId();
            final Path idDir = storageDir.resolve(id);
            Files.createDirectory(idDir);
            final Path targetFile = idDir.resolve(filename);

            try (final var in = new BufferedInputStream(exchange.getRequestBody());
                 final var ou = new BufferedOutputStream(Files.newOutputStream(targetFile))) {
                in.transferTo(ou);
            }

            final byte[] response = id.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.length);
            try (final var os = exchange.getResponseBody()) {
                os.write(response);
            }

            LOGGER.info(IT, "Stored '{}' as ID '{}' ({} bytes)", filename, id, contentLength);
        }
    }

    // GET  /<id>  — DOWNLOAD FILE WITH Content-Disposition AND CORRECT Content-Type
    // HEAD /<id>  — CHECK EXISTENCE (200 OR 404)
    // GET  /      — SERVER INFO
    private static void handleRoot(final HttpExchange exchange) throws IOException {
        try (exchange) {
            final String path = exchange.getRequestURI().getPath();

            if ("/".equals(path)) {
                final String info = WaterMedia.NAME + " v" + WaterMedia.VERSION;
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, info.length());
                try (final var os = exchange.getResponseBody()) {
                    os.write(info.getBytes());
                }
                return;
            }

            final String id = path.substring(1);

            // ONLY ALLOW ALPHANUMERIC TO PREVENT PATH TRAVERSAL
            if (!id.matches("^[a-zA-Z0-9]+$")) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_REQUEST, -1);
                LOGGER.warn(IT, "Received request with invalid ID: {}", id);
                return;
            }

            final Path idDir = storageDir.resolve(id);
            if (!Files.isDirectory(idDir)) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_NOT_FOUND, -1);
                LOGGER.warn(IT, "ID not found: {}", id);
                return;
            }

            final String method = exchange.getRequestMethod().toUpperCase();

            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_METHOD, -1);
                LOGGER.error(IT, "Unsupported HTTP method: {}", method);
                return;
            }

            // FIND THE STORED FILE INSIDE THE ID DIRECTORY
            final Path file;
            try (final var listing = Files.list(idDir)) {
                file = listing.findFirst().orElse(null);
            }

            if (file == null) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_NOT_FOUND, -1);
                LOGGER.error(IT, "No file found in ID directory: {}", idDir);
                return;
            }

            final String filename = file.getFileName().toString();
            String contentType = URLConnection.guessContentTypeFromName(filename);
            if (contentType == null) contentType = "application/octet-stream";

            final long fileSize = Files.size(file);

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");

            // HEAD: RETURN METADATA ONLY
            if ("HEAD".equals(method)) {
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(fileSize));
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, -1);
                return;
            }

            // GET: SERVE FILE WITH RANGE SUPPORT FOR MEDIA SEEKING
            final String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                final String rangeSpec = rangeHeader.substring(6);
                final String[] parts = rangeSpec.split("-", 2);

                long start, end;
                if (parts[0].isEmpty()) {
                    end = fileSize - 1;
                    start = fileSize - Long.parseLong(parts[1]);
                } else {
                    start = Long.parseLong(parts[0]);
                    end = parts.length > 1 && !parts[1].isEmpty() ? Long.parseLong(parts[1]) : fileSize - 1;
                }

                if (start < 0 || end >= fileSize || start > end) {
                    exchange.getResponseHeaders().set("Content-Range", "bytes */" + fileSize);
                    exchange.sendResponseHeaders(416, -1); // Range Not Satisfiable (no HttpURLConnection constant)
                    return;
                }

                final long contentLength = end - start + 1;
                exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_PARTIAL, contentLength);
                try (final var ou = exchange.getResponseBody(); final var in = Files.newInputStream(file)) {
                    in.skipNBytes(start);
                    final byte[] buffer = new byte[8192];
                    long remaining = contentLength;
                    int read;
                    while (remaining > 0 && (read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                        ou.write(buffer, 0, read);
                        remaining -= read;
                    }
                }
            } else {
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename.replace("\"", "_") + "\"");
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, fileSize);
                try (final var ou = exchange.getResponseBody(); final var in = Files.newInputStream(file)) {
                    in.transferTo(ou);
                }
            }
        }
    }
}
