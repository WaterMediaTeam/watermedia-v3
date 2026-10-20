package org.watermedia.api.codecs.writers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.media.FFMediaPlayer;
import org.watermedia.api.media.HeadlessGFXEngine;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.MediaPlayer.Status;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies {@link WebMWriter}: argument and state checks, then a round trip that encodes BGRA frames to VP9-in-WebM,
 * checks the container is a sniffable Matroska stream and decodes it back through {@link FFMediaPlayer} to prove the
 * VP9 bitstream is valid. The FFmpeg-backed cases are skipped (not failed) when the native binaries are absent,
 * matching {@code FFMediaPlayerTest}.
 */
@DisplayName("WebMWriter VP9")
public class WebMWriterTest {

    @Nested
    @DisplayName("Arguments")
    class Arguments {

        @Test
        @DisplayName("Constructor rejects invalid fps, crf and block-compressed pixel formats before looking for FFmpeg")
        void rejectsInvalidArgs() {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertThrows(IllegalArgumentException.class, () -> new WebMWriter(out, 16, 16, PixelFormat.BGRA, 0, 31));
            assertThrows(IllegalArgumentException.class, () -> new WebMWriter(out, 16, 16, PixelFormat.BGRA, 30, 64));
            assertThrows(IllegalArgumentException.class, () -> new WebMWriter(out, 16, 16, PixelFormat.BGRA, 30, -1));
            assertThrows(IllegalArgumentException.class, () -> new WebMWriter(out, 16, 16, PixelFormat.BC7));
        }

        @Test
        @DisplayName("A null or wrongly sized frame and a negative delay are argument errors, a zero delay is accepted and writing after close is a state error")
        void rejectsMisuse() throws IOException {
            assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable — skipping");
            final WebMWriter writer = new WebMWriter(new ByteArrayOutputStream(), 16, 16, PixelFormat.BGRA);
            final ByteBuffer frame = ByteBuffer.allocateDirect(16 * 16 * 4);
            try (writer) {
                assertThrows(IllegalArgumentException.class, () -> writer.writeFrame(ByteBuffer.allocateDirect(16 * 16 * 4 - 1)));
                assertThrows(IllegalArgumentException.class, () -> writer.writeFrame(frame.duplicate(), -1L));
                assertThrows(IllegalArgumentException.class, () -> writer.writeFrame(null));
                writer.writeFrame(frame.duplicate(), 0L);
                assertEquals(1, writer.frameCount());
            }
            assertThrows(IllegalStateException.class, () -> writer.writeFrame(frame.duplicate()));
        }
    }

    @Nested
    @DisplayName("Round trip")
    class RoundTrip {

        @Test
        @DisplayName("The encoded WebM is a sniffable Matroska stream that decodes back through FFMediaPlayer")
        void decodesRoundTrip() throws Exception {
            assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable — skipping");
            final byte[] webm = Fixtures.webm(96, 64, 20, 30, 32);
            // EBML / MATROSKA MAGIC (1A 45 DF A3), WHICH THE PROJECT'S OWN SNIFFER CLASSIFIES AS VIDEO
            assertArrayEquals(new byte[] { 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3 }, Arrays.copyOf(webm, 4));
            assertEquals(MediaType.VIDEO, CodecsAPI.sniff(ByteBuffer.wrap(webm)));
            final Path file = Files.createTempFile("wm-webm-", ".webm");
            file.toFile().deleteOnExit();
            Files.write(file, webm);

            final MRL mrl = MediaAPI.mrl(Fixtures.fileUri(file));
            assertTrue(mrl.await(3000L));
            final HeadlessGFXEngine gfx = MediaAPI.headlessEngine();
            final FFMediaPlayer player = (FFMediaPlayer) MediaAPI.player(mrl, () -> gfx, () -> null);
            try {
                player.start();
                assertTrue(PlayerWait.awaitStatus(player, 15_000L, Status.PLAYING, Status.BUFFERING, Status.PAUSED, Status.ENDED));
                assertTrue(PlayerWait.awaitLoaded(player, 15_000L));
                // THE CONTAINER CARRIES ITS DURATION UP FRONT (MATROSKA Duration + Cues), SO A CLIENT CAN REPORT
                // LENGTH AND SEEK RIGHT AFTER LOAD, NOT ONLY AFTER READING TO THE END
                assertTrue(player.duration() > 0, "duration must be known right after load");
                // Fixtures.webm() WRITES EVERY FRAME WITH A ZERO DELAY, SO EACH ONE ADVANCES BY THE 30 FPS FRAME DURATION
                final long expected = 20 * 1_000L / 30;
                assertTrue(Math.abs(player.duration() - expected) <= 2 * 1_000L / 30,
                        "duration " + player.duration() + " ms, expected about " + expected + " ms");
                assertTrue(player.canSeek(), "a finite WebM with a known duration must be seekable");
                // DECODED VP9 FRAMES MUST ACTUALLY REACH THE ENGINE
                assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0, 8000L), "decoded VP9 frames should reach the engine");
                assertEquals(96, player.sourceWidth());
                assertEquals(64, player.sourceHeight());
            } finally {
                player.stop();
                player.release();
            }
        }
    }
}
