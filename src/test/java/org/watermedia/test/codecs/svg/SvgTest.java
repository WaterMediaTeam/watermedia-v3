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
import java.util.Arrays;
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

    @Test
    @DisplayName("Linear gradient interpolates red→blue across the shape")
    void linearGradientInterpolates() throws IOException {
        final byte[] svg = svg("0 0 100 100",
                "<defs><linearGradient id=\"g\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"0\">"
                + "<stop offset=\"0\" stop-color=\"#ff0000\"/>"
                + "<stop offset=\"1\" stop-color=\"#0000ff\"/></linearGradient></defs>"
                + "<rect width=\"100\" height=\"100\" fill=\"url(#g)\"/>");
        final ImageData img = CodecsAPI.decodeImage(svg);
        final int[] left = rgba(img, 5, 50), right = rgba(img, 94, 50);
        assertTrue(left[0] > 150 && left[2] < 100, "left edge should be red, got " + Arrays.toString(left));
        assertTrue(right[2] > 150 && right[0] < 100, "right edge should be blue, got " + Arrays.toString(right));
    }

    @Test
    @DisplayName("Gradient href chains inherit stops across multiple hops")
    void gradientHrefChainResolvesStops() throws IOException {
        // a -> b -> c: ONLY c DECLARES STOPS AND ONLY a IS REFERENCED — THE CHAIN MUST BE FOLLOWED FULLY
        final byte[] svg = svg("0 0 100 100",
                "<defs>"
                + "<linearGradient id=\"c\"><stop offset=\"0\" stop-color=\"#ff0000\"/>"
                + "<stop offset=\"1\" stop-color=\"#0000ff\"/></linearGradient>"
                + "<linearGradient id=\"b\" xlink:href=\"#c\"/>"
                + "<linearGradient id=\"a\" href=\"#b\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"0\"/>"
                + "</defs>"
                + "<rect width=\"100\" height=\"100\" fill=\"url(#a)\"/>");
        final ImageData img = CodecsAPI.decodeImage(svg);
        final int[] left = rgba(img, 5, 50), right = rgba(img, 94, 50);
        assertTrue(left[0] > 150 && left[2] < 100, "chained gradient must resolve stops (left red), got " + Arrays.toString(left));
        assertTrue(right[2] > 150 && right[0] < 100, "chained gradient must resolve stops (right blue), got " + Arrays.toString(right));
    }

    @Test
    @DisplayName("rotate() transform places the shape at the rotated location")
    void transformRotate() throws IOException {
        // ROTATE 90° ABOUT (50,50) MAPS A RECT AT x∈[70,90],y∈[10,30] TO x∈[70,90],y∈[70,90]
        final byte[] svg = svg("0 0 100 100",
                "<rect x=\"70\" y=\"10\" width=\"20\" height=\"20\" fill=\"#00ff00\" transform=\"rotate(90 50 50)\"/>");
        final ImageData img = CodecsAPI.decodeImage(svg);
        assertTrue(rgba(img, 80, 80)[1] > 150, "rotated rect should cover (80,80)");
        assertTrue(rgba(img, 80, 20)[3] < 16, "original rect location must be empty after rotation");
    }

    @Test
    @DisplayName("matrix() transform translates the shape")
    void transformMatrix() throws IOException {
        final byte[] svg = svg("0 0 100 100",
                "<rect x=\"0\" y=\"40\" width=\"20\" height=\"20\" fill=\"#00ff00\" transform=\"matrix(1 0 0 1 40 0)\"/>");
        final ImageData img = CodecsAPI.decodeImage(svg);
        assertTrue(rgba(img, 50, 50)[1] > 150, "rect should move to x∈[40,60]");
        assertTrue(rgba(img, 10, 50)[3] < 16, "original x∈[0,20] must be empty");
    }

    @Test
    @DisplayName("skewX() shifts rows horizontally by tan(angle)*y")
    void transformSkewX() throws IOException {
        // skewX(45): x' = x + y. AT y≈80 A STRIP AT x∈[0,10] SHIFTS TO x≈[80,90]
        final byte[] svg = svg("0 0 100 100",
                "<rect x=\"0\" y=\"0\" width=\"10\" height=\"100\" fill=\"#00ff00\" transform=\"skewX(45)\"/>");
        final ImageData img = CodecsAPI.decodeImage(svg);
        assertTrue(rgba(img, 84, 80)[1] > 120, "skewed strip should reach x≈84 at y=80");
        assertTrue(rgba(img, 5, 80)[3] < 16, "strip has moved away from x≈5 at y=80");
    }

    @Test
    @DisplayName("A-command arc fills the correct half-disc")
    void arcCommand() throws IOException {
        // SEMICIRCLE FROM (50,10) TO (50,90), sweep=1 → RIGHT HALF-DISC CLOSED BY THE VERTICAL CHORD
        final byte[] svg = svg("0 0 100 100", "<path d=\"M50,10 A40,40 0 0 1 50,90 Z\" fill=\"#ff0000\"/>");
        final ImageData img = CodecsAPI.decodeImage(svg);
        assertTrue(rgba(img, 75, 50)[0] > 150, "right half-disc should be filled at (75,50)");
        assertTrue(rgba(img, 25, 50)[3] < 16, "left half must be empty at (25,50)");
    }

    @Test
    @DisplayName("fill-rule evenodd punches a hole that nonzero fills")
    void evenOddFillRule() throws IOException {
        final String d = "d=\"M10,10 H90 V90 H10 Z M30,30 H70 V70 H30 Z\"";
        final ImageData eo = CodecsAPI.decodeImage(svg("0 0 100 100", "<path fill-rule=\"evenodd\" fill=\"#ff0000\" " + d + "/>"));
        final ImageData nz = CodecsAPI.decodeImage(svg("0 0 100 100", "<path fill=\"#ff0000\" " + d + "/>"));
        assertTrue(rgba(eo, 50, 50)[3] < 16, "evenodd: the inner square is a hole");
        assertTrue(rgba(eo, 20, 20)[0] > 150, "evenodd: the outer ring is filled");
        assertTrue(rgba(nz, 50, 50)[0] > 150, "nonzero: the inner square is filled (no hole)");
    }

    @Test
    @DisplayName("Stroke honours width and round caps")
    void strokeWidthAndCaps() throws IOException {
        final byte[] svg = svg("0 0 100 100",
                "<line x1=\"10\" y1=\"50\" x2=\"90\" y2=\"50\" stroke=\"#ff0000\" stroke-width=\"10\"/>");
        final ImageData img = CodecsAPI.decodeImage(svg);
        assertTrue(rgba(img, 50, 50)[0] > 150, "on the stroke centre");
        assertTrue(rgba(img, 50, 80)[3] < 16, "far from the stroke is empty");
        assertTrue(rgba(img, 92, 50)[0] > 120, "round cap extends past the endpoint");
        assertTrue(rgba(img, 99, 50)[3] < 16, "beyond the round cap is empty");
    }

    @Test
    @DisplayName("hsl() parses to the expected colour")
    void hslColor() throws IOException {
        final ImageData img = CodecsAPI.decodeImage(svg("0 0 10 10", "<rect width=\"10\" height=\"10\" fill=\"hsl(120, 100%, 50%)\"/>"));
        final int[] c = rgba(img, 5, 5);
        assertTrue(c[1] > 200 && c[0] < 60 && c[2] < 60, "hsl(120,100%,50%) is green, got " + Arrays.toString(c));
    }

    @Test
    @DisplayName("Percentage lengths resolve against the viewport")
    void percentageLengths() throws IOException {
        final ImageData img = CodecsAPI.decodeImage(svg("0 0 100 100",
                "<rect x=\"25%\" y=\"25%\" width=\"50%\" height=\"50%\" fill=\"#ff0000\"/>"));
        assertTrue(rgba(img, 50, 50)[0] > 150, "centre of the 25%..75% rect is filled");
        assertTrue(rgba(img, 10, 10)[3] < 16, "outside the rect is empty");
    }

    @Test
    @DisplayName("Absolute width/height take precedence over viewBox for intrinsic size")
    void sizingPrecedence() throws IOException {
        final ImageData sized = CodecsAPI.decodeImage(rawSvg(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"64\" height=\"32\" viewBox=\"0 0 100 100\">"
                + "<rect width=\"100\" height=\"100\" fill=\"#ff0000\"/></svg>"));
        assertEquals(64, sized.width(), "absolute width wins over viewBox");
        assertEquals(32, sized.height(), "absolute height wins over viewBox");

        final ImageData viewBoxOnly = CodecsAPI.decodeImage(svg("0 0 40 20", "<rect width=\"40\" height=\"20\" fill=\"#ff0000\"/>"));
        assertEquals(40, viewBoxOnly.width(), "viewBox provides intrinsic size when width/height are absent");
        assertEquals(20, viewBoxOnly.height());
    }

    @Test
    @DisplayName("display:none subtrees are not painted")
    void displayNoneHidesSubtree() throws IOException {
        // A display:none RED RECT OVER THE WHOLE CANVAS MUST NOT PAINT; THE GREEN BASE SHOWS THROUGH
        final ImageData img = CodecsAPI.decodeImage(svg("0 0 100 100",
                "<rect width=\"100\" height=\"100\" fill=\"#00ff00\"/>"
                + "<rect width=\"100\" height=\"100\" fill=\"#ff0000\" style=\"display:none\"/>"));
        final int[] c = rgba(img, 50, 50);
        assertTrue(c[1] > 150 && c[0] < 90, "green base must show, display:none red rect must be skipped, got " + Arrays.toString(c));
    }

    @Test
    @DisplayName("visibility:hidden shapes are laid out but not painted")
    void visibilityHiddenNotPainted() throws IOException {
        final ImageData img = CodecsAPI.decodeImage(svg("0 0 100 100",
                "<rect width=\"100\" height=\"100\" fill=\"#00ff00\"/>"
                + "<rect width=\"100\" height=\"100\" fill=\"#ff0000\" visibility=\"hidden\"/>"));
        assertTrue(rgba(img, 50, 50)[1] > 150, "hidden red rect must not paint over green");
    }

    @Test
    @DisplayName("A missing operand does not swallow the following command")
    void missingOperandDoesNotEatNextCommand() throws IOException {
        // C HAS ONLY 5 OF 6 OPERANDS THEN z: THE MALFORMED CUBIC IS DROPPED AND z STILL CLOSES M..L..L
        final byte[] svg = svg("0 0 100 100", "<path d=\"M10,10 L90,10 L90,90 C1 2 3 4 5z\" fill=\"#ff0000\"/>");
        final ImageData img = CodecsAPI.decodeImage(svg);
        assertTrue(rgba(img, 80, 40)[0] > 150, "close after the malformed C must still fill the triangle interior");
        assertTrue(rgba(img, 10, 5)[3] < 16, "no bogus cubic to (5,0) should leak fill above the triangle");
    }

    @Test
    @DisplayName("A RGBA request is honoured with a channel swizzle")
    void requestedRgbaIsHonoured() throws IOException {
        final byte[] svg = svg("0 0 10 10", "<rect width=\"10\" height=\"10\" fill=\"#ff0000\"/>");
        try (final ImageReader reader = CodecsAPI.decodeImage(ByteBuffer.wrap(svg), PixelFormat.RGBA)) {
            assertEquals(PixelFormat.RGBA, reader.pixelFormat(), "reader must report the honoured format");
            final ByteBuffer f = reader.next();
            final int i = (reader.height() / 2 * reader.width() + reader.width() / 2) * 4;
            final int r = f.get(i) & 0xFF, g = f.get(i + 1) & 0xFF, b = f.get(i + 2) & 0xFF, a = f.get(i + 3) & 0xFF;
            // RGBA MEMORY ORDER: R FIRST, THEN G, B, A — A RED FILL IS (255,0,0,255)
            assertTrue(r > 200 && g < 60 && b < 60 && a > 200, "expected RGBA red, got " + r + "," + g + "," + b + "," + a);
        }
    }

    @Test
    @DisplayName("A DOCTYPE with an unreachable SYSTEM id decodes without any network fetch")
    void doctypeSystemIdIsNotFetched() {
        final byte[] svg = ("<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE svg SYSTEM \"http://240.0.0.1/unreachable.dtd\">\n"
                + "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\">"
                + "<rect width=\"10\" height=\"10\" fill=\"#ff0000\"/></svg>").getBytes(StandardCharsets.UTF_8);
        // IF THE EXTERNAL DTD WERE RESOLVED, THE UNROUTABLE HOST WOULD HANG (TIMEOUT) OR THROW (DECODE FAIL)
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            final ImageData img = CodecsAPI.decodeImage(svg);
            assertNotNull(img);
            assertTrue(rgba(img, 5, 5)[0] > 150, "the red rect still renders");
        });
    }

    private static byte[] svg(final String viewBox, final String body) {
        return ("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" viewBox=\""
                + viewBox + "\">" + body + "</svg>").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] rawSvg(final String doc) {
        return doc.getBytes(StandardCharsets.UTF_8);
    }

    // READS A PIXEL FROM THE DEFAULT BGRA FRAME AND RETURNS {r, g, b, a}
    private static int[] rgba(final ImageData img, final int x, final int y) {
        final ByteBuffer bgra = img.frames()[0].duplicate();
        final int i = (y * img.width() + x) * 4;
        return new int[] {
                bgra.get(i + 2) & 0xFF, bgra.get(i + 1) & 0xFF, bgra.get(i) & 0xFF, bgra.get(i + 3) & 0xFF
        };
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
