package org.watermedia.api.media.players.util;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.util.NetRequest;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.tools.IOTool;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Two-tier on-disk media cache for {@link org.watermedia.api.media.players media players}.
 * <p>
 * The cache is layered as a single shared store fronted by two logical tiers:
 * <ul>
 *   <li><b>Network tier</b> — raw HTTP bodies fetched from media sources. This is what
 *       {@link #read} and {@link #readFile} operate on today and the only tier currently
 *       wired in. Image bytes for {@code TxMediaPlayer} and short clip bodies for
 *       {@code FFMediaPlayer} land here.</li>
 *   <li><b>Codec tier</b> — reserved for upcoming BC7-compressed frame textures packaged
 *       as DDS containers. The codec writer is activated only when the active GPU
 *       advertises BC7 support; each decoded frame is recompressed on the decode thread
 *       and persisted here so subsequent playbacks can stream the DDS straight to GPU
 *       memory without a software decode pass.</li>
 * </ul>
 * Both tiers share the same on-disk infrastructure (directory, atomic writes, lock
 * striping, index persistence, expiry) routed through the {@link Tier}-aware store
 * primitives below. They never collide because every cache file is prefixed with the
 * tier discriminator and every index entry carries its tier byte.
 * <p>
 * The codec-tier reader/writer surface is intentionally not exposed yet — only the
 * storage primitives are laid out so the BC7/DDS pipeline can slot in without
 * re-shuffling the public cache surface.
 */
public final class NetworkCache {
    private static final Marker IT = MarkerManager.getMarker(NetworkCache.class.getSimpleName());
    private static final int MAGIC = 0x574D4943; // WMIC
    // INDEX V3: ADDS A TIER DISCRIMINATOR BYTE PER ENTRY (V2 ENTRIES ARE DROPPED ON UPGRADE).
    private static final int VERSION = 3;
    private static final int HASH_BYTES = 32;
    private static final int LOCK_STRIPES = 64;
    private static final String INDEX_FILE = "index.dat";
    private static final String FILE_PREFIX = "wm_";
    private static final String FILE_SUFFIX = ".tmp";

    // INDEX KEY = tier.prefix + ":" + hex(hash) SO BOTH TIERS CAN COEXIST FOR THE SAME URI.
    private static final Map<String, Entry> INDEX = new HashMap<>();
    private static final Object[] LOCKS = new Object[LOCK_STRIPES];

    private static Path cacheDir;
    private static Path indexPath;

    static {
        for (int i = 0; i < LOCKS.length; i++) {
            LOCKS[i] = new Object();
        }
    }

    private NetworkCache() {}

    /**
     * Cache layer discriminator. Both tiers share the on-disk infrastructure but live in
     * disjoint key/file namespaces so reads and writes from one tier cannot collide with
     * entries owned by the other.
     */
    public enum Tier {
        /** Raw HTTP response bodies fetched from a media origin. */
        NETWORK("n"),
        /**
         * BC7-compressed frame textures packaged as DDS files. Populated by the codec
         * writer once the BC7 encoder ships; readers stream the bytes straight to the GPU.
         */
        CODEC("c");

        final String prefix;
        Tier(final String prefix) { this.prefix = prefix; }

        static Tier of(final byte tag) {
            final int ordinal = Byte.toUnsignedInt(tag);
            final Tier[] values = values();
            if (ordinal >= values.length) {
                throw new IllegalArgumentException("Unknown cache tier tag: " + ordinal);
            }
            return values[ordinal];
        }

        byte tag() { return (byte) this.ordinal(); }
    }

    // ==========================================================================
    // PUBLIC API — LIFECYCLE
    // ==========================================================================
    public static synchronized void start(final Path dir) throws IOException {
        cacheDir = dir.toAbsolutePath();
        indexPath = cacheDir.resolve(INDEX_FILE);
        Files.createDirectories(cacheDir);
        loadIndex();
        LOGGER.info(IT, "Media network cache initialized at {}", cacheDir);
    }

    public static synchronized void release() {
        INDEX.clear();
        cacheDir = null;
        indexPath = null;
    }

    public static synchronized Path directory() {
        return cacheDir;
    }

    // ==========================================================================
    // PUBLIC API — NETWORK TIER
    // ==========================================================================
    public static CachedBytes read(final URI uri, final RequestHeaders headers, final String accept, final long maxBytes) throws IOException {
        return read(uri, headers, accept, maxBytes, WaterMediaConfig.media.txNetworkCache);
    }

    public static CachedBytes read(final URI uri, final RequestHeaders headers, final String accept,
                                   final long maxBytes, final boolean enabled) throws IOException {
        if (!enabled || !isHttp(uri) || cacheDir == null) {
            return fetch(uri, headers, accept, maxBytes);
        }

        final byte[] hash = keyHash(uri, headers, accept);
        final String hex = hex(hash);
        synchronized (lock(Tier.NETWORK, hex)) {
            final CachedBytes cached = readStoredBytes(Tier.NETWORK, hex, maxBytes);
            if (cached != null) return cached;

            final CachedBytes downloaded = fetch(uri, headers, accept, maxBytes);
            if (downloaded.expiresAt > System.currentTimeMillis()) {
                storeWrite(Tier.NETWORK, hash, downloaded.bytes, downloaded.expiresAt, downloaded.contentType);
            }
            return downloaded;
        }
    }

    public static CachedFile readFile(final URI uri, final RequestHeaders headers, final String accept,
                                      final long maxBytes, final boolean enabled) throws IOException {
        if (!enabled || !isHttp(uri) || cacheDir == null) return null;

        final byte[] hash = keyHash(uri, headers, accept);
        final String hex = hex(hash);
        synchronized (lock(Tier.NETWORK, hex)) {
            final CachedFile cached = readStoredFile(Tier.NETWORK, hex, maxBytes);
            if (cached != null) return cached;

            final CachedBytes downloaded = fetch(uri, headers, accept, maxBytes);
            if (isPlaylist(downloaded.contentType)) return null;
            if (downloaded.expiresAt <= System.currentTimeMillis()) return null;
            final Path file = storeWrite(Tier.NETWORK, hash, downloaded.bytes, downloaded.expiresAt, downloaded.contentType);
            return new CachedFile(file, false, downloaded.expiresAt, downloaded.contentType);
        }
    }

    // ==========================================================================
    // PUBLIC API — CODEC TIER (RESERVED FOR THE UPCOMING BC7/DDS PIPELINE)
    // ==========================================================================
    // The codec writer/reader will be added together with the BC7 encoder. Until then the
    // codec tier remains dormant: the storage primitives below already accept Tier.CODEC,
    // and the on-disk format reserves the discriminator byte, so wiring the encoder amounts
    // to calling storeWrite(Tier.CODEC, ...) per frame and readStoredFile(Tier.CODEC, ...)
    // on playback. No changes to the network tier are needed.

    // ==========================================================================
    // NETWORK FETCH
    // ==========================================================================
    private static CachedBytes fetch(final URI uri, final RequestHeaders headers, final String accept, final long maxBytes) throws IOException {
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
            try (final InputStream in = req.inputStream()) {
                bytes = IOTool.readLimited(in, maxBytes, contentLength);
            }
            return new CachedBytes(bytes, req.contentType(), false, expiry(req));
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

    // ==========================================================================
    // SHARED STORE — TIER-AWARE STORAGE PRIMITIVES
    // ==========================================================================
    // EVERY ENTRY IS KEYED BY (tier, hex(hash)) AND BACKED BY A FILE NAMED
    // wm_<tier.prefix>_<hash>.tmp INSIDE cacheDir. THESE PRIMITIVES ARE SHARED
    // BETWEEN THE NETWORK TIER AND THE FUTURE CODEC TIER.

    private static CachedBytes readStoredBytes(final Tier tier, final String hex, final long maxBytes) throws IOException {
        final Entry entry;
        synchronized (NetworkCache.class) {
            entry = INDEX.get(indexKey(tier, hex));
        }
        if (entry == null) return null;

        final long now = System.currentTimeMillis();
        final Path file = storeFile(tier, hex);
        if (entry.expired(now) || !Files.isRegularFile(file)) {
            storeDelete(tier, hex);
            return null;
        }

        if (Files.size(file) > maxBytes) {
            return null;
        }

        try {
            return new CachedBytes(Files.readAllBytes(file), entry.contentType, true, entry.expiresAt);
        } catch (final IOException e) {
            storeDelete(tier, hex);
            throw e;
        }
    }

    private static CachedFile readStoredFile(final Tier tier, final String hex, final long maxBytes) throws IOException {
        final Entry entry;
        synchronized (NetworkCache.class) {
            entry = INDEX.get(indexKey(tier, hex));
        }
        if (entry == null) return null;

        final long now = System.currentTimeMillis();
        final Path file = storeFile(tier, hex);
        if (entry.expired(now) || !Files.isRegularFile(file)) {
            storeDelete(tier, hex);
            return null;
        }
        if (isPlaylist(entry.contentType)) {
            storeDelete(tier, hex);
            return null;
        }

        if (Files.size(file) > maxBytes) {
            return null;
        }

        return new CachedFile(file, true, entry.expiresAt, entry.contentType);
    }

    private static Path storeWrite(final Tier tier, final byte[] hash, final byte[] bytes,
                                   final long expiresAt, final String contentType) throws IOException {
        Files.createDirectories(cacheDir);
        final String hex = hex(hash);
        final Path file = storeFile(tier, hex);
        final Path tmp = file.resolveSibling(file.getFileName() + ".part");
        Files.write(tmp, bytes);
        IOTool.move(tmp, file);

        synchronized (NetworkCache.class) {
            INDEX.put(indexKey(tier, hex), new Entry(hash, tier, expiresAt, contentType));
            writeIndex();
        }
        return file;
    }

    private static synchronized void storeDelete(final Tier tier, final String hex) {
        INDEX.remove(indexKey(tier, hex));
        try {
            Files.deleteIfExists(storeFile(tier, hex));
            writeIndex();
        } catch (final IOException e) {
            LOGGER.warn(IT, "Failed to delete cache entry {}:{}", tier.prefix, hex, e);
        }
    }

    private static String indexKey(final Tier tier, final String hex) {
        return tier.prefix + ':' + hex;
    }

    private static Path storeFile(final Tier tier, final String hex) {
        return cacheDir.resolve(FILE_PREFIX + tier.prefix + '_' + hex + FILE_SUFFIX);
    }

    private static Object lock(final Tier tier, final String hex) {
        return LOCKS[Math.floorMod(indexKey(tier, hex).hashCode(), LOCKS.length)];
    }

    // ==========================================================================
    // INDEX PERSISTENCE
    // ==========================================================================
    // ON-DISK LAYOUT (V3):
    //   int  MAGIC
    //   int  VERSION
    //   int  count
    //   count * { byte tier, byte[HASH_BYTES] hash, long expiresAt, UTF contentType }
    // OLDER VERSIONS ARE NOT MIGRATED — A FRESH INDEX IS CREATED AND ORPHAN PAYLOAD
    // FILES ARE LEFT BEHIND HARMLESSLY UNTIL THE NEXT MANUAL CLEANUP.

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
                final Tier tier = Tier.of(in.readByte());
                final byte[] hash = new byte[HASH_BYTES];
                in.readFully(hash);
                final long expiresAt = in.readLong();
                final String contentType = in.readUTF();
                final String hex = hex(hash);
                final Entry entry = new Entry(hash, tier, expiresAt, contentType.isEmpty() ? null : contentType);
                if (!entry.expired(now) && Files.isRegularFile(storeFile(tier, hex))) {
                    INDEX.put(indexKey(tier, hex), entry);
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
                out.writeByte(entry.tier.tag());
                out.write(entry.hash);
                out.writeLong(entry.expiresAt);
                out.writeUTF(entry.contentType == null ? "" : entry.contentType);
            }
        }
        IOTool.move(tmp, indexPath);
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================
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
        return Long.MAX_VALUE;
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

    private static byte[] keyHash(final URI uri, final RequestHeaders headers, final String accept) throws IOException {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(uri.toASCIIString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            if (headers != null && !headers.isEmpty()) {
                for (final RequestHeaders.Entry entry: headers.entries()) {
                    digest.update(entry.name().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) ':');
                    digest.update(entry.value().getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) '\n');
                }
            }
            if (accept != null && (headers == null || !headers.has("Accept"))) {
                digest.update("accept:".getBytes(StandardCharsets.UTF_8));
                digest.update(accept.getBytes(StandardCharsets.UTF_8));
            }
            return digest.digest();
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

    // ==========================================================================
    // RECORDS
    // ==========================================================================
    public record CachedBytes(byte[] bytes, String contentType, boolean cached, long expiresAt) {}

    public record CachedFile(Path path, boolean cached, long expiresAt, String contentType) {}

    private record Entry(byte[] hash, Tier tier, long expiresAt, String contentType) {
        boolean expired(final long now) {
            return this.expiresAt >= 0L && this.expiresAt <= now;
        }
    }
}
