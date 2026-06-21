package org.watermedia.test.media.ff;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.players.FFMediaPlayer;
import org.watermedia.api.media.players.MediaPlayer.LodLevel;
import org.watermedia.api.media.players.MediaPlayer.Status;
import org.watermedia.test.support.FakeGFXEngine;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.nio.file.Path;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Decode-pipeline smoke + upload-scaling test for {@link FFMediaPlayer}, driving a
 * local H.264 MP4 through a {@link FakeGFXEngine} (video only, no OpenAL).
 *
 * <p>The whole class is gated on FFmpeg actually loading: {@link MediaBootstrap}
 * boots WaterMedia once and, when the native binaries are missing (typical on
 * headless CI), every test is <b>skipped</b> rather than failed. Scaling is
 * resolved per frame, so {@code maxSize}/{@code lod} are asserted through the
 * dimensions the engine ends up configured with versus the native source size.
 */
@DisplayName("FFMediaPlayer mp4")
public class FFMediaPlayerTest {

    private static final long MRL_TIMEOUT_MS = 3000L;
    private static final long LOAD_TIMEOUT_MS = 15000L;
    private static final long PLAY_OBSERVE_MS = 8000L;

    @BeforeAll
    static void boot() {
        assumeTrue(MediaBootstrap.ffmpegAvailable(),
                "FFmpeg natives unavailable — skipping FFMediaPlayer tests");
    }

    // OPENS A VIDEO-ONLY FF PLAYER, APPLIES THE SCALING CONFIG, STARTS IT AND WAITS UNTIL
    // THE DECODE PIPELINE IS LIVE (gfx DIMENSIONS ARE CONFIGURED BY THEN).
    private static FFMediaPlayer open(final FakeGFXEngine gfx, final Path file, final Consumer<FFMediaPlayer> config) {
        final MRL mrl = MediaAPI.getMRL(Fixtures.fileUri(file));
        assertTrue(mrl.await(MRL_TIMEOUT_MS));

        final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, gfx, null);
        config.accept(player);
        player.start();
        assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PLAYING, Status.BUFFERING, Status.PAUSED));
        assertTrue(PlayerWait.awaitLoaded(player, LOAD_TIMEOUT_MS));
        return player;
    }

    @Test
    @DisplayName("Plays and uploads frames at the native size")
    void testPlaysAndUploadsNativeSize() {
        final FakeGFXEngine gfx = new FakeGFXEngine();
        final FFMediaPlayer player = open(gfx, Fixtures.MP4_H264, p -> {});
        try {
            assertTrue(player.canPlay());
            assertTrue(player.sourceWidth() > 0);
            assertTrue(player.sourceHeight() > 0);

            // NO SCALING REQUESTED — THE UPLOAD MATCHES THE NATIVE SOURCE
            assertEquals(player.sourceWidth(), player.width());
            assertEquals(player.sourceHeight(), player.height());

            // FRAMES MUST ACTUALLY FLOW TO THE ENGINE
            assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0, PLAY_OBSERVE_MS));
            assertNotNull(gfx.lastFormat());
        } finally {
            player.stop();
            player.release();
        }
    }

    @Test
    @DisplayName("maxSize caps the upload below the native size")
    void testMaxSizeCapsUploadDimensions() {
        final FakeGFXEngine gfx = new FakeGFXEngine();
        final FFMediaPlayer player = open(gfx, Fixtures.MP4_H264, p -> p.maxSize(160, 90));
        try {
            assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0, PLAY_OBSERVE_MS));

            final int sw = player.sourceWidth();
            final int sh = player.sourceHeight();
            // EACH AXIS IS CAPPED AT THE REQUESTED MAX (NEVER ABOVE THE SOURCE)
            assertTrue(player.width() > 0 && player.width() <= Math.min(sw, 160));
            assertTrue(player.height() > 0 && player.height() <= Math.min(sh, 90));
            // SOURCE SIZE IS PRESERVED
            assertEquals(sw, player.sourceWidth());
        } finally {
            player.stop();
            player.release();
        }
    }

    @Test
    @DisplayName("LOD reduces the upload dimensions")
    void testLodReducesUploadDimensions() {
        final FakeGFXEngine gfx = new FakeGFXEngine();
        final FFMediaPlayer player = open(gfx, Fixtures.MP4_H264, p -> p.lod(LodLevel.NEAR));
        try {
            assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0, PLAY_OBSERVE_MS));

            final int sw = player.sourceWidth();
            // NEAR = 50%, EVEN-CLAMPED: STAYS WITHIN A FEW PIXELS OF HALF THE SOURCE
            assertTrue(player.width() < sw);
            assertTrue(Math.abs(player.width() - sw / 2) <= 2,
                    "expected ~" + (sw / 2) + " but was " + player.width());
        } finally {
            player.stop();
            player.release();
        }
    }

    @Test
    @DisplayName("Hot LOD change shrinks the live upload")
    void testHotLodChangeShrinksUpload() {
        final FakeGFXEngine gfx = new FakeGFXEngine();
        final FFMediaPlayer player = open(gfx, Fixtures.MP4_H264, p -> {});
        try {
            assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0, PLAY_OBSERVE_MS));
            final int fullWidth = player.width();
            assertEquals(player.sourceWidth(), fullWidth);

            // CHANGE LOD MID-PLAYBACK — THE NEXT DECODED FRAME RESOLVES THE NEW TARGET
            player.lod(LodLevel.FAR_AWAY);
            assertTrue(PlayerWait.awaitCondition(() -> player.width() < fullWidth, PLAY_OBSERVE_MS),
                    "upload width should shrink after a hot LOD change");
        } finally {
            player.stop();
            player.release();
        }
    }
}
