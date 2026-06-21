package org.watermedia.bootstrap.app.render;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;

import java.awt.Color;
import java.nio.ByteBuffer;

/**
 * Backend-agnostic 2D render engine for the bootstrap app UI.
 */
public final class RenderEngine {

    private static final int FLOATS_PER_VERTEX = 8;
    private static final int MAX_VERTICES = 8192;
    private static final float[] IDENTITY = {
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
    };

    private final RenderBackend backend;
    private final Matrix4f projection = new Matrix4f();
    private final Vector4f color = new Vector4f(1f, 1f, 1f, 1f);
    private final float[] vertices = new float[MAX_VERTICES * FLOATS_PER_VERTEX];
    private final float[] batchVertices = new float[MAX_VERTICES * FLOATS_PER_VERTEX];
    private int boundTextureId = -1;
    private DrawMode batchMode;
    private boolean batchTextured;
    private int batchVertexCount;

    public RenderEngine(final RenderBackend backend) {
        this.backend = backend;
    }

    public void init() {
        this.backend.init();
        this.restoreProjection();
    }

    public void cleanup() {
        this.flush();
        this.backend.cleanup();
    }

    public void configureFrameState() {
        this.backend.configureFrameState();
    }

    public void clear(final float r, final float g, final float b, final float a) {
        this.flush();
        this.boundTextureId = -1;
        this.backend.clear(r, g, b, a);
    }

    public void viewport(final int width, final int height) {
        this.flush();
        this.backend.viewport(width, height);
    }

    public void disableDepthTest() {
        this.flush();
        this.backend.disableDepthTest();
    }

    public TextureHandle createTextureHandle(final int width, final int height, final ByteBuffer rgba) {
        this.flush();
        final TextureHandle texture = this.backend.createTexture(width, height, rgba);
        this.boundTextureId = -1;
        return texture;
    }

    public int createTexture(final int width, final int height, final ByteBuffer rgba) {
        return this.createTextureHandle(width, height, rgba).id();
    }

    public void deleteTexture(final TextureHandle texture) {
        this.flush();
        this.backend.deleteTexture(texture);
        this.boundTextureId = -1;
    }

    public void setupOrtho(final int width, final int height) {
        this.flush();
        this.projection.identity().ortho2D(0f, width, height, 0f);
        this.backend.useProjection(this.projection);
    }

    public void restoreProjection() {
        this.flush();
        this.projection.set(IDENTITY);
        this.backend.useProjection(this.projection);
    }

    public void color(final Color c) {
        this.color.set(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, c.getAlpha() / 255f);
    }

    public void color(final float r, final float g, final float b, final float a) {
        this.color.set(r, g, b, a);
    }

    public void color(final float r, final float g, final float b) {
        this.color.set(r, g, b, 1f);
    }

    public void bindTexture(final int textureId) {
        if (this.boundTextureId == textureId) return;
        this.flush();
        this.boundTextureId = textureId;
        this.backend.bindTexture(textureId);
    }

    public void clip(final int x, final int y, final int width, final int height, final int canvasHeight) {
        this.flush();
        this.backend.enableClip(x, y, width, height, canvasHeight);
    }

    public void clearClip() {
        this.flush();
        this.backend.disableClip();
    }

    public void lineWidth(final float width) {
        this.flush();
        this.backend.lineWidth(width);
    }

    public void flush() {
        if (this.batchVertexCount <= 0 || this.batchMode == null) return;
        this.backend.draw(this.batchMode, this.batchVertices, this.batchVertexCount, this.batchTextured);
        this.batchVertexCount = 0;
        this.batchMode = null;
    }

    public void fill(final float x, final float y, final float w, final float h) {
        final Vector4f c = this.color;
        put(0, x, y, 0f, 0f, c);
        put(1, x + w, y, 0f, 0f, c);
        put(2, x + w, y + h, 0f, 0f, c);
        put(3, x, y, 0f, 0f, c);
        put(4, x + w, y + h, 0f, 0f, c);
        put(5, x, y + h, 0f, 0f, c);
        this.draw(DrawMode.TRIANGLES, 6, false);
    }

    public void fill(final float x, final float y, final float w, final float h, final Color c) {
        this.color(c);
        this.fill(x, y, w, h);
    }

    public void fill(final float x, final float y, final float w, final float h,
                     final float r, final float g, final float b, final float a) {
        this.color(r, g, b, a);
        this.fill(x, y, w, h);
    }

