package org.watermedia.api.network;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMedia.BootStatus;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.util.NetRequest;
import org.watermedia.bootstrap.WaterMediaModule;
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

/** File transfer facade: uploads to the remote WaterMedia file server and the lifecycle of the local one. */
public final class NetworkAPI {
    private static final Marker IT = MarkerManager.getMarker(NetworkAPI.class.getSimpleName());
    private static final String STEP_MIME = "MIME registry";
    private static final String STEP_SERVER = "FileServer";
    /** URI scheme of the internal sources the WaterMedia file servers serve. */
    public static final String PROTOCOL_WATER = "water";
    /** Request header carrying the upload token the file server authenticates with. */
    public static final String X_WATERMEDIA_TOKEN = "X-WaterMedia-Token";
    /** Request header carrying the original name of an uploaded file. */
    public static final String X_WATERMEDIA_FILENAME = "X-WaterMedia-Filename";
    private static volatile Uploads uploads;

    private NetworkAPI() {}

    private static final class Uploads {
        final ExecutorService executor = Executors.newFixedThreadPool(4, ThreadTool.workerFactory("NetworkAPI-Upload", Thread.NORM_PRIORITY));
        final Set<NetworkServer.UploadStatus> pending = ConcurrentHashMap.newKeySet();
        volatile boolean active = true;
    }

    /**
     * Uploads multiple files to the remote WaterMedia server on a shared background thread pool.
     * Every file is checked before the first upload starts, so a bad entry submits nothing.
     * @param files the files to upload
     * @return one status tracker per file
     * @throws IllegalArgumentException when an entry is not an existing regular file
     * @throws IllegalStateException when the network service is not running
     */
    public static NetworkServer.UploadStatus[] upload(final File... files) {
        if (files == null) throw new IllegalArgumentException("Files cannot be null");
        for (final File file: files) if (file == null || !file.isFile()) throw new IllegalArgumentException("Not a file: " + file);
        final NetworkServer.UploadStatus[] statuses = new NetworkServer.UploadStatus[files.length];
        for (int i = 0; i < files.length; i++) statuses[i] = upload(files[i]);
        return statuses;
    }

    /**
     * Uploads a file to the remote WaterMedia server in a background thread.
     * The returned {@link NetworkServer.UploadStatus} is updated as the upload progresses; an
     * unreachable or misconfigured remote server fails the status instead of throwing.
     * @param file the file to upload
     * @return status tracker for the upload (poll for progress)
     * @throws IllegalArgumentException when {@code file} is not an existing regular file
     * @throws IllegalStateException when the network service is not running
     */
    public static NetworkServer.UploadStatus upload(final File file) {
        if (file == null || !file.isFile()) throw new IllegalArgumentException("Not a file: " + file);
        if (!WaterMedia.status().running(BootStatus.Id.NETWORK)) throw new IllegalStateException("The network service is not running");
        final NetworkServer.UploadStatus status = new NetworkServer.UploadStatus(file.length());
        synchronized (NetworkAPI.class) {
            final Uploads current = uploads;
            if (current == null || !current.active) throw new IllegalStateException("The network service is not running");
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
     * Validated base URL of the remote file server ({@code network.remoteUrl}) without the
     * trailing slash. Fails fast on a blank or non-absolute http(s) value instead of producing
     * a scheme-less URL that only breaks much later, far from the actual cause.
     *
     * @throws IllegalStateException when the configured value is not an absolute http(s) URL
     */
    public static String remoteUrl() {
        final String base = WaterMediaConfig.network.remoteUrl;
        if (base == null || base.isBlank() || !(base.startsWith("http://") || base.startsWith("https://")))
            throw new IllegalStateException("network.remoteUrl is not an absolute http(s) URL: " + base);
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static void upload(final File file, final NetworkServer.UploadStatus status, final Uploads owner) {
        final String remote;
        try {
            remote = remoteUrl();
        } catch (final IllegalStateException misconfigured) { // A WRONG network.remoteUrl IS AN EXPECTED CONDITION, NOT A BUG: NO TRACE
            LOGGER.warn(IT, "Could not upload '{}': {}", file.getName(), misconfigured.getMessage());
            status.fail(misconfigured.toString());
            return;
        }
        HttpURLConnection conn = null;
        try {
            // STREAMING UPLOADS WITH BYTE-LEVEL PROGRESS ARE OUT OF SCOPE FOR NetRequest, SO THIS DRIVES HttpURLConnection
            final URL url = URI.create(remote + "/upload").toURL();
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
            if (contentType == null) contentType = NetRequest.MIMETYPE_OCTET_STREAM;
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
        } catch (final ConnectException | UnknownHostException | SocketTimeoutException unreachable) {
            // A REMOTE SERVER THAT IS DOWN OR UNRESOLVABLE IS AN EXPECTED CONDITION, NOT A BUG: NO TRACE
            LOGGER.warn(IT, "Could not reach the remote file server to upload '{}': {}", file.getName(), unreachable.getMessage());
            status.fail(unreachable.toString());
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

    // THE FILE SERVER RUNS EVERYWHERE UNDER ALWAYS AND ONLY ON HOSTS WITHOUT A CLIENT UNDER DEDICATED
    static boolean serverEnabled(final boolean client, final WaterMediaConfig.Network.Server server) {
        return server == WaterMediaConfig.Network.Server.ALWAYS || (!client && server == WaterMediaConfig.Network.Server.DEDICATED);
    }

    /** Internal bootstrap operation for network services. */
    public static final class Module extends WaterMediaModule {
        @Override
        protected void start() {
            this.task(1, 2, STEP_MIME);
            NetRequest.installExtraMimeTypes();
            synchronized (NetworkAPI.class) {
                if (uploads != null) throw new IllegalStateException("Previous uploads have not stopped");
                uploads = new Uploads();
            }
            this.task(2, 2, STEP_SERVER);
            if (serverEnabled(WaterMedia.clientSide(), WaterMediaConfig.network.server)) {
                try {
                    NetworkServer.start(WaterMediaConfig.network.serverPort, WaterMedia.cwd());
                } catch (final Exception failure) {
                    this.failure(STEP_SERVER, failure);
                }
            }
        }

        @Override
        protected void release() throws Exception {
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
