package org.watermedia.api.network;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;

import org.watermedia.tools.ThreadTool;

import java.io.File;
import java.io.FileInputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.watermedia.WaterMedia.LOGGER;

public class NetworkAPI {
    static final Marker IT = MarkerManager.getMarker(NetworkAPI.class.getSimpleName());
    public static final String PROTOCOL_WATER = "water";
    public static final String X_WATERMEDIA_ID = "X-WaterMedia-Id";
    public static final String X_WATERMEDIA_TOKEN = "X-WaterMedia-Token";
    public static final String X_WATERMEDIA_FILENAME = "X-WaterMedia-Filename";

    private static final WaterStreamHandler WATER_HANDLER = new WaterStreamHandler();

    public static Map<String, String> parseQuery(final String query) {
        if (query == null || query.isEmpty()) return Map.of();

        final var result = new HashMap<String, String>();
        for (final var param: query.split("&")) {
            final var pair = param.split("=", 2);
            final var key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            final var value = pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            result.put(key, value);
        }
        return result;
    }

    /**
     * Creates a URL using the {@code water://} protocol with the registered handler.
     * Use this when you need to create water:// URLs programmatically.
     * @param url full water:// URL string (e.g. "water://local/textures/img.png")
     * @return the parsed URL bound to the WaterStreamHandler
     */
    public static URL waterURL(final String url) throws MalformedURLException {
        return new URL(null, url, WATER_HANDLER);
    }

    public static String parseWaterURL(final URI url) throws Exception {
        if (!PROTOCOL_WATER.equals(url.getScheme())) {
            throw new IllegalArgumentException("URL must use water:// protocol");
        }

        final String host = url.getHost();
        final String path = url.getPath();

        return switch (host) {
            case WaterStreamHandler.HOST_REMOTE -> {
                String base = WaterMediaConfig.network.remoteHost;
                if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                yield base + path;
            }
            case WaterStreamHandler.HOST_GLOBAL -> {
                String base = WaterStreamHandler.GLOBAL_SERVER;
                base = base.substring(0, base.length() - 1);
                yield base + path;
            }
            case WaterStreamHandler.HOST_LOCAL -> {
                String p = path;
                if (p.startsWith("/")) p = p.substring(1);
                yield WaterMedia.cwd().resolve(p).toString();
            }
            default -> throw new IllegalArgumentException("Unknown water:// host: " + host);
        };
    }

    /**
     * Uploads a file to the remote WaterMedia server in a background thread.
     * The returned {@link UploadStatus} is updated as the upload progresses.
     * @param file the file to upload
     * @return status tracker for the upload (poll for progress)
     */
    public static UploadStatus upload(final File file) {
        final UploadStatus status = new UploadStatus(file.length());

        ThreadTool.createStarted("WaterMedia-Upload-" + file.getName(), () -> doUpload(file, status));

        return status;
    }

    /**
     * Uploads multiple files to the remote WaterMedia server, each in its own background thread.
     * @param files the files to upload
     * @return one status tracker per file
     */
    public static UploadStatus[] upload(final File... files) {
        final UploadStatus[] statuses = new UploadStatus[files.length];
        for (int i = 0; i < files.length; i++) {
            statuses[i] = upload(files[i]);
        }
        return statuses;
    }

    private static void doUpload(final File file, final UploadStatus status) {
        try {
            String base = WaterMediaConfig.network.remoteHost;
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);

            final URL url = new URL(base + "/upload");
            final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("User-Agent", WaterMedia.USER_AGENT);
            conn.setRequestProperty(X_WATERMEDIA_TOKEN, WaterMediaConfig.network.token);
            conn.setRequestProperty(X_WATERMEDIA_FILENAME, file.getName());

            String contentType = URLConnection.guessContentTypeFromName(file.getName());
            if (contentType == null) contentType = "application/octet-stream";
            conn.setRequestProperty("Content-Type", contentType);
            conn.setFixedLengthStreamingMode(file.length());

            try (final var fis = new FileInputStream(file);
                 final var os = conn.getOutputStream()) {
                final byte[] buffer = new byte[8192];
                long uploaded = 0;
                long lastTime = System.nanoTime();
                long lastUploaded = 0;
                int read;

                while ((read = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                    uploaded += read;
                    status.setUploadedBytes(uploaded);

                    final long now = System.nanoTime();
                    final long elapsed = now - lastTime;
                    if (elapsed >= 500_000_000L) {
                        final long bytesInPeriod = uploaded - lastUploaded;
                        status.setSpeed((long) (bytesInPeriod * 1_000_000_000.0 / elapsed));
                        lastTime = now;
                        lastUploaded = uploaded;
                    }
                }
            }

            final int code = conn.getResponseCode();
            if (code == 200) {
                final String id;
                try (final var is = conn.getInputStream()) {
                    id = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
                status.complete(id);
            } else {
                status.fail("Server returned HTTP " + code);
            }

            conn.disconnect();
        } catch (final Exception e) {
            status.fail(e.getMessage());
        }
    }

    /**
     * Formats a bytes-per-second value into a human-readable speed string.
     * Automatically scales from B/s → KB/s → MB/s → GB/s at 1024 boundaries.
     * @param bytesPerSecond the speed in bytes per second
     * @return formatted string (e.g. "1.5 MB/s")
     */
    public static String displaySpeed(final long bytesPerSecond) {
        if (bytesPerSecond < 1024L) return bytesPerSecond + " B/s";
        if (bytesPerSecond < 1024L * 1024) return String.format("%.1f KB/s", bytesPerSecond / 1024.0);
        if (bytesPerSecond < 1024L * 1024 * 1024) return String.format("%.1f MB/s", bytesPerSecond / (1024.0 * 1024));
        return String.format("%.1f GB/s", bytesPerSecond / (1024.0 * 1024 * 1024));
    }

    public static boolean start(final WaterMedia instance) {
        // REGISTER water:// PROTOCOL HANDLER (WORKS ON BOTH CLIENT AND SERVER)
        try {
            URL.setURLStreamHandlerFactory(protocol -> PROTOCOL_WATER.equals(protocol) ? new WaterStreamHandler() : null);
            LOGGER.info(IT, "Registered water:// protocol handler");
        } catch (final Error e) {
            LOGGER.warn(IT, "Could not register water:// factory (already set?): {} — use NetworkAPI.waterURL() instead", e.getMessage());
        }

        // SERVER ONLY STARTS ON SERVER-SIDE WHEN ENABLED IN CONFIG
        LOGGER.info(IT, "Network server is {}enabled on this environment", (WaterMediaConfig.network.forceEnableServer || WaterMediaConfig.network.enableServer) ? "" : "NOT ");
        if (WaterMediaConfig.network.forceEnableServer || (!instance.clientSide && WaterMediaConfig.network.enableServer)) {
            NetServer.start(WaterMediaConfig.network.serverPort, instance);
        }

        return true;
    }
}
