package org.watermedia.test.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.util.NetRequest;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.test.support.LocalHttp;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Integration tests for {@link NetRequest} backed by a loopback HTTP server.
 * Covers status reporting, body decoding, JSON binding, manual redirect
 * following and the {@code accept()} builder shortcut.
 */
@DisplayName("NetRequest")
public class NetRequestTest {

    @Test
    void crossPortRedirectRemovesCredentialsOnWire() throws IOException {
        try (final LocalHttp target = LocalHttp.start("/final", ex -> {
            final StringBuilder received = new StringBuilder();
            for (final String name: List.of("Authorization", "X-WaterMedia-Token", "Cookie", "Cookie2")) {
                received.append(ex.getRequestHeaders().getFirst(name)).append('\n');
            }
            LocalHttp.respond(ex, "text/plain", received.toString().getBytes(StandardCharsets.UTF_8), 0);
        }); final LocalHttp redirector = LocalHttp.start("/start", ex -> {
            ex.getResponseHeaders().set("Location", target.uri("/final").toString());
            ex.sendResponseHeaders(302, -1);
            ex.close();
        }); final NetRequest req = NetRequest.create(redirector.uri("/start"))
                .header("Authorization", "Bearer test")
                .header("X-WaterMedia-Token", "test")
                .header("Cookie", "session=test")
                .header("Cookie2", "session=test")
                .send()) {
            assertEquals(200, req.statusCode());
            assertEquals("null\nnull\nnull\nnull\n", req.readAllAsString());
        }
    }

    @Test
    void sameOriginRedirectPreservesCredentials() throws IOException {
        try (final LocalHttp server = LocalHttp.start("/", ex -> {
            if ("/start".equals(ex.getRequestURI().getPath())) {
                ex.getResponseHeaders().set("Location", "/final");
                ex.sendResponseHeaders(302, -1);
                ex.close();
            } else {
                final String received = ex.getRequestHeaders().getFirst("Authorization") + "|"
                        + ex.getRequestHeaders().getFirst("Cookie");
                LocalHttp.respond(ex, "text/plain", received.getBytes(StandardCharsets.UTF_8), 0);
            }
        }); final NetRequest req = NetRequest.create(server.uri("/start"))
                .header("Authorization", "Bearer test").header("Cookie", "session=test").send()) {
            assertEquals("Bearer test|session=test", req.readAllAsString());
        }
    }

    @Test
    void originPolicyHandlesDowngradesDefaultPortsAndHostCase() throws Exception {
        final var materialize = NetRequest.Builder.class.getDeclaredMethod("materializeHeaders", URI.class);
        materialize.setAccessible(true);
        final NetRequest.Builder builder = NetRequest.create("https://EXAMPLE.com/start")
                .header("Authorization", "Bearer test").header("Proxy-Authorization", "proxy test")
                .header("Cookie", "session=test").header("X-WaterMedia-Token", "test");
        for (final String target: List.of("https://example.com:443/end", "https://EXAMPLE.com/end")) {
            final RequestHeaders headers = (RequestHeaders) materialize.invoke(builder, URI.create(target));
            assertEquals("Bearer test", headers.get("Authorization"));
            assertEquals("session=test", headers.get("Cookie"));
        }
        for (final String target: List.of("http://example.com/end", "http://example.com:443/end",
                "https://example.com:8443/end", "https://other.example/end")) {
            final RequestHeaders headers = (RequestHeaders) materialize.invoke(builder, URI.create(target));
            for (final String name: List.of("Authorization", "Proxy-Authorization", "Cookie", "X-WaterMedia-Token")) {
                assertNull(headers.get(name), name + " leaked to " + target);
            }
        }
    }

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
    @DisplayName("Bodies follow redirects to another origin only when crossOrigin is set")
    void crossOriginBodies() throws IOException {
        try (final LocalHttp target = LocalHttp.start("/final", ex -> {
            final String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            LocalHttp.respond(ex, "text/plain", (ex.getRequestMethod() + " " + body).getBytes(StandardCharsets.UTF_8), 0);
        }); final LocalHttp redirector = LocalHttp.start("/start", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.getResponseHeaders().set("Location", target.uri("/final").toString());
            ex.sendResponseHeaders(307, -1);
            ex.close();
        })) {
            final IOException failure = assertThrows(IOException.class,
                    () -> NetRequest.create(redirector.uri("/start")).method("POST").body("payload").send());
            assertTrue(failure.getMessage().contains("another origin"), failure.getMessage());
            try (final NetRequest req = NetRequest.create(redirector.uri("/start")).method("POST").body("payload", true).send()) {
                assertEquals("POST payload", req.readAllAsString());
            }
        }
    }

    @Test
    @DisplayName("A 303 switches only that send to GET, so a reused builder posts again")
    void seeOtherKeepsTheBuilder() throws IOException {
        final List<String> starts = new CopyOnWriteArrayList<>();
        try (final LocalHttp target = LocalHttp.start("/final", ex -> {
            final String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            LocalHttp.respond(ex, "text/plain", (ex.getRequestMethod() + " " + body).getBytes(StandardCharsets.UTF_8), 0);
        }); final LocalHttp redirector = LocalHttp.start("/start", ex -> {
            starts.add(ex.getRequestMethod() + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.getResponseHeaders().set("Location", target.uri("/final").toString());
            ex.sendResponseHeaders(303, -1);
            ex.close();
        })) {
            final NetRequest.Builder builder = NetRequest.create(redirector.uri("/start")).method("POST").body("payload");
            for (int i = 0; i < 2; i++) {
                try (final NetRequest req = builder.send()) {
                    assertEquals("GET ", req.readAllAsString());
                }
            }
            assertEquals(List.of("POST payload", "POST payload"), starts);
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
