package org.watermedia.test.media.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.media.players.util.NetworkCache;
import org.watermedia.test.support.LocalHttp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NetworkCacheSessionTest {
    @TempDir
    Path directory;

    @Test
    void retiredDownloadCannotPublishIntoRestartedStore() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger hits = new AtomicInteger();
        final var executor = Executors.newFixedThreadPool(2);
        final var originExecutor = Executors.newFixedThreadPool(2);
        try (final LocalHttp origin = LocalHttp.start("/image.png", exchange -> {
            final int hit = hits.incrementAndGet();
            if (hit == 1) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test download timed out");
                } catch (final InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IOException(failure);
                }
            }
            LocalHttp.respond(exchange, "image/png", new byte[] { (byte) hit }, 3600);
        }, originExecutor)) {
            final Path firstStore = this.directory.resolve("first");
            NetworkCache.start(firstStore);
            final var previous = executor.submit(() -> NetworkCache.read(origin.uri("/image.png"), null, "image/*", 1024, true));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            NetworkCache.release();
            final Path secondStore = this.directory.resolve("second");
            NetworkCache.start(secondStore);
            final var current = executor.submit(() -> NetworkCache.read(origin.uri("/image.png"), null, "image/*", 1024, true));
            assertArrayEquals(new byte[] { 2 }, current.get(3, TimeUnit.SECONDS).bytes());
            release.countDown();
            assertInstanceOf(IOException.class, assertThrows(ExecutionException.class,
                    () -> previous.get(3, TimeUnit.SECONDS)).getCause());
            assertFalse(Files.exists(firstStore.resolve("index.dat")));
            final var cached = NetworkCache.read(origin.uri("/image.png"), null, "image/*", 1024, true);
            assertTrue(cached.cached());
            assertArrayEquals(new byte[] { 2 }, cached.bytes());
            assertEquals(2, hits.get());
        } finally {
            release.countDown();
            NetworkCache.release();
            executor.shutdownNow();
            originExecutor.shutdownNow();
        }
    }

    @Test
    void diskBudgetEvictsOldEntriesAndRejectsOversizedFile() throws Exception {
        final int previousBudget = WaterMediaConfig.media.cacheMaxSize;
        WaterMediaConfig.media.cacheMaxSize = 1;
        final byte[] body = new byte[768 * 1024];
        try (final LocalHttp origin = LocalHttp.start("/", exchange -> LocalHttp.respond(exchange, "video/mp4",
                exchange.getRequestURI().getPath().equals("/large") ? new byte[2 * 1024 * 1024] : body, 3600))) {
            NetworkCache.start(this.directory);
            final var first = NetworkCache.readFile(origin.uri("/first"), null, null, 3 * 1024 * 1024, true);
            final var second = NetworkCache.readFile(origin.uri("/second"), null, null, 3 * 1024 * 1024, true);
            assertNotNull(first);
            assertNotNull(second);
            assertFalse(Files.exists(first.path()));
            assertTrue(Files.exists(second.path()));
            assertNull(NetworkCache.readFile(origin.uri("/large"), null, null, 3 * 1024 * 1024, true));
            try (final var files = Files.list(this.directory)) {
                final long bytes = files.filter(path -> path.getFileName().toString().endsWith(".tmp"))
                        .mapToLong(path -> {
                            try { return Files.size(path); }
                            catch (final IOException failure) { throw new IllegalStateException(failure); }
                        }).sum();
                assertTrue(bytes <= 1024 * 1024);
            }
        } finally {
            NetworkCache.release();
            WaterMediaConfig.media.cacheMaxSize = previousBudget;
        }
    }

    @Test
    void reopenedStoreLoadsIndexAndDropsCorruptDerivedFiles() throws Exception {
        final AtomicInteger hits = new AtomicInteger();
        try (final LocalHttp origin = LocalHttp.start("/image", exchange -> {
            hits.incrementAndGet();
            LocalHttp.respond(exchange, "image/png", new byte[] { 7 }, 3600);
        })) {
            NetworkCache.start(this.directory);
            NetworkCache.read(origin.uri("/image"), null, null, 100, true);
            NetworkCache.release();
            NetworkCache.start(this.directory);
            assertTrue(NetworkCache.read(origin.uri("/image"), null, null, 100, true).cached());
            assertEquals(1, hits.get());
            NetworkCache.release();
            Files.write(this.directory.resolve("index.dat"), new byte[] { 1, 2, 3 });
            NetworkCache.start(this.directory);
            assertFalse(NetworkCache.read(origin.uri("/image"), null, null, 100, true).cached());
            assertEquals(2, hits.get());
        } finally {
            NetworkCache.release();
        }
    }
}
