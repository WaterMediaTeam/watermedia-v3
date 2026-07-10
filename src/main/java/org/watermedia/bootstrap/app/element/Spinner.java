package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

/**
 * An inline numeric stepper laid out as {@code [-] value [+]}. The {@code [-]}/{@code [+]} boxes step the
 * value by {@code step} (clamped to {@code [min, max]}) and light up amber on hover and while pressed; the
 * center is an editable field — a click focuses it, digits, one dot and a leading minus type with a
 * blinking caret, and {@code ENTER} or a click elsewhere commits through the same clamp/{@code onChange}
 * path while {@code ESCAPE} cancels. Integer values render without a trailing {@code .0} and an optional
 * suffix is appended to the displayed value.
 */
public final class Spinner extends Element<Spinner> {

    private static final int BUTTON = 24;

    private double value;
    private double min;
    private double max = 100;
    private double step = 1;
    private String suffix = "";
    private Consumer<Double> onChange;
    private float scale = AppTheme.TEXT_BODY;

    // TEXT-ENTRY STATE — editing MIRRORS TextField's focus; editText IS THE UNCOMMITTED BUFFER
    private boolean editing;
    private String editText = "";
    // 0 = NONE, 1 = MINUS HELD, 2 = PLUS HELD — DRIVES THE AMBER PRESS HIGHLIGHT
    private int pressZone;

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
    public boolean textInputActive() {
        return this.editing;
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
        final int w = this.innerWidth();
        final int rightX = leftX + w - BUTTON;
        final int th = canvas.textHeight(this.scale, false);
        final int ty = y + (h - th) / 2;

        // OUTER BOX — A SOFT AMBER GLOW BEHIND IT WHILE EDITING
        if (this.editing) canvas.glow(leftX, y, w, h, 0f, AppTheme.AMBER, Theme.GLOW_WEAK);
        canvas.fill(leftX, y, w, h, AppTheme.alpha(AppTheme.BG_1, 210));

        // MINUS / PLUS ZONES — AMBER WASH + GLYPH ON HOVER, BRIGHTER WHILE PRESSED
        final double mxp = this.ctx != null ? this.ctx.mouseX : -1d;
        final double myp = this.ctx != null ? this.ctx.mouseY : -1d;
        final boolean inRow = this.enabled && myp >= y && myp < y + h;
        this.stepZone(canvas, leftX, y, h, "-", inRow && mxp >= leftX && mxp < leftX + BUTTON, this.pressZone == 1);
        this.stepZone(canvas, rightX, y, h, "+", inRow && mxp >= rightX && mxp < rightX + BUTTON, this.pressZone == 2);

        // VERTICAL DIVIDERS FRAMING THE CENTER FIELD
        canvas.fill(leftX + BUTTON, y, 1, h, AppTheme.STROKE);
        canvas.fill(rightX, y, 1, h, AppTheme.STROKE);

        // CENTER — THE UNCOMMITTED BUFFER WITH A CARET WHILE EDITING, ELSE THE CLAMPED VALUE
        final int midX = leftX + BUTTON;
        final int midW = w - BUTTON * 2;
        final String shown = this.editing ? this.editText : this.label();
        final int tw = canvas.textWidth(shown, this.scale, false);
        final int textX = midX + Math.max(0, (midW - tw) / 2);
        canvas.text(shown, textX, ty, AppTheme.TEXT, this.scale, false);
        if (this.editing && (System.currentTimeMillis() / 500) % 2 == 0) {
            canvas.fill(textX + tw + 1, ty, 1, th, AppTheme.AMBER);
        }

        // OUTER BORDER LAST — AMBER WHILE EDITING
        canvas.stroke(leftX, y, w, h, this.editing ? AppTheme.AMBER : AppTheme.STROKE_BRIGHT, this.editing ? 1.5f : 1f);
    }

    // ONE +/- ZONE: THE HOVER/PRESS AMBER WASH AND THE CENTERED GLYPH IN ITS STATE COLOR
    private void stepZone(final Canvas canvas, final int x, final int y, final int h, final String glyph,
                          final boolean hover, final boolean press) {
        if (press) {
            canvas.glow(x, y, BUTTON, h, 0f, AppTheme.AMBER, 0.4f);
            canvas.fill(x, y, BUTTON, h, AppTheme.alpha(AppTheme.AMBER, 70));
        } else if (hover) {
            canvas.fill(x, y, BUTTON, h, AppTheme.alpha(AppTheme.AMBER, 32));
        }
        final Color gc = hover || press ? AppTheme.AMBER : AppTheme.TEXT_FAINT;
        final int gw = canvas.textWidth(glyph, this.scale, false);
        final int th = canvas.textHeight(this.scale, false);
        canvas.text(glyph, x + (BUTTON - gw) / 2, y + (h - th) / 2, gc, this.scale, false);
    }

