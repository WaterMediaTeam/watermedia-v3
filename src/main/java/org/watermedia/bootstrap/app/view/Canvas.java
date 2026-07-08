package org.watermedia.bootstrap.app.view;

import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.bootstrap.app.ui.TextRenderer;

import java.awt.Color;

/**
 * Drawing surface handed to every {@link View#onDraw}. It is a thin, retained-tree-friendly facade over
 * {@link RenderSystem} plus the shared {@link TextRenderer}: views never touch the render backend
 * directly, so batching, clipping and the glyph atlas stay in one place. Coordinates are screen pixels
 * with the origin at the top-left; the active projection is expected to be the window ortho.
 */
public final class Canvas {

    private final TextRenderer text;
    private int windowWidth;
    private int windowHeight;

    public Canvas(final TextRenderer text) {
        this.text = text;
    }

    public Canvas viewport(final int width, final int height) {
        this.windowWidth = width;
        this.windowHeight = height;
        return this;
    }

    public TextRenderer text() {
        return this.text;
    }

    public int windowWidth() {
        return this.windowWidth;
    }

    public int windowHeight() {
        return this.windowHeight;
    }

    // SOLID FILL — RADIUS 0 STAYS A CRISP RECTANGLE (THE UI IS SQUARE-CORNERED BY DESIGN)
    public void fill(final float x, final float y, final float w, final float h, final Color color) {
        if (color == null) return;
        RenderSystem.fillRounded(x, y, w, h, 0f, color);
    }

    public void fillRound(final float x, final float y, final float w, final float h, final float radius, final Color color) {
        if (color == null) return;
        RenderSystem.fillRounded(x, y, w, h, radius, color);
    }

    public void stroke(final float x, final float y, final float w, final float h, final Color color, final float lineWidth) {
        if (color == null || lineWidth <= 0f) return;
        RenderSystem.rect(x, y, w, h, color, lineWidth);
    }

    public void strokeRound(final float x, final float y, final float w, final float h, final float radius,
                            final Color color, final float lineWidth) {
        if (color == null || lineWidth <= 0f) return;
        RenderSystem.rectRounded(x, y, w, h, radius, color, lineWidth);
    }

    public void glow(final float x, final float y, final float w, final float h, final float radius,
                     final Color color, final float alpha) {
        if (color == null || alpha <= 0f) return;
        RenderSystem.glowRect(x, y, w, h, radius, color, alpha);
    }

    public void shadow(final float x, final float y, final float w, final float h, final float radius, final float alpha) {
        if (alpha <= 0f) return;
        RenderSystem.shadowRect(x, y, w, h, radius, alpha);
    }

    public void gradientV(final float x, final float y, final float w, final float h, final Color top, final Color bottom) {
        RenderSystem.fillGradientV(x, y, w, h,
                top.getRed() / 255f, top.getGreen() / 255f, top.getBlue() / 255f, top.getAlpha() / 255f,
                bottom.getRed() / 255f, bottom.getGreen() / 255f, bottom.getBlue() / 255f, bottom.getAlpha() / 255f);
    }

    public void line(final float x1, final float y1, final float x2, final float y2, final Color color, final float lineWidth) {
        RenderSystem.color(color);
        RenderSystem.lineWidth(lineWidth);
        RenderSystem.line(x1, y1, x2, y2);
    }

    // TEXTURED BLIT WITH AN OPTIONAL TINT — A NULL TINT DRAWS THE IMAGE AT FULL WHITE
    public void image(final int textureId, final float x, final float y, final float w, final float h, final Color tint) {
        if (textureId <= 0) return;
        RenderSystem.bindTexture(textureId);
        if (tint == null) {
            RenderSystem.color(1f, 1f, 1f, 1f);
        } else {
            RenderSystem.color(tint);
        }
        RenderSystem.blit(x, y, w, h);
    }

    // TEXTURED BLIT SAMPLING A SUB-REGION (UV RECT) — USED FOR COVER-FIT CROPPING WITHOUT DISTORTION
    public void image(final int textureId, final float x, final float y, final float w, final float h,
                      final float u0, final float v0, final float u1, final float v1, final Color tint) {
        if (textureId <= 0) return;
        RenderSystem.bindTexture(textureId);
        if (tint == null) {
            RenderSystem.color(1f, 1f, 1f, 1f);
        } else {
            RenderSystem.color(tint);
        }
        RenderSystem.blit(x, y, w, h, u0, v0, u1, v1);
    }

    public void text(final String value, final float x, final float y, final Color color, final float scale, final boolean bold) {
        if (value == null || value.isEmpty()) return;
        if (bold) {
            this.text.renderBold(value, x, y, color, scale);
        } else {
            this.text.render(value, x, y, color, scale);
        }
    }

    public int textWidth(final String value, final float scale, final boolean bold) {
        return bold ? this.text.widthBold(value, scale) : this.text.width(value, scale);
    }

    public int textHeight(final float scale, final boolean bold) {
        return bold ? this.text.glyphHeightBold(scale) : this.text.glyphHeight(scale);
    }

    // SCISSOR CLIP — CALLS MUST BE BALANCED WITH popClip; THE BACKEND KEEPS A SINGLE RECTANGLE, SO NESTED
    // CLIPS ARE INTERSECTED BY THE CALLER (ScrollView) RATHER THAN STACKED HERE
    public void pushClip(final int x, final int y, final int w, final int h) {
        RenderSystem.clip(x, y, w, h, this.windowHeight);
    }

    public void popClip() {
        RenderSystem.clearClip();
    }
}
