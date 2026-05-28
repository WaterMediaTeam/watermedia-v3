package org.watermedia.test.codecs;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.test.support.Fixtures;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Diagnostic decoder benchmarks.
 *
 * <p>These are deliberately observational rather than wall-clock assertions. They keep
 * the benchmark loop close to the public streaming API and report the most useful split
 * points: reader creation, first-frame readiness, full drain, and how far the source
 * buffer has advanced.
 *
 * <p>Results are appended to a TSV report at
 * {@code build/reports/decoder-performance.tsv} after the factory finishes.
 */
public class PerformanceTest {
    private static final int WARMUP_ITERATIONS = 2;
    private static final int MEASURED_ITERATIONS = 5;
    private static final Path REPORT_PATH = Path.of("build", "reports", "decoder-performance.tsv");
    private static final List<String> REPORT_LINES = new ArrayList<>();

    private static final List<BenchmarkCase> CASES = List.of(
            new BenchmarkCase("JPEG_GRAY", Fixtures.JPEG_DIR.resolve("1.jpg")),
            new BenchmarkCase("JPEG_420", Fixtures.JPEG_DIR.resolve("2.jpg")),
            new BenchmarkCase("JPEG_422", Fixtures.JPEG_DIR.resolve("3.jpg")),
            new BenchmarkCase("JPEG_444", Fixtures.JPEG_DIR.resolve("4.jpg")),
            new BenchmarkCase("JPEG_444_APPX", Fixtures.JPEG_DIR.resolve("5.jpg")),
            new BenchmarkCase("JPEG_420_PROG", Fixtures.JPEG_DIR.resolve("6.jpg")),
            new BenchmarkCase("GIF", Fixtures.GIF_DIR.resolve("1.gif")),
            new BenchmarkCase("GIF ALPHA", Fixtures.GIF_DIR.resolve("2.gif")),
            new BenchmarkCase("GIF LARGE", Fixtures.GIF_DIR.resolve("3.gif")),
            new BenchmarkCase("PNG", Fixtures.PNG_DIR.resolve("1.png")),
            new BenchmarkCase("WEBP_LOSSLESS", Fixtures.WEBP_LOSSLESS_DIR.resolve("1.webp")),
            new BenchmarkCase("WEBP_LOSSY_SMALL", Fixtures.WEBP_LOSSY_DIR.resolve("1.webp")),
            new BenchmarkCase("WEBP_LOSSY_MED", Fixtures.WEBP_LOSSY_DIR.resolve("3.webp")),
            new BenchmarkCase("WEBP_LOSSY_LARGE", Fixtures.WEBP_LOSSY_DIR.resolve("6.webp")),
            new BenchmarkCase("WEBP_ANIMATED", Fixtures.WEBP_ANIMATED_DIR.resolve("1.webp")),
            new BenchmarkCase("NETPBM_PPM", Fixtures.NETPBM_DIR.resolve("test.ppm")),
            new BenchmarkCase("NETPBM_PAM_RGBA", Fixtures.NETPBM_DIR.resolve("rgba.pam"))
    );

