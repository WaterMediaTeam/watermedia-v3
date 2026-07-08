package org.watermedia.bootstrap.app.view;

import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.bootstrap.app.ui.PixelIcon;

import java.awt.Color;

/**
 * Draws a bitmap {@link PixelIcon} by key at a fixed pixel size, tinted by a color. It measures to the
 * icon size and vertically centers within a taller box — a reusable leaf for the icon+label chips that
 * the screens draw everywhere.
 */
public final class IconView extends View<IconView> {

    private String icon = "";
    private int iconSize = 24;
    private Color color = org.watermedia.bootstrap.app.ui.AppTheme.NEON;

    public IconView() {
    }

    public IconView(final String icon) {
        this.icon = icon == null ? "" : icon;
    }

    public IconView icon(final String value) {
        this.icon = value == null ? "" : value;
        return this;
    }

    public IconView iconSize(final int size) {
        this.iconSize = Math.max(1, size);
        return this;
    }

    public IconView color(final Color value) {
        this.color = value;
        return this;
    }

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        this.contentWidth = this.iconSize;
        this.contentHeight = this.iconSize;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        if (this.icon.isEmpty()) return;
        RenderSystem.setupOrtho(canvas.windowWidth(), canvas.windowHeight());
        PixelIcon.draw(this.icon, this.innerLeft(), this.innerTop() + Math.max(0, (this.innerHeight() - this.iconSize) / 2),
                this.iconSize, this.color);
    }
}