    public void fillGradientH(final float x, final float y, final float w, final float h,
                              final float r1, final float g1, final float b1, final float a1,
                              final float r2, final float g2, final float b2, final float a2) {
        final Vector4f left = new Vector4f(r1, g1, b1, a1);
        final Vector4f right = new Vector4f(r2, g2, b2, a2);
        put(0, x, y, 0f, 0f, left);
        put(1, x, y + h, 0f, 0f, left);
        put(2, x + w, y + h, 0f, 0f, right);
        put(3, x, y, 0f, 0f, left);
        put(4, x + w, y + h, 0f, 0f, right);
        put(5, x + w, y, 0f, 0f, right);
        this.draw(DrawMode.TRIANGLES, 6, false);
    }

    public void fillGradientV(final float x, final float y, final float w, final float h,
                              final float r1, final float g1, final float b1, final float a1,
                              final float r2, final float g2, final float b2, final float a2) {
        final Vector4f top = new Vector4f(r1, g1, b1, a1);
        final Vector4f bottom = new Vector4f(r2, g2, b2, a2);
        put(0, x, y, 0f, 0f, top);
        put(1, x + w, y, 0f, 0f, top);
        put(2, x + w, y + h, 0f, 0f, bottom);
        put(3, x, y, 0f, 0f, top);
        put(4, x + w, y + h, 0f, 0f, bottom);
        put(5, x, y + h, 0f, 0f, bottom);
        this.draw(DrawMode.TRIANGLES, 6, false);
    }

    public void fillTriangle(final float x1, final float y1,
                             final float x2, final float y2,
                             final float x3, final float y3,
                             final float r, final float g, final float b, final float a) {
        this.color(r, g, b, a);
        put(0, x1, y1, 0f, 0f, this.color);
        put(1, x2, y2, 0f, 0f, this.color);
        put(2, x3, y3, 0f, 0f, this.color);
        this.draw(DrawMode.TRIANGLES, 3, false);
    }

    public void fillRoundedTriangle(final float x1, final float y1,
                                    final float x2, final float y2,
                                    final float x3, final float y3,
                                    final float radius,
                                    final float r, final float g, final float b, final float a) {
        this.color(r, g, b, a);

        final Vector2f p1 = new Vector2f(x1, y1);
        final Vector2f p2 = new Vector2f(x2, y2);
        final Vector2f p3 = new Vector2f(x3, y3);
        final Vector2f center = new Vector2f(p1).add(p2).add(p3).div(3f);

        put(0, center.x, center.y, 0f, 0f, this.color);
        int idx = 1;
        idx = roundedTriangleCorner(idx, p1, p2, p3, radius);
        idx = roundedTriangleCorner(idx, p2, p3, p1, radius);
        idx = roundedTriangleCorner(idx, p3, p1, p2, radius);
        put(idx++, this.vertices[FLOATS_PER_VERTEX], this.vertices[FLOATS_PER_VERTEX + 1], 0f, 0f, this.color);

        this.draw(DrawMode.TRIANGLE_FAN, idx, false);
    }

    private int roundedTriangleCorner(final int startIdx, final Vector2f point, final Vector2f next,
                                      final Vector2f prev, final float radius) {
        final Vector2f toNext = new Vector2f(next).sub(point).normalize();
        final Vector2f toPrev = new Vector2f(prev).sub(point).normalize();
        final Vector2f cornerCenter = new Vector2f(point).add(new Vector2f(toNext).add(toPrev).mul(radius));

        float start = (float) Math.atan2(toPrev.y, toPrev.x);
        float end = (float) Math.atan2(toNext.y, toNext.x);
        while (end < start) end += (float) (Math.PI * 2);

        int idx = startIdx;
        final int segments = 5;
        for (int i = 0; i <= segments; i++) {
            final float angle = start + (end - start) * i / segments;
            put(idx++, cornerCenter.x + (float) Math.cos(angle) * radius,
                    cornerCenter.y + (float) Math.sin(angle) * radius,
                    0f, 0f, this.color);
        }
        return idx;
    }

    public void fillCircle(final float cx, final float cy, final float radius,
                           final float r, final float g, final float b, final float a) {
        this.color(r, g, b, a);
        final int segments = 32;
        put(0, cx, cy, 0f, 0f, this.color);
        for (int i = 0; i <= segments; i++) {
            final float angle = (float) (i * 2 * Math.PI / segments);
            put(1 + i, cx + (float) Math.cos(angle) * radius,
                    cy + (float) Math.sin(angle) * radius,
                    0f, 0f, this.color);
        }
        this.draw(DrawMode.TRIANGLE_FAN, 2 + segments, false);
    }

