package org.watermedia.api.network;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.util.MathUtil;
import org.watermedia.tools.ThreadTool;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.watermedia.WaterMedia.LOGGER;
import static org.watermedia.api.network.NetworkAPI.*;

public final class NetworkServer {
    private static final String ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int ID_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile Running running;
    private static boolean shutdownHook;

    /** Starts the configured listener; invalid configuration and bind failures propagate to the caller. */
    public static synchronized void start(final int port, final WaterMedia instance) throws IOException {
        if (running != null) throw new IllegalStateException("The file server is already running");
        final var config = WaterMediaConfig.network;
        if (config.serverBindAddress == null || config.serverBindAddress.isBlank())
            throw new IllegalArgumentException("The file server requires an explicit bind address");
        final InetAddress bind = InetAddress.getByName(config.serverBindAddress);
        final String token = config.token;
        if (token == null || token.isBlank()) throw new IllegalArgumentException("The file server requires an upload token");
        if (!bind.isLoopbackAddress() && "watermedia_default_token_change_it".equals(token.trim()))
            throw new IllegalArgumentException("A non-loopback file server requires a custom upload token");
        if (config.serverMaxRequests < 1 || config.serverTimeout < 1 || config.maxStorageSize < 1 || config.maxUploadSize < 0)
            throw new IllegalArgumentException("File server concurrency, deadline and storage limits must be positive");
        final Path storage = instance.cwd.resolve("watermedia").resolve("files").toAbsolutePath().normalize();
        Files.createDirectories(storage);
        // OWN THE STORAGE BEFORE CLEANUP, AND KEEP THE LOCK UNTIL ALL WORKERS EXIT.
        final FileChannel storageChannel = FileChannel.open(storage.resolve(".server.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        boolean retained = false;
        Throwable startFailure = null;
        try {
            final FileLock storageLock;
            try { storageLock = storageChannel.tryLock(); }
            catch (final OverlappingFileLockException occupied) {
                throw new IOException("File server storage is already in use: " + storage, occupied);
            }
            if (storageLock == null) throw new IOException("File server storage is already in use: " + storage);
            final var incomplete = new ArrayList<Path>();
            try (final DirectoryStream<Path> entries = Files.newDirectoryStream(storage, ".upload-*")) {
                for (final Path entry: entries) incomplete.add(entry);
            }
            for (final Path entry: incomplete) {
                if (!entry.getFileName().toString().matches("\\.upload-[A-Za-z0-9]{8}")
                        || !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) continue;
                Path partial = null;
                boolean owned = true;
                try (final DirectoryStream<Path> contents = Files.newDirectoryStream(entry)) {
                    for (final Path child: contents) {
                        if (partial != null || !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                            owned = false;
                            break;
                        }
                        partial = child;
                    }
                } catch (final IOException cleanup) {
                    LOGGER.warn(IT, "Could not inspect incomplete upload {}", entry, cleanup);
                    continue;
                }
                if (!owned) continue;
                try {
                    // AN UPLOAD TEMPORARY IS EMPTY OR CONTAINS ONE REGULAR FILE.
                    if (partial != null) Files.deleteIfExists(partial);
                    Files.deleteIfExists(entry);
                } catch (final IOException cleanup) {
                    LOGGER.warn(IT, "Could not remove incomplete upload {}", entry, cleanup);
                }
            }
            long used = 0;
            try (final var files = Files.walk(storage)) {
                final var entries = files.iterator();
                while (entries.hasNext()) {
                    final Path file = entries.next();
                    if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) used = Math.addExact(used, Files.size(file));
                }
            }
            final HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), config.serverMaxRequests);
            ThreadPoolExecutor executor = null;
            ScheduledThreadPoolExecutor deadlines = null;
            try {
                executor = new ThreadPoolExecutor(config.serverMaxRequests, config.serverMaxRequests,
                        0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), ThreadTool.createFactory("NetworkServer", Thread.NORM_PRIORITY));
                deadlines = new ScheduledThreadPoolExecutor(1,
                        ThreadTool.createFactory("NetworkServer-Deadline", Thread.NORM_PRIORITY));
                deadlines.setRemoveOnCancelPolicy(true);
                final Running state = new Running(server, executor, deadlines, storage, storageChannel, token.getBytes(StandardCharsets.UTF_8),
                        config.maxUploadSize * 1024L * 1024L, config.maxStorageSize * 1024L * 1024L, used, config.serverTimeout);
                server.createContext("/upload", exchange -> handleUpload(exchange, state));
                server.createContext("/", exchange -> handleRoot(exchange, state));
                // REJECTED WORK CLOSES ITS CONNECTION IN HTTPSERVER'S DISPATCHER; NO REQUEST QUEUE GROWS.
                server.setExecutor(command -> state.executor.execute(() -> {
                    final Thread worker = Thread.currentThread();
                    final Object lock = new Object();
                    final boolean[] finished = { false };
                    final var timeout = state.deadlines.schedule(() -> {
                        synchronized (lock) {
                            if (!finished[0]) worker.interrupt();
                        }
                    }, state.timeout, TimeUnit.MILLISECONDS);
                    try {
                        command.run();
                    } finally {
                        // INTERRUPTING THE JDK'S SOCKETCHANNEL CLOSES BLOCKED HEADER/BODY IO AND WRITES.
                        synchronized (lock) { finished[0] = true; }
                        timeout.cancel(false);
                        Thread.interrupted();
                    }
                }));
                if (!shutdownHook) {
                    Runtime.getRuntime().addShutdownHook(new Thread(NetworkServer::stop, "NetworkServer-Shutdown"));
                    shutdownHook = true;
                }
                server.start();
                running = state;
                retained = true;
                LOGGER.info(IT, "Started file server at {} with {} request slots and {} bytes of storage capacity",
                        server.getAddress(), config.serverMaxRequests, state.maxStorage);
            } catch (final RuntimeException | Error failure) {
                running = null;
                retained = false;
                try { server.stop(0); }
                catch (final RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                if (executor != null) {
                    try { executor.shutdownNow(); }
                    catch (final RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                }
                if (deadlines != null) {
                    try { deadlines.shutdownNow(); }
                    catch (final RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                }
                throw failure;
            }
        } catch (final IOException | RuntimeException | Error failure) {
            startFailure = failure;
            throw failure;
        } finally {
            if (!retained) {
                try { storageChannel.close(); }
                catch (final IOException cleanup) {
                    if (startFailure == null) throw cleanup;
                    if (cleanup != startFailure) startFailure.addSuppressed(cleanup);
                }
            }
        }
    }

    /** Current listener address, including an assigned ephemeral port, or null when stopped. */
    public static InetSocketAddress address() {
        final Running state = running;
        return state == null || state.stopping ? null : state.server.getAddress();
    }

    /** Stops admission and active transfers; completed files remain on disk. Safe to repeat. */
    public static synchronized void stop() {
        final Running state = running;
        if (state == null) return;
        state.stopping = true;
        state.server.stop(0);
        state.executor.shutdownNow();
        state.deadlines.shutdownNow();
        try {
            if (!state.executor.awaitTermination(5, TimeUnit.SECONDS))
                throw new IllegalStateException("File server transfers have not finished; shutdown must be retried");
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for file server transfers to finish", e);
        }
        try { state.storageChannel.close(); }
        catch (final IOException failure) {
            throw new IllegalStateException("File server storage lock could not be released", failure);
        }
        running = null;
        LOGGER.info(IT, "Stopped file server");
    }

    private static void handleUpload(final HttpExchange exchange, final Running state) throws IOException {
        try (exchange) {
            if (!"/upload".equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_NOT_FOUND, -1);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_METHOD, -1);
                return;
            }
            final String token = exchange.getRequestHeaders().getFirst(X_WATERMEDIA_TOKEN);
            if (token == null || !MessageDigest.isEqual(state.token, token.getBytes(StandardCharsets.UTF_8))) {
                LOGGER.warn(IT, "Rejected unauthorized upload");
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_UNAUTHORIZED, -1);
                return;
            }
            final String filename = exchange.getRequestHeaders().getFirst(X_WATERMEDIA_FILENAME);
            if (filename == null || filename.isBlank() || filename.equals(".") || filename.equals("..")
                    || filename.chars().anyMatch(c -> c < 32 || c == 127 || c == '/' || c == '\\' || c == ':')) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_REQUEST, -1);
                return;
            }
            try {
                final Path name = Path.of(filename);
                if (name.isAbsolute() || name.getNameCount() != 1) {
                    exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_REQUEST, -1);
                    return;
                }
            } catch (final InvalidPathException e) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_REQUEST, -1);
                return;
            }
            final String lengthHeader = exchange.getRequestHeaders().getFirst("Content-Length");
            final long length;
            try { length = lengthHeader == null ? -1 : Long.parseLong(lengthHeader); }
            catch (final NumberFormatException e) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_REQUEST, -1);
                return;
            }
            if (length < 0) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_LENGTH_REQUIRED, -1);
                return;
            }
            if (state.maxUpload > 0 && length > state.maxUpload) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_ENTITY_TOO_LARGE, -1);
                return;
            }
            final boolean admitted;
            synchronized (state) {
                admitted = length <= state.maxStorage - state.used - state.reserved;
                if (admitted) state.reserved += length;
            }
            if (!admitted) {
                exchange.sendResponseHeaders(507, -1);
                return;
            }
            Path temporary = null;
            Path target = null;
            boolean stored = false;
            String id = null;
            try {
                while (temporary == null) {
                    final StringBuilder name = new StringBuilder(ID_LENGTH);
                    for (int i = 0; i < ID_LENGTH; i++) name.append(ID_CHARS.charAt(RANDOM.nextInt(ID_CHARS.length())));
                    id = name.toString();
                    if (Files.exists(state.storage.resolve(id), LinkOption.NOFOLLOW_LINKS)) continue;
                    try { temporary = Files.createDirectory(state.storage.resolve(".upload-" + id)); }
                    catch (final FileAlreadyExistsException ignored) {}
                }
                target = temporary.resolve(filename);
                try (final var input = new BufferedInputStream(exchange.getRequestBody());
                     final var output = new BufferedOutputStream(Files.newOutputStream(target, StandardOpenOption.CREATE_NEW))) {
                    final byte[] buffer = new byte[8192];
                    long remaining = length;
                    while (remaining > 0) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("Upload deadline reached");
                        final int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (read < 0) throw new IOException("Upload ended before Content-Length");
                        output.write(buffer, 0, read);
                        remaining -= read;
                    }
                }
                if (state.stopping || Thread.currentThread().isInterrupted()) throw new IOException("Upload stopped before publication");
                Files.move(temporary, state.storage.resolve(id), StandardCopyOption.ATOMIC_MOVE);
                stored = true;
            } finally {
                long retained = stored ? length : 0;
                if (!stored && temporary != null) {
                    try {
                        if (target != null) Files.deleteIfExists(target);
                        Files.deleteIfExists(temporary);
                    } catch (final IOException cleanup) {
                        LOGGER.warn(IT, "Could not remove incomplete upload {}", temporary, cleanup);
                        retained = length;
                    }
                }
                synchronized (state) {
                    state.reserved -= length;
                    state.used += retained;
                }
            }
            final byte[] response = id.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.length);
            try (final var output = exchange.getResponseBody()) { output.write(response); }
            LOGGER.info(IT, "Stored '{}' as ID '{}' ({} bytes)", filename, id, length);
        }
    }

    private static void handleRoot(final HttpExchange exchange, final Running state) throws IOException {
        try (exchange) {
            final String method = exchange.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_METHOD, -1);
                return;
            }
            final boolean head = "HEAD".equals(method);
            final String path = exchange.getRequestURI().getPath();
            if ("/".equals(path)) {
                final byte[] info = (WaterMedia.NAME + " v" + WaterMedia.VERSION).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(info.length));
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, head ? -1 : info.length);
                if (!head) try (final var output = exchange.getResponseBody()) { output.write(info); }
                return;
            }
            final String id = path.substring(1);
            if (!id.matches("[a-zA-Z0-9]{1,64}")) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_REQUEST, -1);
                return;
            }
            final Path directory = state.storage.resolve(id);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_NOT_FOUND, -1);
                return;
            }
            final Path file;
            try (final var files = Files.list(directory)) {
                file = files.filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)).findFirst().orElse(null);
            }
            if (file == null) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_NOT_FOUND, -1);
                return;
            }
            final String filename = file.getFileName().toString();
            final String contentType = URLConnection.guessContentTypeFromName(filename);
            final long size = Files.size(file);
            exchange.getResponseHeaders().set("Content-Type", contentType == null ? "application/octet-stream" : contentType);
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            if (head) {
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(size));
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, -1);
                return;
            }
            long start = 0;
            long end = size - 1;
            final String range = exchange.getRequestHeaders().getFirst("Range");
            final boolean partial = range != null && range.startsWith("bytes=");
            if (partial) {
                try {
                    final String[] bounds = range.substring(6).split("-", -1);
                    if (bounds.length != 2 || size == 0) throw new IllegalArgumentException("Invalid range");
                    if (bounds[0].isEmpty()) {
                        final long suffix = rangeValue(bounds[1]);
                        if (suffix == 0) throw new IllegalArgumentException("Empty suffix");
                        start = Math.max(0, size - suffix);
                    } else {
                        start = rangeValue(bounds[0]);
                        if (!bounds[1].isEmpty()) end = Math.min(end, rangeValue(bounds[1]));
                    }
                    if (start >= size || start > end) throw new IllegalArgumentException("Unsatisfiable range");
                } catch (final IllegalArgumentException invalid) {
                    exchange.getResponseHeaders().set("Content-Range", "bytes */" + size);
                    exchange.sendResponseHeaders(416, -1);
                    return;
                }
            }
            final long length = size == 0 ? 0 : end - start + 1;
            if (partial) exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + size);
            else exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename.replace('"', '_') + "\"");
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(length));
            exchange.sendResponseHeaders(partial ? HttpURLConnection.HTTP_PARTIAL : HttpURLConnection.HTTP_OK, length == 0 ? -1 : length);
            if (length == 0) return;
            try (final var channel = Files.newByteChannel(file, StandardOpenOption.READ);
                 final var input = Channels.newInputStream(channel);
                 final var output = exchange.getResponseBody()) {
                channel.position(start);
                final byte[] buffer = new byte[8192];
                long remaining = length;
                while (remaining > 0) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Download deadline reached");
                    final int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) throw new IOException("Stored file was truncated during download");
                    output.write(buffer, 0, read);
                    remaining -= read;
                }
            }
        }
    }

    private static long rangeValue(final String value) {
        if (value.isEmpty() || !value.chars().allMatch(c -> c >= '0' && c <= '9'))
            throw new IllegalArgumentException("Invalid range offset");
        final BigInteger number = new BigInteger(value);
        return number.bitLength() > 63 ? Long.MAX_VALUE : number.longValue();
    }

    private static final class Running {
        private final HttpServer server;
        private final ThreadPoolExecutor executor;
        private final ScheduledThreadPoolExecutor deadlines;
        private final Path storage;
        private final FileChannel storageChannel;
        private final byte[] token;
        private final long maxUpload;
        private final long maxStorage;
        private final int timeout;
        private long used;
        private long reserved;
        private volatile boolean stopping;

        private Running(final HttpServer server, final ThreadPoolExecutor executor, final ScheduledThreadPoolExecutor deadlines,
                        final Path storage, final FileChannel storageChannel, final byte[] token,
                        final long maxUpload, final long maxStorage, final long used, final int timeout) {
            this.server = server;
            this.executor = executor;
            this.deadlines = deadlines;
            this.storage = storage;
            this.storageChannel = storageChannel;
            this.token = token;
            this.maxUpload = maxUpload;
            this.maxStorage = maxStorage;
            this.used = used;
            this.timeout = timeout;
        }
    }

    /**
     * Tracks the progress of a file upload to a WaterMedia server.
     * Returned by {@link NetworkAPI#upload(File)} methods.
     * All accessors are thread-safe and can be polled from any thread.
     */
    public static class UploadStatus {
        private final long totalBytes;
        private volatile long uploadedBytes;
        private volatile long speed;
        private volatile String id;
        private volatile boolean complete;
        private volatile boolean failed;
        private volatile String error;

        UploadStatus(final long totalBytes) {
            this.totalBytes = totalBytes;
        }

        public long totalBytes() { return this.totalBytes; }
        public long uploadedBytes() { return this.uploadedBytes; }

        /** Current upload speed in bytes per second */
        public long speed() { return this.speed; }

        /** Server-assigned ID, null until upload completes */
        public String id() { return this.id; }

        public boolean completed() { return this.complete; }
        public boolean failed() { return this.failed; }
        public String error() { return this.error; }

        /** Upload progress from 0.0 to 100.0 */
        public double percentage() {
            return this.totalBytes > 0 ? (this.uploadedBytes * 100.0) / this.totalBytes : 0;
        }

        /** Formatted speed string (e.g. "1.5 MB/s") */
        public String displaySpeed() {
            return MathUtil.displayBytes(this.speed) + "/s";
        }

        synchronized void uploadedBytes(final long bytes) { if (!this.complete && !this.failed) this.uploadedBytes = bytes; }
        synchronized void speed(final long speed) { if (!this.complete && !this.failed) this.speed = speed; }

        synchronized void complete(final String id) {
            if (this.complete || this.failed) return;
            this.id = id;
            this.uploadedBytes = this.totalBytes;
            this.complete = true;
        }

        synchronized void fail(final String error) {
            if (this.complete || this.failed) return;
            this.error = error;
            this.failed = true;
        }
    }
}
