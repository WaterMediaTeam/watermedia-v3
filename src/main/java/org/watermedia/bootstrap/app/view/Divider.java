package org.watermedia.bootstrap.app.view;

import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;

/**
 * A thin horizontal rule used to separate sections. It fills the width it is given and is as tall as its
 * {@link #thickness}.
 */
public final class Divider extends View<Divider> {

    private Color color = AppTheme.STROKE;
    private int thickness = 1;

    public Divider() {
        this.width = MATCH_PARENT;
        this.height = this.thickness;
    }

    public Divider color(final Color value) {
        this.color = value;
        return this;
    }

    public Divider thickness(final int value) {
        this.thickness = Math.max(1, value);
        this.height = this.thickness;
        return this;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        canvas.fill(this.left, this.top, this.measuredWidth, this.thickness, this.color);
    }
}
