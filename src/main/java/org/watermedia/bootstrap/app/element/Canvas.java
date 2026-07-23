package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.bootstrap.app.ui.PixelIcon;
import org.watermedia.bootstrap.app.ui.TextRenderer;

import java.awt.Color;

/**
 * Drawing surface handed to every {@link Element#onDraw}. It is a thin, retained-tree-friendly facade over
 * {@link RenderSystem} plus the shared {@link TextRenderer}: views never touch the render backend
 * directly, so batching, clipping and the glyph atlas stay in one place. Coordinates are screen pixels
 * with the origin at the top-left; the active projection is expected to be the window ortho.
 *
 * <p>A global alpha multiplier ({@link #pushAlpha}/{@link #popAlpha}) scales the opacity of every draw
 * call — fills, strokes, gradients, glows, shadows, images, icons and text — so a container can fade its
 * whole subtree in one place (dialog fade-in).
 */
public final class Canvas {

    private final TextRenderer text;
    // GLOBAL ALPHA MULTIPLIER STACK — MULTIPLICATIVE AND NESTABLE; 16 LEVELS IS FAR BEYOND ANY REAL TREE
    private final float[] alphaStack = new float[16];
    private int alphaDepth;
    private float alpha = 1f;
    private int windowWidth;
    private int windowHeight;

    public Canvas(final TextRenderer text) {
        this.text = text;
    }

    /**
     * Pushes a global alpha multiplier applied to every subsequent draw call. Factors compose
     * multiplicatively when nested; each call must be balanced with {@link #popAlpha()}.
     */
    public void pushAlpha(final float factor) {
        // SATURATE AT CAPACITY — 16 NESTED FADES IS BEYOND ANY REAL TREE; PAST IT WE KEEP MULTIPLYING BUT
        // STOP RECORDING SO A RUNAWAY OR UNBALANCED PUSH CAN NEVER WRITE OUT OF BOUNDS (POP GUARDS UNDERFLOW)
        if (this.alphaDepth < this.alphaStack.length) this.alphaStack[this.alphaDepth] = this.alpha;
        this.alphaDepth++;
        this.alpha *= Math.max(0f, Math.min(1f, factor));
    }

    /** Restores the alpha multiplier active before the matching {@link #pushAlpha(float)}. */
    public void popAlpha() {
        if (this.alphaDepth > 0) {
            this.alphaDepth--;
            if (this.alphaDepth < this.alphaStack.length) this.alpha = this.alphaStack[this.alphaDepth];
        }
    }

    // APPLIES THE GLOBAL ALPHA MULTIPLIER TO A COLOR — IDENTITY (NO ALLOCATION) WHEN NO FADE IS ACTIVE
    private Color mul(final Color c) {
        if (c == null || this.alpha >= 1f) return c;
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(c.getAlpha() * this.alpha));
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

    /** Solid fill; radius 0 stays a crisp rectangle (the UI is square-cornered by design). */
    public void fill(final float x, final float y, final float w, final float h, final Color color) {
        if (color == null) return;
        RenderSystem.fillRounded(x, y, w, h, 0f, this.mul(color));
    }

    /** Solid fill from raw RGBA components — for hairlines and overlays whose alpha is not a theme color. */
    public void fill(final float x, final float y, final float w, final float h,
                     final float r, final float g, final float b, final float a) {
        RenderSystem.fill(x, y, w, h, r, g, b, a * this.alpha);
    }

    public void fillRound(final float x, final float y, final float w, final float h, final float radius, final Color color) {
        if (color == null) return;
        RenderSystem.fillRounded(x, y, w, h, radius, this.mul(color));
    }

    public void stroke(final float x, final float y, final float w, final float h, final Color color, final float lineWidth) {
        if (color == null || lineWidth <= 0f) return;
        RenderSystem.rect(x, y, w, h, this.mul(color), lineWidth);
    }

    public void strokeRound(final float x, final float y, final float w, final float h, final float radius,
                            final Color color, final float lineWidth) {
        if (color == null || lineWidth <= 0f) return;
        RenderSystem.rectRounded(x, y, w, h, radius, this.mul(color), lineWidth);
    }

    public void glow(final float x, final float y, final float w, final float h, final float radius,
                     final Color color, final float alpha) {
        if (color == null || alpha <= 0f) return;
        RenderSystem.glowRect(x, y, w, h, radius, color, alpha * this.alpha);
    }

    public void shadow(final float x, final float y, final float w, final float h, final float radius, final float alpha) {
        if (alpha <= 0f) return;
        RenderSystem.shadowRect(x, y, w, h, radius, alpha * this.alpha);
    }

    public void gradientV(final float x, final float y, final float w, final float h, final Color top, final Color bottom) {
        RenderSystem.fillGradientV(x, y, w, h,
                top.getRed() / 255f, top.getGreen() / 255f, top.getBlue() / 255f, top.getAlpha() / 255f * this.alpha,
                bottom.getRed() / 255f, bottom.getGreen() / 255f, bottom.getBlue() / 255f, bottom.getAlpha() / 255f * this.alpha);
    }

    /** Vertical gradient from raw RGBA stops — for bands whose per-stop alpha is not a theme color's alpha. */
    public void gradientV(final float x, final float y, final float w, final float h,
                          final float r1, final float g1, final float b1, final float a1,
                          final float r2, final float g2, final float b2, final float a2) {
        RenderSystem.fillGradientV(x, y, w, h, r1, g1, b1, a1 * this.alpha, r2, g2, b2, a2 * this.alpha);
    }

