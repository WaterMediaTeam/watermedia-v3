import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.api.media.players.util.NetworkCache;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class NetworkCacheTest {
    @TempDir
    Path tempDir;

    @Test
    public void cachesHttpBytesAndUsesIndexOnSecondRead() throws Exception {
        final byte[] body = new byte[] { 1, 2, 3, 4, 5 };
        final AtomicInteger hits = new AtomicInteger();
        final HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/image.png", exchange -> {
            hits.incrementAndGet();
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
            assertEquals(1, hits.get());
            assertTrue(Files.isRegularFile(cache.resolve("wm_index.dat")));
            assertTrue(hasCachedBody(cache));
        } finally {
            NetworkCache.release();
            server.stop(0);
        }
    }

    @Test
    public void cachesHttpResponseAsReusableFile() throws Exception {
        final byte[] body = new byte[] { 10, 20, 30, 40 };
        final AtomicInteger hits = new AtomicInteger();
        final HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/clip.mp4", exchange -> {
            hits.incrementAndGet();
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
            assertArrayEquals(body, Files.readAllBytes(second.path()));
            assertEquals(1, hits.get());
        } finally {
            NetworkCache.release();
            server.stop(0);
        }
    }

    private static boolean hasCachedBody(final Path cache) throws IOException {
        try (final var stream = Files.list(cache)) {
            return stream.anyMatch(path -> {
                final String name = path.getFileName().toString();
                return name.startsWith("wm_") && name.endsWith(".tmp") && !name.equals("wm_index.dat");
            });
        }
    }
}
