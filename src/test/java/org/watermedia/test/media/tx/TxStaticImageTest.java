package org.watermedia.test.media.tx;

import org.junit.jupiter.api.Test;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.players.MediaPlayer.Status;
import org.watermedia.api.media.players.TxMediaPlayer;
import org.watermedia.test.support.FakeGFXEngine;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.PlayerWait;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke test for the {@link TxMediaPlayer} static-image path: load a
 * single-frame PNG, observe the first GPU upload, and verify that release tears
 * down the engine cleanly.
 */
public class TxStaticImageTest {

    private static final long MRL_TIMEOUT_MS = 2000L;
    private static final long PLAYER_TIMEOUT_MS = 3000L;

    @Test
    public void staticPngLoadsAndUploadsOneFrame() {
        final MRL mrl = MediaAPI.getMRL(Fixtures.fileUri(Fixtures.PNG_STATIC));
        assertTrue(mrl.await(MRL_TIMEOUT_MS));

        final FakeGFXEngine gfx = new FakeGFXEngine(false);
        final TxMediaPlayer player = new TxMediaPlayer(mrl, 0, gfx);
        assertNotNull(player);

        try {
            player.start();
            assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING, Status.PAUSED));
            assertTrue(PlayerWait.awaitLoaded(player, PLAYER_TIMEOUT_MS));

            assertTrue(player.canPlay());
            assertTrue(player.width() > 0);
            assertTrue(player.height() > 0);
            assertTrue(gfx.uploadCount() >= 1);
        } finally {
            player.stop();
            player.release();
        }

        // GFX OWNERSHIP IS EXTERNAL — TEAR DOWN THE ENGINE MANUALLY AND VERIFY THE HOOK FIRES.
        gfx.release();
        assertTrue(gfx.released());
    }
}
