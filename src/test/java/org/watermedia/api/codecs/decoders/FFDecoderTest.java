package org.watermedia.api.codecs.decoders;

import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.SampleType;
import org.watermedia.api.util.Track;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.Origin;
import org.watermedia.test.support.PlayerWait;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.bytedeco.ffmpeg.global.avutil.av_dict_free;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies {@link FFDecoder}: the configured probe policy reaches the main input and a separate audio track through the
 * shared input options, and an abort cuts every blocked open short however many decoders are alive without passing for
 * the end of the input.
 */
@DisplayName("FFDecoder")
public class FFDecoderTest {
    private static final long OPEN_TIMEOUT_MS = 15_000L;
    private static final long ABORT_MS = 5_000L; // HALF THE 10 s I/O TIMEOUT AN OPEN THE ABORT MISSED WAITS FOR

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    @BeforeEach
    void natives() {
        assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable");
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static FFDecoder decoder(final URI uri, final Recorder host) {
        return new FFDecoder("FFThread-Test", uri, null, new HttpHeaders(), true, false, new Linear(true), host);
    }

    private static void assertProbePolicy(final AVFormatContext context, final String what) {
        assertEquals(19_000_000, context.max_analyze_duration(), what + " analyzes for the configured duration");
        assertEquals(23L * 1_024 * 1_024, context.probesize(), what + " probes the configured size");
    }

    // WHETHER THE NAMED THREAD IS INSIDE FFMPEG'S PACKET READ
    private static boolean reading(final String name) {
        for (final Map.Entry<Thread, StackTraceElement[]> entry: Thread.getAllStackTraces().entrySet()) {
            if (!entry.getKey().getName().equals(name)) continue;
            for (final StackTraceElement frame: entry.getValue())
                if (frame.getMethodName().equals("av_read_frame")) return true;
        }
        return false;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Input options")
    class InputOptions {
        private long analysis;
        private int probe;

        @BeforeEach
        void configure() {
            this.analysis = WaterMediaConfig.codecs.ffmpeg.analyzeDuration;
            this.probe = WaterMediaConfig.codecs.ffmpeg.probeSize;
            WaterMediaConfig.codecs.ffmpeg.analyzeDuration = 19_000;
            WaterMediaConfig.codecs.ffmpeg.probeSize = 23;
        }

        @AfterEach
        void restore() {
            WaterMediaConfig.codecs.ffmpeg.analyzeDuration = this.analysis;
            WaterMediaConfig.codecs.ffmpeg.probeSize = this.probe;
        }

        @Test
        @DisplayName("The main input opens with the configured probe policy and the shared input options carry it")
        void mainInputAppliesTheConfiguredProbePolicy() throws Exception {
            final Recorder host = new Recorder();
            final FFDecoder decoder = decoder(Fixtures.fileUri(Fixtures.MP4_H264), host);
            final AVDictionary options = new AVDictionary();
            try {
                decoder.start();
                assertTrue(host.opened.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS), "the main input must open");
                assertNull(host.failure, "the main input must open without a failure");
                // THE DEMUXER PARKS ON THE HELD TIMELINE RIGHT AFTER THE OPEN, SO ITS CONTEXT IS ONLY READ HERE
                assertProbePolicy(decoder.formatContext, "the main input");

                // BUILDING THE SHARED OPTIONS ONLY READS THE DECODER'S HEADERS
                decoder.applyInputOptions(options);
                assertEquals("19000000", av_dict_get(options, "analyzeduration", null, 0).value().getString(), "microseconds");
                assertEquals(String.valueOf(23L * 1_024 * 1_024), av_dict_get(options, "probesize", null, 0).value().getString(), "bytes");
            } finally {
                av_dict_free(options);
                decoder.close();
            }
        }

        @Test
        @DisplayName("A separate audio track opens with the same probe policy")
        void separateTrackAppliesTheConfiguredProbePolicy(@TempDir final Path directory) throws Exception {
            final Path audio = directory.resolve("track.wav");
            Fixtures.wav(audio, 48_000, 1, new byte[9_600]);
            final FFDecoder decoder = decoder(Fixtures.fileUri(Fixtures.MP4_H264), new Recorder());
            try {
                // THE DECODER NEVER STARTS, SO NO DEMUX THREAD RACES THE TRACK FIELDS THE OPEN WRITES
                assertTrue(decoder.initAudioTrack(new Track("Track", "en", audio.toUri())), "the WAV track must open");
                assertProbePolicy(decoder.trackFormatContext, "the audio track");
            } finally {
                decoder.close();
            }
        }
    }

    @Nested
    @DisplayName("Abort")
    class Abort {
        @Test
        @DisplayName("An abort cuts every open blocked on a held origin short, with more decoders alive than JavaCPP binds callbacks for")
        void abortCutsEveryBlockedOpenShort() throws Exception {
            // JAVACPP BINDS AT MOST 10 LIVE INSTANCES OF ONE CALLBACK CLASS
            final int count = 12;
            final List<Recorder> hosts = new ArrayList<>();
            final List<FFDecoder> decoders = new ArrayList<>();
            try (final Origin origin = Origin.of(Fixtures.readAll(Fixtures.MP4_H264), "video/mp4")) {
                origin.hold();
                try {
                    for (int i = 0; i < count; i++) {
                        final Recorder host = new Recorder();
                        final FFDecoder decoder = decoder(origin.uri(".mp4"), host);
                        hosts.add(host);
                        decoders.add(decoder);
                        decoder.start();
                    }
                    assertTrue(PlayerWait.awaitCondition(() -> origin.requests() >= count, OPEN_TIMEOUT_MS), "every open must reach the held origin");

                    final long before = System.nanoTime();
                    for (final FFDecoder decoder: decoders) decoder.stop();
                    final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
                    assertTrue(elapsedMs < ABORT_MS, "the aborts must cut the opens short, they took " + elapsedMs + " ms");
                    for (final Recorder host: hosts) {
                        assertEquals(1L, host.opened.getCount(), "an aborted open never reports the input");
                        assertNull(host.failure, "an aborted open is not a failure");
                    }
                } finally {
                    origin.release();
                    for (final FFDecoder decoder: decoders) decoder.close();
                }
            }
        }

        @Test
        @DisplayName("An abort that cuts a read short never reports the end of the input to the timeline")
        void abortedReadIsNotTheEnd() throws Exception {
            final byte[] webm = Fixtures.webm(96, 64, 180, 30);
            // HALF THE FILE ON A CONNECTION THAT STAYS OPEN: THE DEMUXER WAITS INSIDE av_read_frame FOR THE REST
            try (final Origin origin = Origin.endless(Arrays.copyOf(webm, webm.length / 2), "video/webm")) {
                final Recorder host = new Recorder();
                final Linear timeline = new Linear(false);
                final FFDecoder decoder = new FFDecoder("FFThread-Read", origin.uri(".webm"), null, new HttpHeaders(), true, false, timeline, host);
                try {
                    decoder.start();
                    assertTrue(host.opened.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS), "the partial input must open");
                    assertNull(host.failure, "the partial input must open without a failure");
                    assertTrue(PlayerWait.awaitCondition(() -> reading("FFThread-Read-demux"), OPEN_TIMEOUT_MS), "the demuxer must wait for the rest of the input");
                    decoder.stop();
                    assertFalse(timeline.finished, "an aborted read is the end of the decoder, not of the input");
                } finally {
                    decoder.close();
                }
            }
        }
    }

