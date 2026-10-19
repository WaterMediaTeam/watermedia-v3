package org.watermedia.api.media;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.media.MediaPlayer.Status;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.LocalHttp;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies the {@link FFMediaPlayer} controls issued around a load: a pause or seek while the source opens is latched
 * for the run, a speed chosen before {@code start()} survives into playback, a {@code stop()} during the load returns at
 * once and lands STOPPED without leaking pipeline threads, and an HTTP source without Content-Length plays while the
 * network cache is on. Loopback origins hold their requests so every case observes a real LOADING window.
 */
@DisplayName("FFMediaPlayer control contract")
public class FFControlContractTest {

    // TIMEOUTS
    private static final long MRL_TIMEOUT_MS = 3_000L;
    private static final long LOAD_TIMEOUT_MS = 15_000L;
    private static final long PLAY_OBSERVE_MS = 8_000L;
    private static final long STOP_RETURN_MS = 1_000L;   // stop() ONLY SIGNALS THE PIPELINE, IT NEVER WAITS FOR THE OPEN
    private static final long STOP_SETTLE_MS = 3_000L;   // FFMPEG POLLS ITS INTERRUPT CALLBACK EVERY 100ms
    private static final long THREAD_EXIT_MS = 5_000L;
    private static final long HOLD_LIMIT_MS = 30_000L;   // A HELD REQUEST NEVER OUTLIVES A FAILED TEST BY MORE
    // PLAYBACK
    private static final long SEEK_LANDING_MS = 1_500L;  // A PAUSED RESTART LANDS ON THE KEYFRAME NEAREST THE TARGET
    private static final float SPEED = 2.0f;
    private static final double RATE_FLOOR = 1.25;       // A 2x CLOCK MAY STALL A QUARTER OF THE WINDOW ON A SLOW HOST; A RESET TO 1x NEVER CLEARS THIS
    private static final long RATE_WINDOW_MS = 1_000L;
    private static final int WEBM_FRAMES = 90;

    private boolean cache;

    @BeforeAll
    static void boot() {
        assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable — skipping FFMediaPlayer control tests");
    }

    @BeforeEach
    void rememberCache() { this.cache = WaterMediaConfig.media.ffmpeg.cache; }

    @AfterEach
    void restoreCache() { WaterMediaConfig.media.ffmpeg.cache = this.cache; }

    @Nested
    @DisplayName("Controls before the source opens")
    class Loading {

