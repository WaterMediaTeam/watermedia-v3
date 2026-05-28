package org.watermedia.test.media.mrl;

import org.junit.jupiter.api.Test;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.util.MediaType;
import org.watermedia.test.support.Fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link MRL#sourceByType}, {@link MRL#imageSource}, {@link MRL#videoSource},
 * and {@link MRL#sourcesByType} consistently classify a local image as IMAGE-only.
 */
public class MrlSourcesTest {

    private static final long TIMEOUT_MS = 2000L;

    @Test
    public void localImageOnlyExposesImageSources() {
        final MRL mrl = MediaAPI.getMRL(Fixtures.fileUri(Fixtures.PNG_STATIC));
        assertTrue(mrl.await(TIMEOUT_MS));

        final MRL.Source image = mrl.sourceByType(MediaType.IMAGE);
        assertNotNull(image);
        assertNull(mrl.videoSource());

        // imageSource() IS A CONVENIENCE FOR sourceByType(IMAGE) — VERIFY THE SAME OBJECT COMES BACK.
        assertSame(image, mrl.imageSource());

        assertFalse(mrl.sourcesByType(MediaType.IMAGE).isEmpty());
        assertEquals(0, mrl.sourcesByType(MediaType.VIDEO).size());
    }
}
