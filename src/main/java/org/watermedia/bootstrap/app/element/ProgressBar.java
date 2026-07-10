package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;

/**
 * A horizontal determinate progress bar. It stretches to the parent's width, keeps a fixed track height,
 * and paints a dark neon track with an accent-colored fill whose width is the track width scaled by
 * {@link #progress} (clamped to {@code 0..1}).
 */
public final class ProgressBar extends Element<ProgressBar> {

    private float progress;
    private Color accent = AppTheme.NEON;
    private int trackHeight = 6;

    public ProgressBar() {
        this.width = MAX_PARENT;
        this.height = this.trackHeight;
    }

    public ProgressBar progress(final float value) {
        this.progress = Math.max(0f, Math.min(1f, value));
        return this;
    }

    public ProgressBar accent(final Color value) {
        this.accent = value == null ? AppTheme.NEON : value;
        return this;
    }

    public ProgressBar trackHeight(final int value) {
        this.trackHeight = value;
        this.height = value;
        return this;
    }

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        this.contentWidth = 0;
        this.contentHeight = this.trackHeight;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        canvas.fill(this.left, this.top, this.measuredWidth, this.measuredHeight, AppTheme.alpha(AppTheme.NEON_DARK, 90));
        final int fillW = Math.round(this.measuredWidth * this.progress);
        if (fillW > 0) {
            canvas.fill(this.left, this.top, fillW, this.measuredHeight, this.accent);
        }
    }
}