    public void fillRounded(final float x, final float y, final float w, final float h, float radius) {
        if (radius <= 0f) {
            this.fill(x, y, w, h);
            return;
        }
        radius = Math.min(radius, Math.min(w, h) / 2f);
        this.fill(x + radius, y, w - 2 * radius, h);
        this.fill(x, y + radius, radius, h - 2 * radius);
        this.fill(x + w - radius, y + radius, radius, h - 2 * radius);
        this.fillArc(x + radius, y + radius, radius, (float) Math.PI, (float) (Math.PI * 1.5), 10);
        this.fillArc(x + w - radius, y + radius, radius, (float) (Math.PI * 1.5), (float) (Math.PI * 2), 10);
        this.fillArc(x + w - radius, y + h - radius, radius, 0, (float) (Math.PI * 0.5), 10);
        this.fillArc(x + radius, y + h - radius, radius, (float) (Math.PI * 0.5), (float) Math.PI, 10);
    }

    public void fillRounded(final float x, final float y, final float w, final float h, final float radius,
                            final float r, final float g, final float b, final float a) {
        this.color(r, g, b, a);
        this.fillRounded(x, y, w, h, radius);
    }

    public void fillRounded(final float x, final float y, final float w, final float h,
                            final float radius, final Color c) {
        this.color(c);
        this.fillRounded(x, y, w, h, radius);
    }

    public void rect(final float x, final float y, final float w, final float h) {
        put(0, x, y, 0f, 0f, this.color);
        put(1, x + w, y, 0f, 0f, this.color);
        put(2, x + w, y + h, 0f, 0f, this.color);
        put(3, x, y + h, 0f, 0f, this.color);
        this.draw(DrawMode.LINE_LOOP, 4, false);
    }

    public void rect(final float x, final float y, final float w, final float h,
                     final Color c, final float lineWidth) {
        this.color(c);
        this.lineWidth(lineWidth);
        this.rect(x, y, w, h);
    }

    public void rect(final float x, final float y, final float w, final float h,
                     final float r, final float g, final float b, final float a, final float lineWidth) {
        this.color(r, g, b, a);
        this.lineWidth(lineWidth);
        this.rect(x, y, w, h);
    }

    public void rectRounded(final float x, final float y, final float w, final float h,
                            float radius, final float lineWidth) {
        if (radius <= 0f) {
            this.lineWidth(lineWidth);
            this.rect(x, y, w, h);
            return;
        }
        radius = Math.min(radius, Math.min(w, h) / 2f);
        this.lineWidth(lineWidth);
        final int segments = 10;
        int idx = 0;
        idx = putArc(idx, x + w - radius, y + radius, radius, (float) (-Math.PI / 2), 0f, segments);
        idx = putArc(idx, x + w - radius, y + h - radius, radius, 0f, (float) (Math.PI / 2), segments);
        idx = putArc(idx, x + radius, y + h - radius, radius, (float) (Math.PI / 2), (float) Math.PI, segments);
        idx = putArc(idx, x + radius, y + radius, radius, (float) Math.PI, (float) (Math.PI * 1.5), segments);
        this.draw(DrawMode.LINE_LOOP, idx, false);
    }

    public void rectRounded(final float x, final float y, final float w, final float h, final float radius,
                            final float r, final float g, final float b, final float a, final float lineWidth) {
        this.color(r, g, b, a);
        this.rectRounded(x, y, w, h, radius, lineWidth);
    }

    public void rectRounded(final float x, final float y, final float w, final float h, final float radius,
                            final Color c, final float lineWidth) {
        this.color(c);
        this.rectRounded(x, y, w, h, radius, lineWidth);
    }

    public void lineH(final float x, final float y, final float length) {
        this.line(x, y, x + length, y);
    }

    public void lineH(final float x, final float y, final float length, final Color c, final float lineWidth) {
        this.color(c);
        this.lineWidth(lineWidth);
        this.lineH(x, y, length);
    }

    public void lineH(final float x, final float y, final float length,
                      final float r, final float g, final float b, final float a, final float lineWidth) {
        this.color(r, g, b, a);
        this.lineWidth(lineWidth);
        this.lineH(x, y, length);
    }

