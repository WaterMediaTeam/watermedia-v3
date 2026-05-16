package org.watermedia.bootstrap.app.ui;

import org.watermedia.bootstrap.app.render.RenderSystem;

import java.awt.Color;

/**
 * Drawable box style: fill, gradients, border, radius, glow, shadow and inset data.
 */
public final class BoxStyle {

    private Color fill = AppTheme.BG_1;
    private Color gradientStart;
    private Color gradientEnd;
    private boolean verticalGradient = true;
    private Color border = AppTheme.STROKE_BRIGHT;
    private float borderWidth = 1f;
    private float radius = 0f;
    private Color glow;
    private float glowAlpha;
    private float shadowAlpha;
    private Spacing padding = Spacing.ZERO;
    private Spacing margin = Spacing.ZERO;

    public static BoxStyle panel() {
        return new BoxStyle()
                .gradient(AppTheme.BG_2_ALPHA, AppTheme.BG_1_ALPHA, true)
                .border(AppTheme.STROKE_BRIGHT, 1f)
                .glow(AppTheme.NEON, 0.25f);
    }

    public BoxStyle fill(final Color fill) {
        this.fill = fill;
        this.gradientStart = null;
        this.gradientEnd = null;
        return this;
    }

    public BoxStyle gradient(final Color start, final Color end, final boolean vertical) {
        this.gradientStart = start;
        this.gradientEnd = end;
        this.verticalGradient = vertical;
        return this;
    }

    public BoxStyle border(final Color color, final float width) {
        this.border = color;
        this.borderWidth = width;
        return this;
    }

    public BoxStyle radius(final float radius) {
        this.radius = radius;
        return this;
    }

    public BoxStyle glow(final Color color, final float alpha) {
        this.glow = color;
        this.glowAlpha = alpha;
        return this;
    }

    public BoxStyle shadow(final float alpha) {
        this.shadowAlpha = alpha;
        return this;
    }

    public BoxStyle padding(final Spacing padding) {
        this.padding = padding;
        return this;
    }

    public BoxStyle margin(final Spacing margin) {
        this.margin = margin;
        return this;
    }

    public Spacing padding() {
        return this.padding;
    }

    public Spacing margin() {
        return this.margin;
    }

    public void render(final float x, final float y, final float w, final float h) {
        if (this.shadowAlpha > 0f) {
            RenderSystem.shadowRect(x, y, w, h, this.radius, this.shadowAlpha);
        }
        if (this.glow != null && this.glowAlpha > 0f) {
            RenderSystem.glowRect(x, y, w, h, this.radius, this.glow, this.glowAlpha);
        }

        if (this.gradientStart != null && this.gradientEnd != null) {
            if (this.verticalGradient) {
                fillGradientV(x, y, w, h, this.gradientStart, this.gradientEnd);
            } else {
                fillGradientH(x, y, w, h, this.gradientStart, this.gradientEnd);
            }
        } else if (this.fill != null) {
            RenderSystem.fillRounded(x, y, w, h, this.radius, this.fill);
        }

        if (this.border != null && this.borderWidth > 0f) {
            if (this.radius > 0f) {
                RenderSystem.rectRounded(x, y, w, h, this.radius, this.border, this.borderWidth);
            } else {
                RenderSystem.rect(x, y, w, h, this.border, this.borderWidth);
            }
        }
    }

    private static void fillGradientV(final float x, final float y, final float w, final float h,
                                      final Color top, final Color bottom) {
        RenderSystem.fillGradientV(x, y, w, h,
                top.getRed() / 255f, top.getGreen() / 255f, top.getBlue() / 255f, top.getAlpha() / 255f,
                bottom.getRed() / 255f, bottom.getGreen() / 255f, bottom.getBlue() / 255f, bottom.getAlpha() / 255f);
    }

    private static void fillGradientH(final float x, final float y, final float w, final float h,
                                      final Color left, final Color right) {
        RenderSystem.fillGradientH(x, y, w, h,
                left.getRed() / 255f, left.getGreen() / 255f, left.getBlue() / 255f, left.getAlpha() / 255f,
                right.getRed() / 255f, right.getGreen() / 255f, right.getBlue() / 255f, right.getAlpha() / 255f);
    }
}