    // A TIMELINE WITHOUT SEEKS THAT RECORDS WHETHER THE DEMUXER REPORTED THE END OF THE INPUT;
    // A HELD ONE STAYS PAUSED, SO THE DEMUXER PARKS ONCE THE INPUT IS OPEN
    private static final class Linear implements FFDecoder.Timeline {
        private final boolean held;
        private volatile boolean finished;

        private Linear(final boolean held) { this.held = held; }

        @Override public FFDecoder.Seek consumeSeek() { return null; }
        @Override public boolean seekPending() { return false; }
        @Override public boolean holding() { return this.held; }
        @Override public void awaitChange(final long timeoutMs) throws InterruptedException { Thread.sleep(timeoutMs > 0 ? timeoutMs : 50); }
        @Override public void serial(final int serial) {}
        @Override public void start() {}
        @Override public void signalDemuxResumed() {}
        @Override public void signalDemuxFinished() { this.finished = true; }
        @Override public double frameDurationSec() { return 1.0 / 30.0; }
    }

    // RECORDS THE OPEN OR ITS FAILURE; NOTHING IS CACHED, SO EVERY INPUT OPENS WHERE IT LIVES
    private static final class Recorder implements FFDecoder.Host {
        private final CountDownLatch opened = new CountDownLatch(1);
        private volatile Throwable failure;

        @Override public Path cached(final URI uri) { return null; }
        @Override public FFDecoder.Device vulkan(final int codecId) { return null; }
        @Override public FFDecoder.Pcm audio(final SampleType type, final int channels, final int rate) { return null; }
        @Override public void duration(final long ms) {}
        @Override public void opened(final FFDecoder decoder) { this.opened.countDown(); }

        @Override
        public void failed(final Throwable cause) {
            this.failure = cause;
            this.opened.countDown();
        }
    }
}
