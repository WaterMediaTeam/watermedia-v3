package org.watermedia.api.media;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.*;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.ThreadTool;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Media Resource Locator - Container for URIs and their various qualities.
 * <p>
 * An MRL represents a "gallery" of sources that can contain multiple videos, images,
 * or combinations thereof. Each uri can have multiple qualities and optional
 * slave tracks (audio, subtitles).
 * <p>
 * Thread-safe and cached to avoid duplicate requests from IPlatform implementations.
 */
public final class MRL {
    private static final Marker IT = MarkerManager.getMarker(MRL.class.getSimpleName());
    private static final Map<URI, MRL> LOADED = new ConcurrentHashMap<>(1024);
    private static final Executor LOADER = ThreadTool.createRecomendedThreadPool("MRL-Loader", Thread.NORM_PRIORITY - 1);

    // INSTANCE FIELDS
    public final URI uri;
    private volatile Source[] sources;
    private volatile Instant expiresAt;
    private final List<Consumer<MRL>> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean ready;
    private volatile Throwable exception;

    private MRL(final URI uri) { this.uri = Objects.requireNonNull(uri, "URI cannot be null"); }

    /**
     * Gets or creates an MRL for the given URI.
     * If cached and not expired, returns immediately.
     * Otherwise, starts async platform lookup.
     *
     * @see PlatformAPI
     * @param uri the media URI
     * @return the MRL instance (may still be loading)
     */
    static MRL getMRL(final URI uri) {
        Objects.requireNonNull(uri, "URI cannot be null");

        // CREATE IF DOESN'T EXIST, AND RELOAD IF WAS EXPIRED
        return LOADED.compute(uri, (key, existing) -> {
            if (existing != null) {
                if (existing.expired())
                    existing.reload();
                return existing;
            }
            // CREATE NEW AND START LOADING
            final MRL mrl = new MRL(key);
            LOADER.execute(mrl::load);
            return mrl;
        });
    }

    /**
     * Preloads multiple URIs in parallel.
     * Useful for prefetching playlists or a bunch of well known URLs.
     * @return array of all MRL instances created/existing, in the same order as {@code uris}
     */
    public static MRL[] preload(final URI... uris) {
        final MRL[] result = new MRL[uris.length];
        for (int i = 0; i < uris.length; i++) {
            result[i] = getMRL(uris[i]); // WILL START ASYNC LOAD IF NOT CACHED
        }
        return result;
    }

    /**
     * Reloads all cached MRLs in place. Each MRL's {@link #sources()} array becomes
     * stale until the async reload completes; existing players keep their reference
     * to the MRL and will see the new sources once {@link #ready()} flips back to true.
     */
    public static void reloadAll() {
        LOADED.forEach((uri, mrl) -> mrl.reload());
    }

    /**
     * Restarts the async platform lookup for this MRL. Clears the previous
     * sources/error state immediately; consumers should poll {@link #ready()}
     * (or re-subscribe) to detect completion.
     */
    public void reload() {
        this.sources = null;
        this.expiresAt = null;
        this.ready = false;
        this.exception = null;
        LOADER.execute(this::load);
    }

    private void load() {
        try {
            PlatformData data = PlatformAPI.fetch(this.uri);
            if (data == null) {
                try (final NetRequest req = NetRequest.create(this.uri).method("GET").accept(NetRequest.ACCEPT_MEDIA).send()) {
                    LOGGER.debug("Connected to {} with content type {}", this.uri, req.contentType());

                    if (!DataTool.startsWidth(req.contentType(), "video", "image", "audio", "application/vnd.rn-realmedia", "application/vnd.rn-realmedia-vbr", "application/vnd.apple.mpegurl", "application/octet-stream")) {
                        throw new UnsupportedOperationException("The MRL content is not multimedia, received: " + req.contentType());
                    }

                    final var entry = new DataSource(MediaType.of(req.contentType()), null, null,
                            RequestHeaders.defaults(this.uri),
                            new DataQuality[] {new DataQuality(this.uri, 0, 0)},
                            null, null);

                    data = new PlatformData(null, entry);
                }
            }

            if (data.size() > 0) {
                this.sources = toSources(data);
                this.expiresAt = data.expires();
                this.ready = true;
                this.fireListeners();
                LOGGER.info(IT, "Loaded {} uri(s) for: {}", data.size(), this.uri);
            } else {
                throw new IllegalStateException("[INTERNAL] Result is null or is empty");
            }
        } catch (final Throwable t) {
            LOGGER.error(IT, "Failed to load sources for: {}", this.uri, t);
            this.exception = t;
            this.ready = true;
            this.fireListeners();
        }
    }

    private void fireListeners() {
        // Drain under the list's own lock semantics — any subscribe() that lands
        // after this point is handled by subscribe() itself (fire-immediately).
        synchronized (this.listeners) {
            for (final Consumer<MRL> c: this.listeners) {
                try { c.accept(this); }
                catch (final Throwable t) { LOGGER.error(IT, "Listener failed for {}", this.uri, t); }
            }
            this.listeners.clear();
        }
    }

