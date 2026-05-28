package org.watermedia.test.media.mrl;

import org.junit.jupiter.api.Test;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.test.support.Fixtures;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts the basic readiness contract of {@link MRL}: a successful load flips
 * {@code ready()} on without an error, a bogus URI still flips {@code ready()}
 * but exposes the failure through {@code hasError()}, and a freshly loaded
 * local MRL is not considered expired.
 */
public class MrlReadinessTest {

    private static final long TIMEOUT_MS = 2000L;

    @Test
    public void localImageLoadsCleanly() {
        final MRL mrl = MediaAPI.getMRL(Fixtures.fileUri(Fixtures.PNG_STATIC));
        assertTrue(mrl.await(TIMEOUT_MS));
        assertTrue(mrl.ready());
        assertFalse(mrl.hasError());
        assertTrue(mrl.sourceCount() > 0);
    }

    @Test
    public void nonexistentFileMarksErrorAfterLoad() {
        final MRL mrl = MediaAPI.getMRL(URI.create("file:///nonexistent/abc.png"));
        assertTrue(mrl.await(TIMEOUT_MS));
        assertTrue(mrl.ready());
        assertTrue(mrl.hasError());
    }

    @Test
    public void freshlyLoadedLocalMrlIsNotExpired() {
        final MRL mrl = MediaAPI.getMRL(Fixtures.fileUri(Fixtures.PNG_STATIC));
        assertTrue(mrl.await(TIMEOUT_MS));
        assertFalse(mrl.expired());
    }
}
