package org.watermedia.bootstrap.app.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Container for multiple UI elements with vertical layout.
 */
public class Group extends Element {

    private final List<Element> children = new ArrayList<>();
    private int spacing = 10;
    private int paddingX = 0;
    private int paddingY = 0;

    public Group() {
    }

    public Group spacing(final int s) {
        this.spacing = s;
        return this;
    }

    public Group padding(final int px, final int py) {
        this.paddingX = px;
        this.paddingY = py;
        return this;
    }

    public Group add(final Element e) {
        this.children.add(e);
        return this;
    }

    public Group remove(final Element e) {
        this.children.remove(e);
        return this;
    }

    public Group clear() {
        this.children.clear();
        return this;
    }

    public List<Element> children() {
        return this.children;
    }

    public <T extends Element> T get(final int index, final Class<T> type) {
        if (index >= 0 && index < this.children.size()) {
            final Element e = this.children.get(index);
            if (type.isInstance(e)) return type.cast(e);
        }
        return null;
    }

    @Override
    public void render(final TextRenderer renderer, final int windowW, final int windowH) {
        if (!this.visible) return;

        for (final Element child : this.children) {
            if (child.visible()) {
                child.render(renderer, windowW, windowH);
            }
        }
    }

    @Override
    public Dimension calculateBounds(final TextRenderer text, final int startX, final int startY) {
        final int x = startX + this.paddingX;
        int y = startY + this.paddingY;
        int maxWidth = 0;

        for (final Element child : this.children) {
            final Dimension childBounds = child.calculateBounds(text, x, y);
            child.bounds(childBounds);
            maxWidth = Math.max(maxWidth, childBounds.width());
            y = childBounds.bottom() + this.spacing;
        }

        final int totalHeight = y - startY - this.spacing + this.paddingY;
        this.bounds = new Dimension(startX, startY, maxWidth + this.paddingX * 2, totalHeight);
        return this.bounds;
    }

    @Override
    public boolean handleClick(final double mx, final double my) {
        if (!this.visible || !this.enabled) return false;

        for (final Element child : this.children) {
            if (child.handleClick(mx, my)) return true;
        }
        return false;
    }

    @Override
    public boolean handleHover(final double mx, final double my) {
        if (!this.visible || !this.enabled) return false;

        boolean anyHovered = false;
        for (final Element child : this.children) {
            if (child.handleHover(mx, my)) anyHovered = true;
        }
        return anyHovered;
    }
}
