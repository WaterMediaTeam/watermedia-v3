package org.watermedia.api.media;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.media.MediaPlayer.Status;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Verifies the {@link TxMediaPlayer} streaming path on every animated GIF fixture: the animation loads seekable with a
 * known duration and the lifecycle thread keeps uploading frames over time.
 */
@DisplayName("TxMediaPlayer animated image")
public class TxAnimatedImageTest {

    private static final long MRL_TIMEOUT_MS = 2_000L;
    private static final long PLAYER_TIMEOUT_MS = 3_000L;
    private static final long PLAYBACK_OBSERVATION_MS = 1_500L;

    @BeforeAll
    static void boot() { MediaBootstrap.client(); }

    @TestFactory
    @DisplayName("Animated GIFs stream frames over time")
    Stream<DynamicTest> animatedGifs() {
        final List<Path> fixtures = List.of(
                Fixtures.GIF_ANIMATED,
                Fixtures.GIF_DIR.resolve("2.gif"),
                Fixtures.GIF_DIR.resolve("3.gif"));

        return fixtures.stream().map(path -> dynamicTest(path.getFileName().toString(), () -> {
            final MRL mrl = MediaAPI.mrl(Fixtures.fileUri(path));
            assertTrue(mrl.await(MRL_TIMEOUT_MS));

            // DISABLE FRAME-TEXTURE FAST PATH SO upload() IS CALLED PER FRAME — KEEPS uploadCount() OBSERVABLE.
            final HeadlessGFXEngine gfx = new HeadlessGFXEngine(false);
            final TxMediaPlayer player = new TxMediaPlayer(mrl, 0, gfx);
            try {
                assertTrue(player.start());
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING, Status.PAUSED));
                assertTrue(player.canSeek());
                assertTrue(player.duration() > 0L);

                // BASELINE UPLOAD COUNT THEN OBSERVE AT LEAST ONE MORE UPLOAD WHILE THE LIFECYCLE THREAD RUNS.
                final long baseline = gfx.uploadCount();
                assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > baseline, PLAYBACK_OBSERVATION_MS));
            } finally {
                player.release();
            }
        }));
    }
}
