package org.watermedia.api.network;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.WaterMediaAPI;
import org.watermedia.api.util.NetRequest;

import org.watermedia.tools.ThreadTool;

import java.io.File;
import java.io.FileInputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;

import static org.watermedia.WaterMedia.LOGGER;

public class NetworkAPI extends WaterMediaAPI {
    static final Marker IT = MarkerManager.getMarker(NetworkAPI.class.getSimpleName());
    private static final String STEP_MIME = "MIME registry";
    private static final String STEP_SERVER = "FileServer";
    public static final String PROTOCOL_WATER = "water";
    public static final String X_WATERMEDIA_ID = "X-WaterMedia-Id";
    public static final String X_WATERMEDIA_TOKEN = "X-WaterMedia-Token";
    public static final String X_WATERMEDIA_FILENAME = "X-WaterMedia-Filename";

    /**
     * Uploads a file to the remote WaterMedia server in a background thread.
     * The returned {@link NetworkServer.UploadStatus} is updated as the upload progresses.
     * @param file the file to upload
     * @return status tracker for the upload (poll for progress)
     */
    public static NetworkServer.UploadStatus upload(final File file) {
        final NetworkServer.UploadStatus status = new NetworkServer.UploadStatus(file.length());

        ThreadTool.createStarted("WaterMedia-Upload-" + file.getName(), () -> doUpload(file, status));

        return status;
    }

    /**
     * Uploads multiple files to the remote WaterMedia server, each in its own background thread.
     * @param files the files to upload
     * @return one status tracker per file
     */
    public static NetworkServer.UploadStatus[] upload(final File... files) {
        final NetworkServer.UploadStatus[] statuses = new NetworkServer.UploadStatus[files.length];
        for (int i = 0; i < files.length; i++) {
            statuses[i] = upload(files[i]);
        }
        return statuses;
    }

    private static void doUpload(final File file, final NetworkServer.UploadStatus status) {
        try {
            String base = WaterMediaConfig.network.remoteHost;
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);

            // Streaming uploads with byte-level progress are out of scope for NetRequest,
            // so we drive HttpURLConnection directly — but use URI.toURL() to avoid the
            // deprecated new URL(String) constructor.
            final URL url = URI.create(base + "/upload").toURL();
            final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(WaterMediaConfig.network.requestTimeoutMs);
            conn.setReadTimeout(WaterMediaConfig.network.requestTimeoutMs);
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


    private boolean fileServerEnabled;

    @Override
    public String name() {
        return NetworkAPI.class.getSimpleName();
    }

    @Override
    public void load(final WaterMedia instance) {
        this.fileServerEnabled = WaterMediaConfig.network.forceEnableServer
                || (!instance.clientSide && WaterMediaConfig.network.enableServer);
        this.steps = 1 + (this.fileServerEnabled ? 1 : 0);
        this.step = 0;
        this.stepName = "";
    }

    @Override
    public boolean start(final WaterMedia instance) {
        // EXTEND THE PLATFORM FILE NAME MAP WITH MIME TYPES JAVA DOES NOT KNOW ABOUT
        // (webp, apng, NETPBM variants, mkv, opus, etc). Java's content-types.properties
        // ships a tiny set; without this, URLConnection.guessContentTypeFromName() returns
        // null for most modern media and platform code mis-classifies the resource.
        this.step++;
        this.stepName = STEP_MIME;
        NetRequest.installExtraMimeTypes();

        // SERVER ONLY STARTS ON SERVER-SIDE WHEN ENABLED IN CONFIG
        LOGGER.info(IT, "Network server is {}enabled on this environment", this.fileServerEnabled ? "" : "NOT ");
        if (this.fileServerEnabled) {
            this.step++;
            this.stepName = STEP_SERVER;
            NetworkServer.start(WaterMediaConfig.network.serverPort, instance);
        }

        return true;
    }

    @Override
    public void release(final WaterMedia instance) {
        this.step = 0;
        this.steps = 0;
        this.stepName = "";
        this.fileServerEnabled = false;
    }
}
