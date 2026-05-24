package org.watermedia.api.media.players.util;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.util.NetRequest;
import org.watermedia.api.util.RequestHeaders;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Small binary HTTP cache for media bytes consumed by image/video players.
 */
public final class NetworkCache {
    private static final Marker IT = MarkerManager.getMarker(NetworkCache.class.getSimpleName());
    private static final int MAGIC = 0x574D4943; // WMIC
    private static final int VERSION = 1;
    private static final int HASH_BYTES = 32;
    private static final String INDEX_FILE = "index.dat";
    private static final String FILE_PREFIX = "wm_";
    private static final String FILE_SUFFIX = ".tmp";

    private static final Map<String, Entry> INDEX = new HashMap<>();
    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();

    private static Path cacheDir;
    private static Path indexPath;

    private NetworkCache() {}

    public static synchronized void start(final Path dir) throws IOException {
        cacheDir = dir.toAbsolutePath();
        indexPath = cacheDir.resolve(INDEX_FILE);
        Files.createDirectories(cacheDir);
        loadIndex();
        LOGGER.info(IT, "Media network cache initialized at {}", cacheDir);
    }

    public static synchronized void release() {
        INDEX.clear();
        LOCKS.clear();
        cacheDir = null;
        indexPath = null;
    }

    public static synchronized Path directory() {
        return cacheDir;
    }

    public static CachedBytes read(final URI uri, final RequestHeaders headers, final String accept, final long maxBytes) throws IOException {
        return read(uri, headers, accept, maxBytes, WaterMediaConfig.media.txNetworkCache);
    }

    public static CachedBytes read(final URI uri, final RequestHeaders headers, final String accept,
                                   final long maxBytes, final boolean enabled) throws IOException {
        if (!enabled || !isHttp(uri) || cacheDir == null) {
            return download(uri, headers, accept, maxBytes);
        }

        final byte[] hash = hash(uri);
        final String key = hex(hash);
        final Object lock = LOCKS.computeIfAbsent(key, ignored -> new Object());
        try {
            synchronized (lock) {
                final CachedBytes cached = readCached(key, maxBytes);
                if (cached != null) return cached;

                final CachedBytes downloaded = download(uri, headers, accept, maxBytes);
                if (downloaded.expiresAt > System.currentTimeMillis()) {
                    store(key, hash, downloaded.bytes, downloaded.expiresAt);
                }
                return downloaded;
            }
        } finally {
            LOCKS.remove(key, lock);
        }
    }

    public static CachedFile readFile(final URI uri, final RequestHeaders headers, final String accept,
                                      final long maxBytes, final boolean enabled) throws IOException {
        if (!enabled || !isHttp(uri) || cacheDir == null) return null;

        final byte[] hash = hash(uri);
        final String key = hex(hash);
        final Object lock = LOCKS.computeIfAbsent(key, ignored -> new Object());
        try {
            synchronized (lock) {
                final CachedFile cached = readCachedFile(key, maxBytes);
                if (cached != null) return cached;

                final CachedBytes downloaded = download(uri, headers, accept, maxBytes);
                if (isPlaylist(downloaded.contentType)) return null;
                if (downloaded.expiresAt <= System.currentTimeMillis()) return null;
                final Path file = store(key, hash, downloaded.bytes, downloaded.expiresAt);
                return new CachedFile(file, false, downloaded.expiresAt, downloaded.contentType);
            }
        } finally {
            LOCKS.remove(key, lock);
        }
    }

