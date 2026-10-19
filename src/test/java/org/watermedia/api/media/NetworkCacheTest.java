package org.watermedia.api.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.test.support.LocalHttp;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link NetworkCache} round trips against a loopback HTTP origin ({@link LocalHttp}): each
 * scenario opens its own store, exercises a single cache surface and asserts the index and payload
 * state on disk after the second access.
 */
@DisplayName("NetworkCache round-trip")
public class NetworkCacheTest {

    // ACCEPT HEADERS AND READ LIMIT SHARED ACROSS THE CACHE SCENARIOS
    private static final String ACCEPT_IMAGE = "image/*,*/*";
    private static final String ACCEPT_VIDEO = "video/*,*/*";
    private static final int READ_LIMIT = 1024;

    @TempDir
    Path tempDir;

    // COUNT EVERY LOOPBACK HANDLER INVOCATION, INCLUDING CONCURRENT REQUESTS.
    private final AtomicInteger hits = new AtomicInteger();

    @Test
    @DisplayName("Caches HTTP bytes and uses the index on the second read")
    void testCachesHttpBytesAndUsesIndexOnSecondRead() throws Exception {
        final byte[] body = new byte[] { 1, 2, 3, 4, 5 };
        this.hits.set(0);
        try (final LocalHttp server = LocalHttp.start("/image.png", exchange -> {
            this.hits.incrementAndGet();
            exchange.getResponseHeaders().set("Expires", LocalHttp.expiresIn(3600));
            LocalHttp.respond(exchange, "image/png", body, 0);
        })) {
            final Path cache = this.tempDir.resolve("cache");
            NetworkCache.start(cache);
            final URI uri = server.uri("/image.png");

            final NetworkCache.CachedBytes first = NetworkCache.read(uri, null, ACCEPT_IMAGE, READ_LIMIT, true);
            final NetworkCache.CachedBytes second = NetworkCache.read(uri, null, ACCEPT_IMAGE, READ_LIMIT, true);

            assertFalse(first.cached());
            assertTrue(second.cached());
            assertArrayEquals(body, first.bytes());
            assertArrayEquals(body, second.bytes());
            assertEquals("image/png", second.contentType());
            assertEquals(1, this.hits.get());
            assertTrue(Files.isRegularFile(cache.resolve("index.dat")));

            // VERIFY ONE PAYLOAD FILE LANDED ALONGSIDE THE INDEX.
            try (final var stream = Files.list(cache)) {
                final boolean payloadPresent = stream.anyMatch(path -> {
                    final String name = path.getFileName().toString();
                    return name.startsWith("wm_") && name.endsWith(".tmp");
                });
                assertTrue(payloadPresent);
            }
        }
    }

    @Test
    @DisplayName("Caches an HTTP response as a reusable file")
    void testCachesHttpResponseAsReusableFile() throws Exception {
        final byte[] body = new byte[] { 10, 20, 30, 40 };
        this.hits.set(0);
        try (final LocalHttp server = LocalHttp.start("/clip.mp4", exchange -> {
            this.hits.incrementAndGet();
            LocalHttp.respond(exchange, "video/mp4", body, 3600);
        })) {
            final Path cache = this.tempDir.resolve("cache-file");
            NetworkCache.start(cache);
            final URI uri = server.uri("/clip.mp4");

            final NetworkCache.CachedFile first = NetworkCache.readFile(uri, null, ACCEPT_VIDEO, READ_LIMIT, true);
            final NetworkCache.CachedFile second = NetworkCache.readFile(uri, null, ACCEPT_VIDEO, READ_LIMIT, true);

            assertNotNull(first);
            assertNotNull(second);
            assertFalse(first.cached());
            assertTrue(second.cached());
            assertEquals(first.path(), second.path());
            assertEquals("video/mp4", second.contentType());
            assertArrayEquals(body, Files.readAllBytes(second.path()));
            assertEquals(1, this.hits.get());
        }
    }

