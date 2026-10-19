package org.watermedia.api.media;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.*;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.M3UTool;
import org.watermedia.tools.ThreadTool;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Media Resource Locator - Container for URIs and their various qualities.
 * <p>
 * An MRL represents a "gallery" of sources that can contain multiple videos, images,
 * or combinations thereof. Each source can have multiple qualities and optional
 * secondary tracks (audio, subtitles).
 * <p>
 * Thread-safe and cached to avoid duplicate requests from Platform implementations.
 */
public final class MRL {
    private static final Marker IT = MarkerManager.getMarker(MRL.class.getSimpleName());
    private static volatile Session session;

    // THE CACHE AND ITS LOADERS, CREATED WHEN THE MEDIA MODULE LOADS AND KEPT FOR THE LIFE OF THE PROCESS; THE
    // SESSION MONITOR ORDERS THE CACHE SWEEP AGAINST get() AND reload()
    private static final class Session {
        final Map<URI, MRL> loaded = new ConcurrentHashMap<>(1024);
        final ExecutorService executor = Executors.newFixedThreadPool(Math.min(4, Runtime.getRuntime().availableProcessors()), ThreadTool.workerFactory("MRL-Loader", Thread.NORM_PRIORITY - 1));
        volatile long nextClean = System.currentTimeMillis() + MathUtil.minutesToMs(WaterMediaConfig.media.mrlCleanupInterval);
    }

    static synchronized void start() {
        if (session != null) throw new IllegalStateException("The media module has already started");
        session = new Session();
    }

    // INSTANCE FIELDS
    private final URI uri;
    private final Session owner;
    private volatile Source[] sources;
    private volatile Instant expiresAt;
    private final List<Consumer<MRL>> listeners = new CopyOnWriteArrayList<>();
    private volatile Status status = Status.FETCHING;
    private volatile Throwable exception;
    // LOAD SERIALIZATION: ONLY ONE load() RUNS PER MRL AT A TIME. reloadPending DEFERS A reload()
    // REQUESTED WHILE A LOAD IS IN FLIGHT SO IT DOESN'T WIPE STATE UNDER THE RUNNING FETCH'S FEET.
    // BOTH FIELDS ARE ACCESSED ONLY UNDER loadLock.
    private final Object loadLock = new Object();
    private boolean loading;
    private boolean reloadPending;

    private MRL(final URI uri, final Session owner) {
        if (uri == null) throw new IllegalArgumentException("URI cannot be null");
        this.uri = uri;
        this.owner = owner;
    }

    // CACHE LOOKUPS RETURN IMMEDIATELY; NEW SOURCES START PLATFORM DISCOVERY ASYNCHRONOUSLY.
    static MRL get(final URI uri) {
        if (uri == null) throw new IllegalArgumentException("URI cannot be null");
        final Session current = session;
        if (current == null || !WaterMedia.loaded()) throw new IllegalStateException("The media module is not loaded");
        // A RELATIVE URI CAN NEVER RESOLVE: BORN IN ERROR INSTEAD OF CACHED AND FAILING LATE
        if (!uri.isAbsolute()) return error(uri.toString(), new IllegalArgumentException("URI must be absolute: " + uri));
        synchronized (current) {
            return current.loaded.compute(uri, (key, existing) -> {
                if (existing != null) {
                    if (existing.status() == Status.EXPIRED) existing.reload();
                    return existing;
                }
                final MRL mrl = new MRL(key, current);
                current.executor.execute(mrl::load);
                return mrl;
            });
        }
    }

    static MRL[] preload(final URI... uris) {
        final MRL[] result = new MRL[uris.length];
        for (int i = 0; i < uris.length; i++) {
            result[i] = get(uris[i]); // WILL START ASYNC LOAD IF NOT CACHED
        }
        return result;
    }