    @TestFactory
    Iterable<DynamicTest> measureDecoderPipelines() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final BenchmarkCase benchmarkCase: CASES) {
            tests.add(dynamicTest("decoder performance [" + benchmarkCase.name + "]", () -> {
                final byte[] source = Files.readAllBytes(benchmarkCase.fixture);
                assertTrue(source.length > 0, "Fixture must not be empty: " + benchmarkCase.fixture);

                for (int i = 0; i < WARMUP_ITERATIONS; i++) runSample(source);

                final BenchmarkSample[] samples = new BenchmarkSample[MEASURED_ITERATIONS];
                for (int i = 0; i < MEASURED_ITERATIONS; i++) samples[i] = runSample(source);

                final BenchmarkSample exemplar = samples[0];
                final BenchmarkSummary summary = new BenchmarkSummary(
                        benchmarkCase.name,
                        benchmarkCase.fixture.toString().replace('\\', '/'),
                        source.length,
                        exemplar.width,
                        exemplar.height,
                        exemplar.frames,
                        exemplar.outputBytes,
                        medianMicros(samples, Metric.OPEN),
                        medianMicros(samples, Metric.FIRST_FRAME),
                        medianMicros(samples, Metric.ALL_FRAMES),
                        exemplar.bytesAfterOpen,
                        exemplar.bytesAfterFirst,
                        exemplar.bytesAfterAll,
                        exemplar.duration,
                        exemplar.averageFps,
                        exemplar.variableFrameRate
                );

                REPORT_LINES.add(summary.toTsv());
                System.out.println(summary.toHumanLine());
            }));
        }
        return tests;
    }

    @AfterAll
    static void writePerformanceReport() throws IOException {
        REPORT_LINES.sort(Comparator.naturalOrder());
        final List<String> lines = new ArrayList<>(REPORT_LINES.size() + 1);
        lines.add("decoder\tfixture\tbytes\twidth\theight\tframes\toutput_bytes\topen_us\tfirst_frame_us\tall_frames_us\tbytes_after_open\tbytes_after_first\tbytes_after_all\tduration_ms\taverage_fps\tvariable_frame_rate");
        lines.addAll(REPORT_LINES);
        Files.createDirectories(REPORT_PATH.getParent());
        Files.write(REPORT_PATH, lines);
    }

    private static BenchmarkSample runSample(final byte[] source) throws IOException {
        final ByteBuffer input = ByteBuffer.wrap(source);
        final long start = System.nanoTime();

        try (final ImageReader reader = CodecsAPI.decodeImage(input)) {
            final long afterOpen = System.nanoTime();
            final int bytesAfterOpen = input.position();

            assertTrue(reader.hasNext(), "Reader should produce a first frame");
            ByteBuffer frame = reader.next();
            final long afterFirstFrame = System.nanoTime();
            final int bytesAfterFirst = input.position();

            int frames = 1;
            long outputBytes = assertDirectFrame(frame);
            while (reader.hasNext()) {
                frame = reader.next();
                frames++;
                outputBytes += assertDirectFrame(frame);
            }

            final long afterAllFrames = System.nanoTime();
            return new BenchmarkSample(
                    reader.width(),
                    reader.height(),
                    frames,
                    outputBytes,
                    afterOpen - start,
                    afterFirstFrame - afterOpen,
                    afterAllFrames - start,
                    bytesAfterOpen,
                    bytesAfterFirst,
                    input.position(),
                    reader.duration(),
                    reader.averageFps(),
                    reader.variableFrameRate()
            );
        }
    }

    private static int assertDirectFrame(final ByteBuffer frame) {
        assertNotNull(frame, "Decoded frame buffer must not be null");
        assertTrue(frame.isDirect(), "Decoded frame buffer must be direct");
        assertTrue(frame.remaining() > 0, "Decoded frame buffer must contain pixels");
        return frame.remaining();
    }

    private static long medianMicros(final BenchmarkSample[] samples, final Metric metric) {
        final long[] nanos = new long[samples.length];
        for (int i = 0; i < samples.length; i++) nanos[i] = metric.nanos(samples[i]);
        Arrays.sort(nanos);
        return nanos[nanos.length / 2] / 1_000L;
    }

    private record BenchmarkCase(String name, Path fixture) {}

    private record BenchmarkSample(
            int width,
            int height,
            int frames,
            long outputBytes,
            long openNanos,
            long firstFrameNanos,
            long allFramesNanos,
            int bytesAfterOpen,
            int bytesAfterFirst,
            int bytesAfterAll,
            long duration,
            float averageFps,
            boolean variableFrameRate
    ) {}

    private record BenchmarkSummary(
            String decoder,
            String fixture,
            int sourceBytes,
            int width,
            int height,
            int frames,
            long outputBytes,
            long openMicros,
            long firstFrameMicros,
            long allFramesMicros,
            int bytesAfterOpen,
            int bytesAfterFirst,
            int bytesAfterAll,
            long duration,
            float averageFps,
            boolean variableFrameRate
    ) {
        String toTsv() {
            return String.join("\t",
                    this.decoder,
                    this.fixture,
                    Integer.toString(this.sourceBytes),
                    Integer.toString(this.width),
                    Integer.toString(this.height),
                    Integer.toString(this.frames),
                    Long.toString(this.outputBytes),
                    Long.toString(this.openMicros),
                    Long.toString(this.firstFrameMicros),
                    Long.toString(this.allFramesMicros),
                    Integer.toString(this.bytesAfterOpen),
                    Integer.toString(this.bytesAfterFirst),
                    Integer.toString(this.bytesAfterAll),
                    Long.toString(this.duration),
                    Float.toString(this.averageFps),
                    Boolean.toString(this.variableFrameRate)
            );
        }

        String toHumanLine() {
            return "DECODER_PERF decoder=" + this.decoder
                    + " fixture=" + this.fixture
                    + " size=" + this.sourceBytes + "B"
                    + " image=" + this.width + "x" + this.height
                    + " frames=" + this.frames
                    + " open=" + this.openMicros + "us"
                    + " first=" + this.firstFrameMicros + "us"
                    + " all=" + this.allFramesMicros + "us"
                    + " bytes(open/first/all)=" + this.bytesAfterOpen + "/" + this.bytesAfterFirst + "/" + this.bytesAfterAll
                    + " duration=" + this.duration + "ms"
                    + " fps=" + this.averageFps
                    + " vfr=" + this.variableFrameRate;
        }
    }

    private enum Metric {
        OPEN {
            @Override long nanos(final BenchmarkSample sample) { return sample.openNanos; }
        },
        FIRST_FRAME {
            @Override long nanos(final BenchmarkSample sample) { return sample.firstFrameNanos; }
        },
        ALL_FRAMES {
            @Override long nanos(final BenchmarkSample sample) { return sample.allFramesNanos; }
        };

        abstract long nanos(BenchmarkSample sample);
    }
}
