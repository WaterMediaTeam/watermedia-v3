package org.watermedia.api.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.media.MediaPlayer.Status;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.PlayerWait;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the shared control contract on {@link TxMediaPlayer}: boolean controls answer whether
 * the local state changed, stop() rewinds to zero, a scrub or toggle after ended/stopped restarts
 * the media, frame stepping is paused-only, and the status listener hears every transition.
 */
@DisplayName("TxMediaPlayer control contract")
public class TxControlContractTest {

    private static final long MRL_TIMEOUT_MS = 2_000L;
    private static final long PLAYER_TIMEOUT_MS = 3_000L;
    private static final long DISPLAY_MS = 150L;

    private static TxMediaPlayer open(final Path fixture, final HeadlessGFXEngine gfx) {
        final MRL mrl = MediaAPI.mrl(Fixtures.fileUri(fixture));
        assertTrue(mrl.await(MRL_TIMEOUT_MS));
        return new TxMediaPlayer(mrl, 0, gfx);
    }

    @Nested
    @DisplayName("Terminal states")
    class Terminal {

        @Test
        @DisplayName("stop() reports the transition once, rewinds to zero and keeps the media playable")
        void stopContract() {
            final HeadlessGFXEngine gfx = new HeadlessGFXEngine(false);
            final TxMediaPlayer player = open(Fixtures.GIF_ANIMATED, gfx);
            try {
                assertFalse(player.stop(), "nothing started yet");
                player.start();
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING));
                assertTrue(PlayerWait.awaitCondition(() -> player.time() > 0L, PLAYER_TIMEOUT_MS));
                assertTrue(player.stop());
                assertEquals(Status.STOPPED, player.status());
                assertEquals(0L, player.time());
                assertTrue(player.canPlay(), "a stopped player can start again");
                assertFalse(player.stop(), "already stopped");
                assertFalse(player.pause(), "nothing to pause");
            } finally {
                player.release();
                gfx.release();
            }
        }

        @Test
        @DisplayName("seek() after stop restarts paused at the target and togglePlay() resumes it")
        void seekAfterStopRestartsPaused() {
            final HeadlessGFXEngine gfx = new HeadlessGFXEngine(false);
            final TxMediaPlayer player = open(Fixtures.GIF_ANIMATED, gfx);
            try {
                player.start();
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING));
                final long target = Math.max(1L, player.duration() / 2);
                assertTrue(player.stop());
                assertTrue(player.seek(target));
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PAUSED));
                assertTrue(PlayerWait.awaitCondition(() -> player.time() >= target - 100L, PLAYER_TIMEOUT_MS),
                        "the restart lands at the scrubbed position");
                assertTrue(player.togglePlay());
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING));
            } finally {
                player.release();
                gfx.release();
            }
        }

        @Test
        @DisplayName("A timed still ends even when repeat is on; pause() refuses and togglePlay() restarts it")
        void endedStillRestartsOnToggle() {
            final HeadlessGFXEngine gfx = new HeadlessGFXEngine(false);
            final TxMediaPlayer player = open(Fixtures.JPEG_BASELINE, gfx);
            try {
                player.repeat(true);
                player.displayTime(DISPLAY_MS);
                player.start();
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.ENDED));
                assertFalse(player.pause(), "an ended still has nothing to pause");
                assertFalse(player.resume(), "nor to resume");
                assertFalse(player.nextFrame(), "a still has no other frame");
                assertTrue(player.togglePlay(), "toggling an ended player starts it again");
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING));
            } finally {
                player.release();
                gfx.release();
            }
        }
    }

    @Nested
    @DisplayName("Listener and queries")
    class Listener {

        @Test
        @DisplayName("Every transition reaches the listener and release() lands STOPPED")
        void listenerHearsEveryTransition() {
            final HeadlessGFXEngine gfx = new HeadlessGFXEngine(false);
            final TxMediaPlayer player = open(Fixtures.GIF_ANIMATED, gfx);
            final List<Status> seen = new CopyOnWriteArrayList<>();
            player.onStatus((prev, next) -> seen.add(next));
            try {
                assertFalse(player.canPlay());
                assertFalse(player.canSeek());
                player.start();
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING));
                assertTrue(player.canSeek());
                assertTrue(player.pause());
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PAUSED));
                assertFalse(player.pause(), "already paused");
                assertTrue(player.resume());
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING));
            } finally {
                player.release();
                gfx.release();
            }
            assertEquals(Status.STOPPED, player.status());
            assertEquals(Status.STOPPED, seen.get(seen.size() - 1), "release publishes the final stop");
            assertTrue(seen.indexOf(Status.LOADING) < seen.indexOf(Status.PLAYING), "intermediate states are published in order");
            assertTrue(seen.contains(Status.PAUSED));
            assertFalse(player.canPlay(), "released players are not playable");
        }
    }
}