    public void lineV(final float x, final float y, final float length, final Color c, final float lineWidth) {
        this.color(c);
        this.lineWidth(lineWidth);
        this.lineV(x, y, length);
    }

    public void lineV(final float x, final float y, final float length) {
        this.line(x, y, x, y + length);
    }

    public void line(final float x1, final float y1, final float x2, final float y2) {
        put(0, x1, y1, 0f, 0f, this.color);
        put(1, x2, y2, 0f, 0f, this.color);
        this.draw(DrawMode.LINES, 2, false);
    }

    public void lineStrip(final float[] points) {
        final int count = points.length / 2;
        for (int i = 0; i < count; i++) {
            put(i, points[i * 2], points[i * 2 + 1], 0f, 0f, this.color);
        }
        this.draw(DrawMode.LINE_STRIP, count, false);
    }

    public void lineLoop(final float[] points) {
        final int count = points.length / 2;
        for (int i = 0; i < count; i++) {
            put(i, points[i * 2], points[i * 2 + 1], 0f, 0f, this.color);
        }
        this.draw(DrawMode.LINE_LOOP, count, false);
    }

    public void blit(final float x, final float y, final float w, final float h) {
        put(0, x, y, 0f, 0f, this.color);
        put(1, x + w, y, 1f, 0f, this.color);
        put(2, x + w, y + h, 1f, 1f, this.color);
        put(3, x, y, 0f, 0f, this.color);
        put(4, x + w, y + h, 1f, 1f, this.color);
        put(5, x, y + h, 0f, 1f, this.color);
        this.draw(DrawMode.TRIANGLES, 6, this.boundTextureId > 0);
    }

    public void blit(final float x, final float y, final float w, final float h,
                     final float u0, final float v0, final float u1, final float v1) {
        put(0, x, y, u0, v0, this.color);
        put(1, x + w, y, u1, v0, this.color);
        put(2, x + w, y + h, u1, v1, this.color);
        put(3, x, y, u0, v0, this.color);
        put(4, x + w, y + h, u1, v1, this.color);
        put(5, x, y + h, u0, v1, this.color);
        this.draw(DrawMode.TRIANGLES, 6, this.boundTextureId > 0);
    }

    public void blitNDC(final float x1, final float y1, final float x2, final float y2) {
        this.blitNDC(x1, y1, x2, y2, 0f, 0f, 1f, 1f);
    }

    public void blitNDC(final float x1, final float y1, final float x2, final float y2,
                        final float u0, final float v0, final float u1, final float v1) {
        put(0, x1, y1, u0, v1, this.color);
        put(1, x1, y2, u0, v0, this.color);
        put(2, x2, y2, u1, v0, this.color);
        put(3, x1, y1, u0, v1, this.color);
        put(4, x2, y2, u1, v0, this.color);
        put(5, x2, y1, u1, v1, this.color);
        this.draw(DrawMode.TRIANGLES, 6, this.boundTextureId > 0);
    }

    public void dialogBox(final float x, final float y, final float w, final float h,
                          final Color borderColor, final float borderWidth) {
        this.fill(x, y, w, h, 4f / 255f, 6f / 255f, 26f / 255f, 0.96f);
        this.rect(x, y, w, h, borderColor, borderWidth);
    }

    public void dialogBox(final float x, final float y, final float w, final float h,
                          final float r, final float g, final float b, final float a,
                          final float borderWidth) {
        this.fill(x, y, w, h, 4f / 255f, 6f / 255f, 26f / 255f, 0.96f);
        this.rect(x, y, w, h, r, g, b, a, borderWidth);
    }

    public void fadeLeft(final float width, final float height, final float fadeWidth, final float alpha) {
        put(0, 0, 0, 0f, 0f, new Vector4f(0f, 0f, 0f, alpha));
        put(1, 0, height, 0f, 0f, new Vector4f(0f, 0f, 0f, alpha));
        put(2, fadeWidth, height, 0f, 0f, new Vector4f(0f, 0f, 0f, 0f));
        put(3, 0, 0, 0f, 0f, new Vector4f(0f, 0f, 0f, alpha));
        put(4, fadeWidth, height, 0f, 0f, new Vector4f(0f, 0f, 0f, 0f));
        put(5, fadeWidth, 0, 0f, 0f, new Vector4f(0f, 0f, 0f, 0f));
        this.draw(DrawMode.TRIANGLES, 6, false);
    }