    // CREATES AN MRL BORN IN ERROR FOR INPUT THAT NEVER REACHED THE LOADER (e.g. A MALFORMED URI STRING).
    // NOT CACHED: THE INPUT HAS NO PARSEABLE URI KEY, AND EACH BAD INPUT IS INDEPENDENT. THE RAW STRING IS
    // PRESERVED AS A PERCENT-ENCODED OPAQUE URI FOR DIAGNOSTICS; THE PARSE FAILURE IS THE exception().
    static MRL error(final String rawUri, final Throwable cause) {
        URI key;
        try { key = new URI("mrl-error", rawUri, null); }
        catch (final URISyntaxException e) { key = URI.create("mrl-error:unparseable"); }
        final MRL mrl = new MRL(key, null);
        mrl.exception = cause;
        mrl.status = Status.ERROR;
        return mrl;
    }

    /**
     * Restarts the async platform lookup for this MRL, clearing the previous sources and error
     * state. If a load is already in flight, the refresh is deferred until it finishes rather than
     * wiping state mid-fetch, so concurrent reloads never race or duplicate the lookup.
     * @return the active resource handle; a forgotten handle is replaced rather than revived and an
     *         MRL born in {@link Status#ERROR} from unparseable input returns itself unchanged
     */
    public MRL reload() {
        if (this.owner == null) return this; // BORN IN ERROR FROM UNPARSEABLE INPUT: THERE IS NOTHING TO REFETCH
        synchronized (this.owner) {
            if (this.status == Status.FORGOTTEN) {
                final MRL current = get(this.uri);
                return current.status == Status.FETCHING ? current : current.reload();
            }
            synchronized (this.loadLock) {
                if (this.loading) {
                    this.reloadPending = true;
                    return this;
                }
                this.sources = null;
                this.expiresAt = null;
                this.status = Status.FETCHING;
                this.exception = null;
            }
            this.owner.executor.execute(this::load);
            return this;
        }
    }

    private void load() {
        // CLAIM THE SINGLE IN-FLIGHT SLOT; LOSERS (STALE OR DUPLICATE QUEUED LOADS) BAIL OUT
        synchronized (this.loadLock) {
            if (this.status != Status.FETCHING || this.loading) return;
            this.loading = true;
        }
        try {
            this.doLoad();
        } finally {
            boolean requeue = false;
            synchronized (this.loadLock) {
                this.loading = false;
                // A reload() ARRIVED MID-FETCH — RESET STATE NOW AND RE-QUEUE EXACTLY ONE FRESH LOAD
                if (this.reloadPending) {
                    this.reloadPending = false;
                    this.sources = null;
                    this.expiresAt = null;
                    this.status = Status.FETCHING;
                    this.exception = null;
                    requeue = true;
                }
            }
            if (requeue) this.owner.executor.execute(this::load);
        }
    }