    @Override
    protected boolean onPress(final double mx, final double my) {
        // +/- ACT ON PRESS (SNAPPY) AND HOLD THE AMBER HIGHLIGHT UNTIL RELEASE; THE CENTER FALLS THROUGH
        // TO dispatchClick, WHICH FOCUSES THE EDITOR
        if (!this.enabled || !this.contains(mx, my)) return false;
        final int leftX = this.innerLeft();
        final int rightX = this.innerLeft() + this.innerWidth() - BUTTON;
        if (mx >= leftX && mx < leftX + BUTTON) {
            if (this.editing) this.commit();
            this.pressZone = 1;
            this.apply(this.value - this.step);
            return true;
        }
        if (mx >= rightX && mx < rightX + BUTTON) {
            if (this.editing) this.commit();
            this.pressZone = 2;
            this.apply(this.value + this.step);
            return true;
        }
        return false;
    }

    @Override
    public void onRelease(final double mx, final double my) {
        if (this.pressZone != 0) {
            this.pressZone = 0;
            this.invalidate();
        }
    }

    @Override
    public boolean dispatchClick(final double mx, final double my) {
        if (!this.contains(mx, my)) {
            if (this.editing) this.commit(); // A CLICK ANYWHERE ELSE BLURS AND COMMITS
            return false;
        }
        // MINUS/PLUS ALREADY ACTED ON PRESS — A CLICK IN THE CENTER FOCUSES THE EDITOR
        final int centerL = this.innerLeft() + BUTTON;
        final int centerR = this.innerLeft() + this.innerWidth() - BUTTON;
        if (mx >= centerL && mx < centerR && !this.editing) this.beginEdit();
        return true;
    }

    @Override
    public boolean dispatchKey(final int key, final int action) {
        if (!this.editing || action == GLFW_RELEASE) return false;
        switch (key) {
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> this.commit();
            case GLFW_KEY_ESCAPE -> this.cancel();
            case GLFW_KEY_BACKSPACE -> {
                if (!this.editText.isEmpty()) {
                    this.editText = this.editText.substring(0, this.editText.length() - 1);
                    this.invalidate();
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean dispatchChar(final int codepoint) {
        if (!this.editing) return false;
        // NUMERIC ENTRY ONLY: DIGITS, A SINGLE DOT, AND A LEADING MINUS
        final boolean accept = (codepoint >= '0' && codepoint <= '9')
                || (codepoint == '.' && this.editText.indexOf('.') < 0)
                || (codepoint == '-' && this.editText.isEmpty());
        if (!accept) return false;
        this.editText += (char) codepoint;
        this.invalidate();
        return true;
    }

    private void beginEdit() {
        this.editing = true;
        this.editText = this.numberString();
        this.invalidate();
    }

    private void commit() {
        double parsed = this.value;
        if (!this.editText.isEmpty() && !this.editText.equals("-") && !this.editText.equals(".")) {
            try {
                parsed = Double.parseDouble(this.editText);
            } catch (final NumberFormatException ignored) {
                // KEEP THE CURRENT VALUE ON UNPARSEABLE INPUT
            }
        }
        this.editing = false;
        this.apply(parsed);
    }

    private void cancel() {
        this.editing = false;
        this.invalidate();
    }

    // CLAMP TO THE RANGE, REPORT ONLY REAL CHANGES, AND ALWAYS REPAINT
    private void apply(final double v) {
        final double next = Math.max(this.min, Math.min(this.max, v));
        if (next != this.value) {
            this.value = next;
            if (this.onChange != null) this.onChange.accept(this.value);
        }
        this.invalidate();
    }

    // THE VALUE AS A STRING WITHOUT THE SUFFIX — DROPS THE TRAILING ".0" FOR INTEGER-VALUED NUMBERS
    private String numberString() {
        return this.value == Math.rint(this.value) ? Long.toString((long) this.value) : Double.toString(this.value);
    }

    private String label() {
        final String num = this.numberString();
        return this.suffix.isEmpty() ? num : num + this.suffix;
    }
}
