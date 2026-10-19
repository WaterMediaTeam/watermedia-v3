package org.watermedia.api.media;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the quality buckets of {@link MRL.Source}: closest-fallback resolution
 * via {@link MRL.Source#uri(MediaQuality)}, copy-on-write relabel of an unknown quality via
 * {@code MRL.moveQuality}, and bidirectional URI/quality lookup via
 * {@link MRL.Source#qualityOf(URI)}.
 */
@DisplayName("MRL quality buckets")
public class MrlQualityTest {
    @BeforeAll
    static void clientBootstrap() { MediaBootstrap.client(); }

    private static final long TIMEOUT_MS = 2000L;

    @Test
    @DisplayName("Source resolves a URI through the closest fallback")
    void testSourceResolvesUriThroughClosestFallback() {
        final MRL mrl = MediaAPI.mrl(Fixtures.fileUri(Fixtures.PNG_DIR.resolve("3.png")));
        assertTrue(mrl.await(TIMEOUT_MS));

        final MRL.Source source = mrl.source(0);
        assertNotNull(source);
        assertFalse(source.qualities().isEmpty());

        final URI resolved = source.uri(MediaQuality.HIGHEST);
        assertNotNull(resolved);
    }

    @Test
    @DisplayName("moveQuality relabels the unknown quality of its own URI once, in a fresh source array")
    void testMoveQualityRelabelsUnknownQuality() {
        final MRL mrl = MediaAPI.mrl(Fixtures.fileUri(Fixtures.PNG_DIR.resolve("4.png")));
        assertTrue(mrl.await(TIMEOUT_MS));

        final MRL.Source before = mrl.source(0);
        assertNotNull(before);
        final URI uri = before.qualities().get(MediaQuality.UNKNOWN);
        assertNotNull(uri, "a local file starts with an unknown quality");
        assertFalse(mrl.moveQuality(0, URI.create("file:///elsewhere.png"), MediaQuality.HIGH), "another URI is never relabeled");
        assertTrue(mrl.moveQuality(0, uri, MediaQuality.HIGH));

        final MRL.Source after = mrl.source(0);
        assertNotNull(after);
        assertEquals(uri, after.qualities().get(MediaQuality.HIGH));
        assertFalse(after.qualities().containsKey(MediaQuality.UNKNOWN));
        // PLAYERS KEEP THE SOURCE THEY STARTED WITH: THE RELABEL REPLACES THE ARRAY INSTEAD OF EDITING IT
        assertEquals(uri, before.qualities().get(MediaQuality.UNKNOWN));
        // A RESTART OR A SECOND PLAYER STILL HOLDS THE OLD SOURCE AND ASKS AGAIN: THE URI ALREADY SITS THERE
        assertTrue(mrl.moveQuality(0, uri, MediaQuality.HIGH), "a repeated relabel succeeds");
        assertFalse(mrl.moveQuality(0, uri, MediaQuality.HIGHER), "a known quality is never relabeled");
    }

    @Test
    @DisplayName("qualityOf round-trips a known URI")
    void testQualityOfRoundTripsKnownUri() {
        final MRL mrl = MediaAPI.mrl(Fixtures.fileUri(Fixtures.GIF_DIR.resolve("3.gif")));
        assertTrue(mrl.await(TIMEOUT_MS));

        final MRL.Source source = mrl.source(0);
        assertNotNull(source);
        final MediaQuality known = source.qualities().keySet().iterator().next();
        final URI uri = source.uri(known);

        assertEquals(known, source.qualityOf(uri));
    }
}
