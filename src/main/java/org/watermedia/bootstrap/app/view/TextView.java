package org.watermedia.bootstrap.app.view;

import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;
import java.awt.Font;

/**
 * A single line of text, like Android's {@code TextView}. It measures to the text's intrinsic size at
 * its scale/weight, truncates with an ellipsis when its box is narrower, aligns horizontally by the
 * view {@link View#gravity} and centers vertically within a taller box. Text is measured through the
 * shared {@code TextRenderer} reached via the attached context.
 */
public final class TextView extends View<TextView> {

    private String text = "";
    private float scale = AppTheme.TEXT_BODY;
    private Color color = AppTheme.TEXT;
    private boolean bold;

    public TextView() {
    }

    public TextView(final String text) {
        this.text = text == null ? "" : text;
    }

    public TextView text(final String value) {
        this.text = value == null ? "" : value;
        return this;
    }

    public TextView scale(final float value) {
        this.scale = value;
        return this;
    }

    public TextView color(final Color value) {
        this.color = value;
        return this;
    }

    public TextView bold(final boolean value) {
        this.bold = value;
        return this;
    }

    public String text() {
        return this.text;
    }

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        if (this.ctx == null || this.ctx.text == null || this.text.isEmpty()) {
            this.contentWidth = 0;
            this.contentHeight = this.ctx != null && this.ctx.text != null
                    ? this.textHeight() : 0;
            return;
        }
        this.contentWidth = this.bold ? this.ctx.text.widthBold(this.text, this.scale) : this.ctx.text.width(this.text, this.scale);
        this.contentHeight = this.textHeight();
    }

    private int textHeight() {
        return this.bold ? this.ctx.text.glyphHeightBold(this.scale) : this.ctx.text.glyphHeight(this.scale);
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        if (this.text.isEmpty()) return;
        final int avail = this.innerWidth();
        String draw = this.text;
        if (canvas.textWidth(this.text, this.scale, this.bold) > avail) {
            draw = canvas.text().truncateToWidth(this.text, avail, this.scale, this.bold ? Font.BOLD : Font.PLAIN);
        }
        final int tw = canvas.textWidth(draw, this.scale, this.bold);
        final int th = canvas.textHeight(this.scale, this.bold);
        final int tx = switch (this.gravity) {
            case CENTER, FILL -> this.innerLeft() + (avail - tw) / 2;
            case RIGHT -> this.innerLeft() + avail - tw;
            default -> this.innerLeft();
        };
        final int ty = this.innerTop() + Math.max(0, (this.innerHeight() - th) / 2);
        canvas.text(draw, tx, ty, this.color, this.scale, this.bold);
    }
}
