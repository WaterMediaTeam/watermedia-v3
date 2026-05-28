package org.watermedia.test.codecs.jpeg;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.PamImage;
import org.watermedia.test.support.PixelDiff;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * JPEG decoder verification, exercising baseline and APP-marker fixtures across the four
 * common chroma layouts.
 *
 * <p>Three tests are emitted per fixture: a structural check on the BGRA reader output, a
 * native pixel-format probe (no conversion), and a tolerance-bounded pixel comparison
 * against a pre-generated PAM reference. Tolerances are relaxed because JPEG decoders
 * legitimately differ at the IDCT/upsampling layer.
 */
public class JpegTest {
    // PER-CHANNEL DIFFERENCE BOUNDS — JPEG IS LOSSY AND DECODER VARIANTS ARE NORMAL
    private static final int SMALL_DIFF = 8;
    private static final int MAX_DIFF = 80;
    private static final double MAX_MEAN_DIFF = 6.0;
    private static final double MAX_SMALL_DIFF_PERCENT = 10.0;

    private record Fixture(int id, String encoding, PixelFormat nativeFormat, int nativePlanes) {
        Path jpeg() {
            return Fixtures.JPEG_DIR.resolve(this.id + ".jpg");
        }

        Path raw() {
            return Fixtures.JPEG_DIR.resolve("raw").resolve(this.id + ".pam");
        }
    }

    @TestFactory
    Iterable<DynamicTest> testJPEGFixtures() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Fixture fixture: List.of(
                new Fixture(1, "jpeg400jfif", PixelFormat.GRAY, 1),
                new Fixture(2, "jpeg420exif", PixelFormat.YUV420P, 3),
                new Fixture(3, "jpeg422jfif", PixelFormat.YUV422P, 3),
                new Fixture(4, "jpeg444", PixelFormat.YUV444P, 3),
                new Fixture(5, "jpeg444_all_appx_markers", PixelFormat.YUV444P, 3)
        )) {
            // BGRA STREAM SHAPE CHECK
            tests.add(dynamicTest("JPEG decode [" + fixture.id + " " + fixture.encoding + "]", () -> {
                final ByteBuffer source = ByteBuffer.wrap(Fixtures.readAll(fixture.jpeg()));
                try (final ImageReader reader = CodecsAPI.decodeImage(source, PixelFormat.BGRA)) {
                    assertEquals("JPEG", reader.name());
                    assertEquals(2, source.position(), "JPEG SOI should be consumed by CodecsAPI");
                    assertTrue(reader.width() > 0);
                    assertTrue(reader.height() > 0);
                    assertEquals(PixelFormat.BGRA, reader.pixelFormat());
                    assertEquals(1, reader.frameCount());
                    assertTrue(reader.hasNext());

                    final ByteBuffer frame = reader.next();
                    assertTrue(frame.isDirect());
                    assertEquals(reader.width() * reader.height() * 4, frame.remaining());
                }
            }));

            // NATIVE LAYOUT — NO COLOR CONVERSION REQUESTED
            tests.add(dynamicTest("JPEG native format [" + fixture.id + " " + fixture.encoding + "]", () -> {
                final ByteBuffer source = ByteBuffer.wrap(Fixtures.readAll(fixture.jpeg()));
                try (final ImageReader reader = CodecsAPI.decodeImage(source)) {
                    assertEquals(fixture.nativeFormat, reader.pixelFormat(), fixture.encoding);
                    assertEquals(fixture.nativePlanes, reader.planeCount(), fixture.encoding);
                    final ByteBuffer frame = reader.next();
                    assertTrue(frame.isDirect(), fixture.encoding);

                    // EXPECTED PAYLOAD SIZE FOR EACH NATIVE LAYOUT
                    final int w = reader.width();
                    final int h = reader.height();
                    final int chromaW = (w + 1) >> 1;
                    final int chromaH = (h + 1) >> 1;
                    final int pixels = w * h;
                    final int expectedBytes = switch (fixture.nativeFormat) {
                        case GRAY -> pixels;
                        case YUV420P -> pixels + 2 * chromaW * chromaH;
                        case YUV422P -> pixels + 2 * chromaW * h;
                        case YUV444P -> pixels * 3;
                        default -> throw new IllegalArgumentException(
                                "Unsupported JPEG native format " + fixture.nativeFormat);
                    };
                    assertEquals(expectedBytes, frame.remaining(), fixture.encoding);

                    for (int i = 0; i < reader.planeCount(); i++) {
                        assertNotNull(reader.plane(i), fixture.encoding + " plane " + i);
                        assertTrue(reader.planeStride(i) > 0, fixture.encoding + " stride " + i);
                    }
                }
            }));

            // PIXEL DIFFERENCE AGAINST PAM REFERENCE (BGRA OUTPUT)
            tests.add(dynamicTest("JPEG pixels [" + fixture.id + " " + fixture.encoding + "]", () -> {
                final ImageData image = CodecsAPI.decodeImage(Fixtures.readAll(fixture.jpeg()), PixelFormat.BGRA);
                final PamImage reference = PamImage.read(fixture.raw());

                assertEquals(reference.width(), image.width(), fixture.encoding);
                assertEquals(reference.height(), image.height(), fixture.encoding);
                assertEquals(1, image.frames().length, fixture.encoding);

                // COMPARE ONLY RGB CHANNELS — PAM REFERENCE IS DEPTH 3, BGRA HAS AN EXTRA A
                final ByteBuffer bgra = image.frames()[0].duplicate();
                final int pixelCount = reference.width() * reference.height();
                final byte[] refRgb = reference.pixels();
                final byte[] actualRgb = new byte[pixelCount * 3];
                for (int i = 0; i < pixelCount; i++) {
                    final int srcOffset = i * 4;
                    final int dstOffset = i * 3;
                    actualRgb[dstOffset] = bgra.get(srcOffset + 2);     // R
                    actualRgb[dstOffset + 1] = bgra.get(srcOffset + 1); // G
                    actualRgb[dstOffset + 2] = bgra.get(srcOffset);     // B
                }

                final PixelDiff diff = PixelDiff.compare(refRgb, actualRgb, SMALL_DIFF);
                assertTrue(diff.maxDiff() <= MAX_DIFF,
                        fixture.encoding + " max diff " + diff.maxDiff() + " exceeds " + MAX_DIFF);
                assertTrue(diff.meanDiff() <= MAX_MEAN_DIFF,
                        fixture.encoding + " mean diff " + diff.meanDiff() + " exceeds " + MAX_MEAN_DIFF);
                assertTrue(diff.percentAboveSmall() <= MAX_SMALL_DIFF_PERCENT,
                        fixture.encoding + " has " + diff.percentAboveSmall() + "% values above " + SMALL_DIFF);
            }));
        }
        return tests;
    }
}