    /**
     * Converts the platform-facing {@link DataSource} array into the
     * player-facing {@link Source} array. Each {@link DataQuality} carries the
     * raw {@code width}/{@code height} the platform reported; MRL is the one
     * that maps those into a {@link MediaQuality} bucket here. Platform slave
     * tracks ({@link DataSlave}) are translated to MRL's own
     * {@link SlaveEntry} the player understands.
     */
    private static Source[] toSources(final PlatformData data) {
        final DataSource[] entries = data.entries();
        final Source[] sources = new Source[entries.length];
        for (int i = 0; i < entries.length; i++) {
            final DataSource entry = entries[i];
            if (entry == null) throw new IllegalArgumentException("[INTERNAL] Platform delivered a null entry");
            final EnumMap<MediaQuality, URI> qualities = new EnumMap<>(MediaQuality.class);
            for (final DataQuality v: entry.variants()) {
                qualities.put(MediaQuality.of(v.width(), v.height()), v.uri());
            }
            sources[i] = new Source(entry.type(), entry.thumbnail(), entry.metadata(),
                    entry.headers(), qualities,
                    toSlaves(entry.audioSlaves()), toSlaves(entry.subSlaves()));
        }
        return sources;
    }

    private static List<SlaveEntry> toSlaves(final List<DataSlave> slaves) {
        if (slaves == null || slaves.isEmpty()) return null;
        final List<SlaveEntry> out = new ArrayList<>(slaves.size());
        for (final DataSlave s: slaves) {
            out.add(new SlaveEntry(s.name(), s.lang(), s.uri()));
        }
        return out;
    }

