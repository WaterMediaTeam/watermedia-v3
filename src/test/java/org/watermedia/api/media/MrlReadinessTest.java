package org.watermedia.api.media;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.WaterMedia;
import org.watermedia.api.util.MediaType;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.LocalHttp;
import org.watermedia.test.support.MediaBootstrap;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the basic readiness contract of {@link MRL}: a successful load reports
 * {@code loaded()} without an error, a bogus, relative or empty input is born in
 * {@code ERROR} instead of failing late, a freshly loaded local MRL is not considered expired,
 * and the content-type gate rejects documents while letting oddly spelled media types through.
 */
@DisplayName("MRL readiness")
public class MrlReadinessTest {
    @BeforeAll
    static void clientBootstrap() { MediaBootstrap.client(); }

    private static final long TIMEOUT_MS = 2000L;

    @Test
    @DisplayName("Local image loads cleanly")
    void testLocalImageLoadsCleanly() {
        final MRL mrl = MediaAPI.mrl(Fixtures.fileUri(Fixtures.PNG_STATIC));
        assertTrue(mrl.await(TIMEOUT_MS));
        assertTrue(mrl.status().loaded());
        assertFalse(mrl.status().failed());
        assertTrue(mrl.sourceCount() > 0);
    }

    @Test
    @DisplayName("Nonexistent file marks an error after load")
    void testNonexistentFileMarksErrorAfterLoad() {
        final MRL mrl = MediaAPI.mrl(URI.create("file:///nonexistent/abc.png"));
        assertTrue(mrl.await(TIMEOUT_MS));
        assertFalse(mrl.status().loaded());
        assertTrue(mrl.status().failed());
    }

    @Test
    @DisplayName("Freshly loaded local MRL is not expired")
    void testFreshlyLoadedLocalMrlIsNotExpired() {
        final MRL mrl = MediaAPI.mrl(Fixtures.fileUri(Fixtures.PNG_STATIC));
        assertTrue(mrl.await(TIMEOUT_MS));
        assertNotSame(MRL.Status.EXPIRED, mrl.status());
    }

    @Test
    @DisplayName("Malformed URI string yields an ERROR MRL instead of throwing")
    void testMalformedUriStringYieldsErrorMrl() {
        // SPACES IN THE AUTHORITY MAKE URI.create THROW — mrl(String) MUST ABSORB IT AS Status.ERROR
        final MRL mrl = MediaAPI.mrl("http://exa mple.com/bad path");
        assertNotNull(mrl);
        assertSame(MRL.Status.ERROR, mrl.status());
        assertTrue(mrl.status().failed());
        assertNotNull(mrl.exception());
    }

    @Test
    @DisplayName("Relative and empty inputs are born in ERROR, never cached, and reload to themselves")
    void testRelativeInputsAreBornInError() {
        for (final String input: new String[] { "", "missing-file.png", "relative/path.mp4" }) {
            final MRL mrl = MediaAPI.mrl(input);
            assertSame(MRL.Status.ERROR, mrl.status(), input);
            assertNotNull(mrl.exception(), input);
            assertSame(mrl, mrl.reload(), "an MRL born in ERROR reloads to itself");
        }
        final MRL relative = MediaAPI.mrl(URI.create("abc.png"));
        assertSame(MRL.Status.ERROR, relative.status());
        assertNotSame(relative, MediaAPI.mrl(URI.create("abc.png")), "ERROR-born handles are never cached");
    }

    @Test
    @DisplayName("A file path relative to the session working directory loads")
    void testRelativeFilePathResolvesAgainstCwd() throws IOException {
        // UNDER build/ SO A SCANNER HOLDING THE FRESH FILE NEVER LEAVES JUNK IN THE PROJECT ROOT
        final Path file = WaterMedia.cwd().resolve("build/mrl-relative/test.png");
        Files.createDirectories(file.getParent());
        Files.copy(Fixtures.PNG_STATIC, file, StandardCopyOption.REPLACE_EXISTING);
        final MRL mrl = MediaAPI.mrl("build/mrl-relative/./test.png");
        assertTrue(mrl.await(TIMEOUT_MS));
        assertTrue(mrl.status().loaded());
        assertSame(mrl, MediaAPI.mrl("build/mrl-relative/test.png"), "the normalized path shares the cache entry");
    }

    @Test
    @DisplayName("The content-type gate ignores casing and parameters and rejects documents")
    void testContentTypeGate() throws Exception {
        try (final LocalHttp origin = LocalHttp.start("/", exchange -> {
            final String path = exchange.getRequestURI().getPath();
            final String type = path.endsWith("page") ? "text/html; charset=utf-8"
                    : path.endsWith("hls") ? "application/x-mpegURL" : "Video/MP4; codecs=\"avc1\"";
            LocalHttp.respond(exchange, type, new byte[] { 0, 0, 0, 0 }, 0L);
        })) {
            final MRL video = MediaAPI.mrl(origin.uri("/clip"));
            assertTrue(video.await(TIMEOUT_MS));
            assertTrue(video.status().loaded(), "a capitalized, parameterized video type passes");
            assertEquals(MediaType.VIDEO, video.source(0).type());

            final MRL hls = MediaAPI.mrl(origin.uri("/hls"));
            assertTrue(hls.await(TIMEOUT_MS));
            assertTrue(hls.status().loaded(), "the Accept-header spelling of HLS passes");

            final MRL page = MediaAPI.mrl(origin.uri("/page"));
            assertTrue(page.await(TIMEOUT_MS));
            assertSame(MRL.Status.ERROR, page.status(), "HTML documents are not media");
        }
    }
}