    private void doLoad() {
        try {
            if (this.owner.nextClean <= System.currentTimeMillis()) {
                synchronized (this.owner) {
                    // RE-CHECK UNDER LOCK: ONLY THE FIRST RACER RUNS CLEANUP, LOSERS STILL FALL THROUGH TO THE FETCH BELOW
                    if (this.owner.nextClean <= System.currentTimeMillis()) {
                        // ATOMIC PER-KEY CHECK-AND-REMOVE: computeIfPresent RUNS UNDER THE BIN LOCK, SO A
                        // CONCURRENT get()/reload() CAN'T RESURRECT AN ENTRY WE THEN CLOBBER TO FORGOTTEN.
                        this.owner.loaded.forEach((uri, mrl) -> this.owner.loaded.computeIfPresent(uri, (key, cur) -> {
                            synchronized (cur.loadLock) {
                                if (cur.loading || !cur.status().disposable()) return cur;
                                cur.sources = null;
                                cur.expiresAt = null;
                                cur.listeners.clear();
                                cur.status = Status.FORGOTTEN;
                                return null;
                            }
                        }));
                        this.owner.nextClean = System.currentTimeMillis() + MathUtil.minutesToMs(WaterMediaConfig.media.mrlCleanupInterval);
                    }
                }
            }
        } catch (final Throwable ex) {
            // CLEANUP OF UNRELATED, EXPIRED MRLs FAILED — NON-FATAL FOR THIS LOAD, KEEP GOING.
            LOGGER.error(IT, "Failed to dispose forgotten MRLs", ex);
        }
        try {
            // NO HANDLER CLAIMS A DIRECT LINK; THE PROBE BELOW RESOLVES IT FROM ITS CONTENT TYPE
            Platform.Resolution data = PlatformAPI.fetch(this.uri);
            if (data == null) {
                try (final NetRequest req = NetRequest.create(this.uri).method("GET").accept(NetRequest.ACCEPT_MEDIA).send()) {
                    LOGGER.debug(IT, "Connected to {} with content type {}", this.uri, req.contentType());

                    // FAIL FAST ON NON-2xx
                    if (req.statusCode() >= 400) {
                        throw new IOException("HTTP " + req.statusCode() + " for " + this.uri);
                    }

                    // ONLY DOCUMENT TYPES (HTML, JSON, SCRIPTS) ARE REJECTED, WHATEVER THEIR CASING OR PARAMETERS; text/plain
                    // AND XML PASS BECAUSE MISCONFIGURED HOSTS SERVE MEDIA UNDER THEM AND THE BYTE SNIFF SETTLES THE TYPE
                    final String contentType = req.contentType();
                    final String mime = contentType == null ? "" : contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
                    if (mime.equals("text/html") || mime.equals("text/css") || mime.equals("text/javascript")
                            || mime.equals("application/xhtml+xml") || mime.equals("application/javascript")
                            || mime.equals("application/x-javascript") || mime.equals("application/ecmascript")
                            || mime.equals("application/json") || mime.endsWith("+json")) {
                        throw new IOException("Content is not multimedia (received content-type '" + contentType + "'): " + this.uri);
                    }

                    // DETECT HLS/IPTV AND PARSE IT
                    final String pathLower = this.uri.getPath() == null ? "" : this.uri.getPath().toLowerCase(Locale.ROOT);
                    final boolean m3uShape = mime.contains("mpegurl") || pathLower.endsWith(".m3u") || pathLower.endsWith(".m3u8");

                    if (m3uShape) {
                        final String body = req.text();
                        // IPTV LISTS EXPAND INTO N CHANNELS; HLS MASTER/MEDIA (AND ANYTHING THAT
                        // FAILS TO PARSE) COLLAPSE TO A SINGLE VIDEO SOURCE THE PLAYER DRIVES ITSELF.
                        M3UTool.Playlist parsed;
                        try {
                            parsed = M3UTool.parse(body, this.uri);
                        } catch (final IOException badPlaylist) {
                            // NOT A RECOGNIZABLE PLAYLIST — HAND THE RAW URL TO FFmpeg BELOW
                            LOGGER.debug(IT, "Body from {} is not a recognizable playlist ({}); using the raw URL", this.uri, badPlaylist.getMessage());
                            parsed = null;
                        }
                        if (parsed instanceof final M3UTool.Iptv iptv) {
                            final List<M3UTool.Channel> channels = iptv.channels();
                            if (channels.isEmpty())
                                throw new IOException("IPTV playlist contained no entries: " + this.uri);
                            final List<Platform.Source> sources = new ArrayList<>(channels.size());
                            for (final M3UTool.Channel e: channels) {
                                // CHANNEL URLS ARE ALREADY ABSOLUTE AND VALIDATED BY M3UTool (MALFORMED ONES WERE DROPPED DURING PARSE)
                                final URI childUri = e.uri();
                                // THE LOGO IS BEST-EFFORT AND MUST STAY ON THE WEB: A BAD OR LOCAL ONE LEAVES THE CHANNEL UNILLUSTRATED
                                final URI logoUri = DataTool.uri(e.tvgLogo());
                                // THE IPTV GROUP IS A CATEGORY ("Sports"), NOT WHO PUBLISHED THE CHANNEL
                                final Metadata md = new Metadata(e.title(), e.tvgGroup(), null, 0, null);
                                sources.add(new Platform.Source(MediaType.UNKNOWN, logoUri, md,
                                        HttpHeaders.defaults(childUri),
                                        List.of(new Platform.Quality(childUri, 0, 0)),
                                        null, null));
                            }
                            data = new Platform.Resolution(null, sources);
                        } else {
                            data = new Platform.Resolution(null, List.of(new Platform.Source(MediaType.VIDEO, null, null, HttpHeaders.defaults(this.uri),
                                    List.of(new Platform.Quality(this.uri, 0, 0)),
                                    null, null)));
                        }
                    } else {
                        data = new Platform.Resolution(null, List.of(new Platform.Source(sniffType(req, this.uri), null, null,
                                HttpHeaders.defaults(this.uri),
                                List.of(new Platform.Quality(this.uri, 0, 0)),
                                null, null)));
                    }
                }
            } else if (data.sourceCount() == 1 && data.sources().get(0).type() == MediaType.UNKNOWN) {
                // PLATFORMS THAT CANNOT KNOW THE PAYLOAD TYPE (water://, MEDIAFIRE) DELIVER UNKNOWN, WHICH
                // DISPATCHES TO FFMPEG REGARDLESS OF CONTENT; CLASSIFY THE RESOLVED URL LIKE A DIRECT ONE.
                // MULTI-SOURCE DATA (IPTV LISTS) IS EXEMPT — PROBING EVERY CHANNEL IS A REQUEST STORM.
                final Platform.Source entry = data.sources().get(0);
                final URI target = entry.qualities().get(0).uri();
                try (final NetRequest req = NetRequest.create(target).headers(entry.headers()).method("GET").accept(NetRequest.ACCEPT_MEDIA).send()) {
                    final MediaType type = req.statusCode() >= 400 ? MediaType.UNKNOWN : sniffType(req, target);
                    if (type != MediaType.UNKNOWN) {
                        data = new Platform.Resolution(data.expires(), new Platform.Source(type, entry.thumbnail(), entry.metadata(),
                                entry.headers(), entry.qualities(), entry.audioTracks(), entry.subtitleTracks()));
                    }
                } catch (final IOException e) {
                    LOGGER.warn(IT, "Failed to classify {} resolved from {}: {}", target, this.uri, e.getMessage());
                }
            }

            if (data.sourceCount() > 0) {
                final List<Platform.Source> entries = data.sources();
                final Source[] sources = new Source[entries.size()];
                for (int i = 0; i < entries.size(); i++) {
                    final Platform.Source entry = entries.get(i);
                    if (entry == null) throw new IllegalArgumentException("[INTERNAL] Platform delivered a null entry");
                    // ONE URL PER BUCKET: THE WIDEST RENDITION WINS, THEN THE TALLEST, THEN THE HIGHER FRAME RATE, THEN THE FIRST
                    final EnumMap<MediaQuality, Platform.Quality> best = new EnumMap<>(MediaQuality.class);
                    for (final Platform.Quality v: entry.qualities()) {
                        best.merge(MediaQuality.of(v.width(), v.height()), v, (kept, next) -> next.width() > kept.width()
                                || next.width() == kept.width() && (next.height() > kept.height()
                                || next.height() == kept.height() && next.fps() > kept.fps()) ? next : kept);
                    }
                    final EnumMap<MediaQuality, URI> qualities = new EnumMap<>(MediaQuality.class);
                    best.forEach((bucket, v) -> qualities.put(bucket, v.uri()));

                    sources[i] = new Source(entry.type(), entry.thumbnail(), entry.metadata(),
                            entry.headers(), qualities, entry.audioTracks(), entry.subtitleTracks());
                }


                synchronized (this.loadLock) {
                    this.sources = sources;
                    this.expiresAt = data.expires();
                    this.status = Status.LOADED;
                }
                this.fireListeners();
                LOGGER.info(IT, "Loaded {} uri(s) for: {}", data.sourceCount(), this.uri);
            } else {
                throw new IllegalStateException("[INTERNAL] Platform resolution has no sources for " + this.uri);
            }
        } catch (final Throwable t) {
            // STORE THE FAILURE VERBATIM SO exception() EXPOSES THE REAL MESSAGE, CAUSE AND STACK TO DEVS.
            synchronized (this.loadLock) {
                this.exception = t;
                this.status = t instanceof MatureContentException ? Status.BLOCKED : Status.ERROR;
            }

            // A BLOCK IS EXPECTED AND SELF-DESCRIBING (WARN WITHOUT TRACE); EVERY OTHER FAILURE TRAVELS WITH ITS THROWABLE
            if (t instanceof MatureContentException)
                LOGGER.warn(IT, "Blocked (mature content disabled) {}: {}", this.uri, t.getMessage());
            else
                LOGGER.error(IT, "Failed to resolve {}", this.uri, t);

            this.fireListeners();
        }
    }

