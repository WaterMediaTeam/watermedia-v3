package org.watermedia.api.network;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.media.HeadlessGFXEngine;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.MediaPlayer;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;
import org.watermedia.tools.IOTool;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies {@link NetworkServer} over real HTTP on an ephemeral loopback port: the upload
 * auth/size/traversal guards, ID downloads (GET/HEAD), the Range handling, the root answer and the
 * playback of a stored video through FFmpeg's own HTTP client.
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
        NetworkServer.start(port, cwd);
    }

    @AfterAll
    static void shutdown() throws IOException {
        NetworkServer.stop();
        IOTool.deleteTree(cwd);
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
    @DisplayName("a malformed Range is ignored and the whole file answered")
    void malformedRange() throws IOException {
        final HttpURLConnection up = postUpload(TOKEN, "range.bin", "payload".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, up.getResponseCode());
        final String id = new String(up.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        final HttpURLConnection ranged = open("/" + id, "GET");
        ranged.setRequestProperty("Range", "bytes=abc-");
        assertEquals(200, ranged.getResponseCode());
        assertNull(ranged.getHeaderField("Content-Range"));
        assertEquals("payload", new String(ranged.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("ranges clip at EOF, unsatisfiable ones answer 416, malformed or multipart ones are ignored and HEAD honours them")
    void clipsRangesAtEofAndHandlesEmptyResources() throws IOException {
        final HttpURLConnection upload = postUpload(TOKEN, "bounds.bin", "payload".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, upload.getResponseCode());
        final String id = new String(upload.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        for (final String range: new String[] { "bytes=0-999", "bytes=-999", "bytes=0-", "bytes=0-999999999999999999999999999" }) {
            final HttpURLConnection request = open("/" + id, "GET");
            request.setRequestProperty("Range", range);
            assertEquals(206, request.getResponseCode(), range);
            assertEquals("bytes 0-6/7", request.getHeaderField("Content-Range"));
            assertEquals("payload", new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        }
        for (final String range: new String[] { "bytes=2-", "BYTES=2-6" }) {
            final HttpURLConnection request = open("/" + id, "GET");
            request.setRequestProperty("Range", range);
            assertEquals(206, request.getResponseCode(), range);
            assertEquals("bytes 2-6/7", request.getHeaderField("Content-Range"));
            assertEquals("yload", new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        }
        for (final String range: new String[] { "bytes=7-", "bytes=-0", "bytes=99999999999999999999-" }) {
            final HttpURLConnection request = open("/" + id, "GET");
            request.setRequestProperty("Range", range);
            assertEquals(416, request.getResponseCode(), range);
            assertEquals("bytes */7", request.getHeaderField("Content-Range"));
        }
        for (final String range: new String[] { "bytes=5-3", "bytes=-", "bytes=+1-2", "bytes=0-1,3-4" }) {
            final HttpURLConnection request = open("/" + id, "GET");
            request.setRequestProperty("Range", range);
            assertEquals(200, request.getResponseCode(), range);
            assertNull(request.getHeaderField("Content-Range"), range);
            assertEquals("payload", new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        }
        final HttpURLConnection suffix = open("/" + id, "GET");
        suffix.setRequestProperty("Range", "bytes=-3");
        assertEquals(206, suffix.getResponseCode());
        assertEquals("oad", new String(suffix.getInputStream().readAllBytes(), StandardCharsets.UTF_8));

        final HttpURLConnection head = open("/" + id, "HEAD");
        head.setRequestProperty("Range", "bytes=0-2");
        assertEquals(206, head.getResponseCode());
        assertEquals("bytes 0-2/7", head.getHeaderField("Content-Range"));
        assertEquals("3", head.getHeaderField("Content-Length"));

        final HttpURLConnection empty = postUpload(TOKEN, "empty.bin", new byte[0]);
        assertEquals(200, empty.getResponseCode());
        final String emptyId = new String(empty.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        final HttpURLConnection get = open("/" + emptyId, "GET");
        assertEquals(200, get.getResponseCode());
        assertEquals(0, get.getInputStream().readAllBytes().length);
        assertEquals("0", get.getHeaderField("Content-Length"));
        for (final String range: new String[] { "bytes=0-", "bytes=-1" }) {
            final HttpURLConnection request = open("/" + emptyId, "GET");
            request.setRequestProperty("Range", range);
            assertEquals(416, request.getResponseCode(), range);
        }
    }

    @Test
    @DisplayName("GET / reports the server name without its version and an unknown ID is 404")
    void infoAndMissing() throws IOException {
        final HttpURLConnection root = open("/", "GET");
        assertEquals(200, root.getResponseCode());
        final String info = new String(root.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(info.contains("WaterMedia"));
        assertFalse(info.contains(WaterMedia.VERSION), "An unauthenticated answer must not reveal the version");

        assertEquals(404, open("/zzzzzzzz", "GET").getResponseCode());
    }

    @Test
    @DisplayName("a stored MP4 whose index trails its media plays through FFmpeg's own HTTP client")
    void storedVideoPlaysThroughFFmpeg() throws Exception {
        assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable");
        final boolean cache = WaterMediaConfig.media.ffmpeg.cache;
        // FILES BEYOND cacheMaxResponse SKIP THE PLAYER'S DOWNLOAD CACHE; TURNING IT OFF TAKES THAT PATH WITH THE 9 MB FIXTURE
        WaterMediaConfig.media.ffmpeg.cache = false;
        final Path stored = cwd.resolve("watermedia").resolve("files").resolve("Trailing1");
        Files.createDirectories(stored);
        Files.copy(Fixtures.MP4_H264, stored.resolve("clip.mp4"));
        final HeadlessGFXEngine gfx = MediaAPI.headlessEngine();
        MediaPlayer player = null;
        try {
            final MRL mrl = MediaAPI.mrl(URI.create("http://127.0.0.1:" + port + "/Trailing1"));
            assertTrue(mrl.await(3_000L));
            player = MediaAPI.player(mrl, () -> gfx, () -> null);
            assertNotNull(player);
            assertTrue(player.start());
            // THE MOV DEMUXER SEEKS TO THE TRAILING moov AND BACK BEFORE THE FIRST FRAME, EACH SEEK ON A NEW REQUEST
            final MediaPlayer active = player;
            assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0 || active.error(), 15_000L));
            assertNull(player.exception(), "the stored file must open");
            assertTrue(gfx.uploadCount() > 0, "frames must reach the engine");
        } finally {
            if (player != null) player.release();
            WaterMediaConfig.media.ffmpeg.cache = cache;
        }
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
}
