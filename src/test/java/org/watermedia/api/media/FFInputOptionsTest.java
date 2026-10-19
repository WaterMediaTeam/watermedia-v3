package org.watermedia.api.media;

import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.util.Track;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies that {@link FFMediaPlayer} applies the configured analyze duration and probe size to the main input and to
 * a separate audio track alike.
 */
@DisplayName("FFMediaPlayer input options")
public class FFInputOptionsTest {
    @Test
    @DisplayName("The main input and a separate audio track share the configured probe policy")
    void mainAndSeparateAudioApplyTheConfiguredProbePolicy(@TempDir final Path directory) throws Exception {
        assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable");
        final Path audio = directory.resolve("slave.wav");
        try (final var input = new AudioInputStream(new ByteArrayInputStream(new byte[9_600]),
                new AudioFormat(48_000, 16, 1, true, false), 4_800)) {
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, audio.toFile());
        }
        final long analysis = WaterMediaConfig.media.ffmpeg.analyzeDuration;
        final int probe = WaterMediaConfig.media.ffmpeg.probeSize;
        final var mrl = MediaAPI.mrl(Fixtures.fileUri(Fixtures.MP4_H264));
        assertTrue(mrl.await(3_000));
        final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, new HeadlessGFXEngine(), null);
        try {
            WaterMediaConfig.media.ffmpeg.analyzeDuration = 19_000;
            WaterMediaConfig.media.ffmpeg.probeSize = 23;
            assertTrue(player.startPaused());
            assertTrue(PlayerWait.awaitLoaded(player, 15_000));
            final var track = FFMediaPlayer.class.getDeclaredMethod("initAudioTrack", Track.class);
            track.setAccessible(true);
            assertTrue((boolean) track.invoke(player, new Track("Separate audio", "en", audio.toUri())));
            for (final String name: new String[] { "formatContext", "trackFormatContext" }) {
                final var field = FFMediaPlayer.class.getDeclaredField(name);
                field.setAccessible(true);
                final AVFormatContext context = (AVFormatContext) field.get(player);
                assertEquals(19_000_000, context.max_analyze_duration(), name);
                assertEquals(23L * 1024 * 1024, context.probesize(), name);
            }
        } finally {
            player.release();
            WaterMediaConfig.media.ffmpeg.analyzeDuration = analysis;
            WaterMediaConfig.media.ffmpeg.probeSize = probe;
        }
    }
}
