package org.watermedia.api.platform;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.media.MRL;
import org.watermedia.api.platform.internal.WaterPlatform;
import org.watermedia.api.platform.web.*;
import org.watermedia.api.util.MathUtil;
import org.watermedia.tools.ThreadTool;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Platform API. Owns the {@link Platform} registry and exposes a way to
 * fetch a URI's raw media data — direct links, dimensions, metadata — without
 * going through {@link MRL}.
 * <p>
 * Platforms return {@link Platform.Resolution} (their own structure); {@link MRL} and other
 * consumers build their domain types (e.g. {@code MRL.Source}) from that data. The registry is
 * static and the search workers start on first use, so resolving, searching and registering work at
 * any time; a handler that needs the Binaries module (yt-dlp, YouTube) fails with its own
 * {@link PlatformException} until WaterMedia loads.
 */
public final class PlatformAPI {
    private static final Marker IT = MarkerManager.getMarker(PlatformAPI.class.getSimpleName());
    private static final int HISTORY_LIMIT = 10;
    private static final int DEFAULT_LIMIT = 2; // RESULTS PER PLATFORM WHEN THE CALLER DOES NOT SPECIFY
    private static final int SEARCH_CACHE_LIMIT = 32;
    private static final int SEARCH_THREADS = 4;
    private static final Object SEARCH_LOCK = new Object();
    // COPY-ON-WRITE: REGISTRATION IS RARE WHILE THE MRL LOADER THREADS ITERATE HOT AND MUST NEVER SEE A CME.
    // APPLICATION HANDLERS ARE PROBED FIRST; THE BUILT-IN ONES ARE ENLISTED ONCE AT CLASS LOAD
    static final CopyOnWriteArrayList<Platform> CUSTOM = new CopyOnWriteArrayList<>();
    static final CopyOnWriteArrayList<Platform> BUILTIN = new CopyOnWriteArrayList<>();
    // RECENT QUERIES, MOST RECENT FIRST, CAPPED AT HISTORY_LIMIT — GUARDED BY SEARCH_LOCK
    private static final ArrayDeque<String> HISTORY = new ArrayDeque<>();
    // ACCESS-ORDER LRU OF (limit, query) RESULTS, FLUSHED EVERY platforms.searchCleanupInterval MINUTES AND WHENEVER
    // THE REGISTRY CHANGES; TYPING ISSUES ONE SEARCH PER PARTIAL QUERY, HENCE THE CAP. GUARDED BY SEARCH_LOCK
    private static final Map<String, List<PlatformResult>> SEARCH_CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, List<PlatformResult>> eldest) {
            return this.size() > SEARCH_CACHE_LIMIT;
        }
    };
    private static long nextCacheClean;

    // THE SEARCH WORKERS LIVE FOR THE WHOLE PROCESS ONCE THE FIRST SEARCH CREATES THEM; THE COORDINATOR HAS ITS OWN
    // THREAD SO WAITING FOR BOUNDED PROBES CANNOT DEADLOCK THE POOL. GUARDED BY SEARCH_LOCK
    private static ThreadPoolExecutor searchPool;
    private static ThreadPoolExecutor coordinator;
    private static long searchGeneration;
    private static long registryVersion; // BUMPED UNDER SEARCH_LOCK ON EVERY REGISTRY CHANGE SO A STALE SEARCH NEVER CACHES
    private static Future<?> searchTask; // CURRENT SEARCH — GUARDED BY SEARCH_LOCK, CANCELLED WHEN A NEW ONE STARTS
    private static PlatformSearch searchActive; // ITS HANDLE, ENDED AS SUPERSEDED AT THAT MOMENT — GUARDED BY SEARCH_LOCK

    static {
        // ONE HANDLER THAT CANNOT BE BUILT MUST NOT TAKE THE REGISTRY DOWN WITH IT: THE OTHERS STILL ENLIST
        final List<Supplier<Platform>> factories = new ArrayList<>(List.of(
                WaterPlatform::new, ImgurPlatform::new, KickPlatform::new, StreamablePlatform::new, MedalPlatform::new,
                PornHubPlatform::new, LightshotPlatform::new, TwitchPlatform::new, TwitterPlatform::new, BlueskyPlatform::new,
                BiliBiliPlatform::new, DrivePlatform::new, DropboxPlatform::new, MediaFirePlatform::new, SendvidPlatform::new,
                OdyseePlatform::new, VidLiiPlatform::new, TikTokPlatform::new, DTubePlatform::new));
        // THE yt-dlp HANDLERS LINK THE BINARIES MODULE, SO THEY EXIST ONLY WHEN ITS JAR IS ON THE CLASS PATH
        if (PlatformAPI.class.getClassLoader().getResource("org/watermedia/binaries/WaterMediaBinaries.class") != null) {
            try {
                factories.addAll(NativePlatforms.factories());
            } catch (final RuntimeException | LinkageError failure) {
                LOGGER.warn(IT, "The yt-dlp platforms could not be enlisted; WaterMedia Binaries may be incompatible", failure);
            }
        }
        for (final Supplier<Platform> factory: factories) {
            try {
                BUILTIN.add(factory.get());
            } catch (final RuntimeException | LinkageError failure) {
                LOGGER.warn(IT, "A built-in platform could not be enlisted", failure);
            }
        }
        LOGGER.debug(IT, "Enlisted {} built-in platforms", BUILTIN.size());
    }

    private PlatformAPI() {}


    /**
     * Searches every registered {@link Platform} for {@code query}, with up to
     * {@value #DEFAULT_LIMIT} results per platform. Shorthand for {@link #search(String, int)}.
     *
     * @param query the user's search text
     * @return a live {@link PlatformSearch} handle whose result list fills in off-thread
     */
    public static PlatformSearch search(final String query) {
        return search(query, DEFAULT_LIMIT);
    }

    /**
     * Starts an asynchronous search across every registered {@link Platform} and returns a live
     * {@link PlatformSearch} immediately. The handle's {@link PlatformSearch#results() result list}
     * grows off-thread as each platform answers — platforms are probed concurrently, so hits land in
     * completion order (fast HTTP handlers appear before slow process-spawning ones like yt-dlp), with
     * at most {@code limit} hits per platform.
     * <p>
     * There is one search slot per process: this call cancels (interrupts) any still-running previous
     * search, whoever issued it, whose handle ends at once as {@link PlatformSearch#superseded()
     * superseded} with the hits it gathered, so only the latest handle keeps updating. The {@code query} is recorded
     * in a {@value #HISTORY_LIMIT}-entry {@link PlatformSearch#history() history}; a {@code null} or blank
     * query is a no-op that returns an already-{@link PlatformSearch#done() done}, empty handle and leaves
     * a running search alone.
     *
     * @param query the user's search text
     * @param limit   the maximum number of results to take from each platform (clamped to at least 1)
     * @return a live handle to poll for results
     */
    public static PlatformSearch search(final String query, final int limit) {
        synchronized (SEARCH_LOCK) {
            if (coordinator == null) {
                searchPool = new ThreadPoolExecutor(SEARCH_THREADS, SEARCH_THREADS, 0, TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>(), ThreadTool.workerFactory("PlatformAPI-Search", Thread.NORM_PRIORITY));
                coordinator = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>(1), ThreadTool.workerFactory("PlatformAPI-Coordinator", Thread.NORM_PRIORITY));
            }

            // BLANK QUERY: NOTHING TO SEARCH, NOTHING WORTH REMEMBERING AND NOTHING TO SUPERSEDE
            if (query == null || query.isBlank()) {
                final PlatformSearch empty = new PlatformSearch(query, List.copyOf(HISTORY));
                empty.complete();
                return empty;
            }

            final long generation = ++searchGeneration;
            final ExecutorService pool = searchPool;
            // SUPERSEDE THE PREVIOUS SEARCH — ONLY ONE IS EVER ACTIVE; ITS HANDLE ENDS WITH WHATEVER IT GATHERED
            if (searchTask != null) searchTask.cancel(true);
            if (searchActive != null) searchActive.supersede();
            searchTask = null;
            searchActive = null;

            // RECORD IN HISTORY: DEDUP TO MOST-RECENT-FIRST, CAPPED AT HISTORY_LIMIT
            HISTORY.remove(query);
            HISTORY.addFirst(query);
            while (HISTORY.size() > HISTORY_LIMIT) HISTORY.removeLast();
            final List<String> historySnapshot = List.copyOf(HISTORY);

            // GENERAL CACHE SWEEP: ONCE THE INTERVAL ELAPSES, DROP EVERYTHING SO RESULTS NEVER GO TOO STALE
            final long now = System.currentTimeMillis();
            if (now >= nextCacheClean) {
                SEARCH_CACHE.clear();
                nextCacheClean = now + MathUtil.minutesToMs(WaterMediaConfig.platforms.searchCleanupInterval);
            }

            final int perPlatform = Math.max(1, limit);
            final String cacheKey = perPlatform + " " + query;

            // CACHE HIT: SERVE A PRE-COMPLETED HANDLE WITHOUT TOUCHING THE PLATFORMS
            final List<PlatformResult> cached = SEARCH_CACHE.get(cacheKey);
            if (cached != null) {
                LOGGER.debug(IT, "Search '{}' served from cache ({} result(s))", query, cached.size());
                final PlatformSearch hit = new PlatformSearch(query, historySnapshot);
                hit.add(cached);
                hit.complete();
                return hit;
            }

            final PlatformSearch search = new PlatformSearch(query, historySnapshot);
            final long registry = registryVersion;
            coordinator.purge();
            searchPool.purge();
            searchTask = coordinator.submit(() -> runSearch(search, query, perPlatform, cacheKey, pool, generation, registry));
            searchActive = search;
            return search;
        }
    }

    /**
     * Snapshot of the recent-query {@link PlatformSearch#history() history} (most recent first,
     * at most {@value #HISTORY_LIMIT} entries) — the same list a {@link PlatformSearch} exposes.
     * Lets a UI surface recent searches before a new {@link #search(String) search} is even issued.
     *
     * @return an immutable snapshot of the recent queries, newest first
     */
    public static List<String> searchHistory() {
        synchronized (SEARCH_LOCK) {
            return List.copyOf(HISTORY);
        }
    }

    // FANS EVERY PLATFORM PROBE OUT ONTO THE POOL, APPENDS HITS TO THE LIVE HANDLE AS EACH ANSWERS AND WAITS FOR ALL;
    // A NEWER SEARCH INTERRUPTS IT, CANCELLING THE PROBES AND LEAVING THE HANDLE FROZEN. ONE FAILING PLATFORM NEVER ABORTS IT
    private static void runSearch(final PlatformSearch search, final String query, final int limit, final String cacheKey,
                                  final ExecutorService pool, final long generation, final long registry) {
        LOGGER.debug(IT, "Search '{}' started ({} per platform)", query, limit);
        final Platform[] platforms = probeOrder();
        final List<Future<?>> probes = new ArrayList<>(platforms.length);
        try {
            for (final Platform platform: platforms) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                probes.add(pool.submit(() -> probePlatform(platform, search, limit, generation)));
            }
            for (final Future<?> probe: probes) {
                try {
                    probe.get();
                } catch (final ExecutionException e) {
                    // AN ESCAPED PROBE FAILURE DOES NOT END THE OTHER PROBES OR COMPLETE THEIR HANDLE.
                    LOGGER.warn(IT, "Search probe failed unexpectedly", e.getCause());
                }
            }
        } catch (final InterruptedException e) { // SUPERSEDED — CANCEL THE OUTSTANDING PROBES; search() ALREADY ENDED THE HANDLE
            for (final Future<?> probe: probes) probe.cancel(true);
            Thread.currentThread().interrupt();
            LOGGER.debug(IT, "Search '{}' superseded before completing", query);
            return;
        }

        synchronized (SEARCH_LOCK) {
            if (generation != searchGeneration) return;
            search.complete();
        }
        // CACHE ONLY FULLY-COMPLETED, NON-EMPTY SEARCHES AGAINST AN UNCHANGED REGISTRY (THE INTERRUPTED RETURN ABOVE
        // NEVER REACHES HERE). SKIPPING EMPTY RESULTS LETS A TRANSIENT TOTAL FAILURE RETRY INSTEAD OF SERVING STALE NOTHING
        final List<PlatformResult> finalResults = search.results();
        if (!finalResults.isEmpty()) {
            synchronized (SEARCH_LOCK) {
                if (generation == searchGeneration && registry == registryVersion) SEARCH_CACHE.put(cacheKey, List.copyOf(finalResults));
            }
        }
        // THE QUERY IS USER TEXT: COUNTS GO TO INFO, THE TEXT ITSELF STAYS AT DEBUG
        LOGGER.info(IT, "Search complete with {} result(s) from {} platform(s)", finalResults.size(), platforms.length);
    }

    // ISOLATES PLATFORM FAILURES; THE COORDINATOR ALSO HANDLES ESCAPED FAILURES.
    // CANCELLATION RESTORES THE INTERRUPT FLAG AND RETURNS WITHOUT PUBLISHING RESULTS.
    private static void probePlatform(final Platform platform, final PlatformSearch search, final int limit, final long generation) {
        if (Thread.currentThread().isInterrupted()) return;
        try {
            final List<PlatformResult> hits = platform.search(search.query(), limit);
            if (hits != null && !hits.isEmpty()) {
                // DEFENSIVE PER-PLATFORM CAP: THE CONTRACT IS <= limit, BUT DON'T TRUST A MISBEHAVING HANDLER
                final List<PlatformResult> capped = hits.size() > limit ? hits.subList(0, limit) : hits;
                synchronized (SEARCH_LOCK) {
                    if (generation != searchGeneration || Thread.currentThread().isInterrupted()) return;
                    search.add(capped);
                }
                LOGGER.debug(IT, "Search matched {} result(s) on {}", capped.size(), platform.name());
            }
        } catch (final PlatformException | RuntimeException | LinkageError e) {
            // yt-dlp AND OTHERS WRAP THE INTERRUPT IN THEIR OWN EXCEPTION: IF THE FLAG IS SET, IT WAS A CANCELLATION
            if (Thread.currentThread().isInterrupted()) {
                LOGGER.debug(IT, "Search interrupted on {}", platform.name());
                return;
            }
            LOGGER.warn(IT, "Search failed on {}", platform.name(), e);
        }
    }

    /**
     * Finds the first registered {@link Platform} that handles the given URI
     * and returns its raw {@link Platform.Resolution}. Use this when you only need the direct
     * links plus width/height/metadata, and don't want the {@link MRL} cache,
     * the async {@code Source} model, or the player lifecycle.
     * <p>
     * Handlers are probed in the order {@link #platforms()} lists them: application handlers first,
     * newest first, then the built-in ones, so an app-registered override always wins. The call
     * blocks on network I/O for as long as the matching handler needs and is safe to issue from
     * several threads at once.
     *
     * @param uri the media URI
     * @return raw platform data, or {@code null} if no registered platform handled the URI
     * @throws PlatformException whatever the matching platform throws while resolving; a handler bug
     *         (an escaped runtime exception) and a cancelled resolution are reported the same way
     * @throws IllegalArgumentException when {@code uri} is null
     */
    public static Platform.Resolution fetch(final URI uri) throws PlatformException {
        if (uri == null) throw new IllegalArgumentException("URI cannot be null");
        for (final Platform platform: probeOrder()) {
            final Platform.Resolution data;
            try {
                data = platform.resolve(uri);
            } catch (final RuntimeException | LinkageError e) { // BUG IN THE HANDLER, OR A CANCELLED WAIT IT DID NOT TRANSLATE; A PlatformException PASSES THROUGH
                if (Thread.currentThread().isInterrupted()) throw new PlatformException(platform.name(), "Resolution of " + uri + " was cancelled", e);
                throw new PlatformException(platform.name(), "Unexpected error resolving " + uri + " (" + e + ")", e);
            }
            if (data != null) {
                LOGGER.debug(IT, "Fetched data from {} for {}", platform.name(), uri);
                return data;
            }
        }
        return null; // NOTHING FOUND
    }

    /**
     * Registers an application handler. Application handlers are probed before the built-in ones,
     * newest first, so an app can override a built-in handler with a more specific one; a handler
     * registered twice stays registered once. Cached searches are dropped.
     *
     * @throws IllegalArgumentException when {@code platform} is null
     */
    public static void register(final Platform platform) {
        if (platform == null) throw new IllegalArgumentException("Platform cannot be null");
        if (!CUSTOM.addIfAbsent(platform)) return;
        synchronized (SEARCH_LOCK) {
            SEARCH_CACHE.clear();
            registryVersion++;
        }
        LOGGER.info(IT, "Registered {} platform", platform.name());
    }

    /**
     * Removes a registered handler, built-in or application-provided, for the rest of the process; a
     * no-op when it was never registered. Cached searches are dropped.
     */
    public static void unregister(final Platform platform) {
        if (!CUSTOM.remove(platform) && !BUILTIN.remove(platform)) return;
        synchronized (SEARCH_LOCK) {
            SEARCH_CACHE.clear();
            registryVersion++;
        }
        LOGGER.info(IT, "Unregistered {} platform", platform.name());
    }

    /**
     * Returns an immutable snapshot of the registered handlers in probe order: application handlers
     * newest first, then the built-in ones newest first. Lets a consumer enumerate the active registry
     * without triggering a {@link #fetch(URI)}.
     */
    public static List<Platform> platforms() {
        return List.of(probeOrder());
    }

    // APPLICATION HANDLERS BEFORE THE BUILT-IN ONES, THE MOST RECENTLY REGISTERED OF EACH GROUP FIRST
    private static Platform[] probeOrder() {
        final Platform[] custom = CUSTOM.toArray(Platform[]::new);
        final Platform[] builtin = BUILTIN.toArray(Platform[]::new);
        final Platform[] order = new Platform[custom.length + builtin.length];
        for (int i = 0; i < custom.length; i++) order[i] = custom[custom.length - 1 - i];
        for (int i = 0; i < builtin.length; i++) order[custom.length + i] = builtin[builtin.length - 1 - i];
        return order;
    }

    // THESE CONSTRUCTORS LINK EXECUTABLE WRAPPERS FROM THE BINARIES JAR, SO THE HOLDER IS ONLY TOUCHED WHEN IT EXISTS
    private static final class NativePlatforms {
        private static List<Supplier<Platform>> factories() {
            return List.of(YtDlpPlatform::new, YouTubePlatform::new);
        }
    }
}
