package org.watermedia.bootstrap.app.ui;

import org.watermedia.bootstrap.app.render.RenderSystem;

import java.util.function.Consumer;

/**
 * Binary checkbox with a label.
 */
public class Checkbox extends Element {

    private String label;
    private boolean checked;
    private Consumer<Boolean> onChanged;

    public Checkbox(final String label) {
        this.label = label;
    }

    public Checkbox checked(final boolean checked) {
        this.checked = checked;
        return this;
    }

    public Checkbox onChanged(final Consumer<Boolean> onChanged) {
        this.onChanged = onChanged;
        return this;
    }

    @Override
    public void render(final TextRenderer renderer, final int windowW, final int windowH) {
        final int box = 16;
        RenderSystem.setupOrtho(windowW, windowH);
        RenderSystem.fill(this.bounds.x(), this.bounds.y() + 4, box, box, AppTheme.BG_2);
        RenderSystem.rect(this.bounds.x(), this.bounds.y() + 4, box, box, this.checked ? AppTheme.AMBER : AppTheme.STROKE_BRIGHT, 1f);
        if (this.checked) {
            RenderSystem.fill(this.bounds.x() + 4, this.bounds.y() + 8, 8, 8, AppTheme.AMBER);
            RenderSystem.glowRect(this.bounds.x(), this.bounds.y() + 4, box, box, 0f, AppTheme.AMBER, 0.35f);
        }
        renderer.render(this.label, this.bounds.x() + box + 8, this.bounds.y(), AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY);
        RenderSystem.restoreProjection();
    }

    @Override
    public Dimension calculateBounds(final TextRenderer text, final int startX, final int startY) {
        this.bounds = new Dimension(startX, startY, 24 + text.width(this.label, AppTheme.TEXT_BODY), text.lineHeight(AppTheme.TEXT_BODY));
        return this.bounds;
    }

    @Override
    public boolean handleClick(final double mx, final double my) {
        if (!super.handleClick(mx, my)) return false;
        this.checked = !this.checked;
        if (this.onChanged != null) this.onChanged.accept(this.checked);
        return true;
    }
}
