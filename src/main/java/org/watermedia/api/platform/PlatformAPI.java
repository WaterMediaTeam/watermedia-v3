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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
    private static final int SEARCH_LIMIT = 2; // RESULTS PER PLATFORM
    private static final int SEARCH_CACHE_LIMIT = 32;
    private static final int SEARCH_WORKERS = 2;
    private static final long SEARCH_TYPING_DELAY_MS = 300L;
    private static final Object SEARCH_LOCK = new Object();
    // COPY-ON-WRITE: REGISTRATION IS RARE WHILE THE MRL LOADER THREADS ITERATE HOT AND MUST NEVER SEE A CME.
    // APPLICATION HANDLERS ARE PROBED FIRST; THE BUILT-IN ONES ARE ENLISTED ONCE AT CLASS LOAD
    static final CopyOnWriteArrayList<Platform> CUSTOM = new CopyOnWriteArrayList<>();
    static final CopyOnWriteArrayList<Platform> BUILTIN = new CopyOnWriteArrayList<>();
    // RECENT QUERIES, MOST RECENT FIRST, CAPPED AT HISTORY_LIMIT — GUARDED BY SEARCH_LOCK
    private static final ArrayDeque<String> HISTORY = new ArrayDeque<>();
    // QUERY -> THE LIST ITS SEARCH FILLS, SERVED AS THE SAME INSTANCE WHILE IT FILLS AND AFTER. ACCESS-ORDER LRU, FLUSHED
    // EVERY platforms.searchCleanupInterval MINUTES AND WHENEVER THE REGISTRY CHANGES — GUARDED BY SEARCH_LOCK
    private static final Map<String, List<PlatformResult>> SEARCH_CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, List<PlatformResult>> eldest) {
            return this.size() > SEARCH_CACHE_LIMIT;
        }
    };
    private static long nextCacheClean;

    // ==== SEARCH IN FLIGHT — GUARDED BY SEARCH_LOCK ====
    // THE TWO WORKERS LIVE FOR THE WHOLE PROCESS ONCE THE FIRST SEARCH CREATES THEM; EVERY PLATFORM PROBE IS ONE TASK ON THEM
    private static ScheduledExecutorService searchers;
    private static final List<Future<?>> searchProbes = new ArrayList<>();
    private static String searchQuery; // THE LAST QUERY SEARCHED; TYPING IS DETECTED AGAINST IT
    private static int searchRemaining; // PROBES STILL TO ANSWER; ZERO WHEN NO SEARCH IS IN FLIGHT
    private static long searchGeneration; // BUMPED PER SEARCH, SO A PROBE OF A DISCARDED SEARCH NEVER WRITES
    private static long registryVersion; // BUMPED ON EVERY REGISTRY CHANGE, SO A SEARCH OVERLAPPED BY ONE IS NOT KEPT

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

    /** Searches every registered platform for {@code query}; shorthand for {@link #search(String, boolean)} without a re-search. */
    public static List<PlatformResult> search(final String query) {
        return search(query, false);
    }

    /**
     * Starts a search across every registered {@link Platform} and returns at once the list its results land in,
     * up to {@value #SEARCH_LIMIT} per platform in answer order; {@link #searching()} tells when every platform
     * answered. One search runs at a time: a new query discards the one in flight, its probes interrupted and its
     * partial list dropped, so only the latest list keeps filling. A query that extends the previous one, or that
     * the previous one extends, is typing and waits {@value #SEARCH_TYPING_DELAY_MS} ms for the next keystroke before
     * touching the network; any other query probes at once. A query already searched is served the same list from
     * the cache, finished or still filling, unless {@code reSearch} is set; a blank query returns an empty list and
     * leaves a running search alone.
     *
     * @param query the user's search text
     * @param reSearch whether to drop the cached results of this query and probe the platforms again
     * @return an unmodifiable view of the list the search fills; empty and final for a blank query
     */
    public static List<PlatformResult> search(final String query, final boolean reSearch) {
        if (query == null || query.isBlank()) return List.of();
        final String text = query.strip();
        synchronized (SEARCH_LOCK) {
            // RECENT QUERIES, NEWEST FIRST, WITHOUT DUPLICATES
            HISTORY.remove(text);
            HISTORY.addFirst(text);
            while (HISTORY.size() > HISTORY_LIMIT) HISTORY.removeLast();

            // THE WHOLE CACHE EXPIRES ON THE CONFIGURED INTERVAL, SO RESULTS NEVER GROW TOO STALE
            final long now = System.currentTimeMillis();
            if (now >= nextCacheClean) {
                SEARCH_CACHE.clear();
                nextCacheClean = now + MathUtil.minutesToMs(WaterMediaConfig.platforms.searchCleanupInterval);
            }

            final List<PlatformResult> cached = reSearch ? null : SEARCH_CACHE.get(text);
            if (cached != null) {
                // AN ANSWERED QUERY STILL WINS OVER A DIFFERENT SEARCH IN FLIGHT, WHICH NOBODY WAITS FOR ANY MORE
                if (!text.equals(searchQuery)) discardSearch();
                LOGGER.debug(IT, "Search '{}' served from cache ({} result(s))", text, cached.size());
                return Collections.unmodifiableList(cached);
            }

            // THE NEWEST QUERY WINS: WHATEVER IS IN FLIGHT IS DISCARDED BEFORE THIS SEARCH STARTS
            discardSearch();
            if (searchers == null) {
                final ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(SEARCH_WORKERS, ThreadTool.workerFactory("PlatformAPI-Search", Thread.NORM_PRIORITY));
                pool.setRemoveOnCancelPolicy(true);
                searchers = pool;
            }

            // SHARED FIRST LETTERS MEAN THE USER IS STILL TYPING: THE PROBES WAIT FOR THE NEXT KEYSTROKE
            final boolean typing = searchQuery != null && (text.startsWith(searchQuery) || searchQuery.startsWith(text));
            final long delay = typing ? SEARCH_TYPING_DELAY_MS : 0L;
            final List<PlatformResult> results = new CopyOnWriteArrayList<>(); // TWO WORKERS APPEND WHILE THE CALLER READS EVERY FRAME
            final Platform[] platforms = probeOrder();
            final long generation = ++searchGeneration;
            final long registry = registryVersion;
            searchQuery = text;
            searchRemaining = platforms.length;
            // WITH NOTHING TO ASK THERE IS NOTHING WORTH KEEPING
            if (platforms.length > 0) SEARCH_CACHE.put(text, results);
            for (final Platform platform: platforms)
                searchProbes.add(searchers.schedule(() -> probe(platform, text, results, generation, registry), delay, TimeUnit.MILLISECONDS));
            LOGGER.debug(IT, "Search '{}' started on {} platform(s){}", text, platforms.length, typing ? " after the typing delay" : "");
            return Collections.unmodifiableList(results);
        }
    }

    /** Whether the latest search still waits on platforms; the list {@link #search} returned keeps filling until then. */
    public static boolean searching() {
        synchronized (SEARCH_LOCK) {
            return searchRemaining > 0;
        }
    }

    /**
     * Snapshot of the recent-query history (most recent first, at most {@value #HISTORY_LIMIT}
     * entries), the single list every {@link #search(String) search} records its query in.
     *
     * @return an immutable snapshot of the recent queries, newest first
     */
    public static List<String> searchHistory() {
        synchronized (SEARCH_LOCK) {
            return List.copyOf(HISTORY);
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

    // UTILITY
    // INTERRUPTS THE PROBES IN FLIGHT AND DROPS THEIR PARTIAL LIST FROM THE CACHE; A FINISHED SEARCH ONLY RELEASES ITS TASKS
    private static void discardSearch() {
        for (final Future<?> probe: searchProbes) probe.cancel(true);
        searchProbes.clear();
        if (searchRemaining == 0) return;
        SEARCH_CACHE.remove(searchQuery);
        searchRemaining = 0;
        LOGGER.debug(IT, "Search '{}' discarded", searchQuery);
    }

    // ONE PLATFORM OF ONE SEARCH: ITS HITS JOIN THE LIST UNLESS THE SEARCH WAS DISCARDED MEANWHILE, AND THE LAST PROBE TO
    // ANSWER CLOSES THE SEARCH. A FAILING PLATFORM COSTS ONLY ITS OWN HITS, EVEN WHEN ITS NAME FAILS TOO
    private static void probe(final Platform platform, final String query, final List<PlatformResult> results, final long generation, final long registry) {
        List<PlatformResult> hits = List.of();
        try {
            final List<PlatformResult> found = platform.search(query, SEARCH_LIMIT);
            // THE CONTRACT IS AT MOST SEARCH_LIMIT HITS; A MISBEHAVING HANDLER IS CAPPED
            if (found != null) hits = found.size() > SEARCH_LIMIT ? List.copyOf(found.subList(0, SEARCH_LIMIT)) : found;
            if (!hits.isEmpty()) LOGGER.debug(IT, "Search matched {} result(s) on {}", hits.size(), platform.name());
        } catch (final PlatformException | RuntimeException | LinkageError e) {
            // A DISCARDED SEARCH INTERRUPTS ITS PROBES, WHICH REPORT THE CANCELLED WAIT AS THEIR OWN FAILURE
            if (!Thread.currentThread().isInterrupted()) LOGGER.warn(IT, "Search failed on {}", platform.name(), e);
        } finally {
            synchronized (SEARCH_LOCK) {
                if (generation == searchGeneration) {
                    results.addAll(hits);
                    if (--searchRemaining == 0) {
                        // A LIST LEFT EMPTY, OR GATHERED AGAINST A CHANGED REGISTRY, IS NOT KEPT, SO THE NEXT CALL ASKS AGAIN
                        if (results.isEmpty() || registry != registryVersion) SEARCH_CACHE.remove(query);
                        LOGGER.info(IT, "Search complete with {} result(s)", results.size());
                    }
                }
            }
        }
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
