package org.watermedia.api.platform.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.Platform;
import org.watermedia.api.platform.MatureContentException;
import org.watermedia.api.platform.PlatformApiTest;
import org.watermedia.api.platform.WaterPlatformTest;
import org.watermedia.api.platform.internal.WaterPlatform;

import java.net.URI;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Offline coverage for the URI-claim half of {@link Platform#resolve(URI)}.
 * Since validation now lives inside {@code data}, a foreign URI must be
 * rejected with a {@code null} return <i>before</i> any network access — these
 * cases exercise host matching, suffix rules, path prefixes and pattern
 * matchers without ever touching the network.
 * <p>
 * The other two outcomes of the contract are covered elsewhere offline:
 * the success (instance) and parse-failure (exception) states by
 * {@link WaterPlatformTest}, the registry-level
 * wiring by {@link PlatformApiTest}, and the
 * mature-content gate by {@link #testMatureContentDisabledThrowsBeforeFetch()}.
 */
@DisplayName("Platform URI claim (offline)")
public class PlatformClaimTest {

    static Stream<Arguments> foreignUris() {
        return Stream.of(
                // WaterPlatform — water:// scheme is the only signal
                arguments(WaterPlatform.class, "https://foo/"),
                arguments(WaterPlatform.class, "file:///tmp/x.png"),

                // Bluesky — needs /profile/<handle>/post/<id> or at:// post pattern
                arguments(BlueskyPlatform.class, "https://twitter.com/i/status/1"),
                arguments(BlueskyPlatform.class, "https://bsky.app/about"),

                // BiliBili — host whitelist
                arguments(BiliBiliPlatform.class, "https://youtube.com/watch?v=x"),

                // DTube — host == d.tube or www.d.tube
                arguments(DTubePlatform.class, "https://example.com/"),

                // Drive — host == drive.google.com AND path starts with /file/d/
                arguments(DrivePlatform.class, "https://drive.google.com/drive/folders/X"),
                arguments(DrivePlatform.class, "https://docs.google.com/file/d/ID/view"),

                // Dropbox — host is dropbox.com or www.dropbox.com AND the query carries a whole dl=0 parameter
                arguments(DropboxPlatform.class, "https://www.dropbox.com/s/abc/x.mp4?dl=1"),
                arguments(DropboxPlatform.class, "https://www.dropbox.com/s/abc/x.mp4"),
                arguments(DropboxPlatform.class, "https://www.dropbox.com/s/abc/x.mp4?dl=01"),
                arguments(DropboxPlatform.class, "https://www.dropbox.com/s/abc/x.mp4?xdl=0"),
                arguments(DropboxPlatform.class, "https://example.com/s/abc?dl=0"),

                // Imgur — host equalsIgnoreCase imgur.com
                arguments(ImgurPlatform.class, "https://i.imgur.com/abc.png"),
                arguments(ImgurPlatform.class, "https://example.com/"),

                // Kick — host is kick.com or www.kick.com
                arguments(KickPlatform.class, "https://notkick.com/channelname"),
                arguments(KickPlatform.class, "https://twitch.tv/x"),

                // Lightshot — host equalsIgnoreCase prnt.sc
                arguments(LightshotPlatform.class, "https://light.shot/abc"),

                // Medal — host medal.tv/www.medal.tv AND a clips/clip segment or contentId query
                arguments(MedalPlatform.class, "https://example.com/games/gta-v/clips/abc"),
                arguments(MedalPlatform.class, "https://medal.tv/users/xxlynds"),
                arguments(MedalPlatform.class, "https://medal.tv/games/gta-v"),

                // MediaFire — host is mediafire.com or www.mediafire.com AND path starts /file/
                arguments(MediaFirePlatform.class, "https://notmediafire.com/file/abc/x.zip"),
                arguments(MediaFirePlatform.class, "https://www.mediafire.com/folder/abc"),

                // Odysee — host odysee.com / www.odysee.com
                arguments(OdyseePlatform.class, "https://example.com/"),

                // PornHub — host is pornhub.com or *.pornhub.com AND a viewkey query param
                arguments(PornHubPlatform.class, "https://www.pornhub.com/embed/abc"),
                arguments(PornHubPlatform.class, "https://example.com/view_video.php?viewkey=abc"),
                // LOOKALIKE HOST: A DOT-UNAWARE SUFFIX MATCH WOULD WRONGLY CLAIM THIS AND THROW MatureContentException
                arguments(PornHubPlatform.class, "https://notpornhub.com/view_video.php?viewkey=abc"),

                // Sendvid — host sendvid.com / www.sendvid.com
                arguments(SendvidPlatform.class, "https://example.com/"),

                // Streamable — host is streamable.com or www.streamable.com AND the path carries an id
                arguments(StreamablePlatform.class, "https://streamable.co/abc123"),
                arguments(StreamablePlatform.class, "https://streamable.com/"),
                arguments(StreamablePlatform.class, "https://example.com/"),

                // TikTok — host whitelist
                arguments(TikTokPlatform.class, "https://example.com/"),

                // Twitch — host whitelist
                arguments(TwitchPlatform.class, "https://example.com/"),

                // Twitter — host whitelist AND path carries a numeric /status/<id> segment
                arguments(TwitterPlatform.class, "https://twitter.com/user"),
                arguments(TwitterPlatform.class, "https://twitter.com/user/status/abc"),
                arguments(TwitterPlatform.class, "https://example.com/user/status/1"),

                // VidLii — host vidlii.com / www.vidlii.com
                arguments(VidLiiPlatform.class, "https://example.com/"),

                // yt-dlp / YouTube — host-suffix match must use a dot boundary so lookalikes are foreign
                arguments(YtDlpPlatform.class, "https://example.com/"),
                arguments(YtDlpPlatform.class, "https://xinstagr.am/p/abc"),
                arguments(YouTubePlatform.class, "https://example.com/"),
                arguments(YouTubePlatform.class, "https://notyoutube.com/watch?v=abc")
        );
    }

    @ParameterizedTest(name = "{0} data({1}) == null")
    @MethodSource("foreignUris")
    void testForeignUriYieldsNull(final Class<? extends Platform> type, final String uriString) throws Exception {
        final Platform platform = type.getDeclaredConstructor().newInstance();
        final URI uri = URI.create(uriString);
        assertNull(platform.resolve(uri),
                () -> type.getSimpleName() + ".data(" + uriString + ") must return null for a foreign URI");
    }

    // MATURE GATE — PornHub IS MATURE-ONLY AND REJECTS BEFORE ANY NETWORK ACCESS
    // WHEN allowMatureContent IS OFF, SO THE EXCEPTION STATE IS VERIFIABLE OFFLINE.
    private boolean previousMature;

    @BeforeEach
    void rememberConfig() {
        this.previousMature = WaterMediaConfig.platforms.allowMatureContent;
    }

    @AfterEach
    void restoreConfig() {
        WaterMediaConfig.platforms.allowMatureContent = this.previousMature;
    }

    @Test
    @DisplayName("Mature-only platform throws before any fetch when mature content is disabled")
    void testMatureContentDisabledThrowsBeforeFetch() {
        WaterMediaConfig.platforms.allowMatureContent = false;
        final PornHubPlatform platform = new PornHubPlatform();
        assertThrows(MatureContentException.class,
                () -> platform.resolve(URI.create("https://www.pornhub.com/view_video.php?viewkey=abc")),
                "Mature-only platform must throw when mature content is disabled, before any fetch");
    }
}
