package org.watermedia.test.media.mrl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.test.support.Fixtures;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts the basic readiness contract of {@link MRL}: a successful load flips
 * {@code ready()} on without an error, a bogus URI still flips {@code ready()}
 * but exposes the failure through {@code hasError()}, and a freshly loaded
 * local MRL is not considered expired.
 */
@DisplayName("MRL readiness")
public class MrlReadinessTest {

    private static final long TIMEOUT_MS = 2000L;

    @Test
    @DisplayName("Local image loads cleanly")
    void testLocalImageLoadsCleanly() {
        final MRL mrl = MediaAPI.getMRL(Fixtures.fileUri(Fixtures.PNG_STATIC));
        assertTrue(mrl.await(TIMEOUT_MS));
        assertTrue(mrl.status().loaded());
        assertFalse(mrl.status().failed());
        assertTrue(mrl.sourceCount() > 0);
    }

    @Test
    @DisplayName("Nonexistent file marks an error after load")
    void testNonexistentFileMarksErrorAfterLoad() {
        final MRL mrl = MediaAPI.getMRL(URI.create("file:///nonexistent/abc.png"));
        assertTrue(mrl.await(TIMEOUT_MS));
        assertFalse(mrl.status().loaded());
        assertTrue(mrl.status().failed());
    }

    @Test
    @DisplayName("Freshly loaded local MRL is not expired")
    void testFreshlyLoadedLocalMrlIsNotExpired() {
        final MRL mrl = MediaAPI.getMRL(Fixtures.fileUri(Fixtures.PNG_STATIC));
        assertTrue(mrl.await(TIMEOUT_MS));
        assertNotSame(MRL.Status.EXPIRED, mrl.status());
    }
}
