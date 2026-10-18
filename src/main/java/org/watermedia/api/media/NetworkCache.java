package org.watermedia.api.media;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.util.NetRequest;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.LogTool;

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
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import static org.watermedia.WaterMedia.LOGGER;

/** Shared HTTP body cache with bounded disk storage and isolated startup sessions. */
final class NetworkCache {
    private static final Marker IT = MarkerManager.getMarker(NetworkCache.class.getSimpleName());
    private static final int MAGIC = 0x574D4943;
    private static final int VERSION = 5;
    private static final int HASH_BYTES = 32;
    private static final int ENTRY_MIN_BYTES = HASH_BYTES + 8 + 8 + 2;
    private static final String INDEX_FILE = "index.dat";
    private static final String FILE_PREFIX = "wm_";
    private static final String FILE_SUFFIX = ".tmp";
    private static final String PART_SUFFIX = ".part";
    private static final long DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000;
    // LEFTOVERS YOUNGER THAN THIS MAY BELONG TO ANOTHER PROCESS SHARING THE FOLDER, SO THE STARTUP SWEEP SPARES THEM
    private static final long SWEEP_GRACE_MS = 60_000L;
    private static final long WRITE_REPORT_NS = 10_000_000_000L;
    private static final LogTool.Throttle WRITE_REPORTS = new LogTool.Throttle(WRITE_REPORT_NS);
    private static volatile Store active;

    private NetworkCache() {}

