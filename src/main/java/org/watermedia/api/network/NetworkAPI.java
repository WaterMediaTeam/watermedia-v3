package org.watermedia.api.network;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.WaterMediaModule;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.ThreadTool;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;

import static org.watermedia.WaterMedia.LOGGER;

public final class NetworkAPI extends WaterMediaModule {
    static final Marker IT = MarkerManager.getMarker(NetworkAPI.class.getSimpleName());
    private static final Executor EXECUTOR = ThreadTool.createRecommendedThreadPool("NetworkAPI-Upload", 5);
    private static final String STEP_MIME = "MIME registry";
    private static final String STEP_SERVER = "FileServer";
    public static final String PROTOCOL_WATER = "water";
    public static final String X_WATERMEDIA_ID = "X-WaterMedia-Id";
    public static final String X_WATERMEDIA_TOKEN = "X-WaterMedia-Token";
    public static final String X_WATERMEDIA_FILENAME = "X-WaterMedia-Filename";

    /**
     * Uploads multiple files to the remote WaterMedia server on a shared background thread pool.
     * @param files the files to upload
     * @return one status tracker per file
     */
    public static NetworkServer.UploadStatus[] upload(final File... files) {
        final NetworkServer.UploadStatus[] statuses = new NetworkServer.UploadStatus[files.length];
        for (int i = 0; i < files.length; i++) statuses[i] = upload(files[i]);
        return statuses;
    }

    /**
     * Uploads a file to the remote WaterMedia server in a background thread.
     * The returned {@link NetworkServer.UploadStatus} is updated as the upload progresses.
     * @param file the file to upload
     * @return status tracker for the upload (poll for progress)
     */
    public static NetworkServer.UploadStatus upload(final File file) {
        final NetworkServer.UploadStatus status = new NetworkServer.UploadStatus(file.length());
        EXECUTOR.execute(() -> upload(file, status));
        return status;
    }

    /**
     * Validated base URL of the remote file server ({@code network.remoteHost}) without the
     * trailing slash. Fails fast on a blank or non-absolute http(s) value instead of producing
     * a scheme-less URL that only breaks much later, far from the actual cause.
     */
    public static String remoteHost() throws IOException {
        final String base = WaterMediaConfig.network.remoteHost;
        if (base == null || base.isBlank() || !(base.startsWith("http://") || base.startsWith("https://")))
            throw new IOException("network.remoteHost is not configured (expected an absolute http(s) URL): " + base);
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static void upload(final File file, final NetworkServer.UploadStatus status) {
        HttpURLConnection conn = null;
        try {
            // STREAMING UPLOADS WITH BYTE-LEVEL PROGRESS ARE OUT OF SCOPE FOR NetRequest,
            // SO WE DRIVE HttpURLConnection DIRECTLY — BUT USE URI.toURL() TO AVOID THE
            // DEPRECATED new URL(String) CONSTRUCTOR.
            final URL url = URI.create(remoteHost() + "/upload").toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(WaterMediaConfig.network.timeout);
            conn.setReadTimeout(WaterMediaConfig.network.timeout);
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
                    status.uploadedBytes(uploaded);

                    final long now = System.nanoTime();
                    final long elapsed = now - lastTime;
                    if (elapsed >= 500_000_000L) {
                        final long bytesInPeriod = uploaded - lastUploaded;
                        status.speed((long) (bytesInPeriod * 1_000_000_000.0 / elapsed));
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
        } catch (final Exception e) {
            // e.getMessage() IS OFTEN null (E.G. NPE); e.toString() KEEPS THE EXCEPTION TYPE FOR DIAGNOSIS
            LOGGER.error(IT, "Failed to upload '{}' to remote server", file.getName(), e);
            status.fail(e.toString());
        } finally {
            if (conn != null) conn.disconnect(); // ALWAYS RELEASE THE CONNECTION, EVEN ON A MID-TRANSFER FAILURE
        }
    }

    private boolean fileServerEnabled;

    @Override
    public String name() {
        return NetworkAPI.class.getSimpleName();
    }

    @Override
    protected void load(final WaterMedia instance) {
        super.load(instance);
        this.fileServerEnabled = WaterMediaConfig.network.forceEnableServer
                || (!instance.clientSide && WaterMediaConfig.network.enableServer);
        this.steps = 1 + (this.fileServerEnabled ? 1 : 0);
    }

    @Override
    protected boolean start(final WaterMedia instance) {
        // EXTEND THE PLATFORM FILE NAME MAP WITH MIME TYPES JAVA DOES NOT KNOW ABOUT
        // (webp, apng, NETPBM VARIANTS, mkv, opus, ETC). JAVA'S content-types.properties
        // SHIPS A TINY SET; WITHOUT THIS, URLConnection.guessContentTypeFromName() RETURNS
        // null FOR MOST MODERN MEDIA AND PLATFORM CODE MIS-CLASSIFIES THE RESOURCE.
        this.step++;
        this.stepName = STEP_MIME;
        NetRequest.installExtraMimeTypes();

        // SERVER ONLY STARTS ON SERVER-SIDE WHEN ENABLED IN CONFIG
        LOGGER.info(IT, "Network server is {}enabled on this environment", this.fileServerEnabled ? "" : "NOT ");
        if (this.fileServerEnabled) {
            this.step++;
            this.stepName = STEP_SERVER;
            try {
                NetworkServer.start(WaterMediaConfig.network.serverPort, instance);
            } catch (final Exception e) {
                // A DEAD FILE SERVER MUST NOT MARK THE WHOLE NETWORK MODULE AS CRASHED — RECORD AND CARRY ON
                LOGGER.error(IT, "Failed to start the network file server", e);
                this.failures.add(STEP_SERVER);
            }
        }

        return true;
    }

    @Override
    protected void release(final WaterMedia instance) {
        NetworkServer.stop(); // STOP THE FILE SERVER AND ITS THREAD POOL SO A LATER start() CAN REBIND THE PORT
        this.fileServerEnabled = false;
        super.release(instance);
    }
}
