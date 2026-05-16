package org.watermedia.bootstrap.app.ui;

import org.watermedia.bootstrap.app.render.RenderSystem;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Scrollable list of plain colored text lines.
 */
public class TextList extends Element {

    private final List<Line> lines = new ArrayList<>();
    private int width = 420;
    private int height = 220;
    private int scroll;

    public TextList size(final int width, final int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    public TextList clear() {
        this.lines.clear();
        this.scroll = 0;
        return this;
    }

    public TextList add(final String text, final Color color) {
        this.lines.add(new Line(text, color));
        return this;
    }

    public void scrollBy(final int delta) {
        this.scroll = Math.max(0, Math.min(Math.max(0, this.lines.size() - 1), this.scroll + delta));
    }

    @Override
    public void render(final TextRenderer renderer, final int windowW, final int windowH) {
        RenderSystem.setupOrtho(windowW, windowH);
        BoxStyle.panel().render(this.bounds.x(), this.bounds.y(), this.bounds.width(), this.bounds.height());
        RenderSystem.clip(this.bounds.x(), this.bounds.y(), this.bounds.width(), this.bounds.height(), windowH);

        final int lineH = renderer.lineHeight() - 4;
        final int visible = Math.max(1, (this.bounds.height() - 16) / lineH);
        for (int i = 0; i < visible && i + this.scroll < this.lines.size(); i++) {
            final Line line = this.lines.get(i + this.scroll);
            renderer.render(renderer.truncateToWidth(line.text, this.bounds.width() - 20),
                    this.bounds.x() + 10, this.bounds.y() + 8 + i * lineH, line.color);
        }

        RenderSystem.clearClip();
        RenderSystem.restoreProjection();
    }

    @Override
    public Dimension calculateBounds(final TextRenderer text, final int startX, final int startY) {
        this.bounds = new Dimension(startX, startY, this.width, this.height);
        return this.bounds;
    }

    private record Line(String text, Color color) {
    }
}
