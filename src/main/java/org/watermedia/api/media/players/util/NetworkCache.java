package org.watermedia.api.media.players.util;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.util.NetRequest;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.IOTool;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import static org.watermedia.WaterMedia.LOGGER;

/** Shared HTTP body cache with bounded disk storage and isolated startup sessions. */
public final class NetworkCache {
    private static final Marker IT = MarkerManager.getMarker(NetworkCache.class.getSimpleName());
    private static final int MAGIC = 0x574D4943;
    private static final int VERSION = 4;
    private static final int HASH_BYTES = 32;
    private static final String INDEX_FILE = "index.dat";
    private static final String FILE_PREFIX = "wm_";
    private static final String FILE_SUFFIX = ".tmp";
    private static final long DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static volatile Store active;

    private NetworkCache() {}

    /** Opens a new cache session; retired requests cannot publish into it. */
    public static synchronized void start(final Path dir) throws IOException {
        release();
        final Store store = new Store(dir.toAbsolutePath());
        Files.createDirectories(store.dir);
        loadIndex(store);
        active = store;
        LOGGER.info(IT, "Media network cache initialized at {}", store.dir);
    }

    /** Detaches the store and fails waiting cache readers; existing files remain on disk. */
    public static synchronized void release() {
        final Store store = active;
        active = null;
        if (store == null) return;
        synchronized (store) {
            store.index.clear();
            final IOException failure = new IOException("Media cache session was closed");
            store.inflight.values().forEach(future -> future.completeExceptionally(failure));
            store.inflight.clear();
        }
    }

    /** Reads bytes, using the configured image-cache preference. */
    public static CachedBytes read(final URI uri, final RequestHeaders headers, final String accept, final long maxBytes) throws IOException {
        return read(uri, headers, accept, maxBytes, WaterMediaConfig.media.tx.cache);
    }

    /** Reads bounded HTTP bytes; concurrent requests in this session share one download per key. */
    public static CachedBytes read(final URI uri, final RequestHeaders headers, final String accept,
                                   final long maxBytes, final boolean enabled) throws IOException {
        final Store store = active;
        if (!enabled || !isHttp(uri) || store == null) return fetch(uri, headers, accept, maxBytes);
        final byte[] hash = keyHash(uri, headers, accept);
        final String hex = DataTool.hex(hash);
        synchronized (store) {
            checkSession(store);
            final Entry entry = stored(store, hex);
            if (entry != null && Files.size(file(store, hex)) <= maxBytes) {
                try {
                    return new CachedBytes(Files.readAllBytes(file(store, hex)), entry.contentType, true, entry.expiresAt);
                } catch (final IOException failure) {
                    delete(store, hex);
                    throw failure;
                }
            }
        }
        final CachedBytes downloaded = fetchShared(store, hex, uri, headers, accept, maxBytes);
        synchronized (store) {
            try {
                checkSession(store);
                if (downloaded.expiresAt > System.currentTimeMillis()) write(store, hash, downloaded);
            } finally {
                store.inflight.computeIfPresent(hex, (key, future) -> future.isDone() ? null : future);
            }
        }
        return downloaded;
    }

    /** Returns a cached media file, or null when disabled, uncacheable, oversized or a playlist. */
    public static CachedFile readFile(final URI uri, final RequestHeaders headers, final String accept,
                                      final long maxBytes, final boolean enabled) throws IOException {
        final Store store = active;
        if (!enabled || !isHttp(uri) || store == null) return null;
        final byte[] hash = keyHash(uri, headers, accept);
        final String hex = DataTool.hex(hash);
        synchronized (store) {
            checkSession(store);
            final Entry entry = stored(store, hex);
            if (entry != null) {
                if (isPlaylist(entry.contentType)) delete(store, hex);
                else if (Files.size(file(store, hex)) <= maxBytes)
                    return new CachedFile(file(store, hex), true, entry.contentType);
            }
        }
        final CachedBytes downloaded = fetchShared(store, hex, uri, headers, accept, maxBytes);
        synchronized (store) {
            try {
                checkSession(store);
                if (isPlaylist(downloaded.contentType) || downloaded.expiresAt <= System.currentTimeMillis()) return null;
                final Path file = write(store, hash, downloaded);
                return file == null ? null : new CachedFile(file, false, downloaded.contentType);
            } finally {
                store.inflight.computeIfPresent(hex, (key, future) -> future.isDone() ? null : future);
            }
        }
    }