    public void fadeBottom(final float width, final float height, final float fadeHeight, final float alpha) {
        final float topY = height - fadeHeight;
        put(0, 0, topY, 0f, 0f, new Vector4f(0f, 0f, 0f, 0f));
        put(1, width, topY, 0f, 0f, new Vector4f(0f, 0f, 0f, 0f));
        put(2, width, height, 0f, 0f, new Vector4f(0f, 0f, 0f, alpha));
        put(3, 0, topY, 0f, 0f, new Vector4f(0f, 0f, 0f, 0f));
        put(4, width, height, 0f, 0f, new Vector4f(0f, 0f, 0f, alpha));
        put(5, 0, height, 0f, 0f, new Vector4f(0f, 0f, 0f, alpha));
        this.draw(DrawMode.TRIANGLES, 6, false);
    }

    public void glowRect(final float x, final float y, final float w, final float h,
                         final float radius, final Color glow, final float alpha) {
        final float r = glow.getRed() / 255f;
        final float g = glow.getGreen() / 255f;
        final float b = glow.getBlue() / 255f;
        for (int i = 10; i >= 1; i--) {
            final float spread = i * 2.2f;
            final float falloff = (float) Math.pow(i + 1f, 1.8f);
            if (radius <= 0f) {
                this.fill(x - spread, y - spread, w + spread * 2f, h + spread * 2f, r, g, b, alpha / falloff);
            } else {
                this.fillRounded(x - spread, y - spread, w + spread * 2f, h + spread * 2f,
                        radius + spread, r, g, b, alpha / falloff);
            }
        }
    }

    public void shadowRect(final float x, final float y, final float w, final float h,
                           final float radius, final float alpha) {
        for (int i = 4; i >= 1; i--) {
            final float spread = i * 3f;
            if (radius <= 0f) {
                this.fill(x + spread, y + spread, w, h, 0f, 0f, 0f, alpha / (i + 2f));
            } else {
                this.fillRounded(x + spread, y + spread, w, h,
                        radius, 0f, 0f, 0f, alpha / (i + 2f));
            }
        }
    }

    private void fillArc(final float cx, final float cy, final float radius,
                         final float startAngle, final float endAngle, final int segments) {
        put(0, cx, cy, 0f, 0f, this.color);
        for (int i = 0; i <= segments; i++) {
            final float angle = startAngle + (endAngle - startAngle) * i / segments;
            put(1 + i, cx + (float) Math.cos(angle) * radius,
                    cy + (float) Math.sin(angle) * radius,
                    0f, 0f, this.color);
        }
        this.draw(DrawMode.TRIANGLE_FAN, 2 + segments, false);
    }

    private int putArc(final int startIdx, final float cx, final float cy, final float radius,
                       final float startAngle, final float endAngle, final int segments) {
        int idx = startIdx;
        for (int i = 0; i <= segments; i++) {
            final float angle = startAngle + (endAngle - startAngle) * i / segments;
            put(idx++, cx + (float) Math.cos(angle) * radius,
                    cy + (float) Math.sin(angle) * radius,
                    0f, 0f, this.color);
        }
        return idx;
    }

    private void put(final int index, final float x, final float y,
                     final float u, final float v, final Vector4f c) {
        final int off = index * FLOATS_PER_VERTEX;
        this.vertices[off] = x;
        this.vertices[off + 1] = y;
        this.vertices[off + 2] = u;
        this.vertices[off + 3] = v;
        this.vertices[off + 4] = c.x;
        this.vertices[off + 5] = c.y;
        this.vertices[off + 6] = c.z;
        this.vertices[off + 7] = c.w;
    }

    private void draw(final DrawMode mode, final int count, final boolean textured) {
        if (count <= 0) return;
        if (mode != DrawMode.TRIANGLES && mode != DrawMode.LINES) {
            this.flush();
            this.backend.draw(mode, this.vertices, count, textured);
            return;
        }

        if (this.batchMode != mode || this.batchTextured != textured || this.batchVertexCount + count > MAX_VERTICES) {
            this.flush();
            this.batchMode = mode;
            this.batchTextured = textured;
        }

        System.arraycopy(this.vertices, 0,
                this.batchVertices, this.batchVertexCount * FLOATS_PER_VERTEX,
                count * FLOATS_PER_VERTEX);
        this.batchVertexCount += count;
    }
}
