package org.watermedia.api.media;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.watermedia.api.media.Colorimetry.Matrix;
import org.watermedia.api.media.Colorimetry.Transfer;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.YuvFrames;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Verifies {@link GLEngine} conversions on a real OpenGL context: odd sizes keep their last chroma column, padded
 * planes may end on their last tight row, packed YUYV keeps each pixel's luma, ragged RGB strides land every row,
 * the colorimetry (matrix, range, chroma siting, PQ/HLG and BT.2020) matches the standard formulas, and engines on
 * one context share their conversion programs. Skipped without a display, GLFW or a 3.2 core context.
 */
@DisplayName("GLEngine")
public class GLEngineTest {
    private static final double[] BT601 = { 0.299, 0.114 };
    private static final double[] BT709 = { 0.2126, 0.0722 };
    private static final double[] BT2020 = { 0.2627, 0.0593 };

    private static boolean initialized;
    private static long window = NULL;

    @BeforeAll
    static void context() {
        assumeTrue(!GraphicsEnvironment.isHeadless(), "No display — skipping the OpenGL uploads");
        MediaBootstrap.client();
        // MACOS REFUSES GLFW OUTSIDE THE MAIN THREAD WITH AN EXCEPTION, WHICH ALSO MEANS "NO CONTEXT HERE"
        try { initialized = glfwInit(); }
        catch (final IllegalStateException offMainThread) { initialized = false; }
        assumeTrue(initialized, "GLFW could not initialize — skipping");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        window = glfwCreateWindow(16, 16, "GLEngineTest", NULL, NULL);
        assumeTrue(window != NULL, "No OpenGL 3.2 core context — skipping");
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
    }

    @AfterAll
    static void destroy() {
        if (window != NULL) {
            glfwMakeContextCurrent(NULL);
            glfwDestroyWindow(window);
        }
        if (initialized) glfwTerminate();
    }

    @Test
    @DisplayName("An odd-width YUV420P frame keeps its last chroma column, with padded planes ending on a tight row")
    void oddWidthKeepsLastChromaColumn() {
        final int w = 853;
        final int h = 3;
        final int cw = 427; // FFMPEG ROUNDS 4:2:0 CHROMA UP: A 853x3 FRAME CARRIES 427x2 CHROMA SAMPLES
        final int ch = 2;
        // Y AND U ROWS ARE PADDED BUT EACH PLANE STOPS RIGHT AFTER ITS LAST ROW; U MARKS ONLY ITS LAST COLUMN (STRONG BLUE)
        final ByteBuffer y = YuvFrames.plane(864, w, h, (row, col) -> 128);
        final ByteBuffer u = YuvFrames.plane(448, cw, ch, (row, col) -> col == cw - 1 ? 255 : 128);
        final ByteBuffer v = YuvFrames.plane(0, cw, ch, (row, col) -> 128);
        final GLEngine engine = MediaAPI.glEngine(null, null);
        try {
            assertTrue(engine.format(PixelFormat.YUV420P, w, h, 8));
            engine.upload(new ByteBuffer[] { y, u, v }, new int[] { 864, 448, 0 });
            final ByteBuffer rgba = pixels(engine, w, h);
            final int first = rgba.get(2) & 0xFF;
            final int middle = rgba.get(400 * 4 + 2) & 0xFF;
            final int last = rgba.get((w - 1) * 4 + 2) & 0xFF;
            assertTrue(Math.abs(first - gray(128)) <= 2, "the minimum-size planes were accepted and converted: " + first);
            assertTrue(Math.abs(middle - first) <= 2, "neutral chroma stays neutral: " + first + " vs " + middle);
            assertTrue(last > first + 80, "the last pixel samples the last chroma column: blue " + last + " vs " + first);
        } finally {
            engine.release();
        }
    }

    @Test
    @DisplayName("An odd-width YUYV frame gives every pixel the luma of its own pair")
    void oddWidthPackedLuma() {
        // FIVE PIXELS ARE THREE [Y0 U Y1 V] GROUPS; THE LAST Y1 IS PADDING NO PIXEL SHOWS
        final int[] luma = { 50, 100, 150, 200, 250 };
        final ByteBuffer yuyv = YuvFrames.plane(0, 12, 1, (row, col) -> col % 2 == 1 ? 128 : col / 2 < luma.length ? luma[col / 2] : 0);
        final GLEngine engine = MediaAPI.glEngine(null, null);
        try {
            assertTrue(engine.format(PixelFormat.YUYV, 5, 1, 8));
            engine.upload(new ByteBuffer[] { yuyv }, new int[] { 0 });
            final ByteBuffer rgba = pixels(engine, 5, 1);
            for (int x = 0; x < luma.length; x++)
                assertTrue(Math.abs((rgba.get(x * 4) & 0xFF) - gray(luma[x])) <= 2, "pixel " + x + " shows " + (rgba.get(x * 4) & 0xFF));
        } finally {
            engine.release();
        }
    }

