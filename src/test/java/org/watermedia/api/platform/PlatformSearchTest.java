package org.watermedia.api.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;
import org.watermedia.tools.ThreadTool;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link PlatformAPI#search(String, int)} and its live {@link PlatformSearch} handle
 * offline: history dedup/cap, the timed result cache, the defensive per-platform cap, and
 * supersede semantics. All pure-JVM logic driven by stub {@link Platform}s.
 * <p>
 * The registry is a package-private static and the search state is private, so the latter is
 * reached through reflection: each case snapshots and clears the registry, history and cache
 * before running (hermetic) and restores the registry after.
 */
@DisplayName("PlatformAPI search")
public class PlatformSearchTest {
    @BeforeAll
    static void clientBootstrap() { MediaBootstrap.client(); }

    private List<Platform> savedBuiltin;
    private List<Platform> savedCustom;

    @BeforeEach
    void isolate() throws Exception {
        // SNAPSHOT + CLEAR BOTH REGISTRIES SO ONLY THIS TEST'S STUBS ARE PROBED (NO REAL PLATFORM NETWORKING)
        this.savedBuiltin = new ArrayList<>(PlatformAPI.BUILTIN);
        this.savedCustom = new ArrayList<>(PlatformAPI.CUSTOM);
        PlatformAPI.BUILTIN.clear();
        PlatformAPI.CUSTOM.clear();
        resetSearchState();
    }

    @AfterEach
    void restore() throws Exception {
        cancelSearchTask();
        PlatformAPI.BUILTIN.clear();
        PlatformAPI.CUSTOM.clear();
        PlatformAPI.BUILTIN.addAll(this.savedBuiltin);
        PlatformAPI.CUSTOM.addAll(this.savedCustom);
        resetSearchState();
    }

    @Test
    @DisplayName("Blank query is a no-op that returns an already-done, empty handle")
    void testBlankQueryReturnsDoneEmpty() {
        final PlatformSearch search = PlatformAPI.search("   ", 2);
        assertTrue(search.done(), "A blank query must return an already-done handle");
        assertTrue(search.results().isEmpty(), "A blank query must yield no results");
        assertTrue(PlatformAPI.searchHistory().isEmpty(), "A blank query is not worth remembering");
    }

    @Test
    @DisplayName("History dedups to most-recent-first and caps at the limit")
    void testHistoryDedupAndCap() {
        // DEDUP: RE-SEARCHING 'alpha' MOVES IT BACK TO THE FRONT RATHER THAN DUPLICATING
        PlatformAPI.search("alpha", 2);
        PlatformAPI.search("beta", 2);
        PlatformAPI.search("alpha", 2);
        assertEquals(List.of("alpha", "beta"), PlatformAPI.searchHistory(), "History must dedup, newest first");

        // CAP: 15 DISTINCT QUERIES COLLAPSE TO THE 10 MOST RECENT (q15..q6), NEWEST FIRST
        for (int i = 1; i <= 15; i++) PlatformAPI.search("q" + i, 2);
        final List<String> history = PlatformAPI.searchHistory();
        assertEquals(10, history.size(), "History must be capped at 10 entries");
        assertEquals("q15", history.get(0), "Most recent query must be first");
        assertEquals("q6", history.get(9), "Oldest retained query must be the 10th");
    }

    @Test
    @DisplayName("A platform returning more than the limit is defensively capped")
    void testPerPlatformCap() {
        PlatformAPI.register(new StubPlatform(5, null)); // RETURNS 5 HITS; CALLER ASKS FOR 2
        final PlatformSearch search = PlatformAPI.search("cats", 2);
        assertTrue(PlayerWait.awaitCondition(search::done, 3_000), "Search must complete");
        assertEquals(2, search.results().size(), "Hits beyond the per-platform limit must be dropped");
    }

    @Test
    @DisplayName("An identical query is served from cache without re-probing the platforms")
    void testCacheHitDoesNotReprobe() throws Exception {
        final StubPlatform stub = new StubPlatform(1, null);
        PlatformAPI.register(stub);

        final PlatformSearch first = PlatformAPI.search("dogs", 2);
        assertTrue(PlayerWait.awaitCondition(first::done, 3_000), "Search must complete");
        // CACHING HAPPENS JUST AFTER COMPLETION — WAIT FOR IT TO LAND
        assertTrue(awaitCacheKey("2 dogs", 2_000), "The finished search must land in the cache");
        final int probesAfterFirst = stub.probes.get();

        final PlatformSearch second = PlatformAPI.search("dogs", 2);
        assertTrue(second.done(), "A cache hit must return an already-done handle");
        assertEquals(1, second.results().size(), "The cached results must be replayed");
        assertEquals(probesAfterFirst, stub.probes.get(), "A cache hit must not re-probe the platform");
    }

    @Test
    @DisplayName("Superseding a search ends the old handle at once, marked superseded and empty")
    void testSupersedeEndsOldHandle() throws Exception {
        final StubPlatform stub = new StubPlatform(1, 2_000); // BLOCKS SO THE FIRST SEARCH STAYS IN-FLIGHT
        PlatformAPI.register(stub);

        final PlatformSearch first = PlatformAPI.search("slow-a", 2);
        assertTrue(PlayerWait.awaitCondition(() -> stub.probes.get() == 1, 3_000), "The probe must start");
        assertFalse(first.done());
        assertFalse(first.superseded());
        PlatformAPI.search("slow-b", 2); // SUPERSEDES: CANCELS THE FIRST COORDINATOR + ITS PROBE
        assertTrue(first.done(), "A superseded handle ends as soon as the newer search starts");
        assertTrue(first.superseded());
        Thread.sleep(400);
        assertTrue(first.results().isEmpty(), "A superseded handle never gains the hits of its cancelled probes");
    }

