package org.watermedia.api.media;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.watermedia.api.util.MathUtil;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.test.support.MediaBootstrap;

import java.awt.GraphicsEnvironment;
import java.nio.ByteBuffer;
import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Verifies {@link GLEngine} uploads on a real OpenGL context: an odd-sized frame keeps its last chroma
 * column, padded planes may end on their last tight row, odd-width packed YUYV keeps each pixel's luma,
 * and an RGB stride that is not a whole number of texels lands every row without shearing. Skipped
 * without a display, GLFW or a 3.2 core context.
 */
@DisplayName("GLEngine")
public class GLEngineTest {
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
        final PixelFormat f = PixelFormat.YUV420P;
        final int cw = 427; // FFMPEG ROUNDS 4:2:0 CHROMA UP: A 853x3 FRAME CARRIES 427x2 CHROMA SAMPLES
        final int ch = 2;
        // Y AND U ROWS ARE PADDED BUT EACH PLANE STOPS RIGHT AFTER ITS LAST ROW; U MARKS ONLY ITS LAST COLUMN (STRONG BLUE)
        final ByteBuffer y = plane(864, w, h, (row, col) -> 128);
        final ByteBuffer u = plane(448, cw, ch, (row, col) -> col == cw - 1 ? 255 : 128);
        final ByteBuffer v = plane(0, cw, ch, (row, col) -> 128);
        final GLEngine engine = MediaAPI.glEngine(null, null);
        try {
            assertTrue(engine.format(f, w, h, 8));
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
        final ByteBuffer yuyv = plane(0, 12, 1, (row, col) -> col % 2 == 1 ? 128 : col / 2 < luma.length ? luma[col / 2] : 0);
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
        final ByteBuffer rgb = plane(16, 15, 2, (row, col) -> row * 100 + col + 1);
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

    // UTILITY

    // 8-BIT LIMITED-RANGE LUMA WITH NEUTRAL CHROMA AS THE SHADERS EXPAND IT
    private static int gray(final int luma) {
        return Math.round(MathUtil.clamp((luma / 255f * 1.16438f - 0.07306f) * 255f, 0f, 255f));
    }

    // A PLANE OF rows ROWS OF rowBytes BYTES stride APART (0 = TIGHT), ENDING RIGHT AFTER ITS LAST ROW
    private static ByteBuffer plane(final int stride, final int rowBytes, final int rows, final IntBinaryOperator sample) {
        final int step = stride == 0 ? rowBytes : stride;
        final ByteBuffer buffer = BufferUtils.createByteBuffer((rows - 1) * step + rowBytes);
        for (int row = 0; row < rows; row++)
            for (int col = 0; col < rowBytes; col++) buffer.put(row * step + col, (byte) sample.applyAsInt(row, col));
        return buffer;
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
