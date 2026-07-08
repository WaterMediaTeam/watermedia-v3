package org.watermedia.bootstrap.app.view;

import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;
import java.util.function.Consumer;

/**
 * An inline numeric stepper laid out as {@code [-] value [+]}, with no popup. Fixed 24px outlined boxes on
 * each side decrement and increment the value by {@code step}, clamped to the {@code [min, max]} range;
 * the current value (with an optional suffix) is centered between them. Integer values render without a
 * trailing {@code .0}. Every clamped change reports the new value through {@code onChange}.
 */
public final class Spinner extends View<Spinner> {

    private static final int BUTTON = 24;

    private double value;
    private double min;
    private double max = 100;
    private double step = 1;
    private String suffix = "";
    private Consumer<Double> onChange;
    private float scale = AppTheme.TEXT_BODY;

    public Spinner() {
        this.height = Theme.CONTROL;
    }

    public Spinner value(final double v) {
        this.value = Math.max(this.min, Math.min(this.max, v));
        return this;
    }

    public Spinner range(final double min, final double max) {
        this.min = min;
        this.max = max;
        this.value = Math.max(min, Math.min(max, this.value));
        return this;
    }

    public Spinner step(final double s) {
        this.step = s;
        return this;
    }

    public Spinner suffix(final String s) {
        this.suffix = s == null ? "" : s;
        return this;
    }

    public Spinner onChange(final Consumer<Double> handler) {
        this.onChange = handler;
        return this;
    }

    public Spinner scale(final float value) {
        this.scale = value;
        return this;
    }

    public double value() {
        return this.value;
    }

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        this.contentHeight = Theme.CONTROL;
        final int valueW = this.ctx != null && this.ctx.text != null ? this.ctx.text.width(this.label(), this.scale) : 0;
        this.contentWidth = BUTTON + BUTTON + Math.max(48, valueW);
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final int y = this.innerTop();
        final int h = this.innerHeight();
        final int leftX = this.innerLeft();
        final int rightX = this.innerLeft() + this.innerWidth() - BUTTON;
        final int th = canvas.textHeight(this.scale, false);
        final int ty = y + (h - th) / 2;
        // MINUS AND PLUS BOXES — OUTLINED IN THE ACCENT, GLYPH CENTERED IN EACH
        canvas.stroke(leftX, y, BUTTON, h, AppTheme.NEON, 1f);
        final int mw = canvas.textWidth("-", this.scale, false);
        canvas.text("-", leftX + (BUTTON - mw) / 2, ty, AppTheme.NEON, this.scale, false);
        canvas.stroke(rightX, y, BUTTON, h, AppTheme.NEON, 1f);
        final int pw = canvas.textWidth("+", this.scale, false);
        canvas.text("+", rightX + (BUTTON - pw) / 2, ty, AppTheme.NEON, this.scale, false);
        // VALUE CENTERED IN THE GAP BETWEEN THE TWO BUTTONS
        final String label = this.label();
        final int midX = leftX + BUTTON;
        final int midW = this.innerWidth() - BUTTON * 2;
        final int tw = canvas.textWidth(label, this.scale, false);
        canvas.text(label, midX + Math.max(0, (midW - tw) / 2), ty, AppTheme.TEXT, this.scale, false);
    }

    @Override
    public boolean dispatchClick(final double mx, final double my) {
        if (!this.contains(mx, my)) return false;
        final int leftX = this.innerLeft();
        final int rightX = this.innerLeft() + this.innerWidth() - BUTTON;
        final double next;
        if (mx >= leftX && mx < leftX + BUTTON) {
            next = Math.max(this.min, Math.min(this.max, this.value - this.step));
        } else if (mx >= rightX && mx < rightX + BUTTON) {
            next = Math.max(this.min, Math.min(this.max, this.value + this.step));
        } else {
            return false;
        }
        if (next != this.value) {
            this.value = next;
            this.invalidate();
            if (this.onChange != null) this.onChange.accept(this.value);
        }
        return true;
    }

    private String label() {
        // DROP THE TRAILING ".0" FOR INTEGER-VALUED NUMBERS
        final String num = this.value == Math.rint(this.value) ? Long.toString((long) this.value) : Double.toString(this.value);
        return this.suffix.isEmpty() ? num : num + this.suffix;
    }
}
