package org.watermedia.api.platform.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.watermedia.api.platform.MatureContentException;
import org.watermedia.api.platform.PlatformException;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the offline half of {@link KickPlatform}: which page shapes it reads as a VOD or a clip, and which
 * shapes of its own host it refuses before any network access. Live, VOD and clip resolutions need the Kick
 * API and are covered by the manual platform check instead.
 */
@DisplayName("Kick platform (offline)")
public class KickPlatformTest {
    static final String VOD = "c4a80015-c8ac-47a9-a980-ffa277755103";
    static final String CLIP = "clip_01M6B6P65W5DRXR2PE3M7GSZC0";

    @Nested
    @DisplayName("VOD ids")
    class VodIds {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "https://kick.com/video/" + VOD,
                "https://kick.com/xqc/videos/" + VOD,
                "https://www.kick.com/xqc/videos/" + VOD + "/",
                "https://kick.com/Video/" + VOD + "?t=120",
                "https://kick.com/xqc/Videos/" + VOD + "#comments"
        })
        @DisplayName("Both the legacy /video/<uuid> and the current /<channel>/videos/<uuid> pages yield the uuid")
        void testBothVodShapesYieldTheUuid(final String page) {
            assertEquals(VOD, KickPlatform.vodId(URI.create(page)));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "https://kick.com/xqc",
                "https://kick.com/xqc/videos",
                "https://kick.com/xqc/video/" + VOD,
                "https://kick.com/video",
                "https://kick.com/videos/" + VOD,
                "https://kick.com/xqc/videos/" + VOD + "/extra",
                "https://kick.com/xqc/clips/" + CLIP,
                "https://kick.com/"
        })
        @DisplayName("A channel page, a VOD listing or any other shape yields no uuid")
        void testOtherShapesYieldNoUuid(final String page) {
            assertNull(KickPlatform.vodId(URI.create(page)));
        }
    }

    @Nested
    @DisplayName("Clip ids")
    class ClipIds {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "https://kick.com/xqc/clips/" + CLIP,
                "https://kick.com/xqc?clip=" + CLIP,
                "https://kick.com/xqc/videos/" + VOD + "?clip=" + CLIP,
                "https://www.kick.com/xqc/Clips/" + CLIP + "/"
        })
        @DisplayName("Both the /<channel>/clips/clip_... path and the ?clip=clip_... query yield the clip id")
        void testBothClipShapesYieldTheClipId(final String page) {
            assertEquals(CLIP, KickPlatform.clipId(URI.create(page)));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "https://kick.com/xqc/clips",
                "https://kick.com/xqc/clips/latest",
                "https://kick.com/xqc?clip=latest",
                "https://kick.com/xqc/videos/" + VOD
        })
        @DisplayName("A clip listing or an id without the clip_ prefix yields no clip id")
        void testOtherShapesYieldNoClipId(final String page) {
            assertNull(KickPlatform.clipId(URI.create(page)));
        }
    }

    @Nested
    @DisplayName("Claims")
    class Claims {

        @Test
        @DisplayName("A foreign host is disclaimed with null before any network access")
        void testForeignHostYieldsNull() throws PlatformException {
            final KickPlatform platform = new KickPlatform();
            assertNull(platform.resolve(URI.create("https://notkick.com/xqc/videos/" + VOD)));
            assertNull(platform.resolve(URI.create("https://clips.kick.com/clips/9c/" + CLIP + "/playlist.m3u8")));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "https://kick.com/",
                "https://kick.com/xqc/videos",
                "https://kick.com/xqc/clips",
                "https://kick.com/xqc/video/" + VOD,
                "https://kick.com/xqc/videos/" + VOD + "/extra",
                "https://kick.com/a/b/c/d"
        })
        @DisplayName("A listing or an unknown shape of kick.com is refused without network and is not a mature block")
        void testUnsupportedShapeThrows(final String page) {
            final KickPlatform platform = new KickPlatform();
            final URI uri = URI.create(page);
            final PlatformException thrown = assertThrows(PlatformException.class, () -> platform.resolve(uri));
            assertFalse(thrown instanceof MatureContentException, "An unsupported shape is not a mature block");
            assertTrue(thrown.getMessage().startsWith(KickPlatform.NAME + ": Unsupported URL: "), thrown.getMessage());
        }
    }
}
