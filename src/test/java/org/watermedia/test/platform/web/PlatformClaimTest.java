package org.watermedia.test.platform.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.IPlatform;
import org.watermedia.api.platform.MatureContentException;
import org.watermedia.api.platform.internal.WaterPlatform;
import org.watermedia.api.platform.web.BiliBiliPlatform;
import org.watermedia.api.platform.web.BlueskyPlatform;
import org.watermedia.api.platform.web.DTubePlatform;
import org.watermedia.api.platform.web.DrivePlatform;
import org.watermedia.api.platform.web.DropboxPlatform;
import org.watermedia.api.platform.web.ImgurPlatform;
import org.watermedia.api.platform.web.KickPlatform;
import org.watermedia.api.platform.web.LightshotPlatform;
import org.watermedia.api.platform.web.MediaFirePlatform;
import org.watermedia.api.platform.web.OdyseePlatform;
import org.watermedia.api.platform.web.PornHubPlatform;
import org.watermedia.api.platform.web.SendvidPlatform;
import org.watermedia.api.platform.web.StreamablePlatform;
import org.watermedia.api.platform.web.TikTokPlatform;
import org.watermedia.api.platform.web.TwitchPlatform;
import org.watermedia.api.platform.web.TwitterPlatform;
import org.watermedia.api.platform.web.VidLiiPlatform;

import java.net.URI;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Offline coverage for the URI-claim half of {@link IPlatform#getData(URI)}.
 * Since validation now lives inside {@code getData}, a foreign URI must be
 * rejected with a {@code null} return <i>before</i> any network access — these
 * cases exercise host matching, suffix rules, path prefixes and pattern
 * matchers without ever touching the network.
 * <p>
 * The other two outcomes of the contract are covered elsewhere offline:
 * the success (instance) and parse-failure (exception) states by
 * {@link org.watermedia.test.platform.WaterPlatformTest}, the registry-level
 * wiring by {@link org.watermedia.test.platform.PlatformApiTest}, and the
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

                // Dropbox — host contains "dropbox.com" AND query contains dl=0
                arguments(DropboxPlatform.class, "https://www.dropbox.com/s/abc/x.mp4?dl=1"),
                arguments(DropboxPlatform.class, "https://www.dropbox.com/s/abc/x.mp4"),
                arguments(DropboxPlatform.class, "https://example.com/s/abc?dl=0"),

                // Imgur — host equalsIgnoreCase imgur.com
                arguments(ImgurPlatform.class, "https://i.imgur.com/abc.png"),
                arguments(ImgurPlatform.class, "https://example.com/"),

                // Kick — host equalsIgnoreCase kick.com
                arguments(KickPlatform.class, "https://www.kick.com/channelname"),
                arguments(KickPlatform.class, "https://twitch.tv/x"),

                // Lightshot — host equalsIgnoreCase prnt.sc
                arguments(LightshotPlatform.class, "https://light.shot/abc"),

                // MediaFire — host == www.mediafire.com AND path starts /file/
                arguments(MediaFirePlatform.class, "https://mediafire.com/file/abc/x.zip"),
                arguments(MediaFirePlatform.class, "https://www.mediafire.com/folder/abc"),

                // Odysee — host odysee.com / www.odysee.com
                arguments(OdyseePlatform.class, "https://example.com/"),

                // PornHub — host ends with pornhub.com AND path starts /view_video.php
                arguments(PornHubPlatform.class, "https://www.pornhub.com/embed/abc"),
                arguments(PornHubPlatform.class, "https://example.com/view_video.php?viewkey=abc"),

                // Sendvid — host sendvid.com / www.sendvid.com
                arguments(SendvidPlatform.class, "https://example.com/"),

                // Streamable — host equalsIgnoreCase streamable.com
                arguments(StreamablePlatform.class, "https://www.streamable.com/abc123"),
                arguments(StreamablePlatform.class, "https://example.com/"),

                // TikTok — host whitelist
                arguments(TikTokPlatform.class, "https://example.com/"),

                // Twitch — host whitelist
                arguments(TwitchPlatform.class, "https://example.com/"),

                // Twitter — host whitelist AND path matches status/<id>
                arguments(TwitterPlatform.class, "https://twitter.com/user"),
                arguments(TwitterPlatform.class, "https://example.com/user/status/1"),

                // VidLii — host vidlii.com / www.vidlii.com
                arguments(VidLiiPlatform.class, "https://example.com/")
        );
    }

    @ParameterizedTest(name = "{0} getData({1}) == null")
    @MethodSource("foreignUris")
    void testForeignUriYieldsNull(final Class<? extends IPlatform> type, final String uriString) throws Exception {
        final IPlatform platform = type.getDeclaredConstructor().newInstance();
        final URI uri = URI.create(uriString);
        assertNull(platform.getData(uri),
                () -> type.getSimpleName() + ".getData(" + uriString + ") must return null for a foreign URI");
    }

    // MATURE GATE — PornHub IS MATURE-ONLY AND REJECTS BEFORE ANY NETWORK ACCESS
    // WHEN allowMatureContent IS OFF, SO THE EXCEPTION STATE IS VERIFIABLE OFFLINE.
    private boolean previousMature;

    @BeforeEach
    void rememberConfig() {
        this.previousMature = WaterMediaConfig.media.platforms.allowMatureContent;
    }

    @AfterEach
    void restoreConfig() {
        WaterMediaConfig.media.platforms.allowMatureContent = this.previousMature;
    }

    @Test
    @DisplayName("Mature-only platform throws before any fetch when mature content is disabled")
    void testMatureContentDisabledThrowsBeforeFetch() {
        WaterMediaConfig.media.platforms.allowMatureContent = false;
        final PornHubPlatform platform = new PornHubPlatform();
        assertThrows(MatureContentException.class,
                () -> platform.getData(URI.create("https://www.pornhub.com/view_video.php?viewkey=abc")),
                "Mature-only platform must throw when mature content is disabled, before any fetch");
    }
}
