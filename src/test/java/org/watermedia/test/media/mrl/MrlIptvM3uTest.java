package org.watermedia.test.media.mrl;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.WaterMedia;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.util.MediaType;
import org.watermedia.test.support.Fixtures;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts that an IPTV-style M3U playlist (flat list of channels) loaded via
 * {@link MRL} expands into multiple {@link MRL.Source}s instead of collapsing
 * into one — the regression fix for the previous behaviour where MRL handed
 * the URL straight to the player as if it were HLS.
 */
@DisplayName("MRL IPTV M3U expansion")
public class MrlIptvM3uTest {

    private static final long TIMEOUT_MS = 5000L;
    private static final Path IPTV_FIXTURE = Fixtures.RESOURCES.resolve("m3u").resolve("iptv_sample.m3u8");

    @BeforeAll
    static void initWaterMedia() {
        // MIME TYPE MAPPING FOR .m3u8 IS INSTALLED BY NetworkAPI ON START;
        // WITHOUT IT URLConnection LEAVES file://*.m3u8 CONTENT-TYPE AS NULL
        // AND MRL.load() WOULD REJECT IT BEFORE THE IPTV EXPANSION RUNS.
        try {
            WaterMedia.start("TEST", null, null, false);
        } catch (final IllegalStateException ignored) {
            // ALREADY STARTED BY ANOTHER TEST
        }
    }

    @Test
    @DisplayName("IPTV playlist expands into one source per channel")
    void testIptvPlaylistExpandsIntoOneSourcePerChannel() {
        final MRL mrl = MediaAPI.getMRL(Fixtures.fileUri(IPTV_FIXTURE));
        assertTrue(mrl.await(TIMEOUT_MS));
        assertTrue(mrl.status().loaded());
        assertFalse(mrl.status().failed(), () -> "Unexpected error: " + mrl.exception());

        assertEquals(3, mrl.sourceCount(),
                "IPTV playlist should expand to 3 channels, got " + mrl.sourceCount());
    }

    @Test
    @DisplayName("IPTV channel metadata reaches the sources")
    void testIptvChannelMetadataReachesSources() {
        final MRL mrl = MediaAPI.getMRL(Fixtures.fileUri(IPTV_FIXTURE));
        assertTrue(mrl.await(TIMEOUT_MS));

        final MRL.Source first = mrl.source(0);
        assertNotNull(first);
        assertNotNull(first.metadata());
        assertEquals("Channel One", first.metadata().title());
        assertEquals("USA", first.metadata().author());

        assertNotNull(first.thumbnail());
        assertEquals("https://logo.example/one.png", first.thumbnail().toString());

        // CHANNEL TYPE IS LEFT UNKNOWN AT MRL TIME — THE TRUE TYPE GETS RESOLVED
        // ONLY WHEN A PLAYER ACTUALLY CONNECTS TO THE STREAM URL.
        assertEquals(MediaType.UNKNOWN, first.type());
    }
}
