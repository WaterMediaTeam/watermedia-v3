package org.watermedia.api.media;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.util.MediaType;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that an IPTV-style M3U playlist (a flat list of channels) loaded through {@link MRL}
 * expands into one {@link MRL.Source} per channel, carrying the channel metadata, instead of
 * collapsing into a single HLS-like source.
 */
@DisplayName("MRL IPTV M3U expansion")
public class MrlIptvM3uTest {
    @BeforeAll
    static void clientBootstrap() { MediaBootstrap.client(); }

    private static final long TIMEOUT_MS = 5000L;
    private static final Path IPTV_FIXTURE = Fixtures.RESOURCES.resolve("m3u").resolve("iptv_sample.m3u8");

    @Test
    @DisplayName("IPTV playlist expands into one source per channel")
    void testIptvPlaylistExpandsIntoOneSourcePerChannel() {
        final MRL mrl = MediaAPI.mrl(Fixtures.fileUri(IPTV_FIXTURE));
        assertTrue(mrl.await(TIMEOUT_MS));
        assertTrue(mrl.status().loaded());
        assertFalse(mrl.status().failed(), () -> "Unexpected error: " + mrl.exception());

        assertEquals(3, mrl.sourceCount(),
                "IPTV playlist should expand to 3 channels, got " + mrl.sourceCount());
    }

    @Test
    @DisplayName("IPTV channel metadata reaches the sources")
    void testIptvChannelMetadataReachesSources() {
        final MRL mrl = MediaAPI.mrl(Fixtures.fileUri(IPTV_FIXTURE));
        assertTrue(mrl.await(TIMEOUT_MS));

        final MRL.Source first = mrl.source(0);
        assertNotNull(first);
        assertNotNull(first.metadata());
        assertEquals("Channel One", first.metadata().title());
        // THE IPTV GROUP IS A CATEGORY, SO IT TRAVELS AS THE DESCRIPTION AND NOBODY IS CREDITED AS AUTHOR
        assertEquals("USA", first.metadata().description());
        assertNull(first.metadata().author());

        assertNotNull(first.thumbnail());
        assertEquals("https://logo.example/one.png", first.thumbnail().toString());

        // CHANNEL TYPE IS LEFT UNKNOWN AT MRL TIME — THE TRUE TYPE GETS RESOLVED
        // ONLY WHEN A PLAYER ACTUALLY CONNECTS TO THE STREAM URL.
        assertEquals(MediaType.UNKNOWN, first.type());
    }
}
