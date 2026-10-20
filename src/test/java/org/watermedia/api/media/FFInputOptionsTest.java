package org.watermedia.api.media;

import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.global.avformat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.util.Track;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.bytedeco.ffmpeg.global.avutil.av_dict_free;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies that {@link FFMediaPlayer} applies the configured analyze duration and probe size to the main input it
 * opens and to a separate audio track, through the shared input options.
 */
@DisplayName("FFMediaPlayer input options")
public class FFInputOptionsTest {
    private long analysis;
    private int probe;

    @BeforeEach
    void configure() {
        assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable");
        this.analysis = WaterMediaConfig.media.ffmpeg.analyzeDuration;
        this.probe = WaterMediaConfig.media.ffmpeg.probeSize;
        WaterMediaConfig.media.ffmpeg.analyzeDuration = 19_000;
        WaterMediaConfig.media.ffmpeg.probeSize = 23;
    }

    @AfterEach
    void restore() {
        WaterMediaConfig.media.ffmpeg.analyzeDuration = this.analysis;
        WaterMediaConfig.media.ffmpeg.probeSize = this.probe;
    }

    @Test
    @DisplayName("The main input opens with the configured probe policy and the shared input options carry it")
    void mainInputAppliesTheConfiguredProbePolicy() throws Exception {
        final FFMediaPlayer player = player();
        final AVDictionary options = new AVDictionary();
        try {
            assertTrue(player.startPaused());
            assertTrue(PlayerWait.awaitLoaded(player, 15_000));
            // THE LIFECYCLE THREAD OPENED THE MAIN INPUT AND IS PARKED, SO ITS CONTEXT IS ONLY READ HERE
            assertProbePolicy((AVFormatContext) field(player, "formatContext"), "the main input");

            // BUILDING THE SHARED OPTIONS ONLY READS THE PLAYER'S SOURCE
            final Method apply = FFMediaPlayer.class.getDeclaredMethod("applyInputOptions", AVDictionary.class);
            apply.setAccessible(true);
            apply.invoke(player, options);
            assertEquals("19000000", av_dict_get(options, "analyzeduration", null, 0).value().getString(), "microseconds");
            assertEquals(String.valueOf(23L * 1_024 * 1_024), av_dict_get(options, "probesize", null, 0).value().getString(), "bytes");
        } finally {
            av_dict_free(options);
            player.release();
        }
    }

    @Test
    @DisplayName("A separate audio track opens with the same probe policy")
    void separateTrackAppliesTheConfiguredProbePolicy(@TempDir final Path directory) throws Exception {
        final Path audio = directory.resolve("track.wav");
        Fixtures.wav(audio, 48_000, 1, new byte[9_600]);
        final FFMediaPlayer player = player();
        try {
            // THE PLAYER NEVER STARTS, SO NO LIFECYCLE THREAD RACES THE TRACK FIELDS THE OPEN WRITES
            final Method init = FFMediaPlayer.class.getDeclaredMethod("initAudioTrack", Track.class);
            init.setAccessible(true);
            assertTrue((boolean) init.invoke(player, new Track("Track", "en", audio.toUri())), "the WAV track must open");
            final AVFormatContext track = (AVFormatContext) field(player, "trackFormatContext");
            assertProbePolicy(track, "the audio track");
            avformat.avformat_close_input(track);
        } finally {
            player.release();
        }
    }

    // UTILITY

    private static FFMediaPlayer player() {
        final MRL mrl = MediaAPI.mrl(Fixtures.fileUri(Fixtures.MP4_H264));
        assertTrue(mrl.await(3_000));
        return new FFMediaPlayer(mrl, 0, new HeadlessGFXEngine(), null);
    }

    private static Object field(final FFMediaPlayer player, final String name) throws ReflectiveOperationException {
        final Field field = FFMediaPlayer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(player);
    }

    private static void assertProbePolicy(final AVFormatContext context, final String what) {
        assertEquals(19_000_000, context.max_analyze_duration(), what + " analyzes for the configured duration");
        assertEquals(23L * 1_024 * 1_024, context.probesize(), what + " probes the configured size");
    }
}