    @Test
    @DisplayName("An RGB stride that is not a whole number of texels lands every row in place")
    void raggedRgbStride() {
        // 5 RGB PIXELS ARE 15 BYTES; A 16-BYTE STRIDE CANNOT BE A WHOLE ROW LENGTH IN TEXELS
        final ByteBuffer rgb = YuvFrames.plane(16, 15, 2, (row, col) -> row * 100 + col + 1);
        final GLEngine engine = MediaAPI.glEngine(null, null);
        try {
            assertTrue(engine.format(PixelFormat.RGB, 5, 2, 8));
            engine.upload(new ByteBuffer[] { rgb }, new int[] { 16 });
            final ByteBuffer rgba = pixels(engine, 5, 2);
            for (int row = 0; row < 2; row++) {
                for (int col = 0; col < 5; col++) {
                    for (int c = 0; c < 3; c++)
                        assertEquals(row * 100 + col * 3 + c + 1, rgba.get((row * 5 + col) * 4 + c) & 0xFF, "row " + row + " pixel " + col);
                }
            }
        } finally {
            engine.release();
        }
    }

    @Test
    @DisplayName("Gray frames stay full range unless tagged otherwise")
    void grayIsFullRange() {
        final ByteBuffer gray = YuvFrames.plane(0, 2, 1, (row, col) -> col == 0 ? 16 : 235);
        final ByteBuffer rgba = convert(PixelFormat.GRAY, 2, 1, 8, null, new ByteBuffer[] { gray });
        assertEquals(16, rgba.get(0) & 0xFF, "full-range black level");
        assertEquals(235, rgba.get(4) & 0xFF, "full-range near white");
    }

    @Test
    @DisplayName("Each matrix and range converts like the BT.601, BT.709 and BT.2020 formulas")
    void matricesAndRanges() {
        record Case(Matrix matrix, boolean full, double[] weights) {}
        final Case[] cases = {
                new Case(Matrix.BT601, false, BT601), new Case(Matrix.BT709, false, BT709),
                new Case(Matrix.BT2020, false, BT2020), new Case(Matrix.BT709, true, BT709)
        };
        for (final Case c: cases) {
            final Colorimetry color = new Colorimetry(c.matrix(), c.full(), true, false, Transfer.SDR, false);
            final ByteBuffer rgba = convert(PixelFormat.YUV444P, 2, 2, 8, color, uniform(120, 90, 200));
            final int[] expected = YuvFrames.rgb(c.weights()[0], c.weights()[1], c.full(), 8, 120, 90, 200);
            assertPixels(expected, rgba, c.matrix() + (c.full() ? " full" : " limited"));
        }
    }

    @Test
    @DisplayName("Subsampled chroma sits centered or co-sited with the first luma column and row")
    void chromaSiting() {
        // ONE STRONG-BLUE CHROMA SAMPLE NEXT TO A ZERO ONE: THE BLUE OF EACH PIXEL REVEALS WHERE ITS CHROMA WAS SAMPLED
        final int[] samples = { 255, 0 };
        for (final boolean cosited: new boolean[] { false, true }) {
            final Colorimetry horizontal = new Colorimetry(Matrix.BT601, false, cosited, false, Transfer.SDR, false);
            final ByteBuffer wide = convert(PixelFormat.YUV420P, 4, 2, 8, horizontal, new ByteBuffer[] {
                    YuvFrames.plane(0, 4, 2, (row, col) -> 128),
                    YuvFrames.plane(0, 2, 1, (row, col) -> samples[col]),
                    YuvFrames.plane(0, 2, 1, (row, col) -> 128) });
            final Colorimetry vertical = new Colorimetry(Matrix.BT601, false, false, cosited, Transfer.SDR, false);
            final ByteBuffer tall = convert(PixelFormat.YUV420P, 2, 4, 8, vertical, new ByteBuffer[] {
                    YuvFrames.plane(0, 2, 4, (row, col) -> 128),
                    YuvFrames.plane(0, 1, 2, (row, col) -> samples[row]),
                    YuvFrames.plane(0, 1, 2, (row, col) -> 128) });
            for (int i = 0; i < 4; i++) {
                // CHROMA TEXEL COORDINATE OF LUMA PIXEL i: (i + 0.5) / 2 CENTERED, i / 2 + 0.5 CO-SITED
                final double at = cosited ? i / 2.0 + 0.5 : (i + 0.5) / 2.0;
                final int blue = YuvFrames.rgb(BT601[0], BT601[1], false, 8, 128, (int) Math.round(bilinear(samples, at)), 128)[2];
                final String siting = cosited ? "co-sited" : "centered";
                assertTrue(Math.abs((wide.get(i * 4 + 2) & 0xFF) - blue) <= 3, siting + " column " + i + ": " + (wide.get(i * 4 + 2) & 0xFF) + " vs " + blue);
                assertTrue(Math.abs((tall.get(i * 8 + 2) & 0xFF) - blue) <= 3, siting + " row " + i + ": " + (tall.get(i * 8 + 2) & 0xFF) + " vs " + blue);
            }
        }
    }