    private static CachedBytes download(final URI uri, final RequestHeaders headers, final String accept, final long maxBytes) throws IOException {
        final NetRequest.Builder builder = NetRequest.create(uri).method("GET");
        applyHeaders(builder, headers);
        if (accept != null && (headers == null || !headers.has("Accept"))) {
            builder.accept(accept);
        }

        try (final NetRequest req = builder.send()) {
            if (req.statusCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + req.statusCode() + " for " + uri);
            }

            final long contentLength = req.contentLength();
            if (contentLength > 0L && contentLength > maxBytes) {
                throw new IOException("Media source exceeds cache limit (" + contentLength + " > " + maxBytes + " bytes): " + uri);
            }

            final byte[] bytes;
            try (final InputStream in = req.getInputStream()) {
                bytes = readAllLimited(in, maxBytes);
            }
            return new CachedBytes(bytes, req.contentType(), false, expiresAt(req));
        }
    }

    private static void applyHeaders(final NetRequest.Builder builder, final RequestHeaders headers) {
        if (headers == null || headers.isEmpty()) return;

        final Set<String> seen = new HashSet<>();
        for (final RequestHeaders.Entry entry: headers.entries()) {
            final String key = entry.name().toLowerCase(Locale.ROOT);
            if (seen.add(key)) {
                builder.header(entry.name(), entry.value());
            } else {
                builder.addHeader(entry.name(), entry.value());
            }
        }
    }

    private static CachedBytes readCached(final String key, final long maxBytes) throws IOException {
        final Entry entry;
        synchronized (NetworkCache.class) {
            entry = INDEX.get(key);
        }
        if (entry == null) return null;

        final long now = System.currentTimeMillis();
        final Path file = cacheFile(key);
        if (entry.expired(now) || !Files.isRegularFile(file)) {
            evict(key);
            return null;
        }

        final long size = Files.size(file);
        if (size > maxBytes) {
            return null;
        }

        try {
            return new CachedBytes(Files.readAllBytes(file), null, true, entry.expiresAt);
        } catch (final IOException e) {
            evict(key);
            throw e;
        }
    }

    private static CachedFile readCachedFile(final String key, final long maxBytes) throws IOException {
        final Entry entry;
        synchronized (NetworkCache.class) {
            entry = INDEX.get(key);
        }
        if (entry == null) return null;

        final long now = System.currentTimeMillis();
        final Path file = cacheFile(key);
        if (entry.expired(now) || !Files.isRegularFile(file)) {
            evict(key);
            return null;
        }

        final long size = Files.size(file);
        if (size > maxBytes) {
            return null;
        }

        return new CachedFile(file, true, entry.expiresAt, null);
    }

    private static Path store(final String key, final byte[] hash, final byte[] bytes, final long expiresAt) throws IOException {
        Files.createDirectories(cacheDir);
        final Path file = cacheFile(key);
        final Path tmp = file.resolveSibling(file.getFileName() + ".part");
        Files.write(tmp, bytes);
        move(tmp, file);

        synchronized (NetworkCache.class) {
            INDEX.put(key, new Entry(hash, expiresAt));
            writeIndex();
        }
        return file;
    }

    private static synchronized void evict(final String key) {
        INDEX.remove(key);
        try {
            Files.deleteIfExists(cacheFile(key));
            writeIndex();
        } catch (final IOException e) {
            LOGGER.warn(IT, "Failed to evict cache entry {}", key, e);
        }
    }

    private static synchronized void loadIndex() throws IOException {
        INDEX.clear();
        if (!Files.isRegularFile(indexPath)) return;

        try (final DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(indexPath)))) {
            final int magic = in.readInt();
            final int version = in.readInt();
            final int count = in.readInt();
            if (magic != MAGIC || version != VERSION || count < 0) {
                throw new IOException("Unsupported media cache index");
            }

            final long now = System.currentTimeMillis();
            for (int i = 0; i < count; i++) {
                final byte[] hash = new byte[HASH_BYTES];
                in.readFully(hash);
                final long expiresAt = in.readLong();
                final String key = hex(hash);
                final Entry entry = new Entry(hash, expiresAt);
                if (!entry.expired(now) && Files.isRegularFile(cacheFile(key))) {
                    INDEX.put(key, entry);
                }
            }
        } catch (final IOException e) {
            INDEX.clear();
            LOGGER.warn(IT, "Ignoring corrupt media cache index at {}", indexPath, e);
        }
    }

    private static synchronized void writeIndex() throws IOException {
        if (indexPath == null) return;

        final Path tmp = indexPath.resolveSibling(indexPath.getFileName() + ".part");
        final long now = System.currentTimeMillis();
        try (final DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt((int) INDEX.values().stream().filter(entry -> !entry.expired(now)).count());
            for (final Entry entry: INDEX.values()) {
                if (entry.expired(now)) continue;
                out.write(entry.hash);
                out.writeLong(entry.expiresAt);
            }
        }
        move(tmp, indexPath);
    }

    private static long expiresAt(final NetRequest req) {
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
            if (maxAge >= 0L) return now + Math.max(0L, maxAge) * 1000L;
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
        return Long.MAX_VALUE;
    }

    private static byte[] readAllLimited(final InputStream in, final long maxBytes) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[64 * 1024];
        long total = 0L;
        while (true) {
            final int read = in.read(buffer);
            if (read < 0) break;
            total += read;
            if (total > maxBytes) {
                throw new IOException("Media source exceeds cache limit (" + total + " > " + maxBytes + " bytes)");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
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

    private static Path cacheFile(final String key) {
        return cacheDir.resolve(FILE_PREFIX + key + FILE_SUFFIX);
    }

    private static byte[] hash(final URI uri) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(uri.toASCIIString().getBytes(StandardCharsets.UTF_8));
        } catch (final NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
    }

    private static String hex(final byte[] bytes) {
        final StringBuilder out = new StringBuilder(bytes.length * 2);
        for (final byte b: bytes) {
            out.append(Character.forDigit((b >>> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    private static void move(final Path from, final Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record CachedBytes(byte[] bytes, String contentType, boolean cached, long expiresAt) {}

    public record CachedFile(Path path, boolean cached, long expiresAt, String contentType) {}

    private record Entry(byte[] hash, long expiresAt) {
        boolean expired(final long now) {
            return this.expiresAt >= 0L && this.expiresAt <= now;
        }
    }
}
