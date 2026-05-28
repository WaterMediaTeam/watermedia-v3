package org.watermedia.test.media.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.api.media.players.util.NetworkCache;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.test.support.LocalHttp;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Network-tier round-trip tests for {@link NetworkCache}. Each scenario boots a
 * loopback HTTP origin via {@link LocalHttp}, exercises a single cache surface,
 * and asserts the index/payload state on disk after the second access.
 */
public class NetworkCacheTest {

    @TempDir
    Path tempDir;

    // INCREMENTED BY THE LOOPBACK HANDLER ON EVERY HIT — VOLATILE BECAUSE THE
    // HTTPSERVER WORKER THREAD WRITES AND THE TEST THREAD READS.
    private volatile int hits;

    @Test
    public void cachesHttpBytesAndUsesIndexOnSecondRead() throws Exception {
        final byte[] body = new byte[] { 1, 2, 3, 4, 5 };
        this.hits = 0;
        try (final LocalHttp server = LocalHttp.start("/image.png", exchange -> {
            this.hits++;
            exchange.getResponseHeaders().set("Expires", LocalHttp.expiresIn(3600));
            LocalHttp.respond(exchange, "image/png", body, 0);
        })) {
            final Path cache = this.tempDir.resolve("cache");
            NetworkCache.start(cache);
            try {
                final URI uri = server.uri("/image.png");

                final NetworkCache.CachedBytes first = NetworkCache.read(uri, null, "image/*,*/*", 1024);
                final NetworkCache.CachedBytes second = NetworkCache.read(uri, null, "image/*,*/*", 1024);

                assertFalse(first.cached());
                assertTrue(second.cached());
                assertArrayEquals(body, first.bytes());
                assertArrayEquals(body, second.bytes());
                assertEquals("image/png", second.contentType());
                assertEquals(1, this.hits);
                assertTrue(Files.isRegularFile(cache.resolve("index.dat")));

                // VERIFY ONE PAYLOAD FILE LANDED ALONGSIDE THE INDEX.
                try (final var stream = Files.list(cache)) {
                    final boolean payloadPresent = stream.anyMatch(path -> {
                        final String name = path.getFileName().toString();
                        return name.startsWith("wm_") && name.endsWith(".tmp");
                    });
                    assertTrue(payloadPresent);
                }
            } finally {
                NetworkCache.release();
            }
        }
    }

    @Test
    public void cachesHttpResponseAsReusableFile() throws Exception {
        final byte[] body = new byte[] { 10, 20, 30, 40 };
        this.hits = 0;
        try (final LocalHttp server = LocalHttp.start("/clip.mp4", exchange -> {
            this.hits++;
            LocalHttp.respond(exchange, "video/mp4", body, 3600);
        })) {
            final Path cache = this.tempDir.resolve("cache-file");
            NetworkCache.start(cache);
            try {
                final URI uri = server.uri("/clip.mp4");

                final NetworkCache.CachedFile first = NetworkCache.readFile(uri, null, "video/*,*/*", 1024, true);
                final NetworkCache.CachedFile second = NetworkCache.readFile(uri, null, "video/*,*/*", 1024, true);

                assertNotNull(first);
                assertNotNull(second);
                assertFalse(first.cached());
                assertTrue(second.cached());
                assertEquals(first.path(), second.path());
                assertEquals("video/mp4", second.contentType());
                assertArrayEquals(body, Files.readAllBytes(second.path()));
                assertEquals(1, this.hits);
            } finally {
                NetworkCache.release();
            }
        }
    }

    @Test
    public void separatesCacheEntriesByRequestHeaders() throws Exception {
        final byte[] firstBody = new byte[] { 1, 1, 1 };
        final byte[] secondBody = new byte[] { 2, 2, 2 };
        this.hits = 0;
        try (final LocalHttp server = LocalHttp.start("/variant.png", exchange -> {
            this.hits++;
            final boolean second = "second".equals(exchange.getRequestHeaders().getFirst("X-Variant"));
            final byte[] body = second ? secondBody : firstBody;
            LocalHttp.respond(exchange, "image/png", body, 3600);
        })) {
            final Path cache = this.tempDir.resolve("cache-headers");
            NetworkCache.start(cache);
            try {
                final URI uri = server.uri("/variant.png");
                final RequestHeaders firstHeaders = new RequestHeaders().set("X-Variant", "first");
                final RequestHeaders secondHeaders = new RequestHeaders().set("X-Variant", "second");

                final NetworkCache.CachedBytes first = NetworkCache.read(uri, firstHeaders, "image/*,*/*", 1024);
                final NetworkCache.CachedBytes second = NetworkCache.read(uri, secondHeaders, "image/*,*/*", 1024);
                final NetworkCache.CachedBytes firstAgain = NetworkCache.read(uri, firstHeaders, "image/*,*/*", 1024);

                assertArrayEquals(firstBody, first.bytes());
                assertArrayEquals(secondBody, second.bytes());
                assertArrayEquals(firstBody, firstAgain.bytes());
                assertFalse(first.cached());
                assertFalse(second.cached());
                assertTrue(firstAgain.cached());
                assertEquals(2, this.hits);
            } finally {
                NetworkCache.release();
            }
        }
    }
}