    @Test
    @DisplayName("PQ and HLG grays keep their SDR brightness below the knee and a 1000-nit peak lands on white")
    void hdrTransfers() {
        final int[] cases = { 100, 1_000 };
        for (final int nits: cases) {
            final int code = (int) Math.round(64 + YuvFrames.pq(nits) * 876);
            final ByteBuffer rgba = convert(PixelFormat.YUV420P, 2, 2, 10, new Colorimetry(Matrix.BT2020, false, true, false, Transfer.PQ, true), gray10(code));
            final int shown = rgba.get(0) & 0xFF;
            if (nits == 1_000) assertTrue(shown >= 252, "PQ 1000 nits reaches white: " + shown);
            else assertTrue(Math.abs(shown - YuvFrames.hdrGray(nits)) <= 3, "PQ " + nits + " nits: " + shown + " vs " + YuvFrames.hdrGray(nits));
        }
        final int code = (int) Math.round(64 + 0.5 * 876);
        final ByteBuffer rgba = convert(PixelFormat.YUV420P, 2, 2, 10, new Colorimetry(Matrix.BT2020, false, true, false, Transfer.HLG, true), gray10(code));
        final int expected = YuvFrames.hdrGray(YuvFrames.hlgNits(0.5));
        assertTrue(Math.abs((rgba.get(0) & 0xFF) - expected) <= 3, "HLG half signal: " + (rgba.get(0) & 0xFF) + " vs " + expected);
    }

    @Test
    @DisplayName("BT.2020 primaries map to the same BT.709 color")
    void wideGamut() {
        // A BT.709 COLOR (LINEAR 0.5, 0.3, 0.2) RE-EXPRESSED IN BT.2020 PRIMARIES (BT.2087), ENCODED WITH GAMMA 2.4
        final double[] linear = { 0.5, 0.3, 0.2 };
        final double r = 0.6274 * linear[0] + 0.3293 * linear[1] + 0.0433 * linear[2];
        final double g = 0.0691 * linear[0] + 0.9195 * linear[1] + 0.0114 * linear[2];
        final double b = 0.0164 * linear[0] + 0.0880 * linear[1] + 0.8956 * linear[2];
        final int[] codes = YuvFrames.ycbcr(BT2020[0], BT2020[1], Math.pow(r, 1 / 2.4), Math.pow(g, 1 / 2.4), Math.pow(b, 1 / 2.4));
        final Colorimetry color = new Colorimetry(Matrix.BT2020, false, true, false, Transfer.SDR, true);
        final ByteBuffer rgba = convert(PixelFormat.YUV444P, 2, 2, 8, color, uniform(codes[0], codes[1], codes[2]));
        final int[] expected = new int[3];
        for (int c = 0; c < 3; c++) expected[c] = (int) Math.round(Math.pow(linear[c], 1 / 2.4) * 255);
        assertPixels(expected, rgba, "BT.2020 to BT.709");
    }

    @Test
    @DisplayName("Engines on one context share their conversion programs and the last one released deletes them")
    void sharedConversionKit() throws ReflectiveOperationException {
        final int[] expected = YuvFrames.rgb(BT601[0], BT601[1], false, 8, 120, 90, 200);
        final Colorimetry color = new Colorimetry(Matrix.BT601, false, true, false, Transfer.SDR, false);
        final GLEngine first = MediaAPI.glEngine(null, null);
        final GLEngine second = MediaAPI.glEngine(null, null);
        final Object kit;
        final int program;
        try {
            assertTrue(first.format(PixelFormat.YUV444P, 2, 2, 8, color));
            first.upload(uniform(120, 90, 200), new int[3]);
            assertTrue(second.format(PixelFormat.YUV444P, 2, 2, 8, color));
            second.upload(uniform(120, 90, 200), new int[3]);
            kit = kit(first);
            assertNotNull(kit, "a conversion layout takes the context's kit");
            assertSame(kit, kit(second), "a second engine on the same context reuses the kit");
            program = program(kit);
            assertTrue(GL20.glIsProgram(program), "the shared program is live");

            first.release();
            assertTrue(GL20.glIsProgram(program), "the kit survives while an engine still uses it");
            second.upload(uniform(120, 90, 200), new int[3]);
            assertPixels(expected, pixels(second, 2, 2), "the remaining engine still converts");
        } finally {
            first.release();
            second.release();
        }
        assertFalse(GL20.glIsProgram(program), "the last engine released deletes the program");

        final GLEngine third = MediaAPI.glEngine(null, null);
        try {
            assertTrue(third.format(PixelFormat.YUV444P, 2, 2, 8, color));
            third.upload(uniform(120, 90, 200), new int[3]);
            assertNotSame(kit, kit(third), "a later engine builds a fresh kit");
            assertPixels(expected, pixels(third, 2, 2), "the fresh kit converts");
        } finally {
            third.release();
        }
    }

