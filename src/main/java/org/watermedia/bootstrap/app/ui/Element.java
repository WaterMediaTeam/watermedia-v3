package org.watermedia.bootstrap.app.ui;

import java.awt.*;
import java.util.function.Consumer;

/**
 * Base class for UI elements with position, size, and event handling.
 */
public abstract class Element {

    protected Dimension bounds = Dimension.ZERO;
    protected boolean visible = true;
    protected boolean enabled = true;
    protected boolean hovered = false;
    protected boolean focused = false;

    protected Consumer<Element> onClick;
    protected Consumer<Element> onHover;

    public abstract void render(TextRenderer text, int windowW, int windowH);

    public abstract Dimension calculateBounds(TextRenderer text, int startX, int startY);

    public Dimension bounds() {
        return this.bounds;
    }

    public boolean visible() {
        return this.visible;
    }

    public boolean enabled() {
        return this.enabled;
    }

    public boolean hovered() {
        return this.hovered;
    }

    public boolean focused() {
        return this.focused;
    }

    public Element bounds(final Dimension bounds) {
        this.bounds = bounds;
        return this;
    }

    public Element visible(final boolean v) {
        this.visible = v;
        return this;
    }

    public Element enabled(final boolean e) {
        this.enabled = e;
        return this;
    }

    public Element focused(final boolean f) {
        this.focused = f;
        return this;
    }

    public Element onClick(final Consumer<Element> handler) {
        this.onClick = handler;
        return this;
    }

    public Element onHover(final Consumer<Element> handler) {
        this.onHover = handler;
        return this;
    }

    public boolean contains(final double mx, final double my) {
        return this.bounds.contains(mx, my);
    }

    public boolean handleClick(final double mx, final double my) {
        if (!this.visible || !this.enabled) return false;
        if (this.contains(mx, my)) {
            if (this.onClick != null) this.onClick.accept(this);
            return true;
        }
        return false;
    }

    public boolean handleHover(final double mx, final double my) {
        if (!this.visible || !this.enabled) return false;
        final boolean wasHovered = this.hovered;
        this.hovered = this.contains(mx, my);
        if (this.hovered && !wasHovered && this.onHover != null) {
            this.onHover.accept(this);
        }
        return this.hovered;
    }

    protected Color resolveColor(final Color normal, final Color hoverColor, final Color disabledColor) {
        if (!this.enabled) return disabledColor;
        if (this.hovered || this.focused) return hoverColor;
        return normal;
    }
}
