package org.watermedia.bootstrap.app.ui;

import org.watermedia.bootstrap.app.render.RenderSystem;

import java.awt.Color;
import java.util.function.Consumer;

/**
 * Interactive seek bar with played, buffered and playhead tracks.
 */
public class SeekBar extends Element {

    private float progress;
    private float buffered;
    private float animatedProgress;
    private int width = 240;
    private int height = 18;
    private int trackHeight = 6;
    private Color accent = AppTheme.NEON_LIGHT;
    private Consumer<Float> onChanged;

    public SeekBar progress(final float value) {
        this.progress = clamp01(value);
        return this;
    }

    public SeekBar progressPercent(final int percent) {
        return this.progress(percent / 100f);
    }

    public SeekBar buffered(final float value) {
        this.buffered = clamp01(value);
        return this;
    }

    public SeekBar size(final int width, final int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    public SeekBar accent(final Color accent) {
        this.accent = accent;
        return this;
    }

    public SeekBar onChanged(final Consumer<Float> onChanged) {
        this.onChanged = onChanged;
        return this;
    }

    @Override
    public void render(final TextRenderer renderer, final int windowW, final int windowH) {
        if (!this.visible) return;
        this.animatedProgress += (this.progress - this.animatedProgress) * 0.22f;

        final int trackY = this.bounds.y() + (this.bounds.height() - this.trackHeight) / 2;
        final float playedW = this.bounds.width() * this.animatedProgress;
        final float bufferedW = this.bounds.width() * Math.max(this.buffered, this.animatedProgress);
        final float headX = this.bounds.x() + playedW;

        RenderSystem.setupOrtho(windowW, windowH);
        RenderSystem.fill(this.bounds.x(), trackY, this.bounds.width(), this.trackHeight, AppTheme.BG_3);
        RenderSystem.fill(this.bounds.x(), trackY, bufferedW, this.trackHeight,
                this.accent.getRed() / 255f, this.accent.getGreen() / 255f, this.accent.getBlue() / 255f, 0.22f);
        RenderSystem.fillGradientH(this.bounds.x(), trackY, playedW, this.trackHeight,
                AppTheme.NEON_DARK.getRed() / 255f, AppTheme.NEON_DARK.getGreen() / 255f, AppTheme.NEON_DARK.getBlue() / 255f, 1f,
                this.accent.getRed() / 255f, this.accent.getGreen() / 255f, this.accent.getBlue() / 255f, 1f);
        RenderSystem.glowRect(this.bounds.x(), trackY, playedW, this.trackHeight, 0f, this.accent, 0.35f);
        RenderSystem.fill(headX - 2, trackY - 3, 4, this.trackHeight + 6, AppTheme.AMBER);
        RenderSystem.restoreProjection();
    }

    @Override
    public Dimension calculateBounds(final TextRenderer text, final int startX, final int startY) {
        this.bounds = new Dimension(startX, startY, this.width, this.height);
        return this.bounds;
    }

    @Override
    public boolean handleClick(final double mx, final double my) {
        if (!super.handleClick(mx, my)) return false;
        final float next = clamp01((float) ((mx - this.bounds.x()) / Math.max(1, this.bounds.width())));
        this.progress(next);
        if (this.onChanged != null) this.onChanged.accept(next);
        return true;
    }

    private static float clamp01(final float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