    // RESOLVES THE MEDIA TYPE OF AN OPEN REQUEST: CONTENT-TYPE FIRST, THEN LEADING-BYTE SNIFF
    // (AUTHORITATIVE FOR AMBIGUOUS MIMES LIKE application/octet-stream), THEN THE URL EXTENSION.
    private static MediaType sniffType(final NetRequest req, final URI uri) {
        MediaType type = MediaType.of(req.contentType());
        if (type != MediaType.UNKNOWN) return type;
        try (final InputStream in = req.inputStream()) {
            type = CodecsAPI.sniff(in);
        } catch (final IOException e) {
            LOGGER.warn(IT, "Failed to sniff media type for {}", uri, e);
        }
        return type != MediaType.UNKNOWN ? type : MediaType.ofExtension(uri.getPath());
    }

    // RUNS OUTSIDE ANY MONITOR: A LISTENER MAY WAIT ON A THREAD THAT IS SUBSCRIBING TO THIS SAME MRL. EACH LISTENER IS
    // CLAIMED (REMOVED) BEFORE IT FIRES, SO A RACING subscribe EITHER CLAIMS IT ITSELF OR LEAVES IT TO THIS PASS, NEVER BOTH
    private void fireListeners() {
        for (final Consumer<MRL> c: this.listeners) {
            if (!this.listeners.remove(c)) continue;
            try { c.accept(this); }
            catch (final Throwable t) { LOGGER.error(IT, "Listener failed for {}", this.uri, t); }
        }
    }

