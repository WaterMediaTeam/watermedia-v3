import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.api.media.players.util.NetworkCache;
import org.watermedia.api.util.RequestHeaders;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

public class NetworkCacheTest {
    @TempDir
    Path tempDir;
    private volatile int hits;

    @Test
    public void cachesHttpBytesAndUsesIndexOnSecondRead() throws Exception {
        final byte[] body = new byte[] { 1, 2, 3, 4, 5 };
        this.hits = 0;
        final HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/image.png", exchange -> {
            this.hits++;
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.getResponseHeaders().set("Expires", DateTimeFormatter.RFC_1123_DATE_TIME.format(
                    Instant.now().plusSeconds(3600).atZone(ZoneOffset.UTC)));
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        server.start();
        try {
            final Path cache = this.tempDir.resolve("cache");
            NetworkCache.start(cache);
            final URI uri = URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/image.png");

            final NetworkCache.CachedBytes first = NetworkCache.read(uri, null, "image/*,*/*", 1024);
            final NetworkCache.CachedBytes second = NetworkCache.read(uri, null, "image/*,*/*", 1024);

            assertFalse(first.cached());
            assertTrue(second.cached());
            assertArrayEquals(body, first.bytes());
            assertArrayEquals(body, second.bytes());
            assertEquals("image/png", second.contentType());
            assertEquals(1, this.hits);
            assertTrue(Files.isRegularFile(cache.resolve("index.dat")));
            assertTrue(hasCachedBody(cache));
        } finally {
            NetworkCache.release();
            server.stop(0);
        }
    }

    @Test
    public void cachesHttpResponseAsReusableFile() throws Exception {
        final byte[] body = new byte[] { 10, 20, 30, 40 };
        this.hits = 0;
        final HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/clip.mp4", exchange -> {
            this.hits++;
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.getResponseHeaders().set("Cache-Control", "max-age=3600");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        server.start();
        try {
            final Path cache = this.tempDir.resolve("cache-file");
            NetworkCache.start(cache);
            final URI uri = URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/clip.mp4");

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
            server.stop(0);
        }
    }

    @Test
    public void separatesCacheEntriesByRequestHeaders() throws Exception {
        final byte[] firstBody = new byte[] { 1, 1, 1 };
        final byte[] secondBody = new byte[] { 2, 2, 2 };
        this.hits = 0;
        final HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/variant.png", exchange -> {
            this.hits++;
            final boolean second = "second".equals(exchange.getRequestHeaders().getFirst("X-Variant"));
            final byte[] body = second ? secondBody : firstBody;
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.getResponseHeaders().set("Cache-Control", "max-age=3600");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        server.start();
        try {
            final Path cache = this.tempDir.resolve("cache-headers");
            NetworkCache.start(cache);
            final URI uri = URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/variant.png");
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
            server.stop(0);
        }
    }

    private static boolean hasCachedBody(final Path cache) throws IOException {
        try (final var stream = Files.list(cache)) {
            return stream.anyMatch(path -> {
                final String name = path.getFileName().toString();
                return name.startsWith("wm_") && name.endsWith(".tmp") && !name.equals("index.dat");
            });
        }
    }
}
