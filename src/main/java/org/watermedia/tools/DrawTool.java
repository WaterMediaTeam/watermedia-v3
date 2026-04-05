package org.watermedia.tools;

import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.awt.Color;
import java.nio.FloatBuffer;

/**
 * Core Profile (OpenGL 3.2+) utility class for 2D drawing.
 * Uses VAO/VBO + shaders internally. Call {@link #init()} after GL context creation
 * and {@link #cleanup()} before GL context destruction.
 */
public final class DrawTool {

    private DrawTool() {}

    // GLSL 150 CORE SHADERS
    private static final String VERT_SRC = """
            #version 150 core
            in vec2 position;
            in vec2 texCoord;
            in vec4 vertColor;
            uniform mat4 projection;
            out vec2 vTexCoord;
            out vec4 vColor;
            void main() {
                vTexCoord = texCoord;
                vColor = vertColor;
                gl_Position = projection * vec4(position, 0.0, 1.0);
            }
            """;

    private static final String FRAG_SRC = """
            #version 150 core
            in vec2 vTexCoord;
            in vec4 vColor;
            uniform sampler2D tex;
            uniform float useTexture;
            out vec4 fragColor;
            void main() {
                if (useTexture > 0.5) {
                    fragColor = texture(tex, vTexCoord) * vColor;
                } else {
                    fragColor = vColor;
                }
            }
            """;

