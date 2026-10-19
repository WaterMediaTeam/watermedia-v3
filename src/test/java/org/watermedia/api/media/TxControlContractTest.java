package org.watermedia.api.media;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.watermedia.api.media.MediaPlayer.Status;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
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
 * the media in both the streaming and the frame-texture mode, frame stepping is paused-only, and
 * the status listener hears every transition.
 */
@DisplayName("TxMediaPlayer control contract")
public class TxControlContractTest {

    private static final long MRL_TIMEOUT_MS = 2_000L;
    private static final long PLAYER_TIMEOUT_MS = 3_000L;
    private static final long DISPLAY_MS = 150L;
    private static final long SEEK_WINDOW_MS = 100L; // ONE GIF FRAME: A SCRUB LANDS ON THE FRAME THAT COVERS ITS TARGET
    private static final int TEXTURE_CAP = 16;

    @BeforeAll
    static void boot() { MediaBootstrap.client(); }

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

        @ParameterizedTest(name = "frame textures: {0}")
        @ValueSource(booleans = { false, true })
        @DisplayName("seek() after ENDED lands PAUSED at the target in both playback modes and togglePlay() resumes it")
        void seekAfterEndedRestartsPaused(final boolean textures) {
            final HeadlessGFXEngine gfx = new HeadlessGFXEngine(textures);
            final TxMediaPlayer player = open(Fixtures.GIF_ANIMATED, gfx);
            try {
                // A CAPPED SIZE KEEPS THE FRAME SET INSIDE THE TEXTURE BUDGET, SO THE PRELOADING ENGINE TAKES THE PASSIVE CLOCK
                player.scale(TEXTURE_CAP, TEXTURE_CAP);
                assertTrue(player.start());
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING));
                assertEquals(textures, gfx.preloadedFrames() != null, "the engine picks the playback mode under test");
                final long duration = player.duration();
                assertTrue(duration > 2 * SEEK_WINDOW_MS);
                assertTrue(player.seek(duration - SEEK_WINDOW_MS), "a live scrub runs the animation into its end");
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.ENDED));

                final long target = duration / 2;
                assertTrue(player.seek(target), "scrubbing a finished animation restarts it");
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PAUSED));
                // THE RESTART SHOWS THE FRAME THAT COVERS THE TARGET AND HOLDS THERE
                assertTrue(PlayerWait.awaitCondition(() -> player.time() <= target && player.time() > target - SEEK_WINDOW_MS, PLAYER_TIMEOUT_MS),
                        () -> "the restart lands on the scrubbed frame, not at " + player.time());
                assertTrue(player.canPlay());
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
            player.onStatus((p, prev, next) -> seen.add(next));
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
