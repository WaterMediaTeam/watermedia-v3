package org.watermedia.test.network;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.network.NetworkAPI;
import org.watermedia.api.network.NetworkServer;
import org.watermedia.tools.IOTool;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spins {@link NetworkServer} on an ephemeral loopback port and drives it over real HTTP.
 * Covers the upload auth/size/traversal guards, ID download (GET/HEAD) and the malformed
 * Range path that previously threw an uncaught {@code NumberFormatException}.
 */
@DisplayName("NetworkServer")
public class NetworkServerTest {

    private static final String TOKEN = WaterMediaConfig.network.token;
    private static int port;
    private static Path cwd;

    @BeforeAll
    static void boot() throws Exception {
        cwd = Files.createTempDirectory("wm-netsrv");
        port = freePort();
        NetworkServer.start(port, instanceWithCwd(cwd));
    }

    @AfterAll
    static void shutdown() {
        NetworkServer.stop();
        IOTool.delete(cwd.toFile());
    }

    @Test
    @DisplayName("valid upload returns a short ID; GET and HEAD serve it back")
    void uploadThenDownload() throws IOException {
        final HttpURLConnection up = postUpload(TOKEN, "clip.bin", "payload".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, up.getResponseCode());
        final String id = new String(up.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(id.matches("[A-Za-z0-9]{8}"), "Unexpected ID: " + id);

        final HttpURLConnection get = open("/" + id, "GET");
        assertEquals(200, get.getResponseCode());
        assertEquals("payload", new String(get.getInputStream().readAllBytes(), StandardCharsets.UTF_8));

        final HttpURLConnection head = open("/" + id, "HEAD");
        assertEquals(200, head.getResponseCode());
        assertEquals("7", head.getHeaderField("Content-Length"));
    }

    @Test
    @DisplayName("upload with a wrong token is rejected with 401")
    void badToken() throws IOException {
        assertEquals(401, postUpload("wrong-token", "a.bin", "x".getBytes(StandardCharsets.UTF_8)).getResponseCode());
    }

    @Test
    @DisplayName("upload with a traversal filename is rejected with 400")
    void traversalFilename() throws IOException {
        assertEquals(400, postUpload(TOKEN, "../evil", "x".getBytes(StandardCharsets.UTF_8)).getResponseCode());
    }

    @Test
    @DisplayName("a malformed Range answers 416 instead of crashing the exchange")
    void malformedRange() throws IOException {
        final HttpURLConnection up = postUpload(TOKEN, "range.bin", "payload".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, up.getResponseCode());
        final String id = new String(up.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        final HttpURLConnection ranged = open("/" + id, "GET");
        ranged.setRequestProperty("Range", "bytes=abc-");
        assertEquals(416, ranged.getResponseCode());
    }

    @Test
    @DisplayName("GET / reports server info and an unknown ID is 404")
    void infoAndMissing() throws IOException {
        final HttpURLConnection root = open("/", "GET");
        assertEquals(200, root.getResponseCode());
        assertTrue(new String(root.getInputStream().readAllBytes(), StandardCharsets.UTF_8).contains("WaterMedia"));

        assertEquals(404, open("/zzzzzzzz", "GET").getResponseCode());
    }

    // ---- HELPERS ------------------------------------------------------------

    private static HttpURLConnection open(final String path, final String method) throws IOException {
        final HttpURLConnection c = (HttpURLConnection) URI.create("http://localhost:" + port + path).toURL().openConnection();
        c.setRequestMethod(method);
        return c;
    }

    private static HttpURLConnection postUpload(final String token, final String filename, final byte[] body) throws IOException {
        final HttpURLConnection c = open("/upload", "POST");
        c.setDoOutput(true);
        if (token != null) c.setRequestProperty(NetworkAPI.X_WATERMEDIA_TOKEN, token);
        if (filename != null) c.setRequestProperty(NetworkAPI.X_WATERMEDIA_FILENAME, filename);
        c.setFixedLengthStreamingMode(body.length);
        try (final OutputStream os = c.getOutputStream()) {
            os.write(body);
        }
        return c;
    }

    private static int freePort() throws IOException {
        try (final ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    // BUILDS A STANDALONE WaterMedia CARRYING OUR TEMP cwd, WITHOUT BOOTING THE LIBRARY. THE STATIC
    // SINGLETON IS SAVED/RESTORED SO A REAL BOOT IN THE SAME JVM IS NEVER DISTURBED BY THE CTOR GUARD.
    private static WaterMedia instanceWithCwd(final Path dir) throws Exception {
        final Field field = WaterMedia.class.getDeclaredField("instance");
        field.setAccessible(true);
        final Object saved = field.get(null);
        try {
            field.set(null, null);
            final Constructor<WaterMedia> ctor = WaterMedia.class.getDeclaredConstructor(String.class, Path.class, Path.class, boolean.class);
            ctor.setAccessible(true);
            return ctor.newInstance("WMTEST-NET", dir, dir, false);
        } finally {
            field.set(null, saved);
        }
    }
}
