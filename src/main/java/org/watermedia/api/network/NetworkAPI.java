package org.watermedia.api.network;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMedia.BootStatus;
import org.watermedia.WaterMediaConfig;
import org.watermedia.WaterMediaModule;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.ThreadTool;
import org.watermedia.tools.IOTool;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.watermedia.WaterMedia.LOGGER;

public final class NetworkAPI {
    private NetworkAPI() {}
    static final Marker IT = MarkerManager.getMarker(NetworkAPI.class.getSimpleName());
    private static volatile Uploads uploads;

    private static final class Uploads {
        final ExecutorService executor = Executors.newFixedThreadPool(4, ThreadTool.workerFactory("NetworkAPI-Upload", Thread.NORM_PRIORITY));
        final Set<NetworkServer.UploadStatus> pending = ConcurrentHashMap.newKeySet();
        volatile boolean active = true;
    }
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
        synchronized (NetworkAPI.class) {
            final Uploads current = uploads;
            final BootStatus.State state = WaterMedia.status().state();
            if (current == null || !current.active || state == BootStatus.State.STOPPING || state == BootStatus.State.FAILED) {
                status.fail("Network service is not running");
                return status;
            }
            current.pending.add(status);
            current.executor.execute(() -> {
                try {
                    if (current.active) upload(file, status, current);
                    else status.fail("Network service stopped");
                } finally {
                    if (!status.completed() && !status.failed()) status.fail("Upload ended before completion");
                    current.pending.remove(status);
                }
            });
        }
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

    private static void upload(final File file, final NetworkServer.UploadStatus status, final Uploads owner) {
        HttpURLConnection conn = null;
        try {
            // STREAMING UPLOADS WITH BYTE-LEVEL PROGRESS ARE OUT OF SCOPE FOR NetRequest,
            // SO WE DRIVE HttpURLConnection DIRECTLY — BUT USE URI.toURL() TO AVOID THE
            // DEPRECATED new URL(String) CONSTRUCTOR.
            final URL url = URI.create(remoteHost() + "/upload").toURL();
            conn = (HttpURLConnection) url.openConnection();
            if (!owner.active) throw new IOException("Upload session stopped");
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
                    if (!owner.active || Thread.currentThread().isInterrupted()) throw new IOException("Upload cancelled");
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

            if (!owner.active) throw new IOException("Upload session stopped");
            final int code = conn.getResponseCode();
            if (code == 200) {
                final String id;
                try (final var is = conn.getInputStream()) {
                    id = new String(is.readNBytes(9), StandardCharsets.US_ASCII);
                }
                if (!id.matches("[A-Za-z0-9]{8}")) throw new IOException("Server returned an invalid upload identifier");
                if (!owner.active) throw new IOException("Upload session stopped");
                status.complete(id);
            } else {
                status.fail("Server returned HTTP " + code);
            }
        } catch (final Exception e) {
            // e.getMessage() IS OFTEN null (E.G. NPE); e.toString() KEEPS THE EXCEPTION TYPE FOR DIAGNOSIS
            LOGGER.error(IT, "Failed to upload '{}' to remote server", file.getName(), e);
            status.fail(e.toString());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Internal bootstrap operation for network services. */
    public static final class Module extends WaterMediaModule {
        @Override
        protected void start(final WaterMedia context) {
            this.task(1, 2, STEP_MIME);
            NetRequest.installExtraMimeTypes();
            synchronized (NetworkAPI.class) {
                if (uploads != null) throw new IllegalStateException("Previous uploads have not stopped");
                uploads = new Uploads();
            }
            this.task(2, 2, STEP_SERVER);
            if (WaterMediaConfig.network.forceEnableServer || (!context.clientSide && WaterMediaConfig.network.enableServer)) {
                try {
                    NetworkServer.start(WaterMediaConfig.network.serverPort, context);
                } catch (final Exception failure) {
                    this.failure(STEP_SERVER, failure);
                }
            }
        }

        @Override
        protected void release(final WaterMedia context) throws Exception {
            final Uploads previous;
            synchronized (NetworkAPI.class) {
                previous = uploads;
                if (previous != null) {
                    previous.active = false;
                    previous.executor.shutdownNow();
                    for (final NetworkServer.UploadStatus pending: previous.pending) {
                        if (!pending.completed()) pending.fail("WaterMedia stopped");
                    }
                }
            }
            Throwable failure = null;
            try {
                NetworkServer.stop();
            } catch (final RuntimeException | Error problem) {
                failure = problem;
            }
            try {
                if (previous != null) {
                    // HTTP STREAM CLOSE MAY BLOCK BEHIND A READ; ONLY ITS OWNER CLOSES THE CONNECTION.
                    if (!previous.executor.awaitTermination(30, TimeUnit.SECONDS))
                        throw new IOException("Uploads have not stopped; retry after their network timeout");
                    synchronized (NetworkAPI.class) {
                        if (uploads == previous) uploads = null;
                    }
                }
            } catch (final Exception | Error problem) {
                if (problem instanceof InterruptedException) Thread.currentThread().interrupt();
                failure = IOTool.mergeFailure(failure, problem);
            }
            if (failure instanceof final Error error) throw error;
            if (failure instanceof final Exception error) throw error;
        }
    }
}
