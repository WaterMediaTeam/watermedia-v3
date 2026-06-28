package org.watermedia.api.platform;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.WaterMediaAPI;
import org.watermedia.api.media.MRL;
import org.watermedia.api.platform.internal.WaterPlatform;
import org.watermedia.api.platform.web.*;
import org.watermedia.api.util.MathUtil;
import org.watermedia.tools.ThreadTool;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Platform API. Owns the {@link IPlatform} registry and exposes a way to
 * fetch a URI's raw media data — direct links, dimensions, metadata — without
 * going through {@link MRL}.
 * <p>
 * Platforms return {@link PlatformData} (their own structure); {@link MRL} and other
 * consumers build their domain types (e.g. {@code MRL.Source}) from that data.
 */
public class PlatformAPI extends WaterMediaAPI {
    private static final Marker IT = MarkerManager.getMarker(PlatformAPI.class.getSimpleName());
    // CopyOnWriteArrayList: registration is rare, iteration (from MRL loader threads)
    // is hot and must not throw ConcurrentModificationException.
    static final CopyOnWriteArrayList<IPlatform> PLATFORMS = new CopyOnWriteArrayList<>();

    // SEARCH IS CLIENT-ONLY: A COORDINATOR TASK RUNS THE ACTIVE SEARCH OFF THE CALLER (UI) THREAD AND
    // FANS EACH PLATFORM PROBE OUT ONTO THIS SAME POOL, SO A SLOW PROCESS-SPAWNING HANDLER (yt-dlp/
    // YouTube) NEVER BLOCKS THE FAST HTTP ONES. A NEW SEARCH CANCELS THE IN-FLIGHT COORDINATOR
    // (searchTask), WHICH CANCELS ITS PROBES. CACHED POOL: DAEMON THREADS SPIN UP ON DEMAND AND ARE
    // REAPED WHEN IDLE, SO IT COSTS NOTHING BETWEEN SEARCHES AND CAN'T DEADLOCK COORDINATOR-ON-PROBE.
    private static final ExecutorService SEARCH = Executors.newCachedThreadPool(ThreadTool.createFactory(PlatformAPI.class.getSimpleName() + "-Search", Thread.NORM_PRIORITY));
    private static final Object SEARCH_LOCK = new Object();
    // RECENT QUERIES, MOST RECENT FIRST, CAPPED AT HISTORY_LIMIT — GUARDED BY SEARCH_LOCK
    private static final ArrayDeque<String> HISTORY = new ArrayDeque<>();
    private static final int HISTORY_LIMIT = 10;
    private static final int DEFAULT_LIMIT = 2; // RESULTS PER PLATFORM WHEN THE CALLER DOES NOT SPECIFY
    private static Future<?> searchTask; // CURRENT SEARCH — GUARDED BY SEARCH_LOCK, CANCELLED WHEN A NEW ONE STARTS

