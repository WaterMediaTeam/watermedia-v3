package org.watermedia.test.media.tx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.players.MediaPlayer.Status;
import org.watermedia.api.media.players.TxMediaPlayer;
import org.watermedia.test.support.FakeGFXEngine;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.PlayerWait;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Animated-image smoke test for {@link TxMediaPlayer}. For each animated GIF
 * fixture, drives the player through the standard load + play + pause cycle
 * and verifies the lifecycle thread uploads frames over time.
 */
@DisplayName("TxMediaPlayer animated image")
public class TxAnimatedImageTest {

    private static final long MRL_TIMEOUT_MS = 2000L;
    private static final long PLAYER_TIMEOUT_MS = 3000L;
    private static final long PLAYBACK_OBSERVATION_MS = 1500L;

    @TestFactory
    @DisplayName("Animated GIF load/play/pause cycle")
    Stream<DynamicTest> animatedGifs() {
        final List<Path> fixtures = List.of(
                Fixtures.GIF_DIR.resolve("1.gif"),
                Fixtures.GIF_DIR.resolve("2.gif"),
                Fixtures.GIF_DIR.resolve("3.gif"));

        return fixtures.stream().map(path -> dynamicTest(path.getFileName().toString(), () -> {
            final MRL mrl = MediaAPI.getMRL(Fixtures.fileUri(path));
            assertTrue(mrl.await(MRL_TIMEOUT_MS));

            // DISABLE FRAME-TEXTURE FAST PATH SO upload() IS CALLED PER FRAME — KEEPS uploadCount() OBSERVABLE.
            final FakeGFXEngine gfx = new FakeGFXEngine(false);
            final TxMediaPlayer player = new TxMediaPlayer(mrl, 0, gfx);
            assertNotNull(player);

            try {
                player.start();
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING, Status.PAUSED));
                assertTrue(PlayerWait.awaitLoaded(player, PLAYER_TIMEOUT_MS));

                assertTrue(player.canSeek());
                assertTrue(player.duration() > 0L);

                // BASELINE UPLOAD COUNT THEN OBSERVE AT LEAST ONE MORE UPLOAD WHILE THE LIFECYCLE THREAD RUNS.
                final long baseline = gfx.uploadCount();
                assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > baseline, PLAYBACK_OBSERVATION_MS));

                // PAUSE → PLAY ROUND-TRIP VIA THE TRIGGER LOOP.
                player.pause();
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PAUSED));
                player.resume();
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING));
            } finally {
                player.stop();
                player.release();
            }
        }));
    }
}