    // UTILITY

    // THE ENGINE'S PRIVATE CONVERSION KIT, null BEFORE ITS FIRST CONVERSION LAYOUT
    private static Object kit(final GLEngine engine) throws ReflectiveOperationException {
        final Field field = GLEngine.class.getDeclaredField("kit");
        field.setAccessible(true);
        return field.get(engine);
    }

    // THE KIT'S YUV 3-PLANE PROGRAM (THE ONLY KIND THE SHARING TEST CONVERTS)
    private static int program(final Object kit) throws ReflectiveOperationException {
        final Field field = kit.getClass().getDeclaredField("programs");
        field.setAccessible(true);
        for (final int program: (int[]) field.get(kit)) {
            if (program != 0) return program;
        }
        return 0;
    }

    // 8-BIT LIMITED-RANGE GRAY AS ANY MATRIX SHOWS IT (NEUTRAL CHROMA)
    private static int gray(final int luma) {
        return YuvFrames.rgb(BT601[0], BT601[1], false, 8, luma, 128, 128)[0];
    }

    // A 2x2 YUV444P FRAME OF ONE UNIFORM YCbCr SAMPLE
    private static ByteBuffer[] uniform(final int y, final int cb, final int cr) {
        return new ByteBuffer[] {
                YuvFrames.plane(0, 2, 2, (row, col) -> y),
                YuvFrames.plane(0, 2, 2, (row, col) -> cb),
                YuvFrames.plane(0, 2, 2, (row, col) -> cr) };
    }

    // A 2x2 10-BIT YUV420P GRAY: LUMA code, NEUTRAL CHROMA
    private static ByteBuffer[] gray10(final int code) {
        return new ByteBuffer[] {
                YuvFrames.plane16(2, 2, (row, col) -> code),
                YuvFrames.plane16(1, 1, (row, col) -> 512),
                YuvFrames.plane16(1, 1, (row, col) -> 512) };
    }

    // LINEAR FILTERING OF A ROW OF TEXELS AT A TEXEL COORDINATE, CLAMPED TO THE EDGES LIKE GL_CLAMP_TO_EDGE
    private static double bilinear(final int[] texels, final double at) {
        final double t = Math.max(0.0, Math.min(texels.length - 1.0, at - 0.5));
        final int first = (int) Math.floor(t);
        final int second = Math.min(first + 1, texels.length - 1);
        return texels[first] + (texels[second] - texels[first]) * (t - first);
    }

    // CONVERTS ONE TIGHT FRAME ON A FRESH ENGINE (null COLOR = THE UNTAGGED GUESS) AND READS ITS RGBA BACK
    private static ByteBuffer convert(final PixelFormat format, final int width, final int height, final int bits,
                                      final Colorimetry color, final ByteBuffer[] planes) {
        final GLEngine engine = MediaAPI.glEngine(null, null);
        try {
            assertTrue(color == null ? engine.format(format, width, height, bits) : engine.format(format, width, height, bits, color));
            engine.upload(planes, new int[planes.length]);
            return pixels(engine, width, height);
        } finally {
            engine.release();
        }
    }

    // ASSERTS EVERY PIXEL OF A 2x2 RGBA FRAME SHOWS rgb WITHIN 3 CODES
    private static void assertPixels(final int[] rgb, final ByteBuffer rgba, final String label) {
        for (int p = 0; p < 4; p++) {
            for (int c = 0; c < 3; c++) {
                final int shown = rgba.get(p * 4 + c) & 0xFF;
                assertTrue(Math.abs(shown - rgb[c]) <= 3, label + " pixel " + p + " channel " + c + ": " + shown + " vs " + rgb[c]);
            }
        }
    }

    // READS THE ENGINE'S RGBA TEXTURE BACK, ROW 0 FIRST
    private static ByteBuffer pixels(final GLEngine engine, final int width, final int height) {
        final ByteBuffer out = BufferUtils.createByteBuffer(width * height * 4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, (int) engine.texture());
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return out;
    }
}
