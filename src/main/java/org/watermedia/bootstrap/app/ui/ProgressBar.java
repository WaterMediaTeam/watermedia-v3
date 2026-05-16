package org.watermedia.bootstrap.app.ui;

import org.watermedia.bootstrap.app.render.RenderSystem;

import java.awt.Color;

/**
 * Animated linear progress bar for 0..100 work.
 */
public class ProgressBar extends Element {

    private float progress;
    private float animatedProgress;
    private int width = 320;
    private int height = 8;
    private Color accent = AppTheme.NEON_LIGHT;

    public ProgressBar progress(final float progress) {
        this.progress = clamp01(progress);
        return this;
    }

    public ProgressBar progressPercent(final int percent) {
        return this.progress(percent / 100f);
    }

    public ProgressBar size(final int width, final int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    public ProgressBar accent(final Color accent) {
        this.accent = accent;
        return this;
    }

    @Override
    public void render(final TextRenderer renderer, final int windowW, final int windowH) {
        this.animatedProgress += (this.progress - this.animatedProgress) * 0.18f;
        final float fillW = this.bounds.width() * this.animatedProgress;

        RenderSystem.setupOrtho(windowW, windowH);
        RenderSystem.fill(this.bounds.x(), this.bounds.y(), this.bounds.width(), this.bounds.height(), AppTheme.BG_3);
        RenderSystem.fillGradientH(this.bounds.x(), this.bounds.y(), fillW, this.bounds.height(),
                AppTheme.NEON_DARK.getRed() / 255f, AppTheme.NEON_DARK.getGreen() / 255f, AppTheme.NEON_DARK.getBlue() / 255f, 1f,
                this.accent.getRed() / 255f, this.accent.getGreen() / 255f, this.accent.getBlue() / 255f, 1f);
        RenderSystem.rect(this.bounds.x(), this.bounds.y(), this.bounds.width(), this.bounds.height(), AppTheme.NEON_DARK, 1f);
        RenderSystem.restoreProjection();
    }

    @Override
    public Dimension calculateBounds(final TextRenderer text, final int startX, final int startY) {
        this.bounds = new Dimension(startX, startY, this.width, this.height);
        return this.bounds;
    }

    private static float clamp01(final float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