    /**
     * Blocks until loading reaches a terminal state or the timeout elapses. Discouraged in
     * tick-based game environments: prefer polling {@link #status()} every tick instead.
     *
     * @param timeoutMs maximum wait time in milliseconds
     * @return {@code true} once loading finished in any terminal state (inspect {@link #status()}
     *         for the outcome — it may be a failure), {@code false} if it timed out while still fetching
     */
    public boolean await(final long timeoutMs) {
        final long started = System.nanoTime();
        final long budget = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
        while (this.status == Status.FETCHING) {
            final long remaining = budget - (System.nanoTime() - started);
            if (remaining <= 0) return false;
            if (!ThreadTool.sleep(Math.min(50L, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining))))) return false;
        }
        return true;
    }

    /**
     * Registers a new listener, triggered once loading completes OR fails.
     * If the MRL has already reached a terminal state when this is called, the
     * listener fires immediately on the calling thread; otherwise it fires on the
     * loader thread once loading finishes. After firing, all listeners are dropped
     * — re-subscribe across reloads.
     * <p>
     * Discouraged in tick-based game environments: prefer polling {@link #status()} every tick instead.
     * </p>
     * @param listener consumer invoked with this MRL once it reaches a terminal state (success or failure)
     */
    public void subscribe(final Consumer<MRL> listener) {
        if (this.status == Status.FETCHING) {
            this.listeners.add(listener);
            // A TERMINAL STATE MAY LAND WHILE ADDING: WHEN THE FIRE PASS DID NOT TAKE THIS LISTENER, FIRE IT HERE
            if (this.status == Status.FETCHING || !this.listeners.remove(listener)) return;
        }
        // ANY NON-FETCHING STATE IS TERMINAL: fireListeners() ALREADY RAN, SO FIRE ON THE CALLING THREAD
        try { listener.accept(this); }
        catch (final Throwable t) { LOGGER.error(IT, "Listener failed for {}", this.uri, t); }
    }

    /** Returns the link this MRL was created for, as given by the caller. */
    public URI uri() { return this.uri; }

    /** Returns the current state, marking expired loaded sources as EXPIRED. */
    public Status status() {
        synchronized (this.loadLock) {
            if (this.status == Status.LOADED && this.expiresAt != null && this.expiresAt.isBefore(Instant.now())) {
                this.status = Status.EXPIRED;
            }
            return this.status;
        }
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
     * Returns all {@link Source} instances, or an empty list when none are available.
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
     * @param index the source index
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
     * Internal: rewires a quality bucket inside an existing {@link Source}.
     * Used by FFMediaPlayer when an HLS rendition turns out to map to a
     * different bucket than the platform reported. Public only because the
     * caller lives in a sibling package; not part of the supported API.
     * <p>Do not call from application code.
     */
    public void moveQuality(final int sourceIndex, final MediaQuality from, final MediaQuality to) {
        final Source[] s = this.sources;
        if (s == null || sourceIndex < 0 || sourceIndex >= s.length) return;
        final Source src = s[sourceIndex];
        if (!src.qualities().containsKey(from)) return;
        final EnumMap<MediaQuality, URI> newQualities = new EnumMap<>(src.qualities());
        final URI uri = newQualities.remove(from);
        newQualities.put(to, uri);
        s[sourceIndex] = new Source(src.type(), src.thumbnail(), src.metadata(), src.headers(), newQualities, src.audioTracks(), src.subtitleTracks());
    }



    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof final MRL mrl)) return false;
        return this.uri.equals(mrl.uri());
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
     * A URI Source container with multiple quality support and optional secondary tracks. Each
     * {@link MediaQuality} bucket keeps one URL: the widest rendition the platform listed, then the
     * tallest, then the one with the highest frame rate, then the first listed. The headers are frozen.
     */
    public record Source(MediaType type, URI thumbnail, Metadata metadata, HttpHeaders headers,
                         Map<MediaQuality, URI> qualities, List<Track> audioTracks,
                         List<Track> subtitleTracks) {

        public Source {
            if (qualities.isEmpty())
                throw new IllegalArgumentException("Source constructed with no qualities");

            // DEFENSIVE UNMODIFIABLE COPY: Source IS CACHED AND SHARED ACROSS DECODE THREADS, THE MAP MUST NOT LEAK MUTABLE
            // ENUMMAP KEPT INTERNALLY FOR ORDERING/PERF; keySet() OF AN UNMODIFIABLE MAP IS ALSO SAFE TO EXPOSE
            qualities = Collections.unmodifiableMap(new EnumMap<>(qualities));
            audioTracks = audioTracks == null ? List.of() : List.copyOf(audioTracks);
            subtitleTracks = subtitleTracks == null ? List.of() : List.copyOf(subtitleTracks);
            headers = new HttpHeaders(headers).freeze(); // SHARED ACROSS PLAYER THREADS: NEVER MUTABLE
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

        /**
         * Gets the first quality, in enum order, whose URI equals {@code uri}, or null when none does.
         */
        public MediaQuality qualityOf(final URI uri) {
            if (uri == null) return null;
            for (final var q: this.qualities.entrySet()) {
                if (uri.equals(q.getValue())) {
                    return q.getKey();
                }
            }
            return null;
        }
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

        /**
         * A terminal state the cleanup pass may evict from the cache. Covers every failure
         * (ERROR/BLOCKED) plus stale successes (EXPIRED); without BLOCKED, blocked entries would
         * accumulate in the cache for the JVM lifetime.
         */
        public boolean disposable() {
            return this == ERROR || this == BLOCKED || this == EXPIRED;
        }
    }
}
