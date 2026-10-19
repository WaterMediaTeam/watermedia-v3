package org.watermedia.test.support;

import org.watermedia.api.codecs.writers.WebMWriter;
import org.watermedia.api.util.PixelFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Central catalog of on-disk test fixtures. Every test that needs a sample
 * file pulls it from here so paths stay consistent and renames stay cheap.
 *
 * <p>All paths are resolved against the working directory (Gradle runs tests
 * from the module root, so {@code src/test/resources/...} works without
 * classpath tricks).
 */
public final class Fixtures {

    public static final Path RESOURCES = Path.of("src", "test", "resources");
    public static final Path PNG_DIR = RESOURCES.resolve("png");
    public static final Path JPEG_DIR = RESOURCES.resolve("jpeg");
    public static final Path GIF_DIR = RESOURCES.resolve("gif");
    public static final Path WEBP_LOSSLESS_DIR = RESOURCES.resolve("webp").resolve("lossless");
    public static final Path WEBP_LOSSY_DIR = RESOURCES.resolve("webp").resolve("lossy");
    public static final Path WEBP_ANIMATED_DIR = RESOURCES.resolve("webp").resolve("animated");
    public static final Path NETPBM_DIR = RESOURCES.resolve("netpbm");
    public static final Path SVG_DIR = RESOURCES.resolve("svg");
    public static final Path MP4_DIR = RESOURCES.resolve("mp4");
    /** Hostile corpus driving {@code Pentesting}; regenerate with {@link MaliciousImages}. */
    public static final Path PENTEST_DIR = RESOURCES.resolve("pentest");

    public static final Path MP4_H264 = MP4_DIR.resolve("fibonaccisongh264.mp4");
    public static final Path PNG_STATIC = PNG_DIR.resolve("2.png");
    /** Real-world static PNG with trailing garbage after IEND (waterframes field-test upload). */
    public static final Path PNG_BROKEN = PNG_DIR.resolve("broken.png");
    public static final Path PNG_ANIMATED = PNG_DIR.resolve("1.png");
    public static final Path GIF_ANIMATED = GIF_DIR.resolve("1.gif");
    public static final Path WEBP_ANIMATED = WEBP_ANIMATED_DIR.resolve("1.webp");
    public static final Path WEBP_LOSSLESS = WEBP_LOSSLESS_DIR.resolve("1.webp");
    public static final Path JPEG_BASELINE = JPEG_DIR.resolve("1.jpg");
    public static final Path NETPBM_PPM = NETPBM_DIR.resolve("test.ppm");

    private Fixtures() {}

    /** Reads a fixture file, wrapping IO failures as unchecked for tidy {@code @TestFactory} sites. */
    public static byte[] readAll(final Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read fixture " + path, e);
        }
    }

    /** Returns a {@code file://} URI for a fixture, suitable for {@code MRL}/MediaAPI. */
    public static URI fileUri(final Path path) {
        return path.toAbsolutePath().toUri();
    }

    /** Encodes a moving BGRA gradient into a VP9 WebM through {@link WebMWriter}; needs the FFmpeg natives. */
    public static byte[] webm(final int width, final int height, final int frames, final int fps, final int crf) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (final WebMWriter writer = new WebMWriter(out, width, height, PixelFormat.BGRA, fps, crf)) {
            final ByteBuffer frame = ByteBuffer.allocateDirect(width * height * 4);
            // CONSECUTIVE FRAMES DIFFER SO VP9 HAS REAL CONTENT TO CODE
            for (int f = 0; f < frames; f++) {
                frame.clear();
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    frame.put((byte) ((x + f * 4) & 0xFF));      // B
                    frame.put((byte) ((y + f * 4) & 0xFF));      // G
                    frame.put((byte) ((x + y + f * 8) & 0xFF));  // R
                    frame.put((byte) 0xFF);                       // A
                }
                frame.flip();
                writer.writeFrame(frame);
            }
        }
        return out.toByteArray();
    }
}
