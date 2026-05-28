package org.watermedia.test.media.mrl;

import org.junit.jupiter.api.Test;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.PlayerWait;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Subscription contract for {@link MRL}: pre-ready listeners fire exactly once
 * on load completion, post-ready listeners fire immediately on the calling
 * thread, and a throwing listener never kills the dispatch chain. The MRL
 * cache hands back the same instance for repeated URIs, so each test uses a
 * different fixture URI to avoid cross-contamination.
 */
public class MrlSubscribeTest {

    private static final long TIMEOUT_MS = 2000L;

    @Test
    public void listenerFiresOnceWhenLoadingCompletes() {
        // EACH TEST USES A DISTINCT FIXTURE FILE BECAUSE THE MRL CACHE KEYS BY URI.
        final MRL mrl = MediaAPI.getMRL(Fixtures.fileUri(Fixtures.PNG_DIR.resolve("3.png")));

        // ONE-SHOT COUNTER: SINGLE-ELEMENT ARRAY LETS THE LISTENER MUTATE FROM EITHER THREAD.
        final int[] count = new int[1];
        mrl.subscribe(m -> count[0]++);

        assertTrue(mrl.await(TIMEOUT_MS));
        assertTrue(PlayerWait.awaitCondition(() -> count[0] >= 1, TIMEOUT_MS));
        assertEquals(1, count[0]);
    }

    @Test
    public void listenerFiresImmediatelyWhenAlreadyReady() {
        final MRL mrl = MediaAPI.getMRL(Fixtures.fileUri(Fixtures.PNG_DIR.resolve("4.png")));
        assertTrue(mrl.await(TIMEOUT_MS));

        final int[] count = new int[1];
        final Thread callingThread = Thread.currentThread();
        final Thread[] firedOn = new Thread[1];
        mrl.subscribe(m -> {
            firedOn[0] = Thread.currentThread();
            count[0]++;
        });

        assertEquals(1, count[0]);
        assertEquals(callingThread, firedOn[0]);
    }

    @Test
    public void throwingListenerDoesNotBreakDispatchChain() {
        final MRL mrl = MediaAPI.getMRL(Fixtures.fileUri(Fixtures.GIF_DIR.resolve("2.gif")));

        final boolean[] secondFired = new boolean[1];
        mrl.subscribe(m -> { throw new RuntimeException("BOOM"); });
        mrl.subscribe(m -> secondFired[0] = true);

        assertTrue(mrl.await(TIMEOUT_MS));
        assertTrue(PlayerWait.awaitCondition(() -> secondFired[0], TIMEOUT_MS));
    }
}
