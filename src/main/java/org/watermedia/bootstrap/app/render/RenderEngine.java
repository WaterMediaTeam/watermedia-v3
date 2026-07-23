package org.watermedia.bootstrap.app.render;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.watermedia.api.media.engines.GFXEngine;

import java.awt.Color;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Backend-agnostic 2D render engine for the bootstrap app UI.
 */
public final class RenderEngine {

    private static final int FLOATS_PER_VERTEX = 8;
    // SHARED UI VERTEX CAPACITY — THE GL BACKEND SIZES ITS STREAM VBO TO THE SAME VALUE; KEEP THEM IN SYNC.
    private static final int MAX_VERTICES = 8192;
    // SDF SOFT-RECT FALLOFF DISTANCES (PIXELS) — GLOW MATCHES THE OLD 10-FILL SPREAD (10 * 2.2); SHADOW IS
    // A SMALLER OFFSET HALO. TUNE THESE IF THE GLOW/SHADOW READS TOO TIGHT OR TOO SOFT.
    private static final float GLOW_SOFTNESS = 22f;
    private static final float SHADOW_SOFTNESS = 12f;
    private static final float SHADOW_DX = 2f;
    private static final float SHADOW_DY = 6f;
    private static final float GLOW_STRENGTH = 0.9f; // OVERALL GLOW FORCE, REDUCED 10%
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
    // ACTIVE PROJECTION TRACKING — (-1,-1)=IDENTITY, POSITIVE=ORTHO DIMS, MIN_VALUE=UNSET. LETS REPEATED
    // setupOrtho()/restoreProjection() CALLS WITH THE SAME VALUE SKIP THE FLUSH + RE-UPLOAD (BATCH COALESCING).
    private int orthoW = Integer.MIN_VALUE;
    private int orthoH = Integer.MIN_VALUE;
    // GLOBAL UI SCALE (PHYSICAL PX PER LOGICAL PX). APPLIED AT THE TWO LOGICAL->PHYSICAL SEAMS ONLY:
    // SCISSOR RECTS AND RASTERIZED LINE WIDTHS. GEOMETRY AND THE VIEWPORT ARE NOT TOUCHED — QUADS
    // ALREADY SCALE THROUGH THE ORTHO PROJECTION AND THE VIEWPORT STAYS PHYSICAL. DORMANT AT 1f.
    private float uiScale = 1f;

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

    public int createTexture(final int width, final int height, final ByteBuffer rgba) {
        this.flush();
        final int id = this.backend.createTexture(width, height, rgba).id();
        this.boundTextureId = -1;
        return id;
    }

    public void deleteTexture(final int textureId) {
        this.flush();
        // ONLY THE ID IS KNOWN AT THE APP SEAM; BOTH BACKENDS DELETE BY ID (w/h UNUSED), SO ZERO EXTENTS ARE FINE.
        this.backend.deleteTexture(new TextureHandle(textureId, 0, 0));
        this.boundTextureId = -1;
    }

    public void setupOrtho(final int width, final int height) {
        if (this.orthoW == width && this.orthoH == height) return; // ALREADY ACTIVE — KEEP BATCHING, NO FLUSH/RE-UPLOAD
        this.flush();
        this.orthoW = width;
        this.orthoH = height;
        this.projection.identity().ortho2D(0f, width, height, 0f);
        this.backend.useProjection(this.projection);
    }

