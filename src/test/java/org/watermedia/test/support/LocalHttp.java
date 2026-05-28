package org.watermedia.test.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Loopback HTTP server scaffolding for tests. One instance backs a single
 * handler — callers spin up a server per test and {@code close()} it via
 * try-with-resources.
 *
 * <p>Helps express NetworkCache, NetRequest, and platform tests that need a
 * deterministic, ephemeral origin without hitting the real internet.
 */
public final class LocalHttp implements AutoCloseable {
    private final HttpServer server;

    private LocalHttp(final HttpServer server) {
        this.server = server;
    }

    /** Boots an HTTP server on a random loopback port with the given handler. */
    public static LocalHttp start(final String path, final HttpHandler handler) {
        try {
            final HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext(path, handler);
            server.start();
            return new LocalHttp(server);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to start local HTTP server", e);
        }
    }

    /** Absolute URI for a path served by this server. */
    public URI uri(final String path) {
        final InetSocketAddress addr = this.server.getAddress();
        return URI.create("http://" + addr.getHostString() + ":" + addr.getPort() + path);
    }

    /** Writes a standard 200 OK response with the given body and headers. */
    public static void respond(final HttpExchange exchange, final String contentType, final byte[] body, final long cacheMaxAgeSec) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (cacheMaxAgeSec > 0) {
            exchange.getResponseHeaders().set("Cache-Control", "max-age=" + cacheMaxAgeSec);
        }
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    /** RFC 1123-formatted timestamp {@code n} seconds in the future. */
    public static String expiresIn(final long seconds) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().plusSeconds(seconds).atZone(ZoneOffset.UTC));
    }

    @Override
    public void close() {
        this.server.stop(0);
    }
}
