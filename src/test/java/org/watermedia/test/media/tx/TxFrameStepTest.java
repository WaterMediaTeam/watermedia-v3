package org.watermedia.test.media.tx;

import org.junit.jupiter.api.Test;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.players.MediaPlayer.Status;
import org.watermedia.api.media.players.TxMediaPlayer;
import org.watermedia.test.support.FakeGFXEngine;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.PlayerWait;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Frame-step semantics for {@link TxMediaPlayer}: while paused, {@code nextFrame()}
 * advances {@code time()} and {@code previousFrame()} rewinds it; the player must
 * remain in {@link Status#PAUSED} throughout.
 */
public class TxFrameStepTest {

    private static final long MRL_TIMEOUT_MS = 2000L;
    private static final long PLAYER_TIMEOUT_MS = 3000L;
    private static final long STEP_SETTLE_MS = 2000L;

    @Test
    public void nextAndPreviousFrameMoveClockWhilePaused() {
        final MRL mrl = MediaAPI.getMRL(Fixtures.fileUri(Fixtures.GIF_DIR.resolve("2.gif")));
        assertTrue(mrl.await(MRL_TIMEOUT_MS));

        final FakeGFXEngine gfx = new FakeGFXEngine(false);
        final TxMediaPlayer player = new TxMediaPlayer(mrl, 0, gfx);
        assertNotNull(player);

        try {
            player.start();
            assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING, Status.PAUSED));
            assertTrue(PlayerWait.awaitLoaded(player, PLAYER_TIMEOUT_MS));

            player.pause();
            assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PAUSED));

            final long initial = player.time();

            assertTrue(player.nextFrame());
            assertTrue(PlayerWait.awaitCondition(() -> player.time() > initial, STEP_SETTLE_MS));
            assertEquals(Status.PAUSED, player.status());

            final long afterNext = player.time();
            assertTrue(player.previousFrame());
            assertTrue(PlayerWait.awaitCondition(() -> player.time() < afterNext, STEP_SETTLE_MS));
            assertEquals(Status.PAUSED, player.status());
        } finally {
            player.stop();
            player.release();
        }
    }
}