    // IN-MEMORY RESULT CACHE: SERVES IDENTICAL (limit, caption) QUERIES FROM MEMORY INSTEAD OF RE-QUERYING THE
    // PLATFORMS. THE WHOLE CACHE IS FLUSHED ONCE platforms.searchCacheCleanup MINUTES ELAPSE (GENERAL SWEEP,
    // CHECKED LAZILY ON EACH SEARCH LIKE MRL'S CLEANUP). BOTH FIELDS ARE GUARDED BY SEARCH_LOCK.
    // BOUNDED LRU (access-order): TYPING ISSUES A REAL SEARCH PER PARTIAL QUERY, SO CAP THE RETAINED
    // RESULT SETS (NEWEST-ACCESSED KEPT) RATHER THAN HOLDING EVERY QUERY'S HITS UNTIL THE TIMED FLUSH.
    private static final int SEARCH_CACHE_LIMIT = 32;
    private static final Map<String, List<PlatformResult>> SEARCH_CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, List<PlatformResult>> eldest) {
            return size() > SEARCH_CACHE_LIMIT;
        }
    };
    private static long nextCacheClean;

    /**
     * Searches every registered {@link IPlatform} for {@code caption}, with up to
     * {@value #DEFAULT_LIMIT} results per platform. Shorthand for {@link #search(String, int)}.
     *
     * @param caption the user's search text
     * @return a live {@link PlatformSearch} handle whose result list fills in off-thread
     */
    public static PlatformSearch search(final String caption) {
        return search(caption, DEFAULT_LIMIT);
    }

    /**
     * Starts an asynchronous search across every registered {@link IPlatform} and returns a live
     * {@link PlatformSearch} immediately. The handle's {@link PlatformSearch#results() result list}
     * grows off-thread as each platform answers — platforms are probed concurrently, so hits land in
     * completion order (fast HTTP handlers appear before slow process-spawning ones like yt-dlp), with
     * at most {@code limit} hits per platform.
     * <p>
     * Searches are client-side: this call cancels (interrupts) any still-running previous search, so
     * only the latest handle keeps updating. The {@code caption} is recorded in a
     * {@value #HISTORY_LIMIT}-entry {@link PlatformSearch#history() history}; a {@code null} or blank
     * caption is a no-op that returns an already-{@link PlatformSearch#done() done}, empty handle.
     *
     * @param caption the user's search text
     * @param limit   the maximum number of results to take from each platform (clamped to at least 1)
     * @return a live handle to poll for results
     */
    public static PlatformSearch search(final String caption, final int limit) {
        synchronized (SEARCH_LOCK) {
            // SUPERSEDE THE PREVIOUS SEARCH — ONLY ONE IS EVER ACTIVE
            if (searchTask != null) searchTask.cancel(true);
            searchTask = null;

            // BLANK QUERY: NOTHING TO SEARCH AND NOTHING WORTH REMEMBERING
            if (caption == null || caption.isBlank()) {
                final PlatformSearch empty = new PlatformSearch(caption, List.copyOf(HISTORY));
                empty.complete();
                return empty;
            }

            // RECORD IN HISTORY: DEDUP TO MOST-RECENT-FIRST, CAPPED AT HISTORY_LIMIT
            HISTORY.remove(caption);
            HISTORY.addFirst(caption);
            while (HISTORY.size() > HISTORY_LIMIT) HISTORY.removeLast();
            final List<String> historySnapshot = List.copyOf(HISTORY);

            // GENERAL CACHE SWEEP: ONCE THE INTERVAL ELAPSES, DROP EVERYTHING SO RESULTS NEVER GO TOO STALE
            final long now = System.currentTimeMillis();
            if (now >= nextCacheClean) {
                SEARCH_CACHE.clear();
                nextCacheClean = now + MathUtil.minutesToMs(WaterMediaConfig.platforms.searchCacheCleanup);
            }

            final int perPlatform = Math.max(1, limit);
            final String cacheKey = perPlatform + " " + caption;

            // CACHE HIT: SERVE A PRE-COMPLETED HANDLE WITHOUT TOUCHING THE PLATFORMS
            final List<PlatformResult> cached = SEARCH_CACHE.get(cacheKey);
            if (cached != null) {
                LOGGER.debug(IT, "Search '{}' served from cache ({} result(s))", caption, cached.size());
                final PlatformSearch hit = new PlatformSearch(caption, historySnapshot);
                hit.add(cached);
                hit.complete();
                return hit;
            }

            final PlatformSearch search = new PlatformSearch(caption, historySnapshot);
            searchTask = SEARCH.submit(() -> runSearch(search, caption, perPlatform, cacheKey));
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

    // COORDINATES THE ACTIVE SEARCH: FANS EVERY PLATFORM PROBE OUT ONTO THE SEARCH POOL SO THEY RUN
    // CONCURRENTLY, APPENDING HITS TO THE LIVE HANDLE AS EACH ANSWERS, THEN WAITS FOR ALL TO FINISH.
    // COOPERATIVELY STOPS WHEN A NEWER SEARCH INTERRUPTS US: IT CANCELS THE OUTSTANDING PROBES AND
    // LEAVES THE HANDLE FROZEN (NOT MARKED done). ONE PLATFORM FAILING NEVER ABORTS THE WHOLE SEARCH.
    private static void runSearch(final PlatformSearch search, final String caption, final int limit, final String cacheKey) {
        LOGGER.debug(IT, "Search '{}' started ({} per platform)", caption, limit);
        final List<Future<?>> probes = new ArrayList<>(PLATFORMS.size());
        for (int i = PLATFORMS.size() - 1; i >= 0; i--) {
            final IPlatform platform = PLATFORMS.get(i);
            probes.add(SEARCH.submit(() -> probePlatform(platform, search, caption, limit)));
        }

        try {
            for (final Future<?> probe : probes) probe.get();
        } catch (final InterruptedException e) { // SUPERSEDED — CANCEL THE OUTSTANDING PROBES AND LEAVE THE HANDLE FROZEN
            for (final Future<?> probe : probes) probe.cancel(true);
            Thread.currentThread().interrupt();
            LOGGER.debug(IT, "Search '{}' superseded before completing", caption);
            return;
        } catch (final ExecutionException e) { // probePlatform SWALLOWS ITS OWN FAILURES, SO THIS IS UNEXPECTED — LOG AND CARRY ON
            LOGGER.warn(IT, "Search '{}' probe failed unexpectedly: {}", caption, String.valueOf(e.getCause()));
        }

        search.complete();
        // CACHE ONLY FULLY-COMPLETED, NON-EMPTY SEARCHES (THE INTERRUPTED RETURN ABOVE NEVER REACHES HERE).
        // SKIPPING EMPTY RESULTS LETS A TRANSIENT TOTAL FAILURE RETRY NEXT TIME INSTEAD OF SERVING STALE NOTHING.
        final List<PlatformResult> finalResults = search.results();
        if (!finalResults.isEmpty()) {
            synchronized (SEARCH_LOCK) {
                SEARCH_CACHE.put(cacheKey, List.copyOf(finalResults));
            }
        }
        LOGGER.info(IT, "Search '{}' complete with {} result(s)", caption, finalResults.size());
    }

    // PROBES ONE PLATFORM ON A SEARCH-POOL THREAD AND APPENDS ITS HITS TO THE LIVE HANDLE. NEVER THROWS:
    // A CANCELLATION RESTORES THE INTERRUPT FLAG AND RETURNS, ANY OTHER FAILURE IS LOGGED AND SWALLOWED.
    private static void probePlatform(final IPlatform platform, final PlatformSearch search, final String caption, final int limit) {
        if (Thread.currentThread().isInterrupted()) return;
        try {
            final List<PlatformResult> hits = platform.search(caption, limit);
            if (hits != null && !hits.isEmpty()) {
                // DEFENSIVE PER-PLATFORM CAP: THE CONTRACT IS <= limit, BUT DON'T TRUST A MISBEHAVING HANDLER
                final List<PlatformResult> capped = hits.size() > limit ? hits.subList(0, limit) : hits;
                search.add(capped);
                LOGGER.info(IT, "Search '{}' matched {} result(s) on {}", caption, capped.size(), platform.name());
            }
        } catch (final InterruptedException e) { // CANCELLED — RESTORE THE FLAG SO THE POOL SEES THE INTERRUPT
            Thread.currentThread().interrupt();
            LOGGER.debug(IT, "Search '{}' interrupted on {}", caption, platform.name());
        } catch (final Throwable e) {
            // yt-dlp AND OTHERS WRAP THE INTERRUPT IN THEIR OWN EXCEPTION: IF THE FLAG IS SET, IT WAS A CANCELLATION
            if (Thread.currentThread().isInterrupted()) {
                LOGGER.debug(IT, "Search '{}' interrupted on {}", caption, platform.name());
                return;
            }
            LOGGER.warn(IT, "Search '{}' failed on {}: {}", caption, platform.name(), e.toString());
        }
    }

    /**
     * Finds the first registered {@link IPlatform} that handles the given URI
     * and returns its raw {@link PlatformData}. Use this when you only need the direct
     * links plus width/height/metadata, and don't want the {@link MRL} cache,
     * the async {@code Source} model, or the player lifecycle.
     * <p>
     * Iteration order is reverse-registration: the most recently {@link #register(IPlatform)
     * registered} platform is checked first, so app-registered overrides win over the
     * built-in handlers shipped by WaterMedia.
     *
     * @param uri the media URI
     * @return raw platform data, or {@code null} if no registered platform handled the URI
     * @throws PlatformException whatever the matching platform throws while resolving
     */
    public static PlatformData fetch(final URI uri) throws PlatformException {
        for (int i = PLATFORMS.size() - 1; i >= 0; i--) {
            final IPlatform platform = PLATFORMS.get(i);
            try {
                final PlatformData data = platform.getData(uri);
                if (data != null) {
                    LOGGER.debug(IT, "Fetched data from {} for {}", platform.name(), uri);
                    return data;
                }
            } catch (final PlatformException e) { // ALREADY DISPLAYABLE — RETHROW UNTOUCHED
                throw e;
            } catch (final IOException e) { // NETWORK/IO FAILURE TALKING TO THE PLATFORM — DISPLAYABLE, KEEP ITS DETAIL
                throw new PlatformException(platform.getClass(), "I/O failure resolving " + uri + " (" + e.getMessage() + ")", e);
            } catch (final Throwable e) { // BUG IN THE HANDLER — SURFACE THE CAUSE INLINE, KEEP THE TRACE AS THE CAUSE
                throw new PlatformException(platform.getClass(), "Unexpected error resolving " + uri + " (" + e + ")", e);
            }
        }
        return null; // NOTHING FOUND
    }

    /**
     * Registers a new platform handler. The newly registered handler is checked
     * <i>before</i> any previously registered handler in {@link #fetch(URI)} —
     * apps can override built-in handlers by registering a more specific one.
     */
    public static void register(final IPlatform platform) {
        LOGGER.info(IT, "• Registered {} platform", platform.name());
        PLATFORMS.add(platform);
    }

    private List<IPlatform> pendingPlatforms;

    @Override
    public String name() {
        return PlatformAPI.class.getSimpleName();
    }

    @Override
    public void load(final WaterMedia instance) {
        this.pendingPlatforms = new ArrayList<>();
        if (instance.clientSide) {
            this.pendingPlatforms.add(new WaterPlatform());
            this.pendingPlatforms.add(new ImgurPlatform());
            this.pendingPlatforms.add(new KickPlatform());
            this.pendingPlatforms.add(new StreamablePlatform());
            this.pendingPlatforms.add(new PornHubPlatform());
            this.pendingPlatforms.add(new LightshotPlatform());
            this.pendingPlatforms.add(new TwitchPlatform());
            this.pendingPlatforms.add(new TwitterPlatform());
            this.pendingPlatforms.add(new BlueskyPlatform());
            this.pendingPlatforms.add(new BiliBiliPlatform());
            this.pendingPlatforms.add(new DrivePlatform());
            this.pendingPlatforms.add(new DropboxPlatform());
            this.pendingPlatforms.add(new MediaFirePlatform());
            this.pendingPlatforms.add(new SendvidPlatform());
            this.pendingPlatforms.add(new OdyseePlatform());
            this.pendingPlatforms.add(new VidLiiPlatform());
            this.pendingPlatforms.add(new TikTokPlatform());
            this.pendingPlatforms.add(new DTubePlatform());
            this.pendingPlatforms.add(new YtDlpPlatform());
            this.pendingPlatforms.add(new YouTubePlatform());
        }
        this.steps = this.pendingPlatforms.size();
        this.step = 0;
        this.stepName = "";
    }

    @Override
    public boolean start(final WaterMedia instance) {
        if (!instance.clientSide) {
            LOGGER.warn(IT, "Platform API refuses to load on server-side");
            return false;
        }

        LOGGER.info(IT, "Registering supported platforms");
        for (final IPlatform platform: this.pendingPlatforms) {
            this.step++;
            this.stepName = platform.getClass().getSimpleName();
            register(platform);
            ThreadTool.sleep(50);
        }
        this.pendingPlatforms = null;
        return true;
    }

    @Override
    public void release(final WaterMedia instance) {
        // STOP THE ACTIVE SEARCH AND DROP THE HISTORY; KEEP THE (IDLE DAEMON) POOL FOR A LATER start()
        synchronized (SEARCH_LOCK) {
            if (searchTask != null) searchTask.cancel(true);
            searchTask = null;
            HISTORY.clear();
            SEARCH_CACHE.clear();
            nextCacheClean = 0L;
        }
        PLATFORMS.clear();
        this.pendingPlatforms = null;
        this.step = 0;
        this.steps = 0;
        this.stepName = "";
    }
}
