package org.watermedia.bootstrap.app.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Parent container with horizontal or vertical stacking, padding, margin and gravity.
 */
public class StackContainer extends Element {

    private final List<Element> children = new ArrayList<>();
    private StackOrientation orientation = StackOrientation.VERTICAL;
    private Gravity gravity = Gravity.LEFT;
    private int spacing = 8;
    private Spacing padding = Spacing.ZERO;

    public StackContainer orientation(final StackOrientation orientation) {
        this.orientation = orientation;
        return this;
    }

    public StackContainer gravity(final Gravity gravity) {
        this.gravity = gravity;
        return this;
    }

    public StackContainer spacing(final int spacing) {
        this.spacing = spacing;
        return this;
    }

    public StackContainer padding(final Spacing padding) {
        this.padding = padding;
        return this;
    }

    public StackContainer add(final Element child) {
        this.children.add(child);
        return this;
    }

    public StackContainer clear() {
        this.children.clear();
        return this;
    }

    public List<Element> children() {
        return this.children;
    }

    @Override
    public void render(final TextRenderer renderer, final int windowW, final int windowH) {
        if (!this.visible) return;
        for (final Element child: this.children) {
            if (child.visible()) child.render(renderer, windowW, windowH);
        }
    }

    @Override
    public Dimension calculateBounds(final TextRenderer text, final int startX, final int startY) {
        int cursorX = startX + this.padding.left();
        int cursorY = startY + this.padding.top();
        int maxW = 0;
        int maxH = 0;

        final List<Dimension> measured = new ArrayList<>(this.children.size());
        for (final Element child: this.children) {
            final Dimension childBounds = child.calculateBounds(text, cursorX, cursorY);
            measured.add(childBounds);
            if (this.orientation == StackOrientation.VERTICAL) {
                maxW = Math.max(maxW, childBounds.width());
                cursorY += childBounds.height() + this.spacing;
            } else {
                maxH = Math.max(maxH, childBounds.height());
                cursorX += childBounds.width() + this.spacing;
            }
        }

        final int contentW = this.orientation == StackOrientation.HORIZONTAL
                ? Math.max(0, cursorX - startX - this.padding.left() - (this.children.isEmpty() ? 0 : this.spacing))
                : maxW;
        final int contentH = this.orientation == StackOrientation.VERTICAL
                ? Math.max(0, cursorY - startY - this.padding.top() - (this.children.isEmpty() ? 0 : this.spacing))
                : maxH;

        this.bounds = new Dimension(startX, startY,
                contentW + this.padding.horizontal(),
                contentH + this.padding.vertical());

        this.applyGravity(measured, contentW, contentH);
        return this.bounds;
    }

    private void applyGravity(final List<Dimension> measured, final int contentW, final int contentH) {
        for (int i = 0; i < this.children.size(); i++) {
            final Element child = this.children.get(i);
            final Dimension current = measured.get(i);
            int x = current.x();
            int y = current.y();

            if (this.orientation == StackOrientation.VERTICAL) {
                if (this.gravity == Gravity.CENTER) {
                    x = this.bounds.x() + this.padding.left() + (contentW - current.width()) / 2;
                } else if (this.gravity == Gravity.RIGHT) {
                    x = this.bounds.right() - this.padding.right() - current.width();
                }
            } else {
                if (this.gravity == Gravity.CENTER) {
                    y = this.bounds.y() + this.padding.top() + (contentH - current.height()) / 2;
                } else if (this.gravity == Gravity.BOTTOM) {
                    y = this.bounds.bottom() - this.padding.bottom() - current.height();
                }
            }

            child.bounds(current.withPos(x, y));
        }
    }

    @Override
    public boolean handleClick(final double mx, final double my) {
        if (!this.visible || !this.enabled) return false;
        for (final Element child: this.children) {
            if (child.handleClick(mx, my)) return true;
        }
        return false;
    }

    @Override
    public boolean handleHover(final double mx, final double my) {
        if (!this.visible || !this.enabled) return false;
        boolean hovered = false;
        for (final Element child: this.children) {
            hovered |= child.handleHover(mx, my);
        }
        return hovered;
    }
}
