package org.watermedia.api.media;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.media.engines.ALEngine;
import org.watermedia.api.media.engines.GLEngine;
import org.watermedia.api.media.players.FFMediaPlayer;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.api.media.players.TxMediaPlayer;
import org.watermedia.tools.ThreadTool;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Media Resource Locator - Container for URIs and their various qualities.
 * <p>
 * An MRL represents a "gallery" of sources that can contain multiple videos, images,
 * or combinations thereof. Each source can have multiple qualities and optional
 * slave tracks (audio, subtitles).
 * <p>
 * Thread-safe and cached to avoid duplicate requests from IPlatform implementations.
 */
public final class MRL {
    private static final Marker IT = MarkerManager.getMarker(MRL.class.getSimpleName());
    private static final Map<URI, MRL> CACHE = new ConcurrentHashMap<>(64);
    private static final Executor LOADER = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            ThreadTool.createFactory("MRL-Loader", Thread.NORM_PRIORITY - 1)
    );

    // Cache configuration
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);
    private static final int MAX_CACHE_SIZE = 256;

    // Instance fields
    public final URI uri;
    private volatile Source[] sources;
    private volatile Instant expiresAt;
    private volatile boolean ready;
    private volatile boolean error;

    private MRL(final URI uri) {
        this.uri = Objects.requireNonNull(uri, "URI cannot be null");
    }

    // =========================================================================
    // STATIC FACTORY & CACHE
    // =========================================================================

    /**
     * Gets or creates an MRL for the given URI.
     * If cached and not expired, returns immediately.
     * Otherwise, starts async loading via IPlatform.
     *
     * @param uri the media URI
     * @return the MRL instance (may still be loading)
     */
    public static MRL get(final URI uri) {
        Objects.requireNonNull(uri, "URI cannot be null");

        // Cleanup expired entries periodically
        if (CACHE.size() > MAX_CACHE_SIZE) {
            evictExpired();
        }

        return CACHE.compute(uri, (key, existing) -> {
            // Return existing if valid and not expired
            if (existing != null && !existing.expired()) {
                return existing;
            }

            // Create new and start loading
            final MRL mrl = new MRL(key);
            LOADER.execute(mrl::load);
            return mrl;
        });
    }

    /**
     * Preloads multiple URIs in parallel.
     * Useful for prefetching playlist items.
     */
    public static void preload(final URI... uris) {
        for (final URI uri : uris) {
            get(uri); // Will start async load if not cached
        }
    }

    /**
     * Invalidates a cached MRL, forcing reload on next access.
     */
    public static void invalidate(final URI uri) {
        CACHE.remove(uri);
    }

    /**
     * Clears the entire cache.
     */
    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * Returns current cache size.
     */
    public static int cacheSize() {
        return CACHE.size();
    }

    private static void evictExpired() {
        CACHE.entrySet().removeIf(e -> e.getValue().expired());
    }

    // =========================================================================
    // LOADING
    // =========================================================================

    private void load() {
        try {
            LOGGER.debug(IT, "Loading sources for: {}", this.uri);
            final Source[] loaded = MediaAPI.getSources(this.uri);

            if (loaded != null && loaded.length > 0) {
                this.sources = loaded;
                this.expiresAt = Instant.now().plus(DEFAULT_TTL);
                this.ready = true;
                LOGGER.debug(IT, "Loaded {} source(s) for: {}", loaded.length, this.uri);
            } else {
                this.error = true;
            }
        } catch (final Throwable t) {
            LOGGER.error(IT, "Failed to load sources for: {}", this.uri, t);
            this.error = true;
        }
    }

    // =========================================================================
    // STATE QUERIES
    // =========================================================================

    /**
     * Returns true if sources are ready to use.
     */
    public boolean ready() {
        return this.ready;
    }

    /**
     * Returns true if loading failed.
     */
    public boolean error() {
        return this.error;
    }

    /**
     * Returns true if still loading (not ready and no error).
     */
    public boolean busy() {
        return !this.ready && !this.error;
    }

    /**
     * Returns true if cache entry has expired.
     */
    public boolean expired() {
        return this.expiresAt != null && this.expiresAt.isBefore(Instant.now());
    }

    /**
     * Blocks until loading completes or times out.
     *
     * @param timeoutMs maximum wait time in milliseconds
     * @return true if ready, false if timeout or error
     */
    public boolean await(final long timeoutMs) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (this.busy() && System.currentTimeMillis() < deadline) {
            ThreadTool.sleep(10);
        }
        return this.ready;
    }

    // =========================================================================
    // SOURCE ACCESS
    // =========================================================================

    /**
     * Returns all sources, or empty array if not ready.
     */
    public Source[] sources() {
        final Source[] s = this.sources;
        return s != null ? s : new Source[0];
    }

    /**
     * Returns the number of sources.
     */
    public int sourceCount() {
        final Source[] s = this.sources;
        return s != null ? s.length : 0;
    }

    /**
     * Gets a source by index, with bounds checking.
     *
     * @param index source index
     * @return the source, or null if invalid index or not ready
     */
    public Source source(final int index) {
        final Source[] s = this.sources;
        if (s == null || index < 0 || index >= s.length) {
            return null;
        }
        return s[index];
    }

    /**
     * Gets the first source of a specific type.
     *
     * @param type the media type to find
     * @return the first matching source, or null if none found
     */
    public Source sourceByType(final MediaType type) {
        final Source[] s = this.sources;
        if (s == null) return null;

        for (final Source src : s) {
            if (src.type == type) return src;
        }
        return null;
    }

    /**
     * Gets all sources of a specific type.
     *
     * @param type the media type to filter by
     * @return array of matching sources (never null)
     */
    public Source[] sourcesByType(final MediaType type) {
        final Source[] s = this.sources;
        if (s == null) return new Source[0];

        return Arrays.stream(s)
                .filter(src -> src.type == type)
                .toArray(Source[]::new);
    }

    /**
     * Gets the first video source.
     */
    public Source videoSource() {
        return this.sourceByType(MediaType.VIDEO);
    }

    /**
     * Gets the first image source.
     */
    public Source imageSource() {
        return this.sourceByType(MediaType.IMAGE);
    }

    /**
     * Gets the first audio-only source.
     */
    public Source audioSource() {
        return this.sourceByType(MediaType.AUDIO);
    }

    // =========================================================================
    // MEDIA PLAYER FACTORY
    // =========================================================================

    /**
     * Creates the optimal MediaPlayer for the first available source.
     */
    public MediaPlayer createPlayer(final Thread renderThread, final Executor renderThreadEx,
                                    final GLEngine glEngine, final ALEngine alEngine,
                                    final boolean video, final boolean audio) {
        return this.createPlayer(0, renderThread, renderThreadEx, glEngine, alEngine, video, audio);
    }

    /**
     * Creates a MediaPlayer for a specific source and quality.
     */
    public MediaPlayer createPlayer(final int sourceIndex,
                                    final Thread renderThread, final Executor renderThreadEx,
                                    final GLEngine glEngine, final ALEngine alEngine,
                                    final boolean video, final boolean audio) {
        final Source source = this.source(sourceIndex);
        if (source == null) {
            LOGGER.warn(IT, "Cannot create player: source {} not available for {}", sourceIndex, this.uri);
            return null;
        }

        try {
            if (source.type == MediaType.IMAGE) {
                LOGGER.debug(IT, "Creating TxMediaPlayer for image: {}", source);
                return new TxMediaPlayer(source, renderThread, renderThreadEx, glEngine, video);
            }

            if (FFMediaPlayer.loaded()) {
                LOGGER.debug(IT, "Creating FFMediaPlayer for: {}", source);
                return new FFMediaPlayer(source, renderThread, renderThreadEx, glEngine, alEngine, video, audio);
            }

            LOGGER.error(IT, "No media backend available for: {}", this.uri);
        } catch (final Throwable t) {
            LOGGER.error(IT, "Failed to create player for: {}", this.uri, t);
        }
        return null;
    }

    // =========================================================================
    // EXPIRATION MANAGEMENT
    // =========================================================================

    /**
     * Sets the expiration time for this MRL's cache entry.
     *
     * @param duration time until expiration
     */
    public void setTTL(final Duration duration) {
        this.expiresAt = Instant.now().plus(duration);
    }

    /**
     * Sets an absolute expiration time.
     *
     * @param instant when this entry expires
     */
    public void setExpiresAt(final Instant instant) {
        this.expiresAt = instant;
    }

    // =========================================================================
    // OBJECT METHODS
    // =========================================================================

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof final MRL mrl)) return false;
        return this.uri.equals(mrl.uri);
    }

    @Override
    public int hashCode() {
        return this.uri.hashCode();
    }

    @Override
    public String toString() {
        return "MRL{uri=" + this.uri + ", ready=" + this.ready + ", error=" + this.error + ", sources=" + this.sourceCount() + "}";
    }

    // =========================================================================
    // NESTED TYPES
    // =========================================================================

    /**
     * Media types supported by sources.
     */
    public enum MediaType {
        IMAGE,
        VIDEO,
        AUDIO,
        SUBTITLES,
        UNKNOWN;

        /**
         * Parses a MIME type string into MediaType.
         */
        public static MediaType fromMimeType(final String mimeType) {
            if (mimeType == null || mimeType.isEmpty()) return UNKNOWN;

            final String[] type = mimeType.split("/");
            return switch (type[0].toLowerCase()) {
                case "image" -> IMAGE;
                case "video" -> VIDEO;
                case "audio" -> AUDIO;
                case "text" -> {
                    final String subtype = type.length == 2 ? type[1].toLowerCase() : "";
                    yield (subtype.equals("vtt") || subtype.equals("srt") || subtype.contains("subtitle")) ? SUBTITLES : UNKNOWN;
                }
                default -> UNKNOWN;
            };
        }

        /**
         * Returns true if this type supports video playback.
         */
        public boolean hasVideo() {
            return this == VIDEO || this == IMAGE;
        }

        /**
         * Returns true if this type supports audio playback.
         */
        public boolean hasAudio() {
            return this == VIDEO || this == AUDIO;
        }
    }

    /**
     * Quality levels for media streams.
     */
    public enum Quality {
        UNKNOWN(0),
        Q144P(144),
        LOWEST(240),
        LOW(360),
        MEDIUM(480),
        HIGH(720),
        HIGHER(1080),
        HIGHEST(1440),
        Q4K(2160),
        Q8K(4320);

        private static final Quality[] VALUES = values();

        public final int threshold;

        Quality(final int threshold) {
            this.threshold = threshold;
        }

        /**
         * Determines quality from video dimensions.
         *
         * @param width  video width
         * @param height video height
         * @return the appropriate quality level
         */
        public static Quality of(final int width, final int height) {
            final int size = Math.min(width, height); // Use smaller dimension (height for landscape)
            if (size <= 0) return UNKNOWN;

            // Binary search would be overkill for 10 elements
            for (int i = VALUES.length - 1; i >= 0; i--) {
                if (size >= VALUES[i].threshold) {
                    return VALUES[i];
                }
            }
            return Q144P;
        }

        /**
         * Gets the next higher quality level.
         */
        public Quality higher() {
            final int next = this.ordinal() + 1;
            return next < VALUES.length ? VALUES[next] : this;
        }

        /**
         * Gets the next lower quality level.
         */
        public Quality lower() {
            final int prev = this.ordinal() - 1;
            return prev >= 0 ? VALUES[prev] : this;
        }

        /**
         * Finds the closest available quality from a set.
         *
         * @param available set of available qualities
         * @param preferred the preferred quality
         * @return the closest available quality, or null if none available
         */
        public static Quality closest(final Set<Quality> available, final Quality preferred) {
            if (available == null || available.isEmpty()) return null;
            if (available.contains(preferred)) return preferred;

            // Search outward from preferred
            Quality lower = preferred.lower();
            Quality higher = preferred.higher();

            while (lower != preferred || higher != preferred) {
                if (lower != preferred) {
                    if (available.contains(lower)) return lower;
                    lower = lower.lower();
                }
                if (higher != preferred) {
                    if (available.contains(higher)) return higher;
                    higher = higher.higher();
                }
                // Prevent infinite loop
                if (lower.ordinal() == 0 && higher.ordinal() == VALUES.length - 1) break;
            }

            // Fallback: return any available
            return available.iterator().next();
        }
    }

    /**
     * Metadata for a media source.
     */
    public record Metadata(
            String title,
            String description,
            URI thumbnail,
            Instant publishedAt,
            long duration,
            String author
    ) {
        public Metadata(final String title, final long duration) {
            this(title, null, null, null, duration, null);
        }

        public Metadata(final String title, final String description, final long duration) {
            this(title, description, null, null, duration, null);
        }
    }

    /**
     * A slave track attached to a main source (audio track, subtitles).
     */
    public record Slave(
            SlaveType type,
            String language,
            String label,
            EnumMap<Quality, URI> qualities
    ) {
        public Slave(final SlaveType type, final URI uri) {
            this(type, null, null, new EnumMap<>(Map.of(Quality.UNKNOWN, uri)));
        }

        public Slave(final SlaveType type, final String language, final URI uri) {
            this(type, language, null, new EnumMap<>(Map.of(Quality.UNKNOWN, uri)));
        }

        /**
         * Gets the URI for a specific quality, falling back to closest available.
         */
        public URI uri(final Quality quality) {
            if (this.qualities.containsKey(quality)) {
                return this.qualities.get(quality);
            }
            final Quality closest = Quality.closest(this.qualities.keySet(), quality);
            return closest != null ? this.qualities.get(closest) : null;
        }

        /**
         * Gets the best available URI.
         */
        public URI bestUri() {
            return this.uri(Quality.HIGHEST);
        }

        /**
         * Adds or updates a quality level.
         */
        public Slave withQuality(final Quality quality, final URI uri) {
            final EnumMap<Quality, URI> newQualities = new EnumMap<>(this.qualities);
            newQualities.put(quality, uri);
            return new Slave(this.type, this.language, this.label, newQualities);
        }
    }

    /**
     * Types of slave tracks.
     */
    public enum SlaveType {
        AUDIO,
        SUBTITLES
    }

    /**
     * A media source with multiple quality options and optional slave tracks.
     */
    public record Source(MediaType type, EnumMap<Quality, URI> qualities, List<Slave> slaves, Metadata metadata) {

        public Source(final MediaType type, final URI uri) {
            this(type, new EnumMap<>(Map.of(Quality.UNKNOWN, uri)), List.of(), null);
        }

        public Source(final MediaType type, final URI uri, final Metadata metadata) {
            this(type, new EnumMap<>(Map.of(Quality.UNKNOWN, uri)), List.of(), metadata);
        }

        public Source(final MediaType type, final EnumMap<Quality, URI> qualities) {
            this(type, qualities, List.of(), null);
        }

        public Source(final MediaType type, final EnumMap<Quality, URI> qualities, final Metadata metadata) {
            this(type, qualities, List.of(), metadata);
        }

        // Quality access

        /**
         * Gets the URI for a specific quality, falling back to closest available.
         */
        public URI uri(final Quality quality) {
            if (this.qualities.containsKey(quality)) {
                return this.qualities.get(quality);
            }
            final Quality closest = Quality.closest(this.qualities.keySet(), quality);

            if (closest == null)
                throw new IllegalStateException("No quality was found");

            return this.qualities.get(closest);
        }

        /**
         * Gets the best available URI (highest quality).
         */
        public URI bestUri() {
            return this.uri(Quality.HIGHEST);
        }

        /**
         * Gets the worst available URI (lowest quality).
         */
        public URI worstUri() {
            return this.uri(Quality.Q144P);
        }

        /**
         * Returns all available qualities.
         */
        public Set<Quality> availableQualities() {
            return this.qualities.keySet();
        }

        /**
         * Returns true if a specific quality is available.
         */
        public boolean hasQuality(final Quality quality) {
            return this.qualities.containsKey(quality);
        }

        // Slave access

        /**
         * Returns true if this source has slave tracks.
         */
        public boolean hasSlaves() {
            return this.slaves != null && !this.slaves.isEmpty();
        }

        /**
         * Gets all audio slave tracks.
         */
        public List<Slave> audioSlaves() {
            if (this.slaves == null) return List.of();
            return this.slaves.stream()
                    .filter(s -> s.type == SlaveType.AUDIO)
                    .toList();
        }

        /**
         * Gets all subtitle slave tracks.
         */
        public List<Slave> subtitleSlaves() {
            if (this.slaves == null) return List.of();
            return this.slaves.stream()
                    .filter(s -> s.type == SlaveType.SUBTITLES)
                    .toList();
        }

        /**
         * Finds a slave track by language.
         */
        public Slave slaveByLanguage(final SlaveType type, final String language) {
            if (this.slaves == null || language == null) return null;
            return this.slaves.stream()
                    .filter(s -> s.type == type && language.equalsIgnoreCase(s.language))
                    .findFirst()
                    .orElse(null);
        }

        // Mutation (returns new instances)

        /**
         * Adds or updates a quality level.
         */
        public Source withQuality(final Quality quality, final URI uri) {
            final EnumMap<Quality, URI> newQualities = new EnumMap<>(this.qualities);
            newQualities.put(quality, uri);
            return new Source(this.type, newQualities, this.slaves, this.metadata);
        }

        /**
         * Reassigns a quality from one level to another.
         */
        public Source reassignQuality(final Quality from, final Quality to) {
            if (!this.qualities.containsKey(from)) return this;

            final EnumMap<Quality, URI> newQualities = new EnumMap<>(this.qualities);
            final URI uri = newQualities.remove(from);
            newQualities.put(to, uri);
            return new Source(this.type, newQualities, this.slaves, this.metadata);
        }

        /**
         * Adds a slave track.
         */
        public Source withSlave(final Slave slave) {
            final List<Slave> newSlaves = new ArrayList<>(this.slaves != null ? this.slaves : List.of());
            newSlaves.add(slave);
            return new Source(this.type, this.qualities, List.copyOf(newSlaves), this.metadata);
        }

        /**
         * Sets the metadata.
         */
        public Source withMetadata(final Metadata metadata) {
            return new Source(this.type, this.qualities, this.slaves, metadata);
        }

        // Type queries

        /**
         * Returns true if this is a video source.
         */
        public boolean isVideo() {
            return this.type == MediaType.VIDEO;
        }

        /**
         * Returns true if this is an image source.
         */
        public boolean isImage() {
            return this.type == MediaType.IMAGE;
        }

        /**
         * Returns true if this is an audio-only source.
         */
        public boolean isAudio() {
            return this.type == MediaType.AUDIO;
        }
    }

    // =========================================================================
    // BUILDER FOR COMPLEX SOURCES
    // =========================================================================

    /**
     * Builder for creating Source instances with complex configurations.
     */
    public static final class SourceBuilder {
        private final MediaType type;
        private final EnumMap<Quality, URI> qualities = new EnumMap<>(Quality.class);
        private final List<Slave> slaves = new ArrayList<>();
        private Metadata metadata;

        public SourceBuilder(final MediaType type) {
            this.type = Objects.requireNonNull(type);
        }

        public SourceBuilder quality(final Quality quality, final URI uri) {
            this.qualities.put(quality, uri);
            return this;
        }

        public SourceBuilder uri(final URI uri) {
            return this.quality(Quality.UNKNOWN, uri);
        }

        public SourceBuilder slave(final Slave slave) {
            this.slaves.add(slave);
            return this;
        }

        public SourceBuilder audioSlave(final URI uri) {
            return this.slave(new Slave(SlaveType.AUDIO, uri));
        }

        public SourceBuilder audioSlave(final String language, final URI uri) {
            return this.slave(new Slave(SlaveType.AUDIO, language, uri));
        }

        public SourceBuilder subtitleSlave(final URI uri) {
            return this.slave(new Slave(SlaveType.SUBTITLES, uri));
        }

        public SourceBuilder subtitleSlave(final String language, final URI uri) {
            return this.slave(new Slave(SlaveType.SUBTITLES, language, uri));
        }

        public SourceBuilder metadata(final Metadata metadata) {
            this.metadata = metadata;
            return this;
        }

        public SourceBuilder title(final String title) {
            this.metadata = new Metadata(title, this.metadata != null ? this.metadata.duration() : null);
            return this;
        }

        public Source build() {
            if (this.qualities.isEmpty()) {
                throw new IllegalStateException("At least one quality/URI must be specified");
            }
            return new Source(this.type, this.qualities, List.copyOf(this.slaves), this.metadata);
        }
    }

    /**
     * Creates a new SourceBuilder.
     */
    public static SourceBuilder sourceBuilder(final MediaType type) {
        return new SourceBuilder(type);
    }
}