package org.watermedia.bootstrap.app.ui;

import org.watermedia.bootstrap.app.render.RenderSystem;

import java.util.function.Consumer;

/**
 * Binary switch/toggle.
 */
public class SwitchElement extends Element {

    private boolean on;
    private Consumer<Boolean> onChanged;

    public SwitchElement on(final boolean on) {
        this.on = on;
        return this;
    }

    public boolean on() {
        return this.on;
    }

    public SwitchElement onChanged(final Consumer<Boolean> onChanged) {
        this.onChanged = onChanged;
        return this;
    }

    @Override
    public void render(final TextRenderer renderer, final int windowW, final int windowH) {
        RenderSystem.setupOrtho(windowW, windowH);
        if (this.on) RenderSystem.glowRect(this.bounds.x(), this.bounds.y(), this.bounds.width(), this.bounds.height(), 0f, AppTheme.NEON, 0.25f);
        RenderSystem.fill(this.bounds.x(), this.bounds.y(), this.bounds.width(), this.bounds.height(),
                AppTheme.BG_2.getRed() / 255f, AppTheme.BG_2.getGreen() / 255f, AppTheme.BG_2.getBlue() / 255f, 1f);
        RenderSystem.rect(this.bounds.x(), this.bounds.y(), this.bounds.width(), this.bounds.height(),
                this.on ? AppTheme.NEON_LIGHT : AppTheme.STROKE_BRIGHT, 1f);
        final int knob = this.bounds.height() - 4;
        final int knobX = this.on ? this.bounds.right() - knob - 2 : this.bounds.x() + 2;
        RenderSystem.fill(knobX, this.bounds.y() + 2, knob, knob, this.on ? AppTheme.AMBER : AppTheme.NEON_LIGHT);
        RenderSystem.restoreProjection();
    }

    @Override
    public Dimension calculateBounds(final TextRenderer text, final int startX, final int startY) {
        this.bounds = new Dimension(startX, startY, 44, 20);
        return this.bounds;
    }

    @Override
    public boolean handleClick(final double mx, final double my) {
        if (!super.handleClick(mx, my)) return false;
        this.on = !this.on;
        if (this.onChanged != null) this.onChanged.accept(this.on);
        return true;
    }
}
