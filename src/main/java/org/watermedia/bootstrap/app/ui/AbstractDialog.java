package org.watermedia.bootstrap.app.ui;

import org.watermedia.bootstrap.app.render.RenderSystem;

import java.awt.Color;

/**
 * Code-designed dialog shell. Subclasses render the body.
 */
public abstract class AbstractDialog extends Element {

    protected String title = "";
    protected Color accent = AppTheme.NEON;
    protected Spacing padding = Spacing.all(18);

    public AbstractDialog title(final String title) {
        this.title = title == null ? "" : title;
        return this;
    }

    public AbstractDialog accent(final Color accent) {
        this.accent = accent;
        return this;
    }

    public AbstractDialog padding(final Spacing padding) {
        this.padding = padding;
        return this;
    }

    public void centerIn(final int windowW, final int windowH, final int width, final int height) {
        this.bounds = Dimension.centered(windowW, windowH, width, height);
    }

    @Override
    public final void render(final TextRenderer renderer, final int windowW, final int windowH) {
        if (!this.visible) return;
        RenderSystem.setupOrtho(windowW, windowH);
        RenderSystem.fill(0, 0, windowW, windowH, 0.02f, 0.03f, 0.1f, 0.62f);
        RenderSystem.shadowRect(this.bounds.x(), this.bounds.y(), this.bounds.width(), this.bounds.height(), 0f, 0.55f);
        RenderSystem.glowRect(this.bounds.x(), this.bounds.y(), this.bounds.width(), this.bounds.height(), 0f, this.accent, 0.35f);
        RenderSystem.fill(this.bounds.x(), this.bounds.y(), this.bounds.width(), this.bounds.height(), AppTheme.BG_1);
        RenderSystem.rect(this.bounds.x(), this.bounds.y(), this.bounds.width(), this.bounds.height(), this.accent, 1f);
        RenderSystem.fillGradientH(this.bounds.x(), this.bounds.y(), this.bounds.width(), 38,
                this.accent.getRed() / 255f, this.accent.getGreen() / 255f, this.accent.getBlue() / 255f, 0.12f,
                0f, 0f, 0f, 0f);

        renderer.renderBold(this.title, this.bounds.x() + 14, this.bounds.y() + 10, this.accent, AppTheme.TEXT_BUTTON);
        final Dimension body = new Dimension(this.bounds.x() + this.padding.left(),
                this.bounds.y() + 42 + this.padding.top(),
                this.bounds.width() - this.padding.horizontal(),
                this.bounds.height() - 42 - this.padding.vertical());
        this.renderBody(renderer, body, windowW, windowH);
        RenderSystem.restoreProjection();
    }

    protected abstract void renderBody(TextRenderer renderer, Dimension body, int windowW, int windowH);

    @Override
    public Dimension calculateBounds(final TextRenderer text, final int startX, final int startY) {
        return this.bounds;
    }
}