    private void restoreProjection() {
        if (this.orthoW == -1 && this.orthoH == -1) return; // IDENTITY ALREADY ACTIVE
        this.flush();
        this.orthoW = -1;
        this.orthoH = -1;
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

    public void bindMediaTexture(final long handle) {
        this.flush();
        // SENTINEL: A MEDIA TEXTURE IS BOUND SO blit() EMITS TEXTURED VERTICES. A 64-BIT VULKAN
        // VkImageView DOES NOT FIT boundTextureId, AND MAX_VALUE NEVER COLLIDES WITH A REAL GL ID.
        this.boundTextureId = handle != 0L ? Integer.MAX_VALUE : -1;
        this.backend.bindMediaTexture(handle);
    }

    public void beginFrame() {
        this.backend.beginFrame();
    }

    public void present() {
        this.flush();
        this.backend.present();
    }

    public Supplier<GFXEngine> mediaEngineSupplier(final Thread renderThread, final Executor renderExecutor) {
        return this.backend.mediaEngineSupplier(renderThread, renderExecutor);
    }

    public String deviceName() {
        return this.backend.deviceName();
    }

    public String deviceVersion() {
        return this.backend.deviceVersion();
    }

    /**
     * Sets the UI scale (physical pixels per logical pixel) this engine converts with. Scissor
     * rectangles and rasterized line widths arrive in logical pixels and are mapped to physical
     * pixels using this factor; quad geometry and the viewport are unaffected.
     */
    public void uiScale(final float scale) {
        this.uiScale = scale > 0f ? scale : 1f;
    }

    /** The UI scale (physical pixels per logical pixel) this engine converts with. */
    public float uiScale() {
        return this.uiScale;
    }

    public void clip(final int x, final int y, final int width, final int height, final int canvasHeight) {
        this.flush();
        // LOGICAL -> PHYSICAL: ROUND THE EDGES (NOT THE SIZES) SO ADJACENT CLIPS STAY GAP-FREE AT ANY
        // SCALE; AT uiScale=1 EVERY ROUND IS AN EXACT IDENTITY. THE BACKENDS ONLY FLIP/CLAMP PHYSICAL INTS.
        final float s = this.uiScale;
        final int px = Math.round(x * s);
        final int py = Math.round(y * s);
        final int pw = Math.round((x + width) * s) - px;
        final int ph = Math.round((y + height) * s) - py;
        this.backend.clip(px, py, pw, ph, Math.round(canvasHeight * s));
    }

    public void clearClip() {
        this.flush();
        this.backend.clearClip();
    }

    public void lineWidth(final float width) {
        this.flush();
        // RASTERIZER LINE WIDTH (glLineWidth / vkCmdSetLineWidth) IS PHYSICAL PIXELS — SCALE IT HERE.
        // QUAD-BASED STROKES (strokeQuads) ARE LOGICAL GEOMETRY AND ALREADY SCALE WITH THE ORTHO.
        this.backend.lineWidth(width * this.uiScale);
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
        put(0, x, y, 0f, 0f, r1, g1, b1, a1);
        put(1, x, y + h, 0f, 0f, r1, g1, b1, a1);
        put(2, x + w, y + h, 0f, 0f, r2, g2, b2, a2);
        put(3, x, y, 0f, 0f, r1, g1, b1, a1);
        put(4, x + w, y + h, 0f, 0f, r2, g2, b2, a2);
        put(5, x + w, y, 0f, 0f, r2, g2, b2, a2);
        this.draw(DrawMode.TRIANGLES, 6, false);
    }

    public void fillGradientV(final float x, final float y, final float w, final float h,
                              final float r1, final float g1, final float b1, final float a1,
                              final float r2, final float g2, final float b2, final float a2) {
        put(0, x, y, 0f, 0f, r1, g1, b1, a1);
        put(1, x + w, y, 0f, 0f, r1, g1, b1, a1);
        put(2, x + w, y + h, 0f, 0f, r2, g2, b2, a2);
        put(3, x, y, 0f, 0f, r1, g1, b1, a1);
        put(4, x + w, y + h, 0f, 0f, r2, g2, b2, a2);
        put(5, x, y + h, 0f, 0f, r2, g2, b2, a2);
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

    public void rect(final float x, final float y, final float w, final float h,
                     final Color c, final float lineWidth) {
        this.color(c);
        this.strokeQuads(x, y, w, h, lineWidth);
    }

    public void rect(final float x, final float y, final float w, final float h,
                     final float r, final float g, final float b, final float a, final float lineWidth) {
        this.color(r, g, b, a);
        this.strokeQuads(x, y, w, h, lineWidth);
    }

    // A BOX OUTLINE AS FOUR EDGE QUADS CENTERED ON THE PATH (LIKE A LINE_LOOP OF THAT WIDTH), CORNERS
    // COVERED ONCE BY THE VERTICAL EDGES SO A TRANSLUCENT BORDER IS NOT DOUBLED. UNLIKE LINE_LOOP THESE
    // FILLS JOIN THE TRIANGLE BATCH — NO PER-BORDER FLUSH OR LINE-WIDTH STATE CHANGE.
    private void strokeQuads(final float x, final float y, final float w, final float h, final float lineWidth) {
        final float lw = Math.max(1f, lineWidth);
        final float half = lw / 2f;
        this.fill(x - half, y - half, lw, h + lw);
        this.fill(x + w - half, y - half, lw, h + lw);
        if (w > lw) {
            this.fill(x + half, y - half, w - lw, lw);
            this.fill(x + half, y + h - half, w - lw, lw);
        }
    }

    public void rectRounded(final float x, final float y, final float w, final float h,
                            float radius, final float lineWidth) {
        if (radius <= 0f) {
            this.strokeQuads(x, y, w, h, lineWidth);
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

    public void fadeBottom(final float width, final float height, final float fadeHeight, final float alpha) {
        final float topY = height - fadeHeight;
        put(0, 0, topY, 0f, 0f, 0f, 0f, 0f, 0f);
        put(1, width, topY, 0f, 0f, 0f, 0f, 0f, 0f);
        put(2, width, height, 0f, 0f, 0f, 0f, 0f, alpha);
        put(3, 0, topY, 0f, 0f, 0f, 0f, 0f, 0f);
        put(4, width, height, 0f, 0f, 0f, 0f, 0f, alpha);
        put(5, 0, height, 0f, 0f, 0f, 0f, 0f, alpha);
        this.draw(DrawMode.TRIANGLES, 6, false);
    }

    public void glowRect(final float x, final float y, final float w, final float h,
                         final float radius, final Color glow, final float alpha) {
        final float r = glow.getRed() / 255f;
        final float g = glow.getGreen() / 255f;
        final float b = glow.getBlue() / 255f;
        final float a = alpha * glowPulse(); // -10% FORCE, MODULATED BY A SLOW RANDOM HEARTBEAT
        if (this.backend.supportsSoftRect()) {
            // ONE SDF QUAD INSTEAD OF 10 STACKED FILLS — SAME HALO, A FRACTION OF THE OVERDRAW
            this.flush();
            this.backend.softRect(x, y, w, h, radius, r, g, b, a, GLOW_SOFTNESS);
            return;
        }
        for (int i = 10; i >= 1; i--) {
            final float spread = i * 2.2f;
            final float falloff = (float) Math.pow(i + 1f, 1.8f);
            if (radius <= 0f) {
                this.fill(x - spread, y - spread, w + spread * 2f, h + spread * 2f, r, g, b, a / falloff);
            } else {
                this.fillRounded(x - spread, y - spread, w + spread * 2f, h + spread * 2f,
                        radius + spread, r, g, b, a / falloff);
            }
        }
    }

    // SLOW, NON-REPEATING "HEARTBEAT" MODULATION OF THE GLOW FORCE. THREE INCOMMENSURATE SINES NEVER LOOP
    // EXACTLY, SO THE PULSE FEELS ORGANIC/RANDOM AND BREATHES OVER ~10-30 SECONDS. RESULT IN [0.74, 0.9].
    private static float glowPulse() {
        final double t = System.currentTimeMillis() * 0.001;
        final double wave = Math.sin(t * 0.45) + 0.6 * Math.sin(t * 0.19 + 1.3) + 0.35 * Math.sin(t * 0.83 + 2.7);
        final double n = Math.max(0.0, Math.min(1.0, wave * 0.256 + 0.5));
        return (float) (GLOW_STRENGTH * (0.82 + 0.18 * n));
    }

    public void shadowRect(final float x, final float y, final float w, final float h,
                           final float radius, final float alpha) {
        if (this.backend.supportsSoftRect()) {
            // ONE SDF QUAD (BLACK, OFFSET DOWN-RIGHT) INSTEAD OF 4 STACKED FILLS
            this.flush();
            this.backend.softRect(x + SHADOW_DX, y + SHADOW_DY, w, h, radius, 0f, 0f, 0f, alpha, SHADOW_SOFTNESS);
            return;
        }
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
        this.put(index, x, y, u, v, c.x, c.y, c.z, c.w);
    }

    // SCALAR COLOR OVERLOAD — LETS GRADIENT/FADE VERTICES BE EMITTED WITHOUT ALLOCATING A Vector4f PER CALL
    private void put(final int index, final float x, final float y,
                     final float u, final float v,
                     final float r, final float g, final float b, final float a) {
        final int off = index * FLOATS_PER_VERTEX;
        this.vertices[off] = x;
        this.vertices[off + 1] = y;
        this.vertices[off + 2] = u;
        this.vertices[off + 3] = v;
        this.vertices[off + 4] = r;
        this.vertices[off + 5] = g;
        this.vertices[off + 6] = b;
        this.vertices[off + 7] = a;
    }

    private void draw(final DrawMode mode, final int count, final boolean textured) {
        if (count <= 0) return;
        // GUARD THE SCRATCH/BATCH ARRAYS (AND THE GL VBO) AT THE SINGLE EMIT SEAM SO NO PRIMITIVE OVERFLOWS.
        if (count > MAX_VERTICES) throw new IllegalArgumentException("UI draw exceeds vertex capacity: " + count + " > " + MAX_VERTICES);
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
