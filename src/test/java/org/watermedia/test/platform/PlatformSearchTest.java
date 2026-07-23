package org.watermedia.test.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.platform.IPlatform;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.api.platform.PlatformResult;
import org.watermedia.api.platform.PlatformSearch;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline coverage for {@link PlatformAPI#search(String, int)} and its live {@link PlatformSearch}
 * handle: history dedup/cap, the timed result cache, the defensive per-platform cap, and
 * supersede semantics. All pure-JVM logic driven by stub {@link IPlatform}s.
 * <p>
 * The registry and search state are package-private statics, so this cross-package test reaches
 * them through reflection: it snapshots and clears the registry, history and cache before each
 * case (hermetic) and restores the registry after.
 */
@DisplayName("PlatformAPI search")
public class PlatformSearchTest {

    private List<IPlatform> savedPlatforms;

    @BeforeEach
    void isolate() throws Exception {
        // SNAPSHOT + CLEAR THE REGISTRY SO ONLY THIS TEST'S STUBS ARE PROBED (NO REAL PLATFORM NETWORKING)
        final CopyOnWriteArrayList<IPlatform> platforms = platforms();
        this.savedPlatforms = new ArrayList<>(platforms);
        platforms.clear();
        resetSearchState();
    }

    @AfterEach
    void restore() throws Exception {
        cancelSearchTask();
        final CopyOnWriteArrayList<IPlatform> platforms = platforms();
        platforms.clear();
        platforms.addAll(this.savedPlatforms);
        resetSearchState();
    }

    @Test
    @DisplayName("Blank query is a no-op that returns an already-done, empty handle")
    void testBlankQueryReturnsDoneEmpty() {
        final PlatformSearch search = PlatformAPI.search("   ", 2);
        assertTrue(search.done(), "A blank query must return an already-done handle");
        assertTrue(search.results().isEmpty(), "A blank query must yield no results");
        assertTrue(search.history().isEmpty(), "History is empty after isolation, so the handle carries none");
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
    void testPerPlatformCap() throws Exception {
        PlatformAPI.register(new StubPlatform(5, null)); // RETURNS 5 HITS; CALLER ASKS FOR 2
        final PlatformSearch search = PlatformAPI.search("cats", 2);
        awaitDone(search, 3000);
        assertTrue(search.done(), "Search must complete");
        assertEquals(2, search.results().size(), "Hits beyond the per-platform limit must be dropped");
    }

    @Test
    @DisplayName("An identical query is served from cache without re-probing the platforms")
    void testCacheHitDoesNotReprobe() throws Exception {
        final StubPlatform stub = new StubPlatform(1, null);
        PlatformAPI.register(stub);

        final PlatformSearch first = PlatformAPI.search("dogs", 2);
        awaitDone(first, 3000);
        awaitCacheKey("2 dogs", 2000); // CACHING HAPPENS JUST AFTER completion — WAIT FOR IT TO LAND
        final int probesAfterFirst = stub.probes;

        final PlatformSearch second = PlatformAPI.search("dogs", 2);
        assertTrue(second.done(), "A cache hit must return an already-done handle");
        assertEquals(1, second.results().size(), "The cached results must be replayed");
        assertEquals(probesAfterFirst, stub.probes, "A cache hit must not re-probe the platform");
    }

    @Test
    @DisplayName("Superseding a search freezes the old handle — it is never marked done")
    void testSupersedeFreezesOldHandle() throws Exception {
        PlatformAPI.register(new StubPlatform(1, 2000)); // BLOCKS SO THE FIRST SEARCH STAYS IN-FLIGHT

        final PlatformSearch first = PlatformAPI.search("slow-a", 2);
        Thread.sleep(150); // LET THE PROBE START
        PlatformAPI.search("slow-b", 2); // SUPERSEDES: CANCELS THE FIRST COORDINATOR + ITS PROBE
        Thread.sleep(400);

        assertFalse(first.done(), "A superseded handle must stay frozen (never marked done)");
    }

    // ==========================================================================
    // STUBS + REFLECTION HELPERS
    // ==========================================================================

    // EMITS `count` MARKER HITS PER SEARCH; A NON-NULL `blockMs` SLEEPS (INTERRUPTIBLY) TO STAY IN-FLIGHT.
    private static final class StubPlatform implements IPlatform {
        private final int count;
        private final Integer blockMs;
        private volatile int probes;

        StubPlatform(final int count, final Integer blockMs) {
            this.count = count;
            this.blockMs = blockMs;
        }

        @Override public String name() { return "STUB"; }
        @Override public org.watermedia.api.platform.PlatformData getData(final URI uri) { return null; }

        @Override
        public List<PlatformResult> search(final String query, final int limit) throws Exception {
            this.probes++;
            if (this.blockMs != null) Thread.sleep(this.blockMs);
            final List<PlatformResult> out = new ArrayList<>(this.count);
            for (int i = 0; i < this.count; i++) {
                out.add(new PlatformResult("STUB", query + "#" + i, null, URI.create("https://stub.test/" + i)));
            }
            return out;
        }
    }

    private static void awaitDone(final PlatformSearch search, final long timeoutMs) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (!search.done() && System.currentTimeMillis() < deadline) Thread.sleep(10);
    }

    @SuppressWarnings("unchecked")
    private static void awaitCacheKey(final String key, final long timeoutMs) throws Exception {
        final Object lock = field("SEARCH_LOCK").get(null);
        final Map<String, ?> cache = (Map<String, ?>) field("SEARCH_CACHE").get(null);
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            synchronized (lock) {
                if (cache.containsKey(key)) return;
            }
            Thread.sleep(10);
        }
    }

    @SuppressWarnings("unchecked")
    private static CopyOnWriteArrayList<IPlatform> platforms() throws Exception {
        return (CopyOnWriteArrayList<IPlatform>) field("PLATFORMS").get(null);
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
