package org.watermedia.test.media.mrl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.test.support.Fixtures;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Caching guarantees: {@link MRL#preload(URI...)} fans out into the same
 * underlying instances that {@link MediaAPI#getMRL(URI)} returns, and repeated
 * lookups for the same URI yield the exact same object (reference identity).
 */
@DisplayName("MRL preload caching")
public class MrlPreloadTest {

    @Test
    @DisplayName("preload resolves the same instances as getMRL")
    void testPreloadResolvesSameInstancesAsGetMrl() {
        final URI uriA = Fixtures.fileUri(Fixtures.PNG_STATIC);
        final URI uriB = Fixtures.fileUri(Fixtures.GIF_ANIMATED);

        final MRL[] preloaded = MediaAPI.preloadMRL(uriA, uriB);

        assertEquals(2, preloaded.length);
        assertSame(preloaded[0], MediaAPI.getMRL(uriA));
        assertSame(preloaded[1], MediaAPI.getMRL(uriB));
    }

    @Test
    @DisplayName("getMRL returns the same cached instance for a repeated URI")
    void testGetMrlReturnsSameCachedInstanceForRepeatedUri() {
        final URI uri = Fixtures.fileUri(Fixtures.PNG_DIR.resolve("3.png"));

        final MRL first = MediaAPI.getMRL(uri);
        final MRL second = MediaAPI.getMRL(uri);

        assertSame(first, second);
    }
}
