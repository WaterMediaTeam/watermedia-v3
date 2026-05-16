package org.watermedia.bootstrap.app.ui;

import org.watermedia.bootstrap.app.render.RenderSystem;

import java.awt.Color;

/**
 * Neon button/action tile element.
 */
public class UiButton extends Element {

    private String label;
    private String hint;
    private Color accent = AppTheme.NEON;
    private int minWidth = 120;
    private int height = 36;
    private boolean focusedStyle;

    public UiButton(final String label) {
        this.label = label;
    }

    public UiButton label(final String label) {
        this.label = label;
        return this;
    }

    public UiButton hint(final String hint) {
        this.hint = hint;
        return this;
    }

    public UiButton accent(final Color accent) {
        this.accent = accent;
        return this;
    }

    public UiButton minWidth(final int minWidth) {
        this.minWidth = minWidth;
        return this;
    }

    public UiButton height(final int height) {
        this.height = height;
        return this;
    }

    public UiButton focusedStyle(final boolean focusedStyle) {
        this.focusedStyle = focusedStyle;
        return this;
    }

    @Override
    public void render(final TextRenderer renderer, final int windowW, final int windowH) {
        if (!this.visible) return;
        final boolean hot = this.enabled && (this.hovered || this.focused || this.focusedStyle);
        final Color border = hot ? this.accent : AppTheme.STROKE_BRIGHT;
        final Color textColor = this.enabled ? (hot ? this.accent : AppTheme.TEXT) : AppTheme.TEXT_FAINT;

        RenderSystem.setupOrtho(windowW, windowH);
        if (hot) RenderSystem.glowRect(this.bounds.x(), this.bounds.y(), this.bounds.width(), this.bounds.height(), 0f, this.accent, 0.35f);
        RenderSystem.fill(this.bounds.x(), this.bounds.y(), this.bounds.width(), this.bounds.height(),
                AppTheme.BG_2.getRed() / 255f, AppTheme.BG_2.getGreen() / 255f, AppTheme.BG_2.getBlue() / 255f,
                this.enabled ? 0.92f : 0.48f);
        RenderSystem.rect(this.bounds.x(), this.bounds.y(), this.bounds.width(), this.bounds.height(), border, 1f);

        final int textY = this.bounds.y() + (this.bounds.height() - renderer.lineHeight()) / 2 + 4;
        renderer.render(this.label, this.bounds.x() + 12, textY, textColor);
        if (this.hint != null && !this.hint.isEmpty()) {
            final int hintW = renderer.width(this.hint);
            final int hintX = this.bounds.right() - hintW - 10;
            renderer.render(this.hint, hintX, textY, AppTheme.TEXT_FAINT, 0.72f);
        }
        RenderSystem.restoreProjection();
    }

    @Override
    public Dimension calculateBounds(final TextRenderer text, final int startX, final int startY) {
        final int labelW = text.width(this.label == null ? "" : this.label);
        final int hintW = this.hint == null ? 0 : text.width(this.hint);
        final int width = Math.max(this.minWidth, labelW + hintW + 42);
        this.bounds = new Dimension(startX, startY, width, this.height);
        return this.bounds;
    }
}