    // VERTEX FORMAT: position(2) + texCoord(2) + color(4) = 8 floats = 32 bytes
    private static final int FPV = 8;
    private static final int MAX_VERTS = 512;
    private static final float[] IDENTITY = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};

    // GL RESOURCES
    private static int program;
    private static int uProjection, uUseTexture;
    private static int vao, vbo;
    private static FloatBuffer uploadBuf;
    private static FloatBuffer projBuf;
    private static boolean initialized;

    // CURRENT STATE
    private static float curR = 1f, curG = 1f, curB = 1f, curA = 1f;
    private static final float[] verts = new float[MAX_VERTS * FPV];

    // ──────────────── LIFECYCLE ────────────────

    public static void init() {
        if (initialized) return;

        final int vert = compileShader(GL20.GL_VERTEX_SHADER, VERT_SRC);
        final int frag = compileShader(GL20.GL_FRAGMENT_SHADER, FRAG_SRC);

        program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vert);
        GL20.glAttachShader(program, frag);
        GL20.glBindAttribLocation(program, 0, "position");
        GL20.glBindAttribLocation(program, 1, "texCoord");
        GL20.glBindAttribLocation(program, 2, "vertColor");
        GL20.glLinkProgram(program);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            throw new RuntimeException("DrawTool link: " + GL20.glGetProgramInfoLog(program, 1024));
        }
        GL20.glDetachShader(program, vert);
        GL20.glDetachShader(program, frag);
        GL20.glDeleteShader(vert);
        GL20.glDeleteShader(frag);

        uProjection = GL20.glGetUniformLocation(program, "projection");
        uUseTexture = GL20.glGetUniformLocation(program, "useTexture");

        // SET SAMPLER TO TEXTURE UNIT 0
        GL20.glUseProgram(program);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "tex"), 0);
        GL20.glUseProgram(0);

        // VAO + VBO
        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) MAX_VERTS * FPV * Float.BYTES, GL15.GL_STREAM_DRAW);

        final int stride = FPV * Float.BYTES;
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, stride, 0);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 2L * Float.BYTES);
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, stride, 4L * Float.BYTES);

        GL30.glBindVertexArray(0);

        // CPU BUFFERS
        uploadBuf = MemoryUtil.memAllocFloat(MAX_VERTS * FPV);
        projBuf = MemoryUtil.memAllocFloat(16);
        projBuf.put(IDENTITY).flip();

        initialized = true;
    }

    public static void cleanup() {
        if (!initialized) return;
        GL20.glDeleteProgram(program);
        GL30.glDeleteVertexArrays(vao);
        GL15.glDeleteBuffers(vbo);
        MemoryUtil.memFree(uploadBuf);
        MemoryUtil.memFree(projBuf);
        initialized = false;
    }

    // ──────────────── INTERNAL ────────────────

    private static int compileShader(final int type, final String source) {
        final int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            final String log = GL20.glGetShaderInfoLog(shader, 1024);
            GL20.glDeleteShader(shader);
            throw new RuntimeException("DrawTool shader: " + log);
        }
        return shader;
    }

    private static void v(final int i, final float x, final float y,
                          final float u, final float tv,
                          final float r, final float g, final float b, final float a) {
        final int off = i * FPV;
        verts[off] = x; verts[off + 1] = y;
        verts[off + 2] = u; verts[off + 3] = tv;
        verts[off + 4] = r; verts[off + 5] = g; verts[off + 6] = b; verts[off + 7] = a;
    }

    private static void draw(final int mode, final int count, final boolean textured) {
        if (!initialized) init();

        GL20.glUseProgram(program);
        GL20.glUniformMatrix4fv(uProjection, false, projBuf);
        GL20.glUniform1f(uUseTexture, textured ? 1f : 0f);

        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

        uploadBuf.clear();
        uploadBuf.put(verts, 0, count * FPV);
        uploadBuf.flip();
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, uploadBuf);

        GL11.glDrawArrays(mode, 0, count);

        GL30.glBindVertexArray(0);
        GL20.glUseProgram(0);
    }

    // ──────────────── PROJECTION ────────────────

    public static void setupOrtho(final int width, final int height) {
        projBuf.clear();
        projBuf.put(2f / width).put(0).put(0).put(0);
        projBuf.put(0).put(-2f / height).put(0).put(0);
        projBuf.put(0).put(0).put(-1f).put(0);
        projBuf.put(-1f).put(1f).put(0).put(1f);
        projBuf.flip();
    }

    public static void restoreProjection() {
        projBuf.clear();
        projBuf.put(IDENTITY).flip();
    }

    // ──────────────── COLOR ────────────────

    public static void color(final Color c) {
        curR = c.getRed() / 255f; curG = c.getGreen() / 255f;
        curB = c.getBlue() / 255f; curA = c.getAlpha() / 255f;
    }

    public static void color(final float r, final float g, final float b, final float a) {
        curR = r; curG = g; curB = b; curA = a;
    }

    public static void color(final float r, final float g, final float b) {
        curR = r; curG = g; curB = b; curA = 1f;
    }

    // ──────────────── FILLED SHAPES ────────────────

    public static void fill(final float x, final float y, final float w, final float h) {
        v(0, x,     y,     0, 0, curR, curG, curB, curA);
        v(1, x + w, y,     0, 0, curR, curG, curB, curA);
        v(2, x + w, y + h, 0, 0, curR, curG, curB, curA);
        v(3, x,     y,     0, 0, curR, curG, curB, curA);
        v(4, x + w, y + h, 0, 0, curR, curG, curB, curA);
        v(5, x,     y + h, 0, 0, curR, curG, curB, curA);
        draw(GL11.GL_TRIANGLES, 6, false);
    }

    public static void fill(final float x, final float y, final float w, final float h, final Color c) {
        color(c);
        fill(x, y, w, h);
    }

    public static void fill(final float x, final float y, final float w, final float h,
                            final float r, final float g, final float b, final float a) {
        color(r, g, b, a);
        fill(x, y, w, h);
    }

    public static void fillGradientH(final float x, final float y, final float w, final float h,
                                      final float r1, final float g1, final float b1, final float a1,
                                      final float r2, final float g2, final float b2, final float a2) {
        v(0, x,     y,     0, 0, r1, g1, b1, a1);
        v(1, x,     y + h, 0, 0, r1, g1, b1, a1);
        v(2, x + w, y + h, 0, 0, r2, g2, b2, a2);
        v(3, x,     y,     0, 0, r1, g1, b1, a1);
        v(4, x + w, y + h, 0, 0, r2, g2, b2, a2);
        v(5, x + w, y,     0, 0, r2, g2, b2, a2);
        draw(GL11.GL_TRIANGLES, 6, false);
    }

    public static void fillGradientV(final float x, final float y, final float w, final float h,
                                      final float r1, final float g1, final float b1, final float a1,
                                      final float r2, final float g2, final float b2, final float a2) {
        v(0, x,     y,     0, 0, r1, g1, b1, a1);
        v(1, x + w, y,     0, 0, r1, g1, b1, a1);
        v(2, x + w, y + h, 0, 0, r2, g2, b2, a2);
        v(3, x,     y,     0, 0, r1, g1, b1, a1);
        v(4, x + w, y + h, 0, 0, r2, g2, b2, a2);
        v(5, x,     y + h, 0, 0, r2, g2, b2, a2);
        draw(GL11.GL_TRIANGLES, 6, false);
    }

    public static void fillTriangle(final float x1, final float y1,
                                    final float x2, final float y2,
                                    final float x3, final float y3,
                                    final float r, final float g, final float b, final float a) {
        color(r, g, b, a);
        v(0, x1, y1, 0, 0, curR, curG, curB, curA);
        v(1, x2, y2, 0, 0, curR, curG, curB, curA);
        v(2, x3, y3, 0, 0, curR, curG, curB, curA);
        draw(GL11.GL_TRIANGLES, 3, false);
    }

    public static void fillRoundedTriangle(final float x1, final float y1,
                                           final float x2, final float y2,
                                           final float x3, final float y3,
                                           final float radius,
                                           final float r, final float g, final float b, final float a) {
        color(r, g, b, a);

        float d12x = x2 - x1, d12y = y2 - y1;
        float d23x = x3 - x2, d23y = y3 - y2;
        float d31x = x1 - x3, d31y = y1 - y3;

        float len12 = (float) Math.sqrt(d12x * d12x + d12y * d12y);
        float len23 = (float) Math.sqrt(d23x * d23x + d23y * d23y);
        float len31 = (float) Math.sqrt(d31x * d31x + d31y * d31y);

        d12x /= len12; d12y /= len12;
        d23x /= len23; d23y /= len23;
        d31x /= len31; d31y /= len31;

        float inset = radius * 2f;
        float c1x = x1 + (d12x - d31x) * inset / 2f;
        float c1y = y1 + (d12y - d31y) * inset / 2f;
        float c2x = x2 + (d23x - d12x) * inset / 2f;
        float c2y = y2 + (d23y - d12y) * inset / 2f;
        float c3x = x3 + (d31x - d23x) * inset / 2f;
        float c3y = y3 + (d31y - d23y) * inset / 2f;

        float angle1Start = (float) Math.atan2(-d31y, -d31x);
        float angle1End = (float) Math.atan2(d12y, d12x);
        float angle2Start = (float) Math.atan2(-d12y, -d12x);
        float angle2End = (float) Math.atan2(d23y, d23x);
        float angle3Start = (float) Math.atan2(-d23y, -d23x);
        float angle3End = (float) Math.atan2(d31y, d31x);

        if (angle1End < angle1Start) angle1End += (float) (2 * Math.PI);
        if (angle2End < angle2Start) angle2End += (float) (2 * Math.PI);
        if (angle3End < angle3Start) angle3End += (float) (2 * Math.PI);

        final int arcSeg = 5;
        int idx = 0;

        float cx = (x1 + x2 + x3) / 3f;
        float cy = (y1 + y2 + y3) / 3f;
        v(idx++, cx, cy, 0, 0, curR, curG, curB, curA);

        for (int i = 0; i <= arcSeg; i++) {
            float angle = angle1Start + (angle1End - angle1Start) * i / arcSeg;
            v(idx++, c1x + (float) Math.cos(angle) * radius,
                     c1y + (float) Math.sin(angle) * radius,
                     0, 0, curR, curG, curB, curA);
        }
        for (int i = 0; i <= arcSeg; i++) {
            float angle = angle2Start + (angle2End - angle2Start) * i / arcSeg;
            v(idx++, c2x + (float) Math.cos(angle) * radius,
                     c2y + (float) Math.sin(angle) * radius,
                     0, 0, curR, curG, curB, curA);
        }
        for (int i = 0; i <= arcSeg; i++) {
            float angle = angle3Start + (angle3End - angle3Start) * i / arcSeg;
            v(idx++, c3x + (float) Math.cos(angle) * radius,
                     c3y + (float) Math.sin(angle) * radius,
                     0, 0, curR, curG, curB, curA);
        }
        v(idx++, c1x + (float) Math.cos(angle1Start) * radius,
                 c1y + (float) Math.sin(angle1Start) * radius,
                 0, 0, curR, curG, curB, curA);

        draw(GL11.GL_TRIANGLE_FAN, idx, false);
    }

    public static void fillCircle(final float cx, final float cy, final float radius,
                                  final float r, final float g, final float b, final float a) {
        color(r, g, b, a);
        final int segments = 16;
        v(0, cx, cy, 0, 0, curR, curG, curB, curA);
        for (int i = 0; i <= segments; i++) {
            final float angle = (float) (i * 2 * Math.PI / segments);
            v(1 + i, cx + (float) Math.cos(angle) * radius,
                     cy + (float) Math.sin(angle) * radius,
                     0, 0, curR, curG, curB, curA);
        }
        draw(GL11.GL_TRIANGLE_FAN, 2 + segments, false);
    }

    // ROUNDED RECTANGLES
    public static void fillRounded(final float x, final float y, final float w, final float h, float radius) {
        radius = Math.min(radius, Math.min(w, h) / 2f);
        fill(x + radius, y, w - 2 * radius, h);
        fill(x, y + radius, radius, h - 2 * radius);
        fill(x + w - radius, y + radius, radius, h - 2 * radius);
        fillArc(x + radius, y + radius, radius, (float) Math.PI, (float) (Math.PI * 1.5), 8);
        fillArc(x + w - radius, y + radius, radius, (float) (Math.PI * 1.5), (float) (Math.PI * 2), 8);
        fillArc(x + w - radius, y + h - radius, radius, 0, (float) (Math.PI * 0.5), 8);
        fillArc(x + radius, y + h - radius, radius, (float) (Math.PI * 0.5), (float) Math.PI, 8);
    }

    public static void fillRounded(final float x, final float y, final float w, final float h, final float radius,
                                    final float r, final float g, final float b, final float a) {
        color(r, g, b, a);
        fillRounded(x, y, w, h, radius);
    }

    public static void fillRounded(final float x, final float y, final float w, final float h,
                                    final float radius, final Color c) {
        color(c);
        fillRounded(x, y, w, h, radius);
    }

    private static void fillArc(final float cx, final float cy, final float radius,
                                 final float startAngle, final float endAngle, final int segments) {
        v(0, cx, cy, 0, 0, curR, curG, curB, curA);
        for (int i = 0; i <= segments; i++) {
            float angle = startAngle + (endAngle - startAngle) * i / segments;
            v(1 + i, cx + (float) Math.cos(angle) * radius,
                     cy + (float) Math.sin(angle) * radius,
                     0, 0, curR, curG, curB, curA);
        }
        draw(GL11.GL_TRIANGLE_FAN, 2 + segments, false);
    }

    // ──────────────── OUTLINES ────────────────

    public static void rect(final float x, final float y, final float w, final float h) {
        v(0, x,     y,     0, 0, curR, curG, curB, curA);
        v(1, x + w, y,     0, 0, curR, curG, curB, curA);
        v(2, x + w, y + h, 0, 0, curR, curG, curB, curA);
        v(3, x,     y + h, 0, 0, curR, curG, curB, curA);
        draw(GL11.GL_LINE_LOOP, 4, false);
    }

    public static void rect(final float x, final float y, final float w, final float h,
                            final Color c, final float lineWidth) {
        color(c);
        GL11.glLineWidth(lineWidth);
        rect(x, y, w, h);
    }

    public static void rect(final float x, final float y, final float w, final float h,
                            final float r, final float g, final float b, final float a, final float lineWidth) {
        color(r, g, b, a);
        GL11.glLineWidth(lineWidth);
        rect(x, y, w, h);
    }

    public static void rectRounded(final float x, final float y, final float w, final float h,
                                    float radius, final float lineWidth) {
        radius = Math.min(radius, Math.min(w, h) / 2f);
        GL11.glLineWidth(lineWidth);
        final int segments = 8;
        int idx = 0;
        for (int i = 0; i <= segments; i++) {
            float angle = (float) (-Math.PI / 2) + (float) (Math.PI / 2) * i / segments;
            v(idx++, x + w - radius + (float) Math.cos(angle) * radius,
                     y + radius + (float) Math.sin(angle) * radius,
                     0, 0, curR, curG, curB, curA);
        }
        for (int i = 0; i <= segments; i++) {
            float angle = (float) (Math.PI / 2) * i / segments;
            v(idx++, x + w - radius + (float) Math.cos(angle) * radius,
                     y + h - radius + (float) Math.sin(angle) * radius,
                     0, 0, curR, curG, curB, curA);
        }
        for (int i = 0; i <= segments; i++) {
            float angle = (float) (Math.PI / 2) + (float) (Math.PI / 2) * i / segments;
            v(idx++, x + radius + (float) Math.cos(angle) * radius,
                     y + h - radius + (float) Math.sin(angle) * radius,
                     0, 0, curR, curG, curB, curA);
        }
        for (int i = 0; i <= segments; i++) {
            float angle = (float) Math.PI + (float) (Math.PI / 2) * i / segments;
            v(idx++, x + radius + (float) Math.cos(angle) * radius,
                     y + radius + (float) Math.sin(angle) * radius,
                     0, 0, curR, curG, curB, curA);
        }
        draw(GL11.GL_LINE_LOOP, idx, false);
    }

    public static void rectRounded(final float x, final float y, final float w, final float h, final float radius,
                                    final float r, final float g, final float b, final float a, final float lineWidth) {
        color(r, g, b, a);
        rectRounded(x, y, w, h, radius, lineWidth);
    }

    public static void rectRounded(final float x, final float y, final float w, final float h, final float radius,
                                    final Color c, final float lineWidth) {
        color(c);
        rectRounded(x, y, w, h, radius, lineWidth);
    }

    // ──────────────── LINES ────────────────

    public static void lineH(final float x, final float y, final float length) {
        v(0, x,          y, 0, 0, curR, curG, curB, curA);
        v(1, x + length, y, 0, 0, curR, curG, curB, curA);
        draw(GL11.GL_LINES, 2, false);
    }

    public static void lineH(final float x, final float y, final float length, final Color c, final float lineWidth) {
        color(c);
        GL11.glLineWidth(lineWidth);
        lineH(x, y, length);
    }

    public static void lineH(final float x, final float y, final float length,
                              final float r, final float g, final float b, final float a, final float lineWidth) {
        color(r, g, b, a);
        GL11.glLineWidth(lineWidth);
        lineH(x, y, length);
    }

    public static void lineV(final float x, final float y, final float length, final Color c, final float lineWidth) {
        color(c);
        GL11.glLineWidth(lineWidth);
        lineV(x, y, length);
    }

    public static void lineV(final float x, final float y, final float length) {
        v(0, x, y,          0, 0, curR, curG, curB, curA);
        v(1, x, y + length, 0, 0, curR, curG, curB, curA);
        draw(GL11.GL_LINES, 2, false);
    }

    public static void line(final float x1, final float y1, final float x2, final float y2) {
        v(0, x1, y1, 0, 0, curR, curG, curB, curA);
        v(1, x2, y2, 0, 0, curR, curG, curB, curA);
        draw(GL11.GL_LINES, 2, false);
    }

    public static void lineStrip(final float[] points) {
        final int count = points.length / 2;
        for (int i = 0; i < count; i++) {
            v(i, points[i * 2], points[i * 2 + 1], 0, 0, curR, curG, curB, curA);
        }
        draw(GL11.GL_LINE_STRIP, count, false);
    }

    public static void lineLoop(final float[] points) {
        final int count = points.length / 2;
        for (int i = 0; i < count; i++) {
            v(i, points[i * 2], points[i * 2 + 1], 0, 0, curR, curG, curB, curA);
        }
        draw(GL11.GL_LINE_LOOP, count, false);
    }

    // ──────────────── TEXTURED QUADS ────────────────

    public static void blit(final float x, final float y, final float w, final float h) {
        v(0, x,     y,     0, 0, curR, curG, curB, curA);
        v(1, x + w, y,     1, 0, curR, curG, curB, curA);
        v(2, x + w, y + h, 1, 1, curR, curG, curB, curA);
        v(3, x,     y,     0, 0, curR, curG, curB, curA);
        v(4, x + w, y + h, 1, 1, curR, curG, curB, curA);
        v(5, x,     y + h, 0, 1, curR, curG, curB, curA);
        draw(GL11.GL_TRIANGLES, 6, true);
    }

    public static void blit(final float x, final float y, final float w, final float h,
                            final float u0, final float v0, final float u1, final float v1) {
        v(0, x,     y,     u0, v0, curR, curG, curB, curA);
        v(1, x + w, y,     u1, v0, curR, curG, curB, curA);
        v(2, x + w, y + h, u1, v1, curR, curG, curB, curA);
        v(3, x,     y,     u0, v0, curR, curG, curB, curA);
        v(4, x + w, y + h, u1, v1, curR, curG, curB, curA);
        v(5, x,     y + h, u0, v1, curR, curG, curB, curA);
        draw(GL11.GL_TRIANGLES, 6, true);
    }

    public static void blitNDC(final float x1, final float y1, final float x2, final float y2) {
        v(0, x1, y1, 0, 1, curR, curG, curB, curA);
        v(1, x1, y2, 0, 0, curR, curG, curB, curA);
        v(2, x2, y2, 1, 0, curR, curG, curB, curA);
        v(3, x1, y1, 0, 1, curR, curG, curB, curA);
        v(4, x2, y2, 1, 0, curR, curG, curB, curA);
        v(5, x2, y1, 1, 1, curR, curG, curB, curA);
        draw(GL11.GL_TRIANGLES, 6, true);
    }

    // ──────────────── DIALOG ────────────────

    public static void dialogBox(final float x, final float y, final float w, final float h,
                                  final Color borderColor, final float borderWidth) {
        fill(x, y, w, h, 0, 0, 0, 0.95f);
        rect(x, y, w, h, borderColor, borderWidth);
    }

    public static void dialogBox(final float x, final float y, final float w, final float h,
                                  final float r, final float g, final float b, final float a,
                                  final float borderWidth) {
        fill(x, y, w, h, 0, 0, 0, 0.95f);
        rect(x, y, w, h, r, g, b, a, borderWidth);
    }

    // ──────────────── FADE OVERLAYS ────────────────

    public static void fadeLeft(final float width, final float height, final float fadeWidth, final float alpha) {
        v(0, 0,         0,      0, 0, 0, 0, 0, alpha);
        v(1, 0,         height, 0, 0, 0, 0, 0, alpha);
        v(2, fadeWidth,  height, 0, 0, 0, 0, 0, 0);
        v(3, 0,         0,      0, 0, 0, 0, 0, alpha);
        v(4, fadeWidth,  height, 0, 0, 0, 0, 0, 0);
        v(5, fadeWidth,  0,      0, 0, 0, 0, 0, 0);
        draw(GL11.GL_TRIANGLES, 6, false);
    }

    public static void fadeBottom(final float width, final float height, final float fadeHeight, final float alpha) {
        final float topY = height - fadeHeight;
        v(0, 0,     topY,   0, 0, 0, 0, 0, 0);
        v(1, width, topY,   0, 0, 0, 0, 0, 0);
        v(2, width, height, 0, 0, 0, 0, 0, alpha);
        v(3, 0,     topY,   0, 0, 0, 0, 0, 0);
        v(4, width, height, 0, 0, 0, 0, 0, alpha);
        v(5, 0,     height, 0, 0, 0, 0, 0, alpha);
        draw(GL11.GL_TRIANGLES, 6, false);
    }

    // ──────────────── TEXTURE STATE (NO-OPS IN CORE PROFILE) ────────────────

    public static void enableTextures() { }

    public static void disableTextures() { }

    public static void bindTexture(final int textureId) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
    }
}
