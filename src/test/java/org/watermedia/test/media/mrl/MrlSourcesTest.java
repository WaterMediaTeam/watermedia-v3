package org.watermedia.test.media.mrl;

import org.junit.jupiter.api.DisplayName;
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
 * Verifies {@link MRL#sourceByType} and {@link MRL#sourcesByType} consistently
 * classify a local image as IMAGE-only.
 */
@DisplayName("MRL source classification")
public class MrlSourcesTest {

    private static final long TIMEOUT_MS = 2000L;

    @Test
    @DisplayName("Local image only exposes IMAGE sources")
    void testLocalImageOnlyExposesImageSources() {
        final MRL mrl = MediaAPI.mrl(Fixtures.fileUri(Fixtures.PNG_STATIC));
        assertTrue(mrl.await(TIMEOUT_MS));

        final MRL.Source image = mrl.sourceByType(MediaType.IMAGE);
        assertNotNull(image);
        assertNull(mrl.sourceByType(MediaType.VIDEO));

        // THE FIRST-MATCH LOOKUP AND THE FULL FILTER MUST AGREE ON THE SAME OBJECT.
        assertSame(image, mrl.sourcesByType(MediaType.IMAGE).get(0));

        assertFalse(mrl.sourcesByType(MediaType.IMAGE).isEmpty());
        assertEquals(0, mrl.sourcesByType(MediaType.VIDEO).size());
    }
}