    @Test
    @DisplayName("A blank query leaves the running search alone")
    void testBlankQueryDoesNotSupersede() {
        final StubPlatform stub = new StubPlatform(1, 300); // BLOCKS BRIEFLY SO THE FIRST SEARCH IS STILL IN-FLIGHT
        PlatformAPI.register(stub);

        final PlatformSearch first = PlatformAPI.search("slow-c", 2);
        assertTrue(PlayerWait.awaitCondition(() -> stub.probes.get() == 1, 3_000), "The probe must start");
        assertTrue(PlatformAPI.search("   ", 2).done(), "The blank query is a no-op handle");
        assertTrue(PlayerWait.awaitCondition(first::done, 3_000), "The in-flight search must complete instead of being cancelled by a no-op");
        assertFalse(first.superseded());
        assertEquals(1, first.results().size());
    }

    @Test
    @DisplayName("A registration during a search keeps that search out of the cache")
    void testRegistrationDuringSearchIsNotCached() {
        final StubPlatform stub = new StubPlatform(1, 300); // BLOCKS SO THE REGISTRY CHANGES WHILE THE SEARCH RUNS
        PlatformAPI.register(stub);
        final PlatformSearch first = PlatformAPI.search("race", 2);
        assertTrue(PlayerWait.awaitCondition(() -> stub.probes.get() == 1, 3_000), "The probe must start");
        PlatformAPI.register(new StubPlatform(1, null));
        assertTrue(PlayerWait.awaitCondition(first::done, 3_000), "Search must complete");
        assertEquals(1, first.results().size(), "The running search keeps its snapshot of the registry");

        final PlatformSearch again = PlatformAPI.search("race", 2);
        assertTrue(PlayerWait.awaitCondition(again::done, 3_000), "Search must complete");
        assertEquals(2, again.results().size(), "The query is probed again with the new handler instead of served from cache");
    }

    @Test
    @DisplayName("Registering a handler drops the cached searches")
    void testRegistrationInvalidatesCache() throws Exception {
        final StubPlatform stub = new StubPlatform(1, null);
        PlatformAPI.register(stub);
        final PlatformSearch first = PlatformAPI.search("birds", 2);
        assertTrue(PlayerWait.awaitCondition(first::done, 3_000), "Search must complete");
        assertTrue(awaitCacheKey("2 birds", 2_000), "The finished search must land in the cache");

        PlatformAPI.register(new StubPlatform(1, null));
        final PlatformSearch again = PlatformAPI.search("birds", 2);
        assertTrue(PlayerWait.awaitCondition(again::done, 3_000), "Search must complete");
        assertEquals(2, stub.probes.get(), "A new handler must force the query to be probed again");
        assertEquals(2, again.results().size(), "Both handlers answer the re-probed query");
    }

    // ==========================================================================
    // STUBS + REFLECTION HELPERS
    // ==========================================================================

    // EMITS `count` MARKER HITS PER SEARCH; A NON-NULL `blockMs` SLEEPS (INTERRUPTIBLY) TO STAY IN-FLIGHT.
    private static final class StubPlatform implements Platform {
        private final int count;
        private final Integer blockMs;
        private final AtomicInteger probes = new AtomicInteger();

        StubPlatform(final int count, final Integer blockMs) {
            this.count = count;
            this.blockMs = blockMs;
        }

        @Override public String name() { return "STUB"; }
        @Override public Resolution resolve(final URI uri) { return null; }

        @Override
        public List<PlatformResult> search(final String query, final int limit) throws PlatformException {
            this.probes.incrementAndGet();
            if (this.blockMs != null && !ThreadTool.sleep(this.blockMs)) throw new PlatformException("STUB", "interrupted");
            final List<PlatformResult> out = new ArrayList<>(this.count);
            for (int i = 0; i < this.count; i++) {
                out.add(new PlatformResult(this, query + "#" + i, null, URI.create("https://stub.test/" + i)));
            }
            return out;
        }
    }

    // WAITS FOR key IN THE RESULT CACHE, READ UNDER THE SEARCH LOCK THAT GUARDS ITS WRITES
    @SuppressWarnings("unchecked")
    private static boolean awaitCacheKey(final String key, final long timeoutMs) throws Exception {
        final Object lock = field("SEARCH_LOCK").get(null);
        final Map<String, ?> cache = (Map<String, ?>) field("SEARCH_CACHE").get(null);
        return PlayerWait.awaitCondition(() -> {
            synchronized (lock) {
                return cache.containsKey(key);
            }
        }, timeoutMs);
    }

    private static void cancelSearchTask() throws Exception {
        final Object lock = field("SEARCH_LOCK").get(null);
        final Field taskField = field("searchTask");
        synchronized (lock) {
            final Future<?> task = (Future<?>) taskField.get(null);
            if (task != null) task.cancel(true);
            taskField.set(null, null);
        }
    }

    // CLEARS HISTORY, THE RESULT CACHE AND THE CACHE-SWEEP CLOCK SO EACH CASE STARTS FROM A CLEAN SLATE
    private static void resetSearchState() throws Exception {
        final Object lock = field("SEARCH_LOCK").get(null);
        synchronized (lock) {
            ((ArrayDeque<?>) field("HISTORY").get(null)).clear();
            ((Map<?, ?>) field("SEARCH_CACHE").get(null)).clear();
            field("nextCacheClean").set(null, 0L);
        }
    }

    private static Field field(final String name) throws Exception {
        final Field f = PlatformAPI.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
