package org.watermedia.api.media.players.util;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.common.dds.DDSHeader;
import org.watermedia.api.codecs.readers.BCReader;
import org.watermedia.api.codecs.writers.BCWriter;
import org.watermedia.api.util.NetRequest;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.tools.IOTool;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
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
 *   <li><b>Codec tier</b> — BC7/BC3/BC1-compressed frame textures packaged as DDS containers.
 *       The {@linkplain #mode() codec mode} engages only when {@code media.txCodecCache} is on
 *       <i>and</i> a block-compression codec is {@linkplain CodecsAPI#supports(String) available};
 *       {@code TxMediaPlayer} then feeds decoded frames (plus their delays) to a
 *       {@link CodecWriter}, which recompresses them off the render thread and persists one DDS
 *       per source. Subsequent playbacks read the DDS through a {@link CodecReader} and upload the
 *       blocks straight to GPU memory without any software decode — saving both decode CPU and
 *       (since BCn is a quarter or eighth of RGBA8) VRAM.</li>
 * </ul>
 * Both tiers share the same on-disk infrastructure (directory, atomic writes, lock
 * striping, index persistence, expiry) routed through the {@link Tier}-aware store
 * primitives below. They never collide because every cache file is prefixed with the
 * tier discriminator and every index entry carries its tier byte.
 * <p>
 * The codec mode and its writer/reader are resolved at {@link #start(Path)} from the configured
 * preference and the codecs the JNI bindings have registered; when no BC encoder is present the
 * cache stays in {@link Mode#DISK} and behaves exactly like the network tier alone.
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

    // CODEC-TIER ENTRIES ARE DERIVED ARTEFACTS (NOT HTTP BODIES), SO THEY CARRY A DDS CONTENT TYPE
    // AND NEVER EXPIRE ON THEIR OWN — THEY ARE DROPPED ONLY WHEN UNREADABLE OR ON MANUAL CLEANUP.
    private static final String CODEC_CONTENT_TYPE = "image/vnd-ms.dds";
    private static final long CODEC_NEVER_EXPIRES = Long.MAX_VALUE;

    // INDEX KEY = tier.prefix + ":" + hex(hash) SO BOTH TIERS CAN COEXIST FOR THE SAME URI.
    private static final Map<String, Entry> INDEX = new HashMap<>();
    private static final Object[] LOCKS = new Object[LOCK_STRIPES];

    private static Path cacheDir;
    private static Path indexPath;
    // ACTIVE CACHING STRATEGY, RESOLVED ONCE AT start(). READ FROM PLAYER/DECODE THREADS.
    private static volatile Mode mode = Mode.DISK;

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

    /**
     * Caching strategy resolved at {@link #start(Path)}.
     */
    public enum Mode {
        /** Classic disk cache: HTTP bodies are stored verbatim and re-read instead of re-fetched. */
        DISK,
        /**
         * Codec cache: decoded frames are recompressed to GPU block-compressed textures (BC over
         * DDS) and replayed straight to the GPU. Engages only when {@code media.txCodecCache} is on
         * and a BC codec is {@linkplain CodecsAPI#available(String) available}. The disk tier still
         * backs the first fetch, so this strategy is strictly additive.
         */
        CODEC
    }

    // ==========================================================================
    // PUBLIC API — LIFECYCLE
    // ==========================================================================
    public static synchronized void start(final Path dir) throws IOException {
        cacheDir = dir.toAbsolutePath();
        indexPath = cacheDir.resolve(INDEX_FILE);
        Files.createDirectories(cacheDir);
        loadIndex();
        // CODEC MODE IS OPT-IN AND REQUIRES A NATIVE BC CODEC; OTHERWISE THE CACHE IS DISK-ONLY.
        mode = WaterMediaConfig.media.txCodecCache && CodecsAPI.available(CodecsAPI.CODEC_BC)
                ? Mode.CODEC : Mode.DISK;
        LOGGER.info(IT, "Media network cache initialized at {} (mode={})", cacheDir, mode);
    }

    public static synchronized void release() {
        INDEX.clear();
        cacheDir = null;
        indexPath = null;
        mode = Mode.DISK;
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
    // PUBLIC API — CODEC TIER (BC OVER DDS)
    // ==========================================================================
    /** The active caching strategy resolved at {@link #start(Path)}. */
    public static Mode mode() {
        return mode;
    }

    /** Whether the codec cache (BC over DDS) is active. */
    public static boolean codecEnabled() {
        return mode == Mode.CODEC;
    }

    /**
     * Opens a codec write session that recompresses frames to a BC/DDS texture for {@code uri}.
     * The caller feeds decoded frames (with their delays) through {@link CodecWriter#write} and
     * finalizes with {@link CodecWriter#commit}; aborting (or closing without committing) discards
     * the partial file.
     *
     * @return a session, or {@code null} when the codec cache is inactive (so callers simply skip
     *         codec caching)
     * @throws IOException if the temp file or the BC writer cannot be created
     */
    public static CodecWriter openCodecWriter(final URI uri, final RequestHeaders headers, final String accept,
                                              final int width, final int height, final PixelFormat format) throws IOException {
        if (mode != Mode.CODEC || cacheDir == null) return null;
        final byte[] hash = keyHash(uri, headers, accept);
        final String hex = hex(hash);
        Files.createDirectories(cacheDir);
        final Path file = storeFile(Tier.CODEC, hex);
        // UNIQUE TEMP: A CODEC SESSION SPANS MANY FRAMES OVER TIME, SO IT CANNOT HOLD A STORE LOCK;
        // CONCURRENT WRITERS FOR THE SAME SOURCE GET DISTINCT TEMPS AND THE COMMIT MOVE IS ATOMIC.
        final Path tmp = file.resolveSibling(file.getFileName().toString() + '.' + System.nanoTime() + ".part");
        final OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp));
        try {
            return new CodecWriter(hash, hex, tmp, file, new BCWriter(out, width, height, format));
        } catch (final IOException e) {
            IOTool.closeQuietly(out);
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    /** Whether a committed, non-expired codec texture exists for {@code uri}. */
    public static boolean codecReadable(final URI uri, final RequestHeaders headers, final String accept) throws IOException {
        if (cacheDir == null) return false;
        final String hex = hex(keyHash(uri, headers, accept));
        return validCodecEntry(hex) != null;
    }

    /**
     * Opens a reader over the committed codec texture for {@code uri}, or {@code null} when none is
     * available. A corrupt or unreadable entry is dropped and an {@link IOException} is thrown so
     * the caller falls back to a fresh decode.
     */
    public static BCReader openCodecReader(final URI uri, final RequestHeaders headers, final String accept) throws IOException {
        if (cacheDir == null) return null;
        final String hex = hex(keyHash(uri, headers, accept));
        final Path file = validCodecEntry(hex);
        if (file == null) return null;
        try {
            return new BCReader(ByteBuffer.wrap(Files.readAllBytes(file)));
        } catch (final IOException e) {
            storeDelete(Tier.CODEC, hex);
            throw e;
        }
    }

    // RESOLVES A LIVE CODEC STORE FILE FOR hex, EVICTING THE ENTRY WHEN IT IS EXPIRED OR MISSING.
    private static Path validCodecEntry(final String hex) {
        final Entry entry;
        synchronized (NetworkCache.class) {
            entry = INDEX.get(indexKey(Tier.CODEC, hex));
        }
        if (entry == null) return null;
        final Path file = storeFile(Tier.CODEC, hex);
        if (entry.expired(System.currentTimeMillis()) || !Files.isRegularFile(file)) {
            storeDelete(Tier.CODEC, hex);
            return null;
        }
        return file;
    }

    // ==========================================================================
    // NETWORK FETCH
    // ==========================================================================
    private static CachedBytes fetch(final URI uri, final RequestHeaders headers, final String accept, final long maxBytes) throws IOException {
        final NetRequest.Builder builder = NetRequest.create(uri).method("GET");
        applyHeaders(builder, headers);
        if (accept != null && (headers == null || !headers.has("Accept"))) {
            builder.accept(accept);
        }
        // SOME CDNs (e.g. googlevideo) THROTTLE PLAIN, NON-RANGE GETS TO ~REAL-TIME BITRATE, SO A FULL GET
        // OF A FEW-MB AUDIO TRACK TRICKLES IN OVER ~90s AND STALLS PLAYBACK START. A bytes=0- RANGE FORCES
        // FULL-SPEED DELIVERY (HTTP 206) WHILE STILL RETURNING THE WHOLE BODY.
        if (headers == null || !headers.has("Range")) {
            builder.header("Range", "bytes=0-");
        }

        try (final NetRequest req = builder.send()) {
            // 206 IS EXPECTED FOR THE bytes=0- RANGE ABOVE; 200 STILL COMES BACK FROM SERVERS THAT IGNORE IT
            final int status = req.statusCode();
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException("HTTP " + status + " for " + uri);
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
    // CODEC WRITE SESSION
    // ==========================================================================
    /**
     * A streaming codec write session. Frames flow in via {@link #write}; {@link #commit} finalizes
     * the DDS (patching its frame count), atomically publishes it to the codec tier, and indexes it.
     * The session owns a private temp file until commit, so an aborted or failed write never leaves
     * a half-written entry behind. Not thread-safe — drive it from a single producer.
     */
    public static final class CodecWriter implements Closeable {
        private final byte[] hash;
        private final String hex;
        private final Path tmp;
        private final Path file;
        private final BCWriter writer;
        private boolean closed;

        CodecWriter(final byte[] hash, final String hex, final Path tmp, final Path file, final BCWriter writer) {
            this.hash = hash;
            this.hex = hex;
            this.tmp = tmp;
            this.file = file;
            this.writer = writer;
        }

        /** Encodes one frame and appends it to the texture with its display delay in milliseconds. */
        public void write(final ByteBuffer frame, final long delayMs) throws IOException {
            if (this.closed) throw new IOException("Codec writer is closed");
            this.writer.writeFrame(frame, delayMs);
        }

        /** Frames written so far. */
        public int frameCount() {
            return this.writer.frameCount();
        }

        /**
         * Finalizes and publishes the texture. A session with no frames is discarded instead.
         * Idempotent: a second call (e.g. from {@link #close()}) does nothing.
         */
        public void commit() throws IOException {
            if (this.closed) return;
            this.closed = true;
            if (this.writer.frameCount() == 0) {
                this.discard();
                return;
            }
            try {
                this.writer.close();
                DDSHeader.patchArraySize(this.tmp, this.writer.frameCount());
                IOTool.move(this.tmp, this.file);
            } catch (final IOException e) {
                // FINALIZE FAILED — DON'T LEAVE A HALF-WRITTEN TEMP BEHIND.
                try {
                    Files.deleteIfExists(this.tmp);
                } catch (final IOException ignored) {}
                throw e;
            }
            synchronized (NetworkCache.class) {
                INDEX.put(indexKey(Tier.CODEC, this.hex), new Entry(this.hash, Tier.CODEC, CODEC_NEVER_EXPIRES, CODEC_CONTENT_TYPE));
                writeIndex();
            }
        }

        /** Discards the in-progress texture without publishing it. */
        public void abort() {
            if (this.closed) return;
            this.closed = true;
            this.discard();
        }

        private void discard() {
            IOTool.closeQuietly(this.writer);
            try {
                Files.deleteIfExists(this.tmp);
            } catch (final IOException e) {
                LOGGER.warn(IT, "Failed to delete aborted codec temp {}", this.tmp, e);
            }
        }

        @Override
        public void close() {
            this.abort();
        }
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
