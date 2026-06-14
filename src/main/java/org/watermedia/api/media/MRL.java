package org.watermedia.api.media;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.*;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.MPEGTools;
import org.watermedia.tools.ThreadTool;

import java.io.IOException;
import java.io.InputStream;
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
    private static volatile long NEXT_CLEAN_TIME = System.currentTimeMillis() + MathUtil.minutesToMs(60.0); // NOT CONFIGURABLE, BY DEFAULT OBEY FIRST 60 MINUTES

    // INSTANCE FIELDS
    public final URI uri;
    private volatile Source[] sources;
    private volatile Instant expiresAt;
    private final List<Consumer<MRL>> listeners = new CopyOnWriteArrayList<>();
    private volatile Status status = Status.FETCHING;
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
    static MRL get(final URI uri) {
        Objects.requireNonNull(uri, "URI cannot be null");

        // CREATE IF DOESN'T EXIST, AND RELOAD IF WAS EXPIRED
        return LOADED.compute(uri, (key, existing) -> {
            if (existing != null) {
                if (existing.status() == Status.EXPIRED)
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
    static MRL[] preload(final URI... uris) {
        final MRL[] result = new MRL[uris.length];
        for (int i = 0; i < uris.length; i++) {
            result[i] = get(uris[i]); // WILL START ASYNC LOAD IF NOT CACHED
        }
        return result;
    }

    /**
     * Restarts the async platform lookup for this MRL. Clears the previous
     * sources/error state immediately;
     */
    public void reload() {
        this.sources = null;
        this.expiresAt = null;
        this.status = Status.FETCHING;
        this.exception = null;
        LOADER.execute(this::load);
    }

    private void load() {
        if (this.status != Status.FETCHING) return;
        try {
            if (NEXT_CLEAN_TIME <= System.currentTimeMillis()) {
                synchronized (LOADED) {
                    if (NEXT_CLEAN_TIME > System.currentTimeMillis()) return; // FIRST TO LOCK HAS DONE IT
                    LOADED.forEach((uri, mrl) -> {
                        if (mrl.status().disposable()) {
                            LOADED.remove(uri);
                            mrl.status = Status.FORGOTTEN;
                        }
                    });
                    NEXT_CLEAN_TIME = System.currentTimeMillis() + MathUtil.minutesToMs(WaterMediaConfig.media.mrlManagerCleanupInterval);
                }
            }
        } catch (final Throwable ex) {
            // CLEANUP OF UNRELATED, EXPIRED MRLs FAILED — NON-FATAL FOR THIS LOAD, KEEP GOING.
            LOGGER.error(IT, "Failed to dispose forgotten MRLs", ex);
        }
        try {
            PlatformData data = PlatformAPI.fetch(this.uri);
            if (data == null) {
                try (final NetRequest req = NetRequest.create(this.uri).method("GET").accept(NetRequest.ACCEPT_MEDIA).send()) {
                    LOGGER.debug("Connected to {} with content type {}", this.uri, req.contentType());

                    // FAIL FAST ON NON-2xx
                    if (req.statusCode() >= 400) {
                        throw new IOException("HTTP " + req.statusCode() + " for " + this.uri);
                    }

                    // SCAN RECEIVED CONTENT-TYPE
                    final String contentType = req.contentType();
                    if (contentType != null && !DataTool.startsWidth(contentType, "video", "image", "audio", "application/vnd.rn-realmedia", "application/vnd.rn-realmedia-vbr", "application/vnd.apple.mpegurl", "application/x-mpegurl", "application/octet-stream")) {
                        throw new IOException("Content is not multimedia (received content-type '" + contentType + "'): " + this.uri);
                    }

                    // DETECT HLS/IPTV AND PARSE IT
                    final String pathLower = this.uri.getPath() == null ? "" : this.uri.getPath().toLowerCase();
                    final boolean m3uShape = (contentType != null && contentType.toLowerCase().contains("mpegurl")) || pathLower.endsWith(".m3u") || pathLower.endsWith(".m3u8");

                    if (m3uShape) {
                        final String body = req.readAllAsString();
                        // IPTV LISTS EXPAND INTO N CHANNELS; HLS MASTER/MEDIA (AND ANYTHING THAT
                        // FAILS TO PARSE) COLLAPSE TO A SINGLE VIDEO SOURCE THE PLAYER DRIVES ITSELF.
                        MPEGTools.Playlist parsed;
                        try {
                            parsed = MPEGTools.parse(body, this.uri);
                        } catch (final IOException badPlaylist) {
                            // NOT A RECOGNIZABLE PLAYLIST — HAND THE RAW URL TO FFmpeg BELOW
                            LOGGER.debug(IT, "Body from {} is not a recognizable playlist ({}); using the raw URL", this.uri, badPlaylist.getMessage());
                            parsed = null;
                        }
                        if (parsed instanceof final MPEGTools.Iptv iptv) {
                            final List<MPEGTools.Channel> channels = iptv.channels();
                            if (channels.isEmpty())
                                throw new IOException("IPTV playlist contained no entries: " + this.uri);
                            final List<DataSource> sources = new ArrayList<>(channels.size());
                            for (final MPEGTools.Channel e : channels) {
                                // CHANNEL URLS ARE ALREADY ABSOLUTE AND VALIDATED BY MPEGTools (MALFORMED ONES WERE DROPPED DURING PARSE)
                                final URI childUri = e.url();
                                URI logoUri = null;
                                if (e.tvgLogo() != null && !e.tvgLogo().isEmpty()) {
                                    try {
                                        logoUri = URI.create(e.tvgLogo());
                                    } catch (final
                                    IllegalArgumentException ignored) { /* TVG-LOGO IS BEST-EFFORT, BAD ENTRIES STAY UNILLUSTRATED */ }
                                }
                                final Metadata md = new Metadata(e.title(), null, null, 0, e.tvgGroup());
                                sources.add(new DataSource(MediaType.UNKNOWN, logoUri, md,
                                        RequestHeaders.defaults(childUri),
                                        new DataQuality[]{new DataQuality(childUri, 0, 0)},
                                        null, null));
                            }
                            data = new PlatformData(null, sources.toArray(DataSource[]::new));
                        } else {
                            data = new PlatformData(null, new DataSource(MediaType.VIDEO, null, null, RequestHeaders.defaults(this.uri),
                                    new DataQuality[]{new DataQuality(this.uri, 0, 0)},
                                    null, null));
                        }
                    } else {
                        MediaType type = MediaType.of(contentType);
                        if (type == MediaType.UNKNOWN) {
                            // SERVER GAVE AN AMBIGUOUS MIME (e.g. application/octet-stream). SNIFF THE
                            // LEADING BYTES — AUTHORITATIVE — THEN FALL BACK TO THE URL EXTENSION.
                            try (final InputStream in = req.inputStream()) {
                                type = CodecsAPI.getMediaType(in);
                            } catch (final IOException e) {
                                LOGGER.warn(IT, "Failed to sniff media type for {}", this.uri, e);
                            }
                            if (type == MediaType.UNKNOWN) type = MediaType.ofExtension(this.uri.getPath());
                        }

                        data = new PlatformData(null, new DataSource(type, null, null,
                                RequestHeaders.defaults(this.uri),
                                new DataQuality[]{new DataQuality(this.uri, 0, 0)},
                                null, null));
                    }
                }
            }

            if (data.size() > 0) {
                final DataSource[] entries = data.entries();
                final Source[] sources = new Source[entries.length];
                for (int i = 0; i < entries.length; i++) {
                    final DataSource entry = entries[i];
                    if (entry == null) throw new IllegalArgumentException("[INTERNAL] Platform delivered a null entry");
                    final EnumMap<MediaQuality, URI> qualities = new EnumMap<>(MediaQuality.class);
                    for (final DataQuality v : entry.variants()) {
                        qualities.put(MediaQuality.of(v.width(), v.height()), v.uri());
                    }

                    final List<DataSlave> audioSlaves = entry.audioSlaves();
                    final List<DataSlave> subSlave = entry.subSlaves();

                    sources[i] = new Source(entry.type(), entry.thumbnail(), entry.metadata(),
                            entry.headers(), qualities,
                            audioSlaves.stream().map(s -> new SlaveEntry(s.name(), s.lang(), s.uri())).toList(),
                            subSlave.stream().map(s -> new SlaveEntry(s.name(), s.lang(), s.uri())).toList());
                }


                this.sources = sources;
                this.expiresAt = data.expires();
                this.status = Status.LOADED;
                this.fireListeners();
                LOGGER.info(IT, "Loaded {} uri(s) for: {}", data.size(), this.uri);
            } else {
                throw new IllegalStateException("[INTERNAL] PlatformData resolved to zero entries for " + this.uri);
            }
        } catch (final Throwable t) {
            // STORE THE FAILURE VERBATIM SO exception() EXPOSES THE REAL MESSAGE, CAUSE AND STACK TO DEVS.
            this.exception = t;
            this.status = t instanceof MatureContentException ? Status.BLOCKED : Status.ERROR;

            // EXPECTED FAILURES (BLOCK / PLATFORM / IO) ALREADY CARRY A SELF-DESCRIBING MESSAGE: LOG IT
            // WITHOUT THE STACK TRACE. ONLY GENUINELY UNEXPECTED ERRORS DUMP THE TRACE SO THEY STAND OUT.
            if (t instanceof MatureContentException)
                LOGGER.warn(IT, "Blocked (mature content disabled) {}: {}", this.uri, t.getMessage());
            else if (t instanceof IOException) // PlatformException + DIRECT-LOAD IO FAILURES
                LOGGER.error(IT, "Failed to resolve {}: {}", this.uri, t.getMessage(), t);
            else
                LOGGER.error(IT, "Unexpected error resolving {}", this.uri, t);

            this.fireListeners();
        }
    }

    private void fireListeners() {
        synchronized (this.listeners) {
            for (final Consumer<MRL> c: this.listeners) {
                try { c.accept(this); }
                catch (final Throwable t) { LOGGER.error(IT, "Listener failed for {}", this.uri, t); }
            }
            this.listeners.clear();
        }
    }

    /**
     * Blocks until loading completes or times out.
     * This method call is highly disliked on Game environments where
     * are tick-based, is strongly recommended to check {@link MRL#status()} every tick
     *
     * @param timeoutMs maximum wait time in milliseconds
     * @return true if ready, false if timeout or error
     */
    public boolean await(final long timeoutMs) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        // IF IS STILL FETCHING AND THE DEADLINE IS NOT REACHED YET, WAIT FOR 50 MILLIS
        while (this.status == Status.FETCHING && System.currentTimeMillis() < deadline) {
            ThreadTool.sleep(50);
        }
        return this.status != Status.FETCHING; // FETCHING FINISHED, QUERY IS DONE, NEED TO HANDLE PROPER STATUS
    }

    /**
     * Registers a new listener, gets triggered when {@link MRL#status()} is true.
     * If the MRL is already ready when this is called, the listener fires immediately
     * on the calling thread; otherwise it fires on the loader thread once loading
     * finishes. After firing, all listeners are dropped — re-subscribe across reloads.
     * <p>
     * This method call is highly disliked on Game environments where
     * are tick-based, is strongly recommended to check {@link MRL#status()} every tick
     * </p>
     * @param listener Consumer instance that accepts the ready MRL.
     */
    public void subscribe(final Consumer<MRL> listener) {
        synchronized (this.listeners) {
            if (this.status == Status.LOADED) {
                try { listener.accept(this); }
                catch (final Throwable t) { LOGGER.error(IT, "Listener failed for {}", this.uri, t); }
                return;
            }
            this.listeners.add(listener);
        }
    }

    /**
     * Provides the current status of the MRL
     * Every call validates the sources has been expired.
     * @return status instance, never null
     */
    public Status status() {
        if (this.expiresAt != null && this.expiresAt.isBefore(Instant.now())) {
            this.status = Status.EXPIRED;
        }
        return this.status;
    }

    /**
     * Returns the failure captured while loading this MRL, or {@code null} when it loaded
     * successfully or is still loading. The throwable is self-describing: a
     * {@link MatureContentException} when blocked, a {@link PlatformException} for platform
     * or I/O failures, otherwise the raw internal error. Inspect {@link Throwable#getCause()}
     * for the underlying cause. Cleared on {@link #reload()}.
     */
    public Throwable exception() {
        return this.exception;
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
        return "MRL{uri=" + this.uri + ", status=" + this.status + ", error=" + this.exception + ", sources=" + this.sourceCount() + "}";
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

    public enum Status {
        FETCHING,
        LOADED,
        EXPIRED,
        ERROR,
        BLOCKED,
        FORGOTTEN;

        /** Sources are loaded and usable. */
        public boolean loaded() {
            return this == LOADED;
        }

        /** Loading failed — either a plain error or a platform block. */
        public boolean failed() {
            return this == ERROR || this == BLOCKED;
        }

        /**
         * A terminal, non-loaded state the user must regenerate from:
         * load failures (ERROR/BLOCKED) plus stale instances (EXPIRED/FORGOTTEN).
         */
        public boolean regenerable() {
            return this == ERROR || this == BLOCKED || this == EXPIRED || this == FORGOTTEN;
        }

        public boolean disposable() {
            return this == ERROR || this == EXPIRED;
        }
    }
}
