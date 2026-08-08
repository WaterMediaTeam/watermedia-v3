package org.watermedia.test.codecs.writers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.writers.WebMWriter;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.test.support.MediaBootstrap;

import java.awt.GraphicsEnvironment;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * End-to-end capture demo for {@link WebMWriter}: opens a visible OpenGL window, renders an animated
 * Mandelbrot zoom, reads each frame back from the framebuffer and feeds it straight into the writer,
 * producing a VP9-in-WebM file. It proves the GPU-render → pixel-readback → codec pipeline, not just
 * synthetic buffers.
 *
 * <p>The test is skipped (never failed) when it cannot run unattended: no display (headless CI),
 * missing FFmpeg natives, or GLFW/OpenGL unavailable. On a desktop with a GPU it pops the window,
 * records a few seconds and writes {@code build/test-output/fractal-vp9.webm}. GLFW drives its window
 * from the calling thread, which is fine on Windows/Linux; macOS would need {@code -XstartOnFirstThread}.
 */
@DisplayName("WebMWriter OpenGL fractal capture")
public class WebMWriterFractalTest {

    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final int FRAMES = 150; // ~5s AT 30 FPS

    // FULLSCREEN TRIANGLE FROM gl_VertexID — NO VBO/ATTRIBS NEEDED (STILL NEEDS A BOUND VAO IN CORE)
    private static final String VERT = """
            #version 150 core
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    // MANDELBROT WITH SMOOTH ITERATION COLORING AND A DRIFTING COSINE PALETTE
    private static final String FRAG = """
            #version 150 core
            out vec4 fragColor;
            uniform vec2 uResolution;
            uniform vec2 uCenter;
            uniform float uZoom;
            uniform float uTime;
            void main() {
                vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution) / uResolution.y;
                vec2 c = uCenter + uv / uZoom;
                vec2 z = vec2(0.0);
                const int MAX = 256;
                int i = 0;
                for (; i < MAX; i++) {
                    z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c;
                    if (dot(z, z) > 4.0) break;
                }
                if (i == MAX) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
                float sm = float(i) - log2(log2(dot(z, z))) + 4.0;
                float t = sm / float(MAX);
                vec3 col = 0.5 + 0.5 * cos(6.28318 * (t + vec3(0.0, 0.33, 0.67)) + uTime);
                fragColor = vec4(col, 1.0);
            }
            """;

    @Test
    @DisplayName("Renders a fractal and encodes each frame to VP9/WebM")
    void capturesFractalToWebM() throws Exception {
        assumeTrue(!GraphicsEnvironment.isHeadless(), "No display — skipping GL fractal capture");
        assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable — skipping");

        GLFWErrorCallback.createPrint(System.err).set();
        assumeTrue(glfwInit(), "GLFW could not initialize — skipping");

        long window = NULL;
        ByteBuffer readBuf = null;   // GL READBACK (BOTTOM-UP)
        ByteBuffer frameBuf = null;  // TOP-DOWN COPY HANDED TO THE ENCODER
        final Path output = Path.of("build", "test-output", "fractal-vp9.webm");
        try {
            // VISIBLE OPENGL 3.2 CORE WINDOW, FIXED SIZE SO THE ENCODER DIMENSIONS STAY CONSTANT
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE);
            glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
            window = glfwCreateWindow(WIDTH, HEIGHT, "WaterMedia — VP9 fractal capture", NULL, NULL);
            assumeTrue(window != NULL, "GLFW window creation failed — skipping");
            glfwMakeContextCurrent(window);
            glfwSwapInterval(0); // ENCODING PACES US; DON'T ALSO WAIT FOR VSYNC
            GL.createCapabilities();

            // THE FRAMEBUFFER MAY DIFFER FROM THE WINDOW SIZE UNDER HiDPI — SIZE EVERYTHING TO THE REAL PIXELS
            final int[] fbw = new int[1];
            final int[] fbh = new int[1];
            glfwGetFramebufferSize(window, fbw, fbh);
            final int w = fbw[0];
            final int h = fbh[0];
            GL11.glViewport(0, 0, w, h);

            final int program = linkProgram(VERT, FRAG);
            final int vao = GL30.glGenVertexArrays(); // EMPTY VAO — CORE PROFILE REQUIRES ONE BOUND TO DRAW
            GL30.glBindVertexArray(vao);
            GL20.glUseProgram(program);
            final int uResolution = GL20.glGetUniformLocation(program, "uResolution");
            final int uCenter = GL20.glGetUniformLocation(program, "uCenter");
            final int uZoom = GL20.glGetUniformLocation(program, "uZoom");
            final int uTime = GL20.glGetUniformLocation(program, "uTime");
            GL20.glUniform2f(uResolution, w, h);
            GL20.glUniform2f(uCenter, -0.743643887037151f, 0.13182590420533f); // SEAHORSE VALLEY

            final long rowBytes = (long) w * 4;
            readBuf = MemoryUtil.memAlloc(w * h * 4);
            frameBuf = MemoryUtil.memAlloc(w * h * 4);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);

            Files.createDirectories(output.getParent());
            try (final WebMWriter writer = new WebMWriter(Files.newOutputStream(output), w, h, PixelFormat.RGBA, 30, 28)) {
                for (int f = 0; f < FRAMES && !glfwWindowShouldClose(window); f++) {
                    // ANIMATE — EXPONENTIAL ZOOM INTO THE VALLEY WITH A SLOWLY DRIFTING PALETTE
                    GL20.glUniform1f(uZoom, (float) (0.4 * Math.pow(1.02, f)));
                    GL20.glUniform1f(uTime, f * 0.03f);
                    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                    GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

                    // READ THE BACK BUFFER (BOTTOM-UP), THEN FLIP ROWS INTO TOP-DOWN ENCODER ORDER
                    readBuf.clear();
                    GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, readBuf);
                    final long srcAddr = MemoryUtil.memAddress(readBuf);
                    final long dstAddr = MemoryUtil.memAddress(frameBuf);
                    for (int y = 0; y < h; y++) {
                        MemoryUtil.memCopy(srcAddr + (long) (h - 1 - y) * rowBytes, dstAddr + (long) y * rowBytes, rowBytes);
                    }
                    frameBuf.clear();
                    writer.writeFrame(frameBuf);

                    glfwSwapBuffers(window); // SHOW THE FRAME WE JUST CAPTURED
                    glfwPollEvents();
                }
                assertTrue(writer.frameCount() > 0, "no frames were captured");
            }

            GL20.glUseProgram(0);
            GL30.glBindVertexArray(0);
            GL20.glDeleteProgram(program);
            GL30.glDeleteVertexArrays(vao);

            // VALIDATE THE PRODUCED FILE — REAL MATROSKA MAGIC AND SNIFFED AS VIDEO
            final byte[] webm = Files.readAllBytes(output);
            assertTrue(webm.length > 1024, "encoded WebM is suspiciously small: " + webm.length + " bytes");
            assertEquals((byte) 0x1A, webm[0]);
            assertEquals((byte) 0x45, webm[1]);
            assertEquals((byte) 0xDF, webm[2]);
            assertEquals((byte) 0xA3, webm[3]);
            assertEquals(MediaType.VIDEO, CodecsAPI.getMediaType(new ByteArrayInputStream(webm)));
            System.out.println("[WebMWriterFractalTest] wrote " + webm.length + " bytes -> " + output.toAbsolutePath());
        } finally {
            if (readBuf != null) MemoryUtil.memFree(readBuf);
            if (frameBuf != null) MemoryUtil.memFree(frameBuf);
            if (window != NULL) {
                glfwMakeContextCurrent(NULL);
                glfwDestroyWindow(window);
            }
            glfwTerminate();
            final GLFWErrorCallback cb = glfwSetErrorCallback(null);
            if (cb != null) cb.free();
        }
    }

    // COMPILES + LINKS THE FRACTAL PROGRAM (SHARED VERT+FRAG BOILERPLATE)
    private static int linkProgram(final String vertSrc, final String fragSrc) {
        final int vert = compile(GL20.GL_VERTEX_SHADER, vertSrc);
        final int frag = compile(GL20.GL_FRAGMENT_SHADER, fragSrc);
        final int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vert);
        GL20.glAttachShader(program, frag);
        GL20.glLinkProgram(program);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
            throw new IllegalStateException("Fractal program link failed: " + GL20.glGetProgramInfoLog(program, 2048));
        GL20.glDeleteShader(vert);
        GL20.glDeleteShader(frag);
        return program;
    }

    private static int compile(final int type, final String source) {
        final int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            final String log = GL20.glGetShaderInfoLog(shader, 2048);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("Fractal shader compile failed: " + log);
        }
        return shader;
    }
}