    @Test
    @DisplayName("An unbounded media response (chunked, no Content-Length) is never cached as a file")
    void testUnboundedResponseIsNotCachedAsFile() throws Exception {
        final byte[] body = new byte[] { 1, 2, 3 };
        try (final LocalHttp server = LocalHttp.start("/radio", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
            exchange.getResponseHeaders().set("icy-name", "endless");
            exchange.sendResponseHeaders(200, 0); // CHUNKED: THE LENGTH IS UNKNOWN, LIKE A LIVE RADIO
            exchange.getResponseBody().write(body);
            exchange.close();
        })) {
            final Path cache = this.tempDir.resolve("cache-unbounded");
            NetworkCache.start(cache);
            final URI uri = server.uri("/radio");
            assertThrows(IOException.class, () -> NetworkCache.readFile(uri, null, ACCEPT_VIDEO, READ_LIMIT, true),
                    "the file cache refuses to download an endless source");
            // THE BYTE READER (IMAGES) STILL ACCEPTS CHUNKED RESPONSES
            assertArrayEquals(body, NetworkCache.read(uri, null, ACCEPT_IMAGE, READ_LIMIT, true).bytes());
        }
    }

    @Test
    @DisplayName("An abandoned uncached read stops and leaves the origin instead of reading on to its limit")
    void testAbandonedReadStops() throws Exception {
        final CountDownLatch streaming = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        try (final LocalHttp server = LocalHttp.start("/slow.png", exchange -> {
            // ANNOUNCES A LARGE BODY, THEN TRICKLES ONE BYTE AT A TIME UNTIL THE CLIENT GOES AWAY
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, 1_000_000L);
            streaming.countDown();
            try (final OutputStream out = exchange.getResponseBody()) {
                while (true) {
                    out.write(0);
                    out.flush();
                    Thread.sleep(20L);
                }
            } catch (final IOException | InterruptedException gone) {
                disconnected.countDown();
            }
        })) {
            final URI uri = server.uri("/slow.png");
            final Thread caller = new Thread(() -> {
                try { NetworkCache.read(uri, null, ACCEPT_IMAGE, 2_000_000L, false); }
                catch (final IOException expected) { /* THE INTERRUPT ENDS THE WAIT */ }
            }, "NetworkCacheTest-Caller");
            caller.start();
            assertTrue(streaming.await(5, TimeUnit.SECONDS), "the origin started streaming");
            final long start = System.nanoTime();
            caller.interrupt();
            caller.join(5_000L);
            assertFalse(caller.isAlive(), "the interrupted caller leaves");
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000L, "giving up never waits on the transfer");
            assertTrue(disconnected.await(5, TimeUnit.SECONDS), "the transfer stopped instead of trickling on to its limit");
        }
    }

    @Test
    @DisplayName("A body shorter than its Content-Length fails instead of being cached")
    void testTruncatedBodyIsRefused() throws Exception {
        this.hits.set(0);
        try (final LocalHttp server = LocalHttp.start("/cut.png", exchange -> {
            this.hits.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.getResponseHeaders().set("Expires", LocalHttp.expiresIn(3600));
            exchange.sendResponseHeaders(200, 100L);
            // TEN OF THE HUNDRED ANNOUNCED BYTES: THE FIXED-LENGTH STREAM REFUSES THE SHORT BODY AND DROPS THE CONNECTION
            exchange.getResponseBody().write(new byte[10]);
            exchange.getResponseBody().flush();
            exchange.close();
        })) {
            final Path cache = this.tempDir.resolve("cache-truncated");
            NetworkCache.start(cache);
            final URI uri = server.uri("/cut.png");
            assertThrows(IOException.class, () -> NetworkCache.read(uri, null, ACCEPT_IMAGE, READ_LIMIT, true), "a short body is a failure");
            try (final var stream = Files.list(cache)) {
                assertFalse(stream.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")), "nothing was cached");
            }
        }
    }

    @Test
    @DisplayName("Derives stable on-disk cache keys")
    void testDerivesStableOnDiskCacheKeys() throws Exception {
        final byte[] body = new byte[] { 7, 7, 7 };
        try (final LocalHttp server = LocalHttp.start("/pinned.png", exchange ->
                LocalHttp.respond(exchange, "image/png", body, 3600))) {
            final Path cache = this.tempDir.resolve("cache-key");
            NetworkCache.start(cache);
            final URI uri = server.uri("/pinned.png");
            final HttpHeaders headers = new HttpHeaders().set("X-Variant", "first");
            NetworkCache.read(uri, headers, ACCEPT_IMAGE, READ_LIMIT, true);

            // RE-DERIVE THE KEY FROM ITS CONTRACT: sha256(uri + '\n' + LOWERCASED "name:value"
            // LINES + "accept:" TAIL) HEX-ENCODED INTO THE PAYLOAD FILENAME. IF THE DERIVATION
            // EVER DRIFTS, EVERY EXISTING ON-DISK CACHE IS ORPHANED — THIS TEST PINS IT.
            final String key = uri.toASCIIString() + '\n' + "x-variant:first\n" + "accept:" + ACCEPT_IMAGE;
            final byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(hash.length * 2);
            for (final byte b: hash) {
                hex.append(Character.forDigit((b >>> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            assertTrue(Files.isRegularFile(cache.resolve("wm_" + hex + ".tmp")));
        }
    }

    @Test
    @DisplayName("Separates cache entries by request headers")
    void testSeparatesCacheEntriesByRequestHeaders() throws Exception {
        final byte[] firstBody = new byte[] { 1, 1, 1 };
        final byte[] secondBody = new byte[] { 2, 2, 2 };
        this.hits.set(0);
        try (final LocalHttp server = LocalHttp.start("/variant.png", exchange -> {
            this.hits.incrementAndGet();
            final boolean second = "second".equals(exchange.getRequestHeaders().getFirst("X-Variant"));
            final byte[] body = second ? secondBody : firstBody;
            LocalHttp.respond(exchange, "image/png", body, 3600);
        })) {
            final Path cache = this.tempDir.resolve("cache-headers");
            NetworkCache.start(cache);
            final URI uri = server.uri("/variant.png");
            final HttpHeaders firstHeaders = new HttpHeaders().set("X-Variant", "first");
            final HttpHeaders secondHeaders = new HttpHeaders().set("X-Variant", "second");

            final NetworkCache.CachedBytes first = NetworkCache.read(uri, firstHeaders, ACCEPT_IMAGE, READ_LIMIT, true);
            final NetworkCache.CachedBytes second = NetworkCache.read(uri, secondHeaders, ACCEPT_IMAGE, READ_LIMIT, true);
            final NetworkCache.CachedBytes firstAgain = NetworkCache.read(uri, firstHeaders, ACCEPT_IMAGE, READ_LIMIT, true);

            assertArrayEquals(firstBody, first.bytes());
            assertArrayEquals(secondBody, second.bytes());
            assertArrayEquals(firstBody, firstAgain.bytes());
            assertFalse(first.cached());
            assertFalse(second.cached());
            assertTrue(firstAgain.cached());
            assertEquals(2, this.hits.get());
        }
    }
}