        @Test
        @DisplayName("A pause during the first load is latched and lands PAUSED; a seek there is refused until the source opens")
        void pauseDuringFirstLoad() throws Exception {
            WaterMediaConfig.media.ffmpeg.cache = false;
            try (final Origin origin = Origin.of(Fixtures.readAll(Fixtures.MP4_H264), "video/mp4")) {
                final MRL mrl = resolve(origin, ".mp4");
                final HeadlessGFXEngine gfx = new HeadlessGFXEngine(false);
                final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, gfx, null);
                try {
                    origin.hold();
                    assertTrue(player.start());
                    assertTrue(origin.awaitEntered(LOAD_TIMEOUT_MS), "the open must reach the held origin");
                    assertEquals(Status.LOADING, player.status());
                    assertTrue(player.pause(), "a pause while loading is latched");
                    assertFalse(player.pause(), "the latched intent is already a pause");
                    assertFalse(player.seek(1_000L), "nothing is seekable before the source opens");
                    assertEquals(Status.LOADING, player.status(), "no control may leave LOADING before the pipeline opens");

                    origin.release();
                    assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PAUSED), () -> "landed " + player.status());
                    assertEquals(0L, player.time(), "the latched pause holds the start");
                    assertTrue(player.canSeek(), "an opened source is seekable");
                    assertTrue(player.resume());
                    assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PLAYING));
                    assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0, PLAY_OBSERVE_MS));
                } finally {
                    origin.release();
                    player.release();
                }
            }
        }

        @Test
        @DisplayName("A seek and a pause during a restart's load are latched and land PAUSED at the target")
        void seekDuringReload() throws Exception {
            WaterMediaConfig.media.ffmpeg.cache = false;
            try (final Origin origin = Origin.of(Fixtures.readAll(Fixtures.MP4_H264), "video/mp4")) {
                final MRL mrl = resolve(origin, ".mp4");
                final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, new HeadlessGFXEngine(false), null);
                try {
                    assertTrue(player.start());
                    assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PLAYING));
                    final long duration = player.duration();
                    assertTrue(duration > 2 * SEEK_LANDING_MS, "the fixture must be long enough to tell the target apart");
                    final long target = duration / 2;
                    assertTrue(player.stop());
                    assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.STOPPED));

                    origin.hold();
                    assertTrue(player.start());
                    assertTrue(origin.awaitEntered(LOAD_TIMEOUT_MS), "the reload must reach the held origin");
                    assertEquals(Status.LOADING, player.status());
                    assertTrue(player.seek(target), "a source that opened before stays seekable while it reloads");
                    assertTrue(player.pause(), "a pause while reloading is latched");
                    assertEquals(Status.LOADING, player.status(), "a latched seek must not leave LOADING before the pipeline opens");

                    origin.release();
                    assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PAUSED), () -> "landed " + player.status());
                    assertTrue(Math.abs(player.time() - target) < SEEK_LANDING_MS, "the reload lands near the latched target: " + player.time());
                } finally {
                    origin.release();
                    player.release();
                }
            }
        }

        @Test
        @DisplayName("A speed chosen before start() survives into playback")
        void speedBeforeStart() throws Exception {
            final MRL mrl = MediaAPI.mrl(Fixtures.fileUri(Fixtures.MP4_H264));
            assertTrue(mrl.await(MRL_TIMEOUT_MS));
            final HeadlessGFXEngine gfx = new HeadlessGFXEngine(false);
            final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, gfx, null);
            try {
                assertTrue(player.speed(SPEED), "a player that never ran takes a new speed");
                assertTrue(player.start());
                assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PLAYING));
                assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0, PLAY_OBSERVE_MS));
                assertEquals(SPEED, player.speed());

                // BRACKET BOTH READINGS: THE CLOCK MUST RUN AT THE CHOSEN RATE, NOT AT THE 1x A FRESH RUN COULD RESET IT TO
                final long before = System.nanoTime();
                final long from = player.time();
                final long started = System.nanoTime();
                Thread.sleep(RATE_WINDOW_MS);
                final long reading = System.nanoTime();
                final long to = player.time();
                final long after = System.nanoTime();
                final long minimum = (long) (TimeUnit.NANOSECONDS.toMillis(reading - started) * RATE_FLOOR);
                // EACH READING TRUNCATES THE CLOCK TO WHOLE MILLISECONDS
                final long maximum = (long) (TimeUnit.NANOSECONDS.toMillis(after - before) * SPEED) + 2L;
                assertTrue(to - from >= minimum && to - from <= maximum,
                        "the clock must advance within [" + minimum + ", " + maximum + "] ms, advanced " + (to - from));
            } finally {
                player.release();
            }
        }
    }

    @Nested
    @DisplayName("Slow and unbounded HTTP origins")
    class Origins {

        @Test
        @DisplayName("stop() while FFmpeg opens a held origin returns at once and lands STOPPED without leaking threads")
        void stopWhileFFmpegOpens() throws Exception {
            stopWhileLoading(false, frame -> frame.getMethodName().equals("avformat_open_input"));
        }

        @Test
        @DisplayName("stop() while the network cache downloads a held origin returns at once and lands STOPPED without leaking threads")
        void stopWhileTheCacheDownloads() throws Exception {
            stopWhileLoading(true, frame -> frame.getClassName().equals(NetworkCache.class.getName()));
        }

        @Test
        @DisplayName("An HTTP source without Content-Length plays while the network cache is on, streamed instead of cached")
        void unboundedSourceBypassesTheCache() throws Exception {
            WaterMediaConfig.media.ffmpeg.cache = true;
            try (final Origin origin = Origin.endless(Fixtures.webm(96, 64, WEBM_FRAMES, 30, 32), "video/webm")) {
                final MRL mrl = resolve(origin, ".webm");
                final int probes = origin.requests();
                final HeadlessGFXEngine gfx = new HeadlessGFXEngine(false);
                final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, gfx, null);
                try {
                    assertTrue(player.start());
                    // A CACHE DOWNLOADING THE ENDLESS BODY WOULD HOLD THE LOAD UNTIL ITS READ TIMEOUT, LONG AFTER THIS WAIT
                    assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0, PLAY_OBSERVE_MS),
                            () -> "no frame reached the engine, status " + player.status());
                    assertNull(player.exception());
                    assertTrue(origin.requests() - probes >= 2, "the cache must ask first and FFmpeg then stream the source itself");
                } finally {
                    player.release();
                }
            }
        }
    }

    // HOLDS THE ORIGIN UNTIL A NEW PIPELINE THREAD BLOCKS WHERE blocked SAYS, THEN STOPS THE LOADING PLAYER THERE
    private static void stopWhileLoading(final boolean cache, final Predicate<StackTraceElement> blocked) throws Exception {
        WaterMediaConfig.media.ffmpeg.cache = cache;
        try (final Origin origin = Origin.of(Fixtures.readAll(Fixtures.MP4_H264), "video/mp4")) {
            final MRL mrl = resolve(origin, ".mp4");
            final Set<Thread> baseline = Set.copyOf(Thread.getAllStackTraces().keySet());
            final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, new HeadlessGFXEngine(false), null);
            try {
                origin.hold();
                assertTrue(player.start());
                assertTrue(origin.awaitEntered(LOAD_TIMEOUT_MS), "the open must reach the held origin");
                assertTrue(PlayerWait.awaitCondition(() -> pipeline(baseline).stream()
                        .anyMatch(thread -> Arrays.stream(thread.getStackTrace()).anyMatch(blocked)), LOAD_TIMEOUT_MS),
                        "the load must block where this case expects");
                assertEquals(Status.LOADING, player.status());

                final long before = System.nanoTime();
                assertTrue(player.stop(), "a loading player has a pipeline to stop");
                final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
                assertTrue(elapsedMs < STOP_RETURN_MS, "stop() must not wait for the open, it took " + elapsedMs + " ms");
                assertTrue(PlayerWait.awaitStatus(player, STOP_SETTLE_MS, Status.STOPPED), () -> "still " + player.status());
                assertNull(player.exception(), "an open cut short by stop() is not a failure");
                assertFalse(player.canPlay(), "the source never opened");
                assertTrue(PlayerWait.awaitCondition(() -> pipeline(baseline).isEmpty(), THREAD_EXIT_MS),
                        () -> "pipeline threads left behind: " + pipeline(baseline));
            } finally {
                origin.release();
                player.release();
            }
        }
    }

    // RESOLVES A FRESH MRL FOR THE ORIGIN; ITS PROBE GOES THROUGH BEFORE ANY hold()
    private static MRL resolve(final Origin origin, final String extension) {
        final MRL mrl = MediaAPI.mrl(origin.uri(extension));
        assertTrue(mrl.await(MRL_TIMEOUT_MS));
        assertEquals(MRL.Status.LOADED, mrl.status());
        return mrl;
    }

    // PIPELINE THREADS (FFThread-*) STARTED AFTER THE BASELINE AND STILL ALIVE
    private static List<Thread> pipeline(final Set<Thread> baseline) {
        final List<Thread> alive = new ArrayList<>();
        for (final Thread thread: Thread.getAllStackTraces().keySet())
            if (!baseline.contains(thread) && thread.getName().startsWith("FFThread-")) alive.add(thread);
        return alive;
    }

    // LOOPBACK ORIGIN SERVING ONE BODY UNDER EVERY PATH. hold() PARKS LATER REQUESTS BEFORE THEIR HEADERS UNTIL release(), SO A
    // PLAYER STAYS IN LOADING AS LONG AS A TEST NEEDS; AN ENDLESS ORIGIN SENDS ITS BODY CHUNKED AND NEVER FINISHES IT
    private static final class Origin implements AutoCloseable {
        private final ExecutorService workers = Executors.newCachedThreadPool();
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicInteger requests = new AtomicInteger();
        private final LocalHttp http;
        private volatile CountDownLatch gate = new CountDownLatch(0);
        private volatile CountDownLatch entered = new CountDownLatch(1);

        private Origin(final byte[] body, final String type, final boolean endless) {
            this.http = LocalHttp.start("/", exchange -> {
                this.requests.incrementAndGet();
                final CountDownLatch admission = this.gate;
                this.entered.countDown();
                try {
                    if (!admission.await(HOLD_LIMIT_MS, TimeUnit.MILLISECONDS)) throw new IOException("Held request outlived its test");
                    if (!endless) {
                        LocalHttp.respondRange(exchange, type, body);
                        return;
                    }
                    exchange.getResponseHeaders().set("Content-Type", type);
                    exchange.sendResponseHeaders(200, 0); // CHUNKED: NO Content-Length AND NO RANGES, LIKE A LIVE FEED
                    exchange.getResponseBody().write(body);
                    exchange.getResponseBody().flush();
                    if (!this.closed.await(HOLD_LIMIT_MS, TimeUnit.MILLISECONDS)) throw new IOException("Endless feed outlived its test");
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            }, this.workers);
        }

        static Origin of(final byte[] body, final String type) { return new Origin(body, type, false); }
        static Origin endless(final byte[] body, final String type) { return new Origin(body, type, true); }

        // A FRESH PATH PER CALL KEEPS EVERY TEST ON ITS OWN MRL AND CACHE KEY
        URI uri(final String extension) { return this.http.uri("/clip-" + System.nanoTime() + extension); }

        // PARKS EVERY LATER REQUEST; awaitEntered() ANSWERS ONCE THE FIRST ONE ARRIVES
        void hold() {
            this.entered = new CountDownLatch(1);
            this.gate = new CountDownLatch(1);
        }

        boolean awaitEntered(final long timeoutMs) throws InterruptedException { return this.entered.await(timeoutMs, TimeUnit.MILLISECONDS); }
        void release() { this.gate.countDown(); }
        int requests() { return this.requests.get(); }

        @Override
        public void close() {
            this.gate.countDown();
            this.closed.countDown();
            this.http.close();
            this.workers.shutdownNow();
        }
    }
}
