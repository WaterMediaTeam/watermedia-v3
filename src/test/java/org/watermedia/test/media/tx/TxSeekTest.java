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
 * Seek smoke test for {@link TxMediaPlayer}'s streaming path. Issues a mid-
 * duration seek and verifies the lifecycle thread eventually resumes playback
 * after settling on the target frame.
 */
public class TxSeekTest {

    private static final long MRL_TIMEOUT_MS = 2000L;
    private static final long PLAYER_TIMEOUT_MS = 3000L;
    private static final long SEEK_SETTLE_MS = 3000L;

    @Test
    public void seekJumpsToMidDurationAndResumesPlayback() {
        final MRL mrl = MediaAPI.getMRL(Fixtures.fileUri(Fixtures.GIF_ANIMATED));
        assertTrue(mrl.await(MRL_TIMEOUT_MS));

        final FakeGFXEngine gfx = new FakeGFXEngine(false);
        final TxMediaPlayer player = new TxMediaPlayer(mrl, 0, gfx);
        assertNotNull(player);

        try {
            player.start();
            assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING, Status.PAUSED));
            assertTrue(PlayerWait.awaitLoaded(player, PLAYER_TIMEOUT_MS));
            assertTrue(player.canSeek());

            final long duration = player.duration();
            assertTrue(duration > 0L);
            final long target = duration / 2;

            assertTrue(player.seek(target));

            // SEEK IS ASYNC — WAIT FOR THE LIFECYCLE THREAD TO LAND NEAR THE TARGET.
            // FRAME GRANULARITY + ONGOING PLAYBACK MEANS time() COULD HAVE ADVANCED PAST target
            // BY ANOTHER FRAME OR TWO BY THE TIME WE OBSERVE IT, SO TOLERATE A GENEROUS WINDOW.
            assertTrue(PlayerWait.awaitCondition(
                    () -> {
                        final long t = player.time();
                        return t >= target - Math.max(duration / 4, 500L)
                                && t <= target + Math.max(duration / 2, 1000L);
                    },
                    SEEK_SETTLE_MS));

            // EVENTUALLY THE PLAYER RETURNS TO PLAYING (POSSIBLY THROUGH A BRIEF BUFFERING WINDOW).
            assertTrue(PlayerWait.awaitStatus(player, SEEK_SETTLE_MS, Status.PLAYING, Status.BUFFERING));
            assertTrue(PlayerWait.awaitStatus(player, SEEK_SETTLE_MS, Status.PLAYING));
        } finally {
            player.stop();
            player.release();
        }
    }
}