    public void gradientH(final float x, final float y, final float w, final float h, final Color left, final Color right) {
        RenderSystem.fillGradientH(x, y, w, h,
                left.getRed() / 255f, left.getGreen() / 255f, left.getBlue() / 255f, left.getAlpha() / 255f * this.alpha,
                right.getRed() / 255f, right.getGreen() / 255f, right.getBlue() / 255f, right.getAlpha() / 255f * this.alpha);
    }

    /** Horizontal gradient from raw RGBA stops — see {@link #gradientV}. */
    public void gradientH(final float x, final float y, final float w, final float h,
                          final float r1, final float g1, final float b1, final float a1,
                          final float r2, final float g2, final float b2, final float a2) {
        RenderSystem.fillGradientH(x, y, w, h, r1, g1, b1, a1 * this.alpha, r2, g2, b2, a2 * this.alpha);
    }

    /** Draws a straight line between two points at the given width. */
    public void line(final float x1, final float y1, final float x2, final float y2, final Color color, final float lineWidth) {
        RenderSystem.color(this.mul(color));
        RenderSystem.lineWidth(lineWidth);
        RenderSystem.line(x1, y1, x2, y2);
    }

    /** Axis-aligned horizontal rule — a hairline/underline of the given length and weight. */
    public void lineH(final float x, final float y, final float length, final Color color, final float lineWidth) {
        if (color == null || lineWidth <= 0f) return;
        RenderSystem.lineH(x, y, length, this.mul(color), lineWidth);
    }

    /** Textured blit with an optional tint — a null tint draws the image at full white. */
    public void image(final int textureId, final float x, final float y, final float w, final float h, final Color tint) {
        if (textureId <= 0) return;
        RenderSystem.bindTexture(textureId);
        if (tint == null) {
            RenderSystem.color(1f, 1f, 1f, this.alpha);
        } else {
            RenderSystem.color(this.mul(tint));
        }
        RenderSystem.blit(x, y, w, h);
    }

    /** Textured blit sampling a sub-region (UV rect) — used for cover-fit cropping without distortion. */
    public void image(final int textureId, final float x, final float y, final float w, final float h,
                      final float u0, final float v0, final float u1, final float v1, final Color tint) {
        if (textureId <= 0) return;
        RenderSystem.bindTexture(textureId);
        if (tint == null) {
            RenderSystem.color(1f, 1f, 1f, this.alpha);
        } else {
            RenderSystem.color(this.mul(tint));
        }
        RenderSystem.blit(x, y, w, h, u0, v0, u1, v1);
    }

    /**
     * Media-player frame blit — the handle is the player's 64-bit media texture (a GL texture name or a
     * Vulkan {@code VkImageView}), bound through the backend's media path instead of the plain 2D path.
     */
    public void mediaImage(final long handle, final float x, final float y, final float w, final float h) {
        if (handle == 0L) return;
        RenderSystem.bindMediaTexture(handle);
        RenderSystem.color(1f, 1f, 1f, this.alpha);
        RenderSystem.blit(x, y, w, h);
    }

    /** Media blit sampling a sub-region (UV rect) — used for cover-fit cropping without distortion. */
    public void mediaImage(final long handle, final float x, final float y, final float w, final float h,
                           final float u0, final float v0, final float u1, final float v1) {
        if (handle == 0L) return;
        RenderSystem.bindMediaTexture(handle);
        RenderSystem.color(1f, 1f, 1f, this.alpha);
        RenderSystem.blit(x, y, w, h, u0, v0, u1, v1);
    }

    /** Pixel-icon draw routed through the canvas so the global alpha multiplier applies (dialog fades). */
    public void icon(final String name, final int x, final int y, final int size, final Color color) {
        PixelIcon.draw(name, x, y, size, this.mul(color));
    }

    public void text(final String value, final float x, final float y, final Color color, final float scale, final boolean bold) {
        if (value == null || value.isEmpty()) return;
        // THE TEXT RENDERER RECEIVES THE ALREADY-MULTIPLIED COLOR — IT SETS IT ONCE FOR THE WHOLE RUN
        final Color c = this.mul(color);
        if (bold) {
            this.text.renderBold(value, x, y, c, scale);
        } else {
            this.text.render(value, x, y, c, scale);
        }
    }

    public int textWidth(final String value, final float scale, final boolean bold) {
        return bold ? this.text.widthBold(value, scale) : this.text.width(value, scale);
    }

    public int textHeight(final float scale, final boolean bold) {
        return bold ? this.text.glyphHeightBold(scale) : this.text.glyphHeight(scale);
    }

    /** Draws a letter-spaced run — glyph by glyph with extra logical-px spacing, the global alpha applied once. */
    public void textSpaced(final String value, final float x, final float y, final Color color,
                           final float scale, final boolean bold, final int letterSpacing) {
        if (value == null || value.isEmpty()) return;
        this.text.renderSpaced(value, x, y, this.mul(color), scale, bold, letterSpacing);
    }

    /** Width of a letter-spaced run — matches {@link #textSpaced} without allocating per-character strings. */
    public int textSpacedWidth(final String value, final float scale, final boolean bold, final int letterSpacing) {
        return this.text.spacedWidth(value, scale, bold, letterSpacing);
    }

    /**
     * Scissor clip — calls must be balanced with {@link #popClip}. The backend keeps a single rectangle,
     * so nested clips are intersected by the caller ({@code ParentScroll}) rather than stacked here.
     */
    public void pushClip(final int x, final int y, final int w, final int h) {
        RenderSystem.clip(x, y, w, h, this.windowHeight);
    }

    public void popClip() {
        RenderSystem.clearClip();
    }
}
