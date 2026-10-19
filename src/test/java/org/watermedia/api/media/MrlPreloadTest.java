package org.watermedia.api.media;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies the MRL cache: {@link MediaAPI#preload(URI...)} returns the same instances that
 * {@link MediaAPI#mrl(URI)} returns, and repeated lookups of one URI yield the same object.
 */
@DisplayName("MRL preload caching")
public class MrlPreloadTest {
    @BeforeAll
    static void clientBootstrap() { MediaBootstrap.client(); }

    @Test
    @DisplayName("preload resolves the same instances as mrl")
    void testPreloadResolvesSameInstancesAsMrl() {
        final URI uriA = Fixtures.fileUri(Fixtures.PNG_STATIC);
        final URI uriB = Fixtures.fileUri(Fixtures.GIF_ANIMATED);

        final MRL[] preloaded = MediaAPI.preload(uriA, uriB);

        assertEquals(2, preloaded.length);
        assertSame(preloaded[0], MediaAPI.mrl(uriA));
        assertSame(preloaded[1], MediaAPI.mrl(uriB));
    }

    @Test
    @DisplayName("mrl returns the same cached instance for a repeated URI")
    void testMrlReturnsSameCachedInstanceForRepeatedUri() {
        final URI uri = Fixtures.fileUri(Fixtures.PNG_DIR.resolve("3.png"));

        final MRL first = MediaAPI.mrl(uri);
        final MRL second = MediaAPI.mrl(uri);

        assertSame(first, second);
    }
}
