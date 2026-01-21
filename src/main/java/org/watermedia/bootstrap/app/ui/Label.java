package org.watermedia.bootstrap.app.ui;

import java.awt.*;

/**
 * Simple text label element.
 */
public class Label extends Element {

    private String text;
    private Color color;

    public Label(final String text, final Color color) {
        this.text = text;
        this.color = color;
    }

    public String text() {
        return this.text;
    }

    public Color color() {
        return this.color;
    }

    public Label text(final String t) {
        this.text = t;
        return this;
    }

    public Label color(final Color c) {
        this.color = c;
        return this;
    }

    @Override
    public void render(final TextRenderer renderer, final int windowW, final int windowH) {
        if (!this.visible || this.text == null) return;
        renderer.render(this.text, this.bounds.x(), this.bounds.y(), this.color);
    }

    @Override
    public Dimension calculateBounds(final TextRenderer text, final int startX, final int startY) {
        final int w = text.width(this.text);
        final int h = text.lineHeight();
        this.bounds = new Dimension(startX, startY, w, h);
        return this.bounds;
    }
}