    private static CachedBytes fetchShared(final Store store, final String hex, final URI uri,
                                           final RequestHeaders headers, final String accept, final long maxBytes) throws IOException {
        final CompletableFuture<CachedBytes> mine = new CompletableFuture<>();
        final CompletableFuture<CachedBytes> leader;
        synchronized (store) {
            checkSession(store);
            leader = store.inflight.putIfAbsent(hex, mine);
        }
        if (leader != null) {
            try {
                final CachedBytes shared = leader.get();
                checkSession(store);
                if (shared.bytes.length <= maxBytes) return shared;
            } catch (final InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted waiting for media cache download");
            } catch (final ExecutionException failure) {
                checkSession(store);
            }
            return fetch(uri, headers, accept, maxBytes);
        }
        try {
            final CachedBytes downloaded = fetch(uri, headers, accept, maxBytes);
            checkSession(store);
            mine.complete(downloaded);
            return downloaded;
        } catch (final IOException | RuntimeException | Error failure) {
            mine.completeExceptionally(failure);
            store.inflight.remove(hex, mine);
            throw failure;
        }
    }

    private static CachedBytes fetch(final URI uri, final RequestHeaders headers, final String accept, final long maxBytes) throws IOException {
        final NetRequest.Builder builder = NetRequest.create(uri).method("GET").headers(headers);
        if (accept != null && (headers == null || !headers.has("Accept"))) builder.accept(accept);
        // A FULL RANGE REQUEST AVOIDS REAL-TIME THROTTLING ON SOME MEDIA CDNS.
        if (headers == null || !headers.has("Range")) builder.header("Range", "bytes=0-");
        try (final NetRequest req = builder.send()) {
            final int status = req.statusCode();
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL)
                throw new IOException("HTTP " + status + " for " + uri);
            final long contentLength = req.contentLength();
            if (contentLength > maxBytes)
                throw new IOException("Media source exceeds cache limit (" + contentLength + " > " + maxBytes + " bytes): " + uri);
            final byte[] bytes;
            try (final InputStream in = req.inputStream()) {
                bytes = IOTool.readLimited(in, maxBytes, contentLength);
            }
            return new CachedBytes(bytes, req.contentType(), false, expiry(req));
        }
    }

    private static void checkSession(final Store store) throws IOException {
        if (active != store) throw new IOException("Media cache session was closed");
    }

    // STORE OPERATIONS HOLD THE SESSION MONITOR; NETWORK TRANSFERS NEVER HOLD IT.
    private static Entry stored(final Store store, final String hex) throws IOException {
        final Entry entry = store.index.get(hex);
        if (entry != null && (entry.expiresAt <= System.currentTimeMillis() || !Files.isRegularFile(file(store, hex)))) {
            delete(store, hex);
            return null;
        }
        return entry;
    }

    private static Path write(final Store store, final byte[] hash, final CachedBytes body) throws IOException {
        final long budget = Math.max(1L, WaterMediaConfig.media.cacheMaxSize) * 1024L * 1024L;
        if (body.bytes.length > budget) return null;
        final String hex = DataTool.hex(hash);
        final Path file = file(store, hex);
        if (stored(store, hex) != null && Files.size(file) == body.bytes.length) return file;
        final Path partial = Files.createTempFile(store.dir, FILE_PREFIX, ".part");
        try {
            Files.write(partial, body.bytes);
            IOTool.move(partial, file);
        } finally {
            Files.deleteIfExists(partial);
        }
        store.index.put(hex, new Entry(hash, body.expiresAt, body.contentType));
        final List<StoreFile> files = new ArrayList<>();
        try (final var paths = Files.list(store.dir)) {
            for (final Path path: paths.toList()) {
                final String name = path.getFileName().toString();
                if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX) || !Files.isRegularFile(path)) continue;
                files.add(new StoreFile(path, Files.size(path), Files.getLastModifiedTime(path).toMillis()));
            }
        }
        long total = files.stream().mapToLong(StoreFile::size).sum();
        files.sort(Comparator.comparingLong(StoreFile::modified));
        for (final StoreFile old: files) {
            if (total <= budget) break;
            if (old.path.equals(file)) continue;
            Files.deleteIfExists(old.path);
            final String name = old.path.getFileName().toString();
            store.index.remove(name.substring(FILE_PREFIX.length(), name.length() - FILE_SUFFIX.length()));
            total -= old.size;
        }
        writeIndex(store);
        return file;
    }

    private static void delete(final Store store, final String hex) throws IOException {
        Files.deleteIfExists(file(store, hex));
        store.index.remove(hex);
        writeIndex(store);
    }

    private static Path file(final Store store, final String hex) {
        return store.dir.resolve(FILE_PREFIX + hex + FILE_SUFFIX);
    }

    private static void loadIndex(final Store store) throws IOException {
        if (!Files.isRegularFile(store.indexPath)) return;
        try (final DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(store.indexPath)))) {
            final int magic = in.readInt();
            final int version = in.readInt();
            final int count = in.readInt();
            if (magic != MAGIC || version != VERSION || count < 0 || count > (Files.size(store.indexPath) - 12) / 42)
                throw new IOException("Unsupported media cache index");
            final long now = System.currentTimeMillis();
            for (int i = 0; i < count; i++) {
                final byte[] hash = new byte[HASH_BYTES];
                in.readFully(hash);
                final long expiresAt = in.readLong();
                final String contentType = in.readUTF();
                final String hex = DataTool.hex(hash);
                if (expiresAt > now && Files.isRegularFile(file(store, hex)))
                    store.index.put(hex, new Entry(hash, expiresAt, contentType.isEmpty() ? null : contentType));
            }
            if (in.read() != -1) throw new IOException("Trailing media cache index bytes");
        } catch (final IOException | RuntimeException failure) {
            store.index.clear();
            LOGGER.warn(IT, "Discarding unsupported or corrupt media cache index at {}", store.indexPath, failure);
            // CACHE FILES ARE DERIVED DATA; OBSOLETE INDEX FORMATS ARE DROPPED, NOT MIGRATED.
            try (final var paths = Files.list(store.dir)) {
                for (final Path path: paths.toList()) {
                    final String name = path.getFileName().toString();
                    if (name.startsWith(FILE_PREFIX) && (name.endsWith(FILE_SUFFIX) || name.endsWith(".part")))
                        Files.deleteIfExists(path);
                }
            }
            Files.deleteIfExists(store.indexPath);
        }
    }

    private static void writeIndex(final Store store) throws IOException {
        final Path partial = Files.createTempFile(store.dir, "index", ".part");
        try {
            final long now = System.currentTimeMillis();
            store.index.values().removeIf(entry -> entry.expiresAt <= now);
            try (final DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(partial)))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(store.index.size());
                for (final Entry entry: store.index.values()) {
                    out.write(entry.hash);
                    out.writeLong(entry.expiresAt);
                    out.writeUTF(entry.contentType == null ? "" : entry.contentType);
                }
            }
            IOTool.move(partial, store.indexPath);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private static long expiry(final NetRequest req) {
        final long now = System.currentTimeMillis();
        final String cacheControl = req.header("Cache-Control");
        if (cacheControl != null) {
            long maxAge = -1L;
            for (final String raw: cacheControl.split(",")) {
                final String directive = raw.trim().toLowerCase(Locale.ROOT);
                if (directive.equals("no-store") || directive.equals("no-cache")) return -1L;
                if (directive.startsWith("max-age=")) {
                    try {
                        maxAge = Long.parseLong(directive.substring("max-age=".length()).replace("\"", ""));
                    } catch (final NumberFormatException ignored) {
                        maxAge = -1L;
                    }
                }
            }
            if (maxAge >= 0L) {
                if (maxAge >= Long.MAX_VALUE / 1000L) return Long.MAX_VALUE;
                final long millis = maxAge * 1000L;
                return millis >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + millis;
            }
        }

        final String pragma = req.header("Pragma");
        if (pragma != null && pragma.toLowerCase(Locale.ROOT).contains("no-cache")) return -1L;

        final String expires = req.header("Expires");
        if (expires != null && !expires.isBlank()) {
            try {
                return DateTimeFormatter.RFC_1123_DATE_TIME.parse(expires, Instant::from).toEpochMilli();
            } catch (final DateTimeParseException ignored) {
                return -1L;
            }
        }
        // NO CACHING HEADERS — BOUND THE LIFETIME INSTEAD OF CACHING FOREVER.
        return now + DEFAULT_TTL_MS;
    }

    private static boolean isHttp(final URI uri) {
        final String scheme = uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private static boolean isPlaylist(final String contentType) {
        if (contentType == null) return false;
        final String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.contains("mpegurl") || lower.contains("dash+xml");
    }

    // CANONICAL CACHE KEY: uri + '\n' + LOWERCASED "name:value" LINES + OPTIONAL "accept:" TAIL.
    // THE BYTE STREAM MUST STAY STABLE — ANY DRIFT ORPHANS EVERY EXISTING ON-DISK CACHE.
    private static byte[] keyHash(final URI uri, final RequestHeaders headers, final String accept) {
        final StringBuilder key = new StringBuilder(uri.toASCIIString()).append('\n');
        if (headers != null && !headers.isEmpty()) {
            for (final RequestHeaders.Entry entry: headers.entries()) {
                key.append(entry.name().toLowerCase(Locale.ROOT)).append(':').append(entry.value()).append('\n');
            }
        }
        if (accept != null && (headers == null || !headers.has("Accept"))) {
            key.append("accept:").append(accept);
        }
        return DataTool.sha256(key.toString().getBytes(StandardCharsets.UTF_8));
    }

    public record CachedBytes(byte[] bytes, String contentType, boolean cached, long expiresAt) {}
    public record CachedFile(Path path, boolean cached, String contentType) {}
    private record Entry(byte[] hash, long expiresAt, String contentType) {}
    private record StoreFile(Path path, long size, long modified) {}

    private static final class Store {
        private final Path dir;
        private final Path indexPath;
        private final Map<String, Entry> index = new HashMap<>();
        private final Map<String, CompletableFuture<CachedBytes>> inflight = new ConcurrentHashMap<>();

        private Store(final Path dir) {
            this.dir = dir;
            this.indexPath = dir.resolve(INDEX_FILE);
        }
    }
}
