package org.watermedia.test.media.mrl;

import org.junit.jupiter.api.Test;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.test.support.Fixtures;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quality bucket semantics on {@link MRL.Source}: closest-fallback resolution
 * via {@link MRL.Source#uri(MediaQuality)}, in-place quality remap via
 * {@link MRL#moveQuality}, and bidirectional URI/quality lookup via
 * {@link MRL.Source#qualityOf(URI)}.
 */
public class MrlQualityTest {

    private static final long TIMEOUT_MS = 2000L;

    @Test
    public void sourceResolvesUriThroughClosestFallback() {
        final MRL mrl = MediaAPI.getMRL(Fixtures.fileUri(Fixtures.PNG_DIR.resolve("3.png")));
        assertTrue(mrl.await(TIMEOUT_MS));

        final MRL.Source source = mrl.source(0);
        assertNotNull(source);
        assertFalse(source.availableQualities().isEmpty());

        final URI resolved = source.uri(MediaQuality.HIGHER);
        assertNotNull(resolved);
    }

    @Test
    public void moveQualityRewritesQualityKey() {
        final MRL mrl = MediaAPI.getMRL(Fixtures.fileUri(Fixtures.PNG_DIR.resolve("4.png")));
        assertTrue(mrl.await(TIMEOUT_MS));

        final MRL.Source before = mrl.source(0);
        final MediaQuality oldKey = before.availableQualities().iterator().next();

        // SKIP THE NO-OP CASE WHERE HIGH IS ALREADY THE KEY — TEST REMAINS MEANINGFUL.
        final MediaQuality target = oldKey == MediaQuality.HIGH ? MediaQuality.MEDIUM : MediaQuality.HIGH;
        mrl.moveQuality(0, oldKey, target);

        final MRL.Source after = mrl.source(0);
        assertTrue(after.hasQuality(target));
        assertFalse(after.hasQuality(oldKey));
    }

    @Test
    public void qualityOfRoundTripsKnownUri() {
        final MRL mrl = MediaAPI.getMRL(Fixtures.fileUri(Fixtures.GIF_DIR.resolve("3.gif")));
        assertTrue(mrl.await(TIMEOUT_MS));

        final MRL.Source source = mrl.source(0);
        final MediaQuality known = source.availableQualities().iterator().next();
        final URI uri = source.uri(known);

        assertEquals(known, source.qualityOf(uri));
    }
}
