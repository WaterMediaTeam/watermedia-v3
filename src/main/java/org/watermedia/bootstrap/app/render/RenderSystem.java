package org.watermedia.bootstrap.app.render;

import org.watermedia.bootstrap.app.render.opengl.OpenGLRenderBackend;

import java.awt.Color;
import java.nio.ByteBuffer;

/**
 * Static facade for the active UI render engine.
 */
public final class RenderSystem {

    private static RenderEngine engine = new RenderEngine(new OpenGLRenderBackend());

    private RenderSystem() {
    }

    public static RenderEngine engine() {
        return engine;
    }

    public static void setEngine(final RenderEngine nextEngine) {
        if (nextEngine == null) throw new IllegalArgumentException("Render engine cannot be null");
        engine = nextEngine;
    }

    public static void init() { engine.init(); }
    public static void cleanup() { engine.cleanup(); }
    public static void flush() { engine.flush(); }
    public static void configureFrameState() { engine.configureFrameState(); }
    public static void clear(final float r, final float g, final float b, final float a) { engine.clear(r, g, b, a); }
    public static void viewport(final int width, final int height) { engine.viewport(width, height); }
    public static void disableDepthTest() { engine.disableDepthTest(); }
    public static int createTexture(final int width, final int height, final ByteBuffer rgba) { return engine.createTexture(width, height, rgba); }
    public static TextureHandle createTextureHandle(final int width, final int height, final ByteBuffer rgba) { return engine.createTextureHandle(width, height, rgba); }
    public static void deleteTexture(final TextureHandle texture) { engine.deleteTexture(texture); }
    public static void setupOrtho(final int width, final int height) { engine.setupOrtho(width, height); }
    public static void restoreProjection() { engine.restoreProjection(); }
    public static void color(final Color c) { engine.color(c); }
    public static void color(final float r, final float g, final float b, final float a) { engine.color(r, g, b, a); }
    public static void color(final float r, final float g, final float b) { engine.color(r, g, b); }
    public static void bindTexture(final int textureId) { engine.bindTexture(textureId); }
    public static void clip(final int x, final int y, final int width, final int height, final int canvasHeight) { engine.clip(x, y, width, height, canvasHeight); }
    public static void clearClip() { engine.clearClip(); }
    public static void lineWidth(final float width) { engine.lineWidth(width); }
    public static void fill(final float x, final float y, final float w, final float h) { engine.fill(x, y, w, h); }
    public static void fill(final float x, final float y, final float w, final float h, final Color c) { engine.fill(x, y, w, h, c); }
    public static void fill(final float x, final float y, final float w, final float h, final float r, final float g, final float b, final float a) { engine.fill(x, y, w, h, r, g, b, a); }
    public static void fillGradientH(final float x, final float y, final float w, final float h, final float r1, final float g1, final float b1, final float a1, final float r2, final float g2, final float b2, final float a2) { engine.fillGradientH(x, y, w, h, r1, g1, b1, a1, r2, g2, b2, a2); }
    public static void fillGradientV(final float x, final float y, final float w, final float h, final float r1, final float g1, final float b1, final float a1, final float r2, final float g2, final float b2, final float a2) { engine.fillGradientV(x, y, w, h, r1, g1, b1, a1, r2, g2, b2, a2); }
    public static void fillTriangle(final float x1, final float y1, final float x2, final float y2, final float x3, final float y3, final float r, final float g, final float b, final float a) { engine.fillTriangle(x1, y1, x2, y2, x3, y3, r, g, b, a); }
    public static void fillRoundedTriangle(final float x1, final float y1, final float x2, final float y2, final float x3, final float y3, final float radius, final float r, final float g, final float b, final float a) { engine.fillRoundedTriangle(x1, y1, x2, y2, x3, y3, radius, r, g, b, a); }
    public static void fillCircle(final float cx, final float cy, final float radius, final float r, final float g, final float b, final float a) { engine.fillCircle(cx, cy, radius, r, g, b, a); }
    public static void fillRounded(final float x, final float y, final float w, final float h, final float radius) { engine.fillRounded(x, y, w, h, radius); }
    public static void fillRounded(final float x, final float y, final float w, final float h, final float radius, final float r, final float g, final float b, final float a) { engine.fillRounded(x, y, w, h, radius, r, g, b, a); }
    public static void fillRounded(final float x, final float y, final float w, final float h, final float radius, final Color c) { engine.fillRounded(x, y, w, h, radius, c); }
    public static void rect(final float x, final float y, final float w, final float h) { engine.rect(x, y, w, h); }
    public static void rect(final float x, final float y, final float w, final float h, final Color c, final float lineWidth) { engine.rect(x, y, w, h, c, lineWidth); }
    public static void rect(final float x, final float y, final float w, final float h, final float r, final float g, final float b, final float a, final float lineWidth) { engine.rect(x, y, w, h, r, g, b, a, lineWidth); }
    public static void rectRounded(final float x, final float y, final float w, final float h, final float radius, final float lineWidth) { engine.rectRounded(x, y, w, h, radius, lineWidth); }
    public static void rectRounded(final float x, final float y, final float w, final float h, final float radius, final float r, final float g, final float b, final float a, final float lineWidth) { engine.rectRounded(x, y, w, h, radius, r, g, b, a, lineWidth); }
    public static void rectRounded(final float x, final float y, final float w, final float h, final float radius, final Color c, final float lineWidth) { engine.rectRounded(x, y, w, h, radius, c, lineWidth); }
    public static void lineH(final float x, final float y, final float length) { engine.lineH(x, y, length); }
    public static void lineH(final float x, final float y, final float length, final Color c, final float lineWidth) { engine.lineH(x, y, length, c, lineWidth); }
    public static void lineH(final float x, final float y, final float length, final float r, final float g, final float b, final float a, final float lineWidth) { engine.lineH(x, y, length, r, g, b, a, lineWidth); }
    public static void lineV(final float x, final float y, final float length, final Color c, final float lineWidth) { engine.lineV(x, y, length, c, lineWidth); }
    public static void lineV(final float x, final float y, final float length) { engine.lineV(x, y, length); }
    public static void line(final float x1, final float y1, final float x2, final float y2) { engine.line(x1, y1, x2, y2); }
    public static void lineStrip(final float[] points) { engine.lineStrip(points); }
    public static void lineLoop(final float[] points) { engine.lineLoop(points); }
    public static void blit(final float x, final float y, final float w, final float h) { engine.blit(x, y, w, h); }
    public static void blit(final float x, final float y, final float w, final float h, final float u0, final float v0, final float u1, final float v1) { engine.blit(x, y, w, h, u0, v0, u1, v1); }
    public static void blitNDC(final float x1, final float y1, final float x2, final float y2) { engine.blitNDC(x1, y1, x2, y2); }
    public static void dialogBox(final float x, final float y, final float w, final float h, final Color borderColor, final float borderWidth) { engine.dialogBox(x, y, w, h, borderColor, borderWidth); }
    public static void dialogBox(final float x, final float y, final float w, final float h, final float r, final float g, final float b, final float a, final float borderWidth) { engine.dialogBox(x, y, w, h, r, g, b, a, borderWidth); }
    public static void fadeLeft(final float width, final float height, final float fadeWidth, final float alpha) { engine.fadeLeft(width, height, fadeWidth, alpha); }
    public static void fadeBottom(final float width, final float height, final float fadeHeight, final float alpha) { engine.fadeBottom(width, height, fadeHeight, alpha); }
    public static void glowRect(final float x, final float y, final float w, final float h, final float radius, final Color glow, final float alpha) { engine.glowRect(x, y, w, h, radius, glow, alpha); }
    public static void shadowRect(final float x, final float y, final float w, final float h, final float radius, final float alpha) { engine.shadowRect(x, y, w, h, radius, alpha); }
}
