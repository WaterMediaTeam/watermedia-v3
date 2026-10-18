package org.watermedia.api.media;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.platform.Platform;
import org.watermedia.api.platform.Platform.Quality;
import org.watermedia.api.platform.Platform.Resolution;
import org.watermedia.api.platform.Platform.Source;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.api.util.MediaType;
import org.watermedia.test.support.MediaBootstrap;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how {@link MRL} folds platform renditions into one URL per {@link MediaQuality} bucket:
 * the widest wins, then the tallest, then the highest frame rate, then the first listed; and that
 * the headers a resolved source carries are frozen.
 */
@DisplayName("MRL quality buckets")
public class MrlQualityBucketTest {
    private static final long TIMEOUT_MS = 5000L;

    @BeforeAll
    static void clientBootstrap() {
        MediaBootstrap.client();
    }

    @Nested
    @DisplayName("One URL per bucket")
    class Buckets {

        @Test
        @DisplayName("Widest, then tallest, then first listed")
        void sizeTieBreak() {
            final URI claimed = URI.create("watertest://bucket/clip");
            final URI slow = URI.create("https://example.invalid/720p30.mp4");
            final URI fast = URI.create("https://example.invalid/720p60.mp4");
            final URI wide = URI.create("https://example.invalid/720wide.mp4");
            final URI first = URI.create("https://example.invalid/360-first.mp4");
            final URI second = URI.create("https://example.invalid/360-second.mp4");
            final Source source = new Source(MediaType.VIDEO, null, null, null, List.of(
                    new Quality(slow, 1280, 720, 30f), new Quality(fast, 1280, 720, 60f), new Quality(wide, 1290, 720, 24f),
                    new Quality(first, 640, 360), new Quality(second, 640, 360)), null, null);
            final MRL.Source resolved = resolve(claimed, source);
            assertEquals(wide, resolved.qualities().get(MediaQuality.HIGH), "the widest rendition owns the bucket");
            assertEquals(first, resolved.qualities().get(MediaQuality.LOW), "equal renditions keep the first listed");
        }

        @Test
        @DisplayName("Frame rate breaks ties between renditions of the same size")
        void frameRateTieBreak() {
            final URI claimed = URI.create("watertest://bucket/fps");
            final URI slow = URI.create("https://example.invalid/1080p30.mp4");
            final URI fast = URI.create("https://example.invalid/1080p60.mp4");
            // THE SLOWER RENDITION IS LISTED FIRST SO ONLY THE FRAME RATE CAN PICK THE FASTER ONE
            final Source source = new Source(MediaType.VIDEO, null, null, null, List.of(
                    new Quality(slow, 1920, 1080, 30f), new Quality(fast, 1920, 1080, 60f)), null, null);
            assertEquals(fast, resolve(claimed, source).qualities().get(MediaQuality.HIGHER));
        }
    }

    @Nested
    @DisplayName("Resolved headers")
    class Headers {

        @Test
        @DisplayName("A resolved source carries frozen headers")
        void frozenHeaders() {
            final URI claimed = URI.create("watertest://bucket/headers");
            final Source source = new Source(MediaType.VIDEO, null, null, null,
                    List.of(new Quality(URI.create("https://example.invalid/clip.mp4"), 0, 0)), null, null);
            final MRL.Source resolved = resolve(claimed, source);
            assertTrue(resolved.headers().frozen());
            assertThrows(IllegalStateException.class, () -> resolved.headers().set("X-Test", "1"));
        }
    }

    // RESOLVES claimed THROUGH A MINIMAL HANDLER THAT CLAIMS EXACTLY THAT URI
    private static MRL.Source resolve(final URI claimed, final Source... entries) {
        final Platform platform = new Platform() {
            @Override
            public String name() {
                return "Bucket Test";
            }

            @Override
            public Resolution resolve(final URI uri) {
                return claimed.equals(uri) ? new Resolution(null, List.of(entries)) : null;
            }
        };
        PlatformAPI.register(platform);
        try {
            final MRL mrl = MediaAPI.mrl(claimed);
            assertTrue(mrl.await(TIMEOUT_MS));
            return mrl.source(0);
        } finally {
            PlatformAPI.unregister(platform);
        }
    }
}
