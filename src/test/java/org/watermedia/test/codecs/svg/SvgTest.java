package org.watermedia.test.codecs.svg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.UnsupportedFormatException;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.test.support.Fixtures;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * SVG decoder verification. The reference SVGs under {@code src/test/resources/svg} are rasterized
 * by the pure-Java decoder; each render is also written to {@code build/test-svg/} so the output can
 * be eyeballed against the ground-truth PNGs under {@code svg/png}.
 *
 * <p>Assertions are deterministic (contract + non-trivial content); exact pixel parity with a
 * browser/Illustrator render is not expected from a from-scratch anti-aliased rasterizer.
 */
@DisplayName("SVG decoder")
public class SvgTest {

    private static final int CAP = 512; // DEFAULT WaterMediaConfig.decoders.svgMaxSize

    @TestFactory
    @DisplayName("Decode contract")
    Iterable<DynamicTest> contract() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Path svg: svgFiles()) {
            final String name = svg.getFileName().toString();
            tests.add(dynamicTest("SVG contract [" + name + "]", () -> {
                final byte[] bytes = Fixtures.readAll(svg);

                final ByteBuffer bb = ByteBuffer.wrap(bytes);
                try (final ImageReader reader = CodecsAPI.decodeImage(bb)) {
                    assertEquals("SVG", reader.name());
                    assertEquals(0, bb.position(), "SVG is its own body — position must not advance");
                    assertTrue(reader.width() > 0 && reader.height() > 0, "positive dimensions");
                    assertTrue(reader.width() <= CAP && reader.height() <= CAP, "max side capped to " + CAP);
                    assertEquals(PixelFormat.BGRA, reader.pixelFormat());
                    assertEquals(ImageData.Scan.EMPTY.frameCount(), reader.frameCount(), "SVG is a single static frame");

                    assertTrue(reader.hasNext());
                    final ByteBuffer frame = reader.next();
                    assertTrue(frame.isDirect(), "frame must be a direct buffer");
                    assertEquals(reader.width() * reader.height() * 4, frame.capacity(), "BGRA buffer size");
                    assertFalse(reader.hasNext(), "only one frame");

                    assertTrue(reader.reset(), "reset must be supported");
                    assertTrue(reader.hasNext(), "reset re-arms the frame");
                }

                final ImageData img = CodecsAPI.decodeImage(bytes);
                assertNotNull(img);
                assertEquals(1, img.frames().length, "single frame");
                assertTrue(img.width() > 0 && img.height() > 0);
            }));
        }
        return tests;
    }

    @Test
    @DisplayName("Renders non-trivial content and dumps PNGs for inspection")
    void renderAndDump() throws IOException {
        final Path outDir = Path.of("build", "test-svg");
        Files.createDirectories(outDir);

        for (final Path svg: svgFiles()) {
            final String name = svg.getFileName().toString();
            final ImageData img = CodecsAPI.decodeImage(Fixtures.readAll(svg));
            final int w = img.width(), h = img.height();
            final ByteBuffer bgra = img.frames()[0].duplicate();

            // BGRA → ARGB FOR INSPECTION AND COVERAGE STATS
            final int[] argb = new int[w * h];
            int opaque = 0;
            final Set<Integer> colors = new HashSet<>();
            for (int i = 0; i < argb.length; i++) {
                final int b = bgra.get(i * 4) & 0xFF;
                final int g = bgra.get(i * 4 + 1) & 0xFF;
                final int r = bgra.get(i * 4 + 2) & 0xFF;
                final int a = bgra.get(i * 4 + 3) & 0xFF;
                argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
                if (a > 16) opaque++;
                if (a > 200 && colors.size() < 64) colors.add(r << 16 | g << 8 | b);
            }

            final BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            out.setRGB(0, 0, w, h, argb, 0, w);
            ImageIO.write(out, "png", outDir.resolve(name.replace(".svg", ".png")).toFile());

            // PROVES THE RASTERIZER ACTUALLY DREW SHAPES (NOT A BLANK CANVAS). SOME REFERENCES ARE
            // LEGITIMATELY MONOCHROME (e.g. a black line-art silhouette), SO ONLY COVERAGE IS ASSERTED.
            final double coverage = (double) opaque / (w * h);
            assertTrue(coverage > 0.01, name + " rendered almost nothing (coverage=" + coverage + ")");
            assertTrue(!colors.isEmpty(), name + " produced no opaque pixels");
        }
    }

    @Test
    @DisplayName("Non-SVG XML is not accepted as an image")
    void rejectsNonSvgXml() {
        final byte[] html = "<?xml version=\"1.0\"?>\n<html><body>hi</body></html>".getBytes(StandardCharsets.UTF_8);
        assertThrows(UnsupportedFormatException.class, () -> CodecsAPI.decodeImage(html));
    }

    @Test
    @DisplayName("Path data with a stray operand after Z does not hang")
    void closePathWithStrayOperandTerminates() {
        // d="...z5": Z CONSUMES NO INPUT — THE PARSER MUST STILL ADVANCE AND FINISH (NO INFINITE LOOP)
        final byte[] svg = svg("0 0 100 100", "<path d=\"M10,10 L90,90 L10,90 z5\" fill=\"#ff0000\"/>");
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            final ImageData img = CodecsAPI.decodeImage(svg);
            assertNotNull(img);
        });
    }

    @Test
    @DisplayName("Pathologically deep nesting fails with IOException, not StackOverflowError/hang")
    void deepNestingIsBounded() {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < 4000; i++) b.append("<g>");
        b.append("<rect width=\"10\" height=\"10\"/>");
        for (int i = 0; i < 4000; i++) b.append("</g>");
        final byte[] svg = svg("0 0 100 100", b.toString());
        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertThrows(IOException.class, () -> CodecsAPI.decodeImage(svg)));
    }

    @Test
    @DisplayName("rgb() with surrounding spaces parses to the right colour")
    void rgbWithSpacesParses() throws IOException {
        final byte[] svg = svg("0 0 10 10", "<rect width=\"10\" height=\"10\" fill=\"rgb( 200, 10, 10 )\"/>");
        final ImageData img = CodecsAPI.decodeImage(svg);
        final ByteBuffer bgra = img.frames()[0].duplicate();
        final int centre = (img.height() / 2 * img.width() + img.width() / 2) * 4;
        final int b = bgra.get(centre) & 0xFF, g = bgra.get(centre + 1) & 0xFF, r = bgra.get(centre + 2) & 0xFF;
        // MUST BE RED, NOT THE INHERITED BLACK FALLBACK A PARSE FAILURE WOULD PRODUCE
        assertTrue(r > 150 && g < 70 && b < 70, "expected red, got rgb(" + r + "," + g + "," + b + ")");
    }

    @Test
    @DisplayName("reset() then next() returns a fully-readable frame")
    void resetRewindsFrame() throws IOException {
        try (final ImageReader reader = CodecsAPI.decodeImage(
                ByteBuffer.wrap(svg("0 0 10 10", "<rect width=\"10\" height=\"10\" fill=\"#00ff00\"/>")))) {
            final ByteBuffer first = reader.next();
            final int cap = first.capacity();
            while (first.hasRemaining()) first.get(); // DRAIN THE BUFFER LIKE A GL UPLOAD WOULD
            assertTrue(reader.reset());
            final ByteBuffer second = reader.next();
            assertEquals(0, second.position(), "frame must be rewound to position 0 on the second pass");
            assertEquals(cap, second.remaining(), "the whole frame must be readable again");
        }
    }

    private static byte[] svg(final String viewBox, final String body) {
        return ("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"" + viewBox + "\">" + body + "</svg>")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static List<Path> svgFiles() {
        try (final Stream<Path> entries = Files.list(Fixtures.SVG_DIR)) {
            final List<Path> files = entries
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".svg"))
                    .sorted()
                    .toList();
            assertTrue(!files.isEmpty(), "No SVG fixtures under " + Fixtures.SVG_DIR);
            return files;
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to list SVG fixtures", e);
        }
    }
}
