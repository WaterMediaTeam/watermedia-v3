package org.watermedia.test.platform.web;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.watermedia.api.platform.IPlatform;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Offline coverage for every built-in {@link IPlatform#validate(URI)}. The cases
 * exercise host matching, suffix rules, path prefixes, and pattern matchers —
 * never the network. Each row is {@code (platformClass, uri, expectMatch)} and
 * the test instantiates a fresh platform per row via its no-arg constructor.
 */
public class ValidateUriTest {

    static Stream<Arguments> cases() {
        return Stream.of(
                // WaterPlatform — water:// scheme is the only signal
                arguments(WaterPlatform.class, "water://local/foo.png", true),
                arguments(WaterPlatform.class, "water://global/x", true),
                arguments(WaterPlatform.class, "water://remote/x", true),
                arguments(WaterPlatform.class, "https://foo/", false),
                arguments(WaterPlatform.class, "file:///tmp/x.png", false),

                // Bluesky — web pattern requires /profile/<handle>/post/<id>; at:// path
                arguments(BlueskyPlatform.class, "https://bsky.app/profile/handle/post/abc123", true),
                arguments(BlueskyPlatform.class, "https://www.bsky.app/profile/handle.bsky.social/post/abc", true),
                arguments(BlueskyPlatform.class, "at://did:plc:abc/app.bsky.feed.post/xyz", true),
                arguments(BlueskyPlatform.class, "https://twitter.com/i/status/1", false),
                arguments(BlueskyPlatform.class, "https://bsky.app/about", false),

                // BiliBili — host whitelist
                arguments(BiliBiliPlatform.class, "https://www.bilibili.com/video/BV1xx411c7mu", true),
                arguments(BiliBiliPlatform.class, "https://live.bilibili.com/12345", true),
                arguments(BiliBiliPlatform.class, "https://b23.tv/abcd", true),
                arguments(BiliBiliPlatform.class, "https://youtube.com/watch?v=x", false),

                // DTube — host == d.tube or www.d.tube
                arguments(DTubePlatform.class, "https://d.tube/v/user/abcd", true),
                arguments(DTubePlatform.class, "https://www.d.tube/v/user/abcd", true),
                arguments(DTubePlatform.class, "https://example.com/", false),

                // Drive — host == drive.google.com AND path starts with /file/d/
                arguments(DrivePlatform.class, "https://drive.google.com/file/d/ID123/view", true),
                arguments(DrivePlatform.class, "https://drive.google.com/drive/folders/X", false),
                arguments(DrivePlatform.class, "https://docs.google.com/file/d/ID/view", false),

                // Dropbox — host contains "dropbox.com" AND query contains dl=0
                arguments(DropboxPlatform.class, "https://www.dropbox.com/s/abc/x.mp4?dl=0", true),
                arguments(DropboxPlatform.class, "https://dropbox.com/s/abc/x.mp4?dl=0", true),
                arguments(DropboxPlatform.class, "https://www.dropbox.com/s/abc/x.mp4?dl=1", false),
                arguments(DropboxPlatform.class, "https://www.dropbox.com/s/abc/x.mp4", false),
                arguments(DropboxPlatform.class, "https://example.com/s/abc?dl=0", false),

                // Imgur — host equalsIgnoreCase imgur.com
                arguments(ImgurPlatform.class, "https://imgur.com/gallery/abc", true),
                arguments(ImgurPlatform.class, "https://IMGUR.COM/a/xyz", true),
                arguments(ImgurPlatform.class, "https://i.imgur.com/abc.png", false),
                arguments(ImgurPlatform.class, "https://example.com/", false),

                // Kick — host equalsIgnoreCase kick.com
                arguments(KickPlatform.class, "https://kick.com/channelname", true),
                arguments(KickPlatform.class, "https://www.kick.com/channelname", false),
                arguments(KickPlatform.class, "https://twitch.tv/x", false),

                // Lightshot — host equalsIgnoreCase prnt.sc
                arguments(LightshotPlatform.class, "https://prnt.sc/abc123", true),
                arguments(LightshotPlatform.class, "https://light.shot/abc", false),

                // MediaFire — host == www.mediafire.com AND path starts /file/
                arguments(MediaFirePlatform.class, "https://www.mediafire.com/file/abc/x.zip", true),
                arguments(MediaFirePlatform.class, "https://mediafire.com/file/abc/x.zip", false),
                arguments(MediaFirePlatform.class, "https://www.mediafire.com/folder/abc", false),

                // Odysee — host odysee.com / www.odysee.com
                arguments(OdyseePlatform.class, "https://odysee.com/@channel/video", true),
                arguments(OdyseePlatform.class, "https://www.odysee.com/@channel/video", true),
                arguments(OdyseePlatform.class, "https://example.com/", false),

                // PornHub — host ends with pornhub.com AND path starts /view_video.php
                arguments(PornHubPlatform.class, "https://www.pornhub.com/view_video.php?viewkey=abc", true),
                arguments(PornHubPlatform.class, "https://pornhub.com/view_video.php?viewkey=abc", true),
                arguments(PornHubPlatform.class, "https://www.pornhub.com/embed/abc", false),
                arguments(PornHubPlatform.class, "https://example.com/view_video.php?viewkey=abc", false),

                // Sendvid — host sendvid.com / www.sendvid.com
                arguments(SendvidPlatform.class, "https://sendvid.com/abc123", true),
                arguments(SendvidPlatform.class, "https://www.sendvid.com/abc123", true),
                arguments(SendvidPlatform.class, "https://example.com/", false),

                // Streamable — host equalsIgnoreCase streamable.com
                arguments(StreamablePlatform.class, "https://streamable.com/abc123", true),
                arguments(StreamablePlatform.class, "https://STREAMABLE.com/abc123", true),
                arguments(StreamablePlatform.class, "https://www.streamable.com/abc123", false),
                arguments(StreamablePlatform.class, "https://example.com/", false),

                // TikTok — host whitelist
                arguments(TikTokPlatform.class, "https://www.tiktok.com/@user/video/12345", true),
                arguments(TikTokPlatform.class, "https://vm.tiktok.com/ZMabc/", true),
                arguments(TikTokPlatform.class, "https://example.com/", false),

                // Twitch — host whitelist
                arguments(TwitchPlatform.class, "https://www.twitch.tv/somechannel", true),
                arguments(TwitchPlatform.class, "https://clips.twitch.tv/SomeClip", true),
                arguments(TwitchPlatform.class, "https://player.twitch.tv/?channel=foo", true),
                arguments(TwitchPlatform.class, "https://example.com/", false),

                // Twitter — host whitelist AND path matches status/<id>
                arguments(TwitterPlatform.class, "https://twitter.com/user/status/1234567890", true),
                arguments(TwitterPlatform.class, "https://x.com/user/status/1234567890", true),
                arguments(TwitterPlatform.class, "https://www.twitter.com/user/status/1", true),
                arguments(TwitterPlatform.class, "https://twitter.com/user", false),
                arguments(TwitterPlatform.class, "https://example.com/user/status/1", false),

                // VidLii — host vidlii.com / www.vidlii.com
                arguments(VidLiiPlatform.class, "https://vidlii.com/watch?v=abc", true),
                arguments(VidLiiPlatform.class, "https://www.vidlii.com/watch?v=abc", true),
                arguments(VidLiiPlatform.class, "https://example.com/", false)
        );
    }

    @ParameterizedTest(name = "{0} validate({1}) == {2}")
    @MethodSource("cases")
    void validate(final Class<? extends IPlatform> type, final String uriString, final boolean expectMatch) throws Exception {
        final IPlatform platform = type.getDeclaredConstructor().newInstance();
        final URI uri = URI.create(uriString);
        assertEquals(expectMatch, platform.validate(uri),
                () -> type.getSimpleName() + ".validate(" + uriString + ") expected " + expectMatch);
    }
}
