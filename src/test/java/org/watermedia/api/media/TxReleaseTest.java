package org.watermedia.api.media;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.Platform;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.LocalHttp;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that releasing a {@link TxMediaPlayer} never waits for a preparation that has no worker yet, nor for a held
 * download, whose socket read runs on a cache worker while the preparation waits interruptibly.
 */
@DisplayName("TxMediaPlayer release")
public class TxReleaseTest {
    private static final long RELEASE_RETURN_MS = 2_000L;

    @BeforeAll static void boot() { MediaBootstrap.client(); }

    @Test
    @DisplayName("Releasing a preparation still queued behind a busy pool returns at once and frees the graphics")
    void cancellingQueuedPreparationDoesNotWaitForANonexistentWorker() throws Exception {
        final MRL mrl = MediaAPI.mrl(Fixtures.fileUri(Fixtures.PNG_STATIC));
        assertTrue(mrl.await(3_000));
        final var field = TxMediaPlayer.class.getDeclaredField("SINGLE_FRAME_POOL");
        field.setAccessible(true);
        final ThreadPoolExecutor pool = (ThreadPoolExecutor) field.get(null);
        final CountDownLatch entered = new CountDownLatch(pool.getCorePoolSize());
        final CountDownLatch resume = new CountDownLatch(1);
        final List<Future<?>> blockers = new ArrayList<>();
        final HeadlessGFXEngine gfx = new HeadlessGFXEngine();
        final TxMediaPlayer player = new TxMediaPlayer(mrl, 0, gfx);
        try {
            for (int i = 0; i < pool.getCorePoolSize(); i++) {
                blockers.add(pool.submit(() -> {
                    entered.countDown();
                    try { resume.await(); }
                    catch (final InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }));
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(player.start());
            final long before = System.nanoTime();
            player.release();
            final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
            assertTrue(elapsedMs < RELEASE_RETURN_MS, "release() must not wait for a worker that never started, it took " + elapsedMs + " ms");
            assertTrue(gfx.released());
            assertFalse(player.start());
            assertTrue(idle(player));
        } finally {
            resume.countDown();
            for (final Future<?> blocker: blockers) blocker.get(3, TimeUnit.SECONDS);
            player.release();
        }
        assertEquals(0, gfx.uploadCount());
        assertNull(gfx.format());
    }

    @Test
    @DisplayName("A preparation waiting on a held download releases at once and frees the graphics")
    void heldDownloadDoesNotStallRelease() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);
        final byte[] image = "P6\n1 1\n255\nRGB".getBytes(StandardCharsets.US_ASCII);
        final int previousTimeout = WaterMediaConfig.network.timeout;
        WaterMediaConfig.network.timeout = 20_000;
        try (final LocalHttp origin = LocalHttp.start("/held.pnm", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/x-portable-pixmap");
            exchange.sendResponseHeaders(200, image.length);
            entered.countDown();
            try {
                if (!resume.await(15, TimeUnit.SECONDS)) throw new IOException("Held image timed out");
            }
            catch (final InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            try { exchange.getResponseBody().write(image); }
            finally { exchange.close(); }
        })) {
            final URI key = URI.create("fixture:tx-release-" + System.nanoTime());
            final Platform platform = new Platform() {
                @Override public String name() { return "Blocked image fixture"; }
                @Override public Resolution resolve(final URI uri) {
                    return !key.equals(uri) ? null : new Resolution(null,
                            new Source(MediaType.IMAGE, null, null, new HttpHeaders(),
                                    List.of(new Quality(origin.uri("/held.pnm"), 0, 0)), null, null));
                }
            };
            PlatformAPI.register(platform);
            TxMediaPlayer player = null;
            try {
                final MRL mrl = MediaAPI.mrl(key);
                assertTrue(mrl.await(3_000));
                assertEquals(MRL.Status.LOADED, mrl.status());
                final HeadlessGFXEngine gfx = new HeadlessGFXEngine();
                player = new TxMediaPlayer(mrl, 0, gfx);
                final TxMediaPlayer active = player;
                assertTrue(player.start());
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                // THE SOCKET READ RUNS ON A CACHE WORKER; THE RELEASE MUST FIND THE PREPARATION WAITING FOR IT
                final Field prepareField = TxMediaPlayer.class.getDeclaredField("prepareThread");
                prepareField.setAccessible(true);
                assertTrue(PlayerWait.awaitCondition(() -> {
                    final Thread prepare;
                    try { prepare = (Thread) prepareField.get(active); }
                    catch (final IllegalAccessException failure) { throw new AssertionError(failure); }
                    if (prepare == null) return false;
                    for (final StackTraceElement frame: prepare.getStackTrace())
                        if (frame.getClassName().equals(NetworkCache.class.getName())) return true;
                    return false;
                }, 3_000), "The preparation did not wait for its download");
                final long before = System.nanoTime();
                player.release();
                final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
                assertTrue(elapsedMs < RELEASE_RETURN_MS, "release() waited " + elapsedMs + " ms for the held download");
                assertTrue(gfx.released());
                assertTrue(idle(active), "the preparation left with the release");
                assertEquals(0, gfx.uploadCount());
                assertFalse(player.start());
            } finally {
                resume.countDown();
                PlatformAPI.unregister(platform);
                if (player != null) {
                    final TxMediaPlayer active = player;
                    assertTrue(PlayerWait.awaitCondition(() -> idle(active), 5_000));
                    player.release();
                }
            }
        } finally {
            resume.countDown();
            WaterMediaConfig.network.timeout = previousTimeout;
        }
    }

    private static boolean idle(final TxMediaPlayer player) {
        try {
            final var lock = TxMediaPlayer.class.getDeclaredField("signals");
            final var active = TxMediaPlayer.class.getDeclaredField("prepareActive");
            lock.setAccessible(true);
            active.setAccessible(true);
            synchronized (lock.get(player)) { return active.getInt(player) == 0; }
        } catch (final ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
