package org.watermedia.test.media.tx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.players.MediaPlayer.LodLevel;
import org.watermedia.api.media.players.MediaPlayer.Status;
import org.watermedia.api.media.players.TxMediaPlayer;
import org.watermedia.test.support.FakeGFXEngine;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.PlayerWait;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the {@link TxMediaPlayer} upload-scaling pipeline ({@code maxSize} +
 * {@link LodLevel}) end to end against a known-size fixture, asserting on the
 * dimensions the engine is actually configured with ({@code width()}/{@code height()})
 * versus the untouched native size ({@code sourceWidth()}/{@code sourceHeight()}).
 *
 * <p>The static PNG fixture is {@code 150x150}, which keeps the expected results
 * easy to verify by hand. Scaling is applied <b>before</b> {@code start()} because
 * static images bake their upload size at preparation time.
 */
@DisplayName("TxMediaPlayer scaling")
public class TxScalingTest {

    private static final long MRL_TIMEOUT_MS = 2000L;
    private static final long PLAYER_TIMEOUT_MS = 3000L;
    private static final long HOT_CHANGE_MS = 3000L;

    private static final int NATIVE = 150; // src/test/resources/png/2.png IS 150x150

    // BUILDS A STATIC-IMAGE PLAYER, APPLIES THE GIVEN SCALING CONFIG, STARTS IT AND
    // WAITS UNTIL THE FIRST FRAME IS LIVE SO width()/height() REFLECT THE UPLOAD SIZE.
    private static TxMediaPlayer startStatic(final FakeGFXEngine gfx, final Consumer<TxMediaPlayer> config) {
        final MRL mrl = MediaAPI.getMRL(Fixtures.fileUri(Fixtures.PNG_STATIC));
        assertTrue(mrl.await(MRL_TIMEOUT_MS));

        final TxMediaPlayer player = new TxMediaPlayer(mrl, 0, gfx);
        config.accept(player);
        player.start();
        assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING, Status.PAUSED));
        assertTrue(PlayerWait.awaitLoaded(player, PLAYER_TIMEOUT_MS));
        return player;
    }

    @Test
    @DisplayName("No scaling uploads the native size")
    void testNoScalingUploadsNativeSize() {
        final FakeGFXEngine gfx = new FakeGFXEngine();
        final TxMediaPlayer player = startStatic(gfx, p -> {});
        try {
            assertEquals(NATIVE, player.sourceWidth());
            assertEquals(NATIVE, player.sourceHeight());
            assertEquals(NATIVE, player.width());
            assertEquals(NATIVE, player.height());
            assertEquals(LodLevel.MAX, player.lod());
        } finally {
            player.stop();
            player.release();
        }
    }

    @Test
    @DisplayName("maxSize caps the upload below the native size")
    void testMaxSizeCapsBelowNative() {
        final FakeGFXEngine gfx = new FakeGFXEngine();
        final TxMediaPlayer player = startStatic(gfx, p -> p.maxSize(100, 100));
        try {
            // min(150, 100) = 100 ON EACH AXIS; SOURCE SIZE IS LEFT UNTOUCHED
            assertEquals(100, player.width());
            assertEquals(100, player.height());
            assertEquals(NATIVE, player.sourceWidth());
            assertEquals(100, player.maxWidth());
        } finally {
            player.stop();
            player.release();
        }
    }

    @Test
    @DisplayName("maxSize above the native size never upscales")
    void testMaxSizeNeverUpscales() {
        final FakeGFXEngine gfx = new FakeGFXEngine();
        final TxMediaPlayer player = startStatic(gfx, p -> p.maxSize(4000, 4000));
        try {
            assertEquals(NATIVE, player.width());
            assertEquals(NATIVE, player.height());
        } finally {
            player.stop();
            player.release();
        }
    }

    @Test
    @DisplayName("maxSize 0 means unlimited")
    void testZeroMaxSizeIsUnlimited() {
        final FakeGFXEngine gfx = new FakeGFXEngine();
        final TxMediaPlayer player = startStatic(gfx, p -> p.maxSize(0, 0));
        try {
            assertEquals(NATIVE, player.width());
            assertEquals(NATIVE, player.height());
        } finally {
            player.stop();
            player.release();
        }
    }

    @Test
    @DisplayName("LOD halves the dimensions")
    void testLodHalvesDimensions() {
        final FakeGFXEngine gfx = new FakeGFXEngine();
        final TxMediaPlayer player = startStatic(gfx, p -> p.lod(LodLevel.NEAR));
        try {
            // 150 * 50% = 75, EVEN-CLAMPED DOWN TO 74 FOR CHROMA ALIGNMENT
            assertEquals(74, player.width());
            assertEquals(74, player.height());
        } finally {
            player.stop();
            player.release();
        }
    }

    @Test
    @DisplayName("maxSize and LOD compose")
    void testMaxSizeAndLodCompose() {
        final FakeGFXEngine gfx = new FakeGFXEngine();
        final TxMediaPlayer player = startStatic(gfx, p -> {
            p.maxSize(100, 100);
            p.lod(LodLevel.NEAR);
        });
        try {
            // min(150, 100) = 100, THEN * 50% = 50
            assertEquals(50, player.width());
            assertEquals(50, player.height());
        } finally {
            player.stop();
            player.release();
        }
    }

    @Test
    @DisplayName("Hot LOD change shrinks the live upload (streaming GIF)")
    void testHotLodChangeShrinksUpload() {
        final MRL mrl = MediaAPI.getMRL(Fixtures.fileUri(Fixtures.GIF_ANIMATED));
        assertTrue(mrl.await(MRL_TIMEOUT_MS));

        // FakeGFXEngine(false) DISABLES THE FRAME-TEXTURE FAST PATH SO THE GIF RUNS THE
        // STREAMING LIFECYCLE (MODE 3), WHERE applyTarget() RE-RUNS EVERY LOOP ITERATION.
        final FakeGFXEngine gfx = new FakeGFXEngine(false);
        final TxMediaPlayer player = new TxMediaPlayer(mrl, 0, gfx);
        try {
            player.start();
            assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING, Status.PAUSED));
            assertTrue(PlayerWait.awaitLoaded(player, PLAYER_TIMEOUT_MS));

            final int fullWidth = player.width();
            assertTrue(fullWidth > 0);
            assertEquals(player.sourceWidth(), fullWidth);

            // CHANGE LOD ON THE FLY — THE STREAMING LOOP MUST PICK IT UP AND UPLOAD SMALLER
            player.lod(LodLevel.FAR_AWAY);
            assertTrue(PlayerWait.awaitCondition(() -> player.width() < fullWidth, HOT_CHANGE_MS),
                    "upload width should shrink after a hot LOD change");
        } finally {
            player.stop();
            player.release();
        }
    }

    @Nested
    @DisplayName("Argument validation")
    class Validation {

        private TxMediaPlayer newPlayer() {
            final MRL mrl = MediaAPI.getMRL(Fixtures.fileUri(Fixtures.PNG_STATIC));
            assertTrue(mrl.await(MRL_TIMEOUT_MS));
            return new TxMediaPlayer(mrl, 0, new FakeGFXEngine());
        }

        @Test
        @DisplayName("Negative maxSize is rejected")
        void testNegativeMaxSizeRejected() {
            final TxMediaPlayer player = newPlayer();
            assertThrows(IllegalArgumentException.class, () -> player.maxSize(-1, 10));
            assertThrows(IllegalArgumentException.class, () -> player.maxSize(10, -1));
        }

        @Test
        @DisplayName("Null LOD is rejected")
        void testNullLodRejected() {
            final TxMediaPlayer player = newPlayer();
            assertThrows(IllegalArgumentException.class, () -> player.lod(null));
        }
    }
}
