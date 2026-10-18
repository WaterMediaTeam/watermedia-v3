package org.watermedia.api.media;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.watermedia.api.media.MediaPlayer.LodLevel;
import org.watermedia.api.media.MediaPlayer.Status;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Decode-pipeline smoke + upload-scaling test for {@link FFMediaPlayer}, driving a
 * local H.264 MP4 through a {@link HeadlessGFXEngine} (video only, no OpenAL).
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
    // A PAUSED RESTART LANDS ON THE KEYFRAME NEAREST THE TARGET, SO THE POSITION IS A WINDOW, NOT A POINT
    private static final long SEEK_LANDING_MS = 1_500L;

    @BeforeAll
    static void boot() {
        assumeTrue(MediaBootstrap.ffmpegAvailable(),
                "FFmpeg natives unavailable — skipping FFMediaPlayer tests");
    }

    // OPENS A VIDEO-ONLY FF PLAYER, APPLIES THE SCALING CONFIG, STARTS IT AND WAITS UNTIL
    // THE DECODE PIPELINE IS LIVE (gfx DIMENSIONS ARE CONFIGURED BY THEN).
    private static FFMediaPlayer open(final HeadlessGFXEngine gfx, final Path file, final Consumer<FFMediaPlayer> config) {
        final MRL mrl = MediaAPI.mrl(Fixtures.fileUri(file));
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
        final HeadlessGFXEngine gfx = new HeadlessGFXEngine();
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
            assertNotNull(gfx.format());
        } finally {
            player.stop();
            player.release();
        }
    }

    @Test
    @DisplayName("maxSize caps the upload below the native size")
    void testMaxSizeCapsUploadDimensions() {
        final HeadlessGFXEngine gfx = new HeadlessGFXEngine();
        final FFMediaPlayer player = open(gfx, Fixtures.MP4_H264, p -> p.scale(160, 90));
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
        final HeadlessGFXEngine gfx = new HeadlessGFXEngine();
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
        final HeadlessGFXEngine gfx = new HeadlessGFXEngine();
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

    @Test
    @DisplayName("A seek after ENDED restarts paused at the target; stop() from ENDED rewinds; release lands STOPPED")
    void seekAfterEndedRestartsPaused() {
        final HeadlessGFXEngine gfx = new HeadlessGFXEngine(false);
        final FFMediaPlayer player = open(gfx, Fixtures.MP4_H264, p -> {});
        final List<Status> seen = new CopyOnWriteArrayList<>();
        player.onStatus((prev, next) -> seen.add(next));
        try {
            assertTrue(player.fps() > 0f, "a decoded video stream reports its rate");
            assertTrue(player.canPlay());
            final long duration = player.duration();
            assertTrue(duration > 1_000L);
            assertTrue(player.seek(duration - 500L));
            assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.ENDED));
            assertTrue(player.canPlay(), "an ended media is still playable");
            assertFalse(player.pause(), "nothing to pause after the end");

            final long target = duration / 2;
            assertTrue(player.seek(target));
            assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PAUSED));
            assertTrue(Math.abs(player.time() - target) < SEEK_LANDING_MS, "the restart lands near the scrubbed position: " + player.time());

            assertTrue(player.togglePlay());
            assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PLAYING));
            assertTrue(player.stop());
            assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.STOPPED));
            assertEquals(0L, player.time());
            assertFalse(player.stop(), "already stopped");
        } finally {
            player.release();
        }
        assertEquals(Status.STOPPED, player.status());
        assertFalse(player.canPlay());
        assertTrue(seen.contains(Status.LOADING) && seen.contains(Status.PAUSED), "intermediate transitions are published: " + seen);
        gfx.release();
    }

    @Test
    @DisplayName("A still image through FFmpeg stays up until displayTime ends it, ignoring repeat")
    void stillImageDisplayTime() {
        // A PPM IS A TRUE SINGLE-FRAME SOURCE DECODED IN SOFTWARE (THE PNG FIXTURES ARE APNG STREAMS FOR
        // FFMPEG AND THE GRAY JPEG IS REFUSED BY HARDWARE DECODERS)
        final MRL mrl = MediaAPI.mrl(Fixtures.fileUri(Fixtures.NETPBM_PPM));
        assertTrue(mrl.await(MRL_TIMEOUT_MS));
        final HeadlessGFXEngine gfx = new HeadlessGFXEngine(false);
        final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, gfx, null);
        try {
            player.repeat(true);
            player.displayTime(400L);
            assertTrue(player.start());
            assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PLAYING));
            assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0, LOAD_TIMEOUT_MS));
            assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.ENDED));
            assertEquals(400L, player.duration());
            assertEquals(1L, gfx.uploadCount(), "the still is uploaded once, never looped");
        } finally {
            player.release();
        }
        gfx.release();
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = { "ENDED", "ERROR" })
    void terminalCallbackCannotFreeItsOwnPlaybackResources(final Status terminal, @TempDir final Path directory) throws Exception {
        final Path input = terminal == Status.ERROR ? directory.resolve("removed.mp4") : Fixtures.MP4_H264;
        if (terminal == Status.ERROR) Files.copy(Fixtures.MP4_H264, input);
        final MRL mrl = MediaAPI.mrl(Fixtures.fileUri(input));
        assertTrue(mrl.await(MRL_TIMEOUT_MS));
        assertEquals(MRL.Status.LOADED, mrl.status());
        // REMOVE ONLY THE TEMP COPY AFTER RESOLUTION TO EXERCISE A REAL DEMUX OPEN FAILURE.
        if (terminal == Status.ERROR) Files.delete(input);
        final HeadlessGFXEngine graphics = new HeadlessGFXEngine();
        final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, graphics, null);
        final CompletableFuture<Throwable> callback = new CompletableFuture<>();
        final Thread[] callbackThread = new Thread[1];
        final boolean[] retained = new boolean[1];
        player.onStatus((previous, next) -> {
            if (next != terminal || callback.isDone()) return;
            callbackThread[0] = Thread.currentThread();
            Throwable failure = null;
            try { player.release(); }
            catch (final Throwable rejected) { failure = rejected; }
            retained[0] = !graphics.released();
            callback.complete(failure);
        });
        try {
            assertTrue(player.start());
            if (terminal == Status.ENDED) {
                assertTrue(PlayerWait.awaitCondition(() -> graphics.uploadCount() > 0, LOAD_TIMEOUT_MS));
                assertTrue(player.duration() > 0);
                assertTrue(player.seek(Math.max(0, player.duration() - 1000)));
            }
            assertInstanceOf(IllegalStateException.class, callback.get(LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertNotSame(Thread.currentThread(), callbackThread[0]);
            assertTrue(retained[0], "Callback release must retain graphics");
            assertFalse(Thread.currentThread().isInterrupted(), "Only the playback thread was interrupted");
            player.onStatus(null);
            player.release();
            assertTrue(graphics.released());
        } finally {
            player.onStatus(null);
            player.release();
        }
    }
}
