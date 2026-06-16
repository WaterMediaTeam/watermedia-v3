package org.watermedia.test.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.util.NetRequest;
import org.watermedia.test.support.LocalHttp;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link NetRequest} backed by a loopback HTTP server.
 * Covers status reporting, body decoding, JSON binding, manual redirect
 * following and the {@code accept()} builder shortcut.
 */
@DisplayName("NetRequest")
public class NetRequestTest {

    @Test
    @DisplayName("plain GET returns 200 and the configured body")
    void testSimpleGet() throws IOException {
        try (final LocalHttp server = LocalHttp.start("/text",
                ex -> LocalHttp.respond(ex, "text/plain", "hello".getBytes(), 0))) {

            try (final NetRequest req = NetRequest.create(server.uri("/text")).send()) {
                assertEquals(200, req.statusCode());
                assertEquals("hello", req.readAllAsString());
                assertNotNull(req.contentType());
                assertTrue(req.contentType().contains("text/plain"));
            }
        }
    }

    @Test
    @DisplayName("json(Map.class) parses an application/json body")
    void testJsonBinding() throws IOException {
        try (final LocalHttp server = LocalHttp.start("/data",
                ex -> LocalHttp.respond(ex, "application/json", "{\"a\":1}".getBytes(), 0))) {

            try (final NetRequest req = NetRequest.create(server.uri("/data"))
                    .accept(NetRequest.ACCEPT_JSON)
                    .send()) {
                assertEquals(200, req.statusCode());
                final Map<?, ?> map = req.json(Map.class);
                assertNotNull(map);
                // GSON DESERIALIZES UNTYPED NUMBERS AS Double
                assertEquals(1.0, map.get("a"));
            }
        }
    }

    @Test
    @DisplayName("302 redirect is followed to the final URI")
    void testFollowsRedirect() throws IOException {
        // TARGET SERVER FIRST SO ITS ABSOLUTE URL CAN BE EMBEDDED IN THE REDIRECTOR'S Location HEADER
        try (final LocalHttp target = LocalHttp.start("/final",
                ex -> LocalHttp.respond(ex, "text/plain", "done".getBytes(), 0))) {

            final String absoluteLocation = target.uri("/final").toString();
            try (final LocalHttp redirector = LocalHttp.start("/start", ex -> {
                ex.getResponseHeaders().set("Location", absoluteLocation);
                ex.sendResponseHeaders(302, -1);
                ex.close();
            })) {
                try (final NetRequest req = NetRequest.create(redirector.uri("/start")).send()) {
                    assertEquals(200, req.statusCode());
                    assertEquals("done", req.readAllAsString());
                    assertTrue(req.uri().getPath().endsWith("/final"));
                }
            }
        }
    }

    @Test
    @DisplayName("accept() builder sets the outbound Accept header")
    void testAcceptHeader() throws IOException {
        try (final LocalHttp server = LocalHttp.start("/echo",
                ex -> LocalHttp.respond(ex, "text/plain", "ok".getBytes(), 0))) {

            try (final NetRequest req = NetRequest.create(server.uri("/echo"))
                    .accept("image/png")
                    .send()) {
                assertEquals("image/png", req.requestHeaders().get("Accept"));
            }
        }
    }
}
