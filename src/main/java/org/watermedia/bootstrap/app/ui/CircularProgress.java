package org.watermedia.bootstrap.app.ui;

import org.watermedia.bootstrap.app.render.RenderSystem;

import java.awt.Color;

/**
 * Circular progress ring for 0..100 work.
 */
public class CircularProgress extends Element {

    private float progress;
    private float animatedProgress;
    private int size = 64;
    private float stroke = 5f;
    private Color accent = AppTheme.NEON_LIGHT;

    public CircularProgress progress(final float progress) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        return this;
    }

    public CircularProgress progressPercent(final int percent) {
        return this.progress(percent / 100f);
    }

    public CircularProgress size(final int size) {
        this.size = size;
        return this;
    }

    public CircularProgress stroke(final float stroke) {
        this.stroke = stroke;
        return this;
    }

    public CircularProgress accent(final Color accent) {
        this.accent = accent;
        return this;
    }

    @Override
    public void render(final TextRenderer renderer, final int windowW, final int windowH) {
        this.animatedProgress += (this.progress - this.animatedProgress) * 0.18f;
        final float cx = this.bounds.centerX();
        final float cy = this.bounds.centerY();
        final float radius = (this.bounds.width() - this.stroke) / 2f;

        RenderSystem.setupOrtho(windowW, windowH);
        RenderSystem.lineWidth(this.stroke);
        drawArc(cx, cy, radius, 0f, 1f, AppTheme.alpha(AppTheme.STROKE_BRIGHT, 110));
        drawArc(cx, cy, radius, -0.25f, this.animatedProgress - 0.25f, this.accent);
        RenderSystem.lineWidth(1f);
        RenderSystem.fillCircle(cx, cy, 3f, AppTheme.AMBER.getRed() / 255f, AppTheme.AMBER.getGreen() / 255f, AppTheme.AMBER.getBlue() / 255f, 1f);

        final String pct = Math.round(this.progress * 100f) + "%";
        renderer.render(pct, this.bounds.centerX() - renderer.width(pct) / 2,
                this.bounds.centerY() - renderer.lineHeight() / 2 + 4,
                AppTheme.TEXT_SOFT, 0.62f);
        RenderSystem.restoreProjection();
    }

    private static void drawArc(final float cx, final float cy, final float radius,
                                final float startTurn, final float endTurn, final Color color) {
        RenderSystem.color(color);
        final int segments = 48;
        float lastX = 0f;
        float lastY = 0f;
        final int count = Math.max(1, (int) (segments * Math.abs(endTurn - startTurn)));
        for (int i = 0; i <= count; i++) {
            final float t = startTurn + (endTurn - startTurn) * i / count;
            final float angle = t * (float) (Math.PI * 2);
            final float x = cx + (float) Math.cos(angle) * radius;
            final float y = cy + (float) Math.sin(angle) * radius;
            if (i > 0) RenderSystem.line(lastX, lastY, x, y);
            lastX = x;
            lastY = y;
        }
    }

    @Override
    public Dimension calculateBounds(final TextRenderer text, final int startX, final int startY) {
        this.bounds = new Dimension(startX, startY, this.size, this.size);
        return this.bounds;
    }
}
