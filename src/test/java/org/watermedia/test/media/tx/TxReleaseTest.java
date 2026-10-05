package org.watermedia.test.media.tx;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.engines.HeadlessGFXEngine;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.api.media.players.TxMediaPlayer;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.LocalHttp;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TxReleaseTest {
    @BeforeAll static void boot() { MediaBootstrap.client(); }

    @Test
    void cancellingQueuedPreparationDoesNotWaitForANonexistentWorker() throws Exception {
        final MRL mrl = MediaAPI.mrl(Fixtures.fileUri(Fixtures.PNG_STATIC));
        assertTrue(mrl.await(3000));
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
            assertTimeout(Duration.ofSeconds(2), player::release);
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
    void blockedPreparationRetainsGraphicsUntilReleaseCanBeRetried() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);
        final byte[] image = "P6\n1 1\n255\nRGB".getBytes(StandardCharsets.US_ASCII);
        final int previousTimeout = WaterMediaConfig.network.timeout;
        WaterMediaConfig.network.timeout = 20000;
        final int previousPlayers = MediaPlayer.openPlayers();
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
            final IPlatform platform = new IPlatform() {
                @Override public String name() { return "Blocked image fixture"; }
                @Override public PlatformData data(final URI uri) {
                    return !key.equals(uri) ? null : new PlatformData(null,
                            new DataSource(MediaType.IMAGE, null, null, new RequestHeaders(),
                                    List.of(new DataQuality(origin.uri("/held.pnm"), 0, 0)), null, null));
                }
            };
            PlatformAPI.register(platform);
            TxMediaPlayer player = null;
            try {
                final MRL mrl = MediaAPI.mrl(key);
                assertTrue(mrl.await(3000));
                assertEquals(MRL.Status.LOADED, mrl.status());
                final HeadlessGFXEngine gfx = new HeadlessGFXEngine();
                player = new TxMediaPlayer(mrl, 0, gfx);
                final TxMediaPlayer active = player;
                assertTrue(player.start());
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, player::release);
                assertFalse(gfx.released());
                assertEquals(previousPlayers + 1, MediaPlayer.openPlayers());
                assertFalse(player.start());
                resume.countDown();
                assertTrue(PlayerWait.awaitCondition(() -> idle(active), 3000));
                assertEquals(0, gfx.uploadCount());
                assertNull(gfx.format());
                player.release();
                assertTrue(gfx.released());
                assertEquals(previousPlayers, MediaPlayer.openPlayers());
            } finally {
                resume.countDown();
                PlatformAPI.unregister(platform);
                if (player != null) {
                    final TxMediaPlayer active = player;
                    assertTrue(PlayerWait.awaitCondition(() -> idle(active), 5000));
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