    /**
     * Blocks until loading completes or times out.
     * This method call is highly disliked on Game environments where
     * are tick-based, is strongly recommended to check {@link MRL#ready()} every tick
     *
     * @param timeoutMs maximum wait time in milliseconds
     * @return true if ready, false if timeout or error
     */
    public boolean await(final long timeoutMs) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (!this.ready && System.currentTimeMillis() < deadline) {
            ThreadTool.sleep(10);
        }
        return this.ready;
    }

    /**
     * Registers a new listener, gets triggered when {@link MRL#ready()} is true.
     * If the MRL is already ready when this is called, the listener fires immediately
     * on the calling thread; otherwise it fires on the loader thread once loading
     * finishes. After firing, all listeners are dropped — re-subscribe across reloads.
     * <p>
     * This method call is highly disliked on Game environments where
     * are tick-based, is strongly recommended to check {@link MRL#ready()} every tick
     * </p>
     * @param listener Consumer instance that accepts the ready MRL.
     */
    public void subscribe(final Consumer<MRL> listener) {
        synchronized (this.listeners) {
            if (this.ready) {
                try { listener.accept(this); }
                catch (final Throwable t) { LOGGER.error(IT, "Listener failed for {}", this.uri, t); }
                return;
            }
            this.listeners.add(listener);
        }
    }

    /**
     * Checks if the MRL finish loading and can be validated
     * @return true is loading has finished, you must validate if is valid and has any existing error using {@link #hasError()}
     */
    public boolean ready() {
        return this.ready;
    }

    /**
     * Checks if the MRL has encountered any error during loading, if there's ANY error present that menas
     * the MRL has failed ENTIRELY to load and stays in an invalid state.
     * @return true if there's any error present and the MRL is not able to create any player.
     */
    public boolean hasError() {
        return this.exception != null;
    }

    /**
     * Returns the exception instance occurred while loading the MRL.
     * This got cleared on reload
     */
    public Throwable exception() {
        return this.exception;
    }

    /**
     * Checks if cache entry has expired.
     * @see PlatformData#expires()
     */
    public boolean expired() {
        return this.expiresAt != null && this.expiresAt.isBefore(Instant.now());
    }

    /**
     * Returns all {@link Source} instances, or empty array if not ready.
     */
    public List<Source> sources() {
        final Source[] s = this.sources;
        return s != null ? List.of(s) : List.of();
    }

    /**
     * Returns the available number of {@link Source} instances.
     */
    public int sourceCount() {
        final Source[] s = this.sources;
        return s != null ? s.length : 0;
    }

    /**
     * Gets the {@link Source} by index.
     *
     * @param index uri index
     * @return the {@link Source}, or null if invalid index or not ready
     */
    public Source source(final int index) {
        final Source[] s = this.sources;
        if (s == null || index < 0 || index >= s.length) {
            return null;
        }
        return s[index];
    }

    /**
     * Gets the first {@link Source} of a specific type.
     *
     * @param type the media type to find
     * @return the first matching {@link Source}, or null if none found
     */
    public Source sourceByType(final MediaType type) {
        final Source[] s = this.sources;
        if (s == null) return null;

        for (final Source src: s) {
            if (src.type == type) return src;
        }
        return null;
    }

    /**
     * Gets all {@link Source} instances of a specific type.
     *
     * @param type the media type to filter by
     * @return List of matching {@link Source} (not null)
     */
    public List<Source> sourcesByType(final MediaType type) {
        final Source[] s = this.sources;
        if (s == null) return List.of();

        return Arrays.stream(s)
                .filter(src -> src.type == type)
                .toList();
    }

    /**
     * Gets the first video {@link Source}.
     */
    public Source videoSource() {
        return this.sourceByType(MediaType.VIDEO);
    }

    /**
     * Gets the first image {@link Source}.
     */
    public Source imageSource() {
        return this.sourceByType(MediaType.IMAGE);
    }

    /**
     * Gets the first audio-only {@link Source}.
     */
    public Source audioSource() {
        return this.sourceByType(MediaType.AUDIO);
    }

    /**
     * Internal: rewires a quality bucket inside an existing {@link Source}.
     * Used by FFMediaPlayer when an HLS rendition turns out to map to a
     * different bucket than the platform reported. Public only because the
     * caller lives in a sibling package — not part of the supported API.
     *
     * @apiNote do not call from application code.
     */
    public void moveQuality(final int sourceIndex, final MediaQuality from, final MediaQuality to) {
        final Source[] s = this.sources;
        if (s == null || sourceIndex < 0 || sourceIndex >= s.length) return;
        final Source src = s[sourceIndex];
        if (!src.qualities().containsKey(from)) return;
        final EnumMap<MediaQuality, URI> newQualities = new EnumMap<>(src.qualities());
        final URI uri = newQualities.remove(from);
        newQualities.put(to, uri);
        s[sourceIndex] = new Source(src.type(), src.thumbnail(), src.metadata(), src.headers(), newQualities, src.audioSlaves(), src.subSlaves());
    }



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
        return "MRL{uri=" + this.uri + ", ready=" + this.ready + ", error=" + this.exception + ", sources=" + this.sourceCount() + "}";
    }

    /**
     * A URI Source container with multiple quality support and optional slave sources.
     */
    public record Source(MediaType type, URI thumbnail, Metadata metadata, RequestHeaders headers,
                         EnumMap<MediaQuality, URI> qualities, List<SlaveEntry> audioSlaves,
                         List<SlaveEntry> subSlaves) {

        public Source {
            if (qualities.isEmpty())
                throw new IllegalArgumentException("Source constructed with no qualities");

            audioSlaves = audioSlaves == null ? List.of() : List.copyOf(audioSlaves);
            subSlaves = subSlaves == null ? List.of() : List.copyOf(subSlaves);
            if (headers == null) headers = new RequestHeaders();
        }

        /**
         * Gets the URI for a specific quality, falling back to closest available.
         */
        public URI uri(final MediaQuality quality) {
            if (this.qualities.containsKey(quality)) {
                return this.qualities.get(quality);
            }
            final MediaQuality closest = MediaQuality.closest(this.qualities.keySet(), quality);

            if (closest == null)
                throw new IllegalStateException("No quality was found");

            return this.qualities.get(closest);
        }

        public MediaQuality qualityOf(final URI uri) {
            if (uri == null) return null;
            for (final var q: this.qualities.entrySet()) {
                if (uri.equals(q.getValue())) {
                    return q.getKey();
                }
            }
            return null;
        }

        /**
         * Gets the best available URI (highest quality).
         */
        public URI bestUri() {
            return this.uri(MediaQuality.HIGHER);
        }

        /**
         * Gets the worst available URI (lowest quality).
         */
        public URI worstUri() {
            return this.uri(MediaQuality.Q144P);
        }

        /**
         * Returns all available qualities.
         */
        public Set<MediaQuality> availableQualities() {
            return this.qualities.keySet();
        }

        /**
         * Returns true if a specific quality is available.
         */
        public boolean hasQuality(final MediaQuality quality) {
            return this.qualities.containsKey(quality);
        }

        /**
         * Returns true if this uri has slave tracks.
         */
        public boolean hasSlaves() {
            return this.audioSlaves != null && !this.audioSlaves.isEmpty();
        }

        /**
         * Gets all audio slave tracks.
         */
        public List<SlaveEntry> audioSlaves() {
            if (this.audioSlaves == null) return List.of();
            return this.audioSlaves;
        }

        /**
         * Gets all subtitle slave tracks.
         */
        public List<SlaveEntry> subSlaves() {
            if (this.subSlaves == null) return List.of();
            return this.subSlaves;
        }

        /**
         * Returns true if this is a video uri.
         */
        public boolean isVideo() {
            return this.type == MediaType.VIDEO;
        }

        /**
         * Returns true if this is an image uri.
         */
        public boolean isImage() {
            return this.type == MediaType.IMAGE;
        }

        /**
         * Returns true if this is an audio-only uri.
         */
        public boolean isAudio() {
            return this.type == MediaType.AUDIO;
        }
    }

    /**
     * Slave entry for a media {@link Source}
     */
    public record SlaveEntry(String name, String lang, URI uri) {
    }
}
