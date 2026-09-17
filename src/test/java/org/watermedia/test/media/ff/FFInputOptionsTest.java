package org.watermedia.test.media.ff;

import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.engines.HeadlessGFXEngine;
import org.watermedia.api.media.players.FFMediaPlayer;
import org.watermedia.api.util.Slave;
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

class FFInputOptionsTest {
    @Test
    void mainAndSeparateAudioApplyTheConfiguredProbePolicy(@TempDir final Path directory) throws Exception {
        assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable");
        final Path audio = directory.resolve("slave.wav");
        try (final var input = new AudioInputStream(new ByteArrayInputStream(new byte[9600]),
                new AudioFormat(48_000, 16, 1, true, false), 4800)) {
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, audio.toFile());
        }
        final long analysis = WaterMediaConfig.media.ffmpeg.analyzeDuration;
        final int probe = WaterMediaConfig.media.ffmpeg.probeSize;
        final var mrl = MediaAPI.mrl(Fixtures.fileUri(Fixtures.MP4_H264));
        assertTrue(mrl.await(3000));
        final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, new HeadlessGFXEngine(), null);
        try {
            WaterMediaConfig.media.ffmpeg.analyzeDuration = 19_000;
            WaterMediaConfig.media.ffmpeg.probeSize = 23;
            assertTrue(player.startPaused());
            assertTrue(PlayerWait.awaitLoaded(player, 15_000));
            final var slave = FFMediaPlayer.class.getDeclaredMethod("initAudioSlave", Slave.class);
            slave.setAccessible(true);
            assertTrue((boolean) slave.invoke(player, new Slave("Separate audio", "en", audio.toUri())));
            for (final String name: new String[] { "formatContext", "slaveFormatContext" }) {
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
