package org.watermedia.test.media.tx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.players.MediaPlayer.Status;
import org.watermedia.api.media.players.TxMediaPlayer;
import org.watermedia.api.media.engines.HeadlessGFXEngine;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.PlayerWait;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link TxMediaPlayer#displayTime(long)}: a static image with a finite display duration
 * must transition to ENDED via the passive clock so a status-driven playlist advances off
 * {@code ended()}, while an unlimited (default) static image shows forever.
 */
@DisplayName("TxMediaPlayer static image display time")
public class TxDisplayTimeTest {

    private static final long MRL_TIMEOUT_MS = 2000L;
    private static final long PLAYER_TIMEOUT_MS = 3000L;
    private static final long DISPLAY_MS = 300L;

    // CAPTURES THE publishStatus TRANSITION FROM THE STATUS-QUERY THREAD; volatile FOR VISIBILITY.
    private static final class StatusCapture {
        private volatile Status from;
        private volatile Status to;
        void accept(final Status prev, final Status next) { this.from = prev; this.to = next; }
    }

    @Test
    @DisplayName("A timed static image transitions to ENDED after displayTime")
    void testTimedStaticEnds() {
        final MRL mrl = MediaAPI.mrl(Fixtures.fileUri(Fixtures.JPEG_BASELINE));
        assertTrue(mrl.await(MRL_TIMEOUT_MS));

        final HeadlessGFXEngine gfx = new HeadlessGFXEngine(false);
        final TxMediaPlayer player = new TxMediaPlayer(mrl, 0, gfx);
        final StatusCapture cap = new StatusCapture();
        player.onStatus(cap::accept);

        try {
            player.displayTime(DISPLAY_MS);
            player.start();
            assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING, Status.PAUSED));
            assertTrue(PlayerWait.awaitLoaded(player, PLAYER_TIMEOUT_MS));
            // THE DISPLAY DURATION IS REPORTED AS THE PLAYER DURATION.
            assertEquals(DISPLAY_MS, player.duration());

            // THE PASSIVE CLOCK MUST DRIVE THE STILL IMAGE TO ENDED WITHOUT ANY LIFECYCLE THREAD.
            assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.ENDED));
            assertTrue(player.ended());
            assertEquals(Status.PLAYING, cap.from);
            assertEquals(Status.ENDED, cap.to);
        } finally {
            player.stop();
            player.release();
        }
        gfx.release();
    }

    @Test
    @DisplayName("An unlimited static image never ends")
    void testUnlimitedStaticNeverEnds() throws InterruptedException {
        final MRL mrl = MediaAPI.mrl(Fixtures.fileUri(Fixtures.JPEG_BASELINE));
        assertTrue(mrl.await(MRL_TIMEOUT_MS));

        final HeadlessGFXEngine gfx = new HeadlessGFXEngine(false);
        final TxMediaPlayer player = new TxMediaPlayer(mrl, 0, gfx);

        try {
            // NO displayTime SET (ms <= 0) — CURRENT BEHAVIOUR: SHOW FOREVER.
            player.start();
            assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING, Status.PAUSED));
            assertTrue(PlayerWait.awaitLoaded(player, PLAYER_TIMEOUT_MS));

            Thread.sleep(400L);
            assertFalse(player.ended(), "an unlimited static image must not transition to ENDED");
            assertEquals(0L, player.duration());
        } finally {
            player.stop();
            player.release();
        }
        gfx.release();
    }
}