    /** Opens a new cache session; retired requests cannot publish into it. */
    public static synchronized void start(final Path dir) throws IOException {
        release();
        final Store store = new Store(dir.toAbsolutePath());
        Files.createDirectories(store.dir);
        loadIndex(store);
        // PAYLOADS THE INDEX DOES NOT VOUCH FOR (CRASHED WRITES, EXPIRED ENTRIES, OLD FORMATS) ARE DERIVED DATA
        final long stale = System.currentTimeMillis() - SWEEP_GRACE_MS;
        try (final var paths = Files.list(store.dir)) {
            for (final Path path: paths.toList()) {
                final String name = path.getFileName().toString();
                final boolean payload = name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX);
                final boolean orphan = name.endsWith(PART_SUFFIX) || (payload && !store.index.containsKey(
                        name.substring(FILE_PREFIX.length(), name.length() - FILE_SUFFIX.length())));
                if (!orphan) continue;
                try {
                    if (Files.getLastModifiedTime(path).toMillis() < stale) Files.deleteIfExists(path);
                } catch (final IOException ignored) {
                    // ANOTHER PROCESS MAY HAVE JUST REMOVED OR REPLACED IT; THE NEXT START SWEEPS AGAIN
                }
            }
        }
        active = store;
        LOGGER.info(IT, "Media network cache initialized at {} ({} entries, {} bytes)", store.dir, store.index.size(), store.totalBytes);
    }

    /** Detaches the store and fails waiting cache readers; existing files remain on disk. */
    public static synchronized void release() {
        final Store store = active;
        active = null;
        if (store == null) return;
        synchronized (store) {
            store.index.clear();
            store.totalBytes = 0;
            final IOException failure = new IOException("Media cache session was closed");
            store.inflight.values().forEach(future -> future.completeExceptionally(failure));
            store.inflight.clear();
        }
    }

    /** Reads bounded HTTP bytes; concurrent requests in this session share one download per key. */
    public static CachedBytes read(final URI uri, final HttpHeaders headers, final String accept,
                                   final long maxBytes, final boolean enabled) throws IOException {
        final Store store = active;
        if (!enabled || !isHttp(uri) || store == null) return fetch(uri, headers, accept, maxBytes, false);
        final byte[] hash = keyHash(uri, headers, accept);
        final String hex = DataTool.hex(hash);
        final Entry entry;
        synchronized (store) {
            checkSession(store);
            entry = stored(store, hex);
        }
        // THE PAYLOAD IS READ OUTSIDE THE MONITOR SO ONE SLOW DISK HIT NEVER STALLS THE OTHER CACHE USERS
        if (entry != null && entry.size <= maxBytes) {
            final Path file = file(store, hex);
            try {
                return new CachedBytes(Files.readAllBytes(file), entry.contentType, true, entry.expiresAt);
            } catch (final NoSuchFileException evicted) {
                // ANOTHER WRITE EVICTED THE ENTRY BETWEEN THE LOOKUP AND THE READ, WHICH IS A PLAIN MISS
                LOGGER.debug(IT, "Media cache entry {} was evicted while being read", file);
            } catch (final IOException failure) {
                synchronized (store) {
                    if (active == store && store.index.get(hex) == entry) {
                        try { delete(store, hex); }
                        catch (final IOException ignored) { /* THE NEXT LOOKUP RETRIES THE DELETE */ }
                    }
                }
                LOGGER.warn(IT, "Dropping unreadable media cache entry {}", file, failure);
            }
        }
        final CachedBytes downloaded = fetchShared(store, hex, uri, headers, accept, maxBytes, false);
        try {
            if (downloaded.expiresAt > System.currentTimeMillis()) store(store, hex, hash, downloaded);
        } finally {
            store.inflight.computeIfPresent(hex, (key, future) -> future.isDone() ? null : future);
        }
        return downloaded;
    }

    /**
     * Returns a cached media file, or null when disabled, uncacheable, oversized or a playlist. Only
     * bounded responses are downloaded: a missing Content-Length or an ICY stream is an endless
     * source and fails fast so the caller streams it directly.
     */
    public static CachedFile readFile(final URI uri, final HttpHeaders headers, final String accept,
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
                else if (entry.size <= maxBytes) return new CachedFile(file(store, hex), true, entry.contentType);
            }
        }
        final CachedBytes downloaded = fetchShared(store, hex, uri, headers, accept, maxBytes, true);
        try {
            if (isPlaylist(downloaded.contentType) || downloaded.expiresAt <= System.currentTimeMillis()) return null;
            final Path file = store(store, hex, hash, downloaded);
            return file == null ? null : new CachedFile(file, false, downloaded.contentType);
        } finally {
            store.inflight.computeIfPresent(hex, (key, future) -> future.isDone() ? null : future);
        }
    }

    private static CachedBytes fetchShared(final Store store, final String hex, final URI uri, final HttpHeaders headers,
                                           final String accept, final long maxBytes, final boolean bounded) throws IOException {
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
            return fetch(uri, headers, accept, maxBytes, bounded);
        }
        try {
            final CachedBytes downloaded = fetch(uri, headers, accept, maxBytes, bounded);
            checkSession(store);
            mine.complete(downloaded);
            return downloaded;
        } catch (final IOException | RuntimeException | Error failure) {
            mine.completeExceptionally(failure);
            store.inflight.remove(hex, mine);
            throw failure;
        }
    }

    // bounded REFUSES ENDLESS SOURCES (NO Content-Length, ICY RADIO) BEFORE READING A SINGLE BODY BYTE
    private static CachedBytes fetch(final URI uri, final HttpHeaders headers, final String accept, final long maxBytes,
                                     final boolean bounded) throws IOException {
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
            if (bounded && (contentLength < 0 || req.header("icy-metaint") != null || req.header("icy-name") != null))
                throw new IOException("Unbounded media source is not cacheable: " + uri);
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

    // INDEX OPERATIONS HOLD THE SESSION MONITOR; NETWORK TRANSFERS, PAYLOAD READS AND PAYLOAD STAGING NEVER HOLD IT.
    // THE INDEX IS ACCESS-ORDERED, SO A LOOKUP MARKS ITS ENTRY AS RECENTLY USED AND THE HEAD IS THE EVICTION CANDIDATE
    private static Entry stored(final Store store, final String hex) throws IOException {
        final Entry entry = store.index.get(hex);
        if (entry != null && (entry.expiresAt <= System.currentTimeMillis() || !Files.isRegularFile(file(store, hex)))) {
            delete(store, hex);
            return null;
        }
        return entry;
    }

    // A CACHE FAILURE NEVER FAILS THE DOWNLOAD THAT ALREADY SUCCEEDED: THE CALLER JUST GETS NO CACHED FILE
    private static Path store(final Store store, final String hex, final byte[] hash, final CachedBytes body) {
        final long budget = Math.max(1L, WaterMediaConfig.media.cacheBudget) * 1024L * 1024L;
        final long size = body.bytes.length;
        if (size > budget) return null;
        final Path file = file(store, hex);
        try {
            final Path partial = Files.createTempFile(store.dir, FILE_PREFIX, PART_SUFFIX);
            try {
                Files.write(partial, body.bytes);
                synchronized (store) {
                    if (active != store) return null;
                    final Entry existing = stored(store, hex);
                    if (existing != null) {
                        if (existing.size == size) return file;
                        delete(store, hex);
                    }
                    IOTool.move(partial, file);
                    store.index.put(hex, new Entry(hash, body.expiresAt, size, body.contentType));
                    store.totalBytes += size;
                    // EVICTS FROM THE LEAST RECENTLY USED END UNTIL THE STORE FITS; THE FRESH ENTRY IS THE TAIL AND FITS ALONE
                    final Iterator<Map.Entry<String, Entry>> eldest = store.index.entrySet().iterator();
                    while (store.totalBytes > budget) {
                        final Map.Entry<String, Entry> old = eldest.next();
                        if (old.getKey().equals(hex)) break;
                        Files.deleteIfExists(file(store, old.getKey()));
                        store.totalBytes -= old.getValue().size;
                        eldest.remove();
                    }
                    flushIndex(store);
                    return file;
                }
            } finally {
                Files.deleteIfExists(partial);
            }
        } catch (final IOException failure) {
            LogTool.throttled(LOGGER, Level.WARN, IT, WRITE_REPORTS, "Media cache write failed for {}", file, failure);
            return null;
        }
    }

    private static void delete(final Store store, final String hex) throws IOException {
        Files.deleteIfExists(file(store, hex));
        final Entry entry = store.index.remove(hex);
        if (entry != null) store.totalBytes -= entry.size;
        flushIndex(store);
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
            if (magic != MAGIC || version != VERSION || count < 0 || count > (Files.size(store.indexPath) - 12) / ENTRY_MIN_BYTES)
                throw new IOException("Unsupported media cache index");
            final long now = System.currentTimeMillis();
            for (int i = 0; i < count; i++) {
                final byte[] hash = new byte[HASH_BYTES];
                in.readFully(hash);
                final long expiresAt = in.readLong();
                final long size = in.readLong();
                final String contentType = in.readUTF();
                final String hex = DataTool.hex(hash);
                final Path file = file(store, hex);
                // AN ENTRY IS TRUSTED ONLY WHILE ITS PAYLOAD IS WHOLE; THE REST IS LEFT FOR THE STARTUP SWEEP
                if (expiresAt > now && size >= 0 && Files.isRegularFile(file) && Files.size(file) == size) {
                    store.index.put(hex, new Entry(hash, expiresAt, size, contentType.isEmpty() ? null : contentType));
                    store.totalBytes += size;
                }
            }
            if (in.read() != -1) throw new IOException("Trailing media cache index bytes");
        } catch (final IOException | RuntimeException failure) {
            // CACHE FILES ARE DERIVED DATA; OBSOLETE INDEX FORMATS ARE DROPPED, NOT MIGRATED.
            store.index.clear();
            store.totalBytes = 0;
            LOGGER.warn(IT, "Discarding unsupported or corrupt media cache index at {}", store.indexPath, failure);
            Files.deleteIfExists(store.indexPath);
        }
    }

    // THE INDEX IS A FEW DOZEN BYTES PER ENTRY, SO EVERY MUTATION REWRITES IT ATOMICALLY AND NO WRITE IS EVER LOST
    private static void flushIndex(final Store store) throws IOException {
        final Path partial = Files.createTempFile(store.dir, "index", PART_SUFFIX);
        try {
            try (final DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(partial)))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(store.index.size());
                for (final Entry entry: store.index.values()) {
                    out.write(entry.hash);
                    out.writeLong(entry.expiresAt);
                    out.writeLong(entry.size);
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
    private static byte[] keyHash(final URI uri, final HttpHeaders headers, final String accept) {
        final StringBuilder key = new StringBuilder(uri.toASCIIString()).append('\n');
        if (headers != null && !headers.empty()) {
            for (final HttpHeaders.Entry entry: headers.entries()) {
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
    private record Entry(byte[] hash, long expiresAt, long size, String contentType) {}

    private static final class Store {
        private final Path dir;
        private final Path indexPath;
        private final Map<String, Entry> index = new LinkedHashMap<>(64, 0.75f, true);
        private final Map<String, CompletableFuture<CachedBytes>> inflight = new ConcurrentHashMap<>();
        private long totalBytes;

        private Store(final Path dir) {
            this.dir = dir;
            this.indexPath = dir.resolve(INDEX_FILE);
        }
    }
}
