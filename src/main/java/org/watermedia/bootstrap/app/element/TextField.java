package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Spacing;

import java.awt.Color;
import java.awt.Font;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

/**
 * A single-line editable text box with focus and a blinking caret. It fills the parent's width, paints a
 * translucent background and a hairline border that lights up in the accent (amber by default) with a
 * soft glow while focused, and shows a faint placeholder when empty. A click grabs focus, printable
 * characters append (up to {@code maxLength}) and backspace drops the last character; every edit reports
 * the new text through {@code onChange}. The {@code focused} state is inherited from {@link Element}.
 */
public final class TextField extends Element<TextField> {

    private String value = "";
    private String placeholder = "";
    private Color accent = AppTheme.AMBER;
    private int maxLength = 256;
    private Consumer<String> onChange;
    private float scale = AppTheme.TEXT_BODY;

    public TextField() {
        this.width = MAX_PARENT;
        this.padding = Spacing.hv(10, 8);
    }

    public TextField value(final String v) {
        this.value = v == null ? "" : v;
        return this;
    }

    public TextField placeholder(final String v) {
        this.placeholder = v == null ? "" : v;
        return this;
    }

    public TextField accent(final Color color) {
        this.accent = color == null ? AppTheme.NEON : color;
        return this;
    }

    public TextField maxLength(final int value) {
        this.maxLength = Math.max(0, value);
        return this;
    }

    public TextField onChange(final Consumer<String> handler) {
        this.onChange = handler;
        return this;
    }

    public TextField scale(final float value) {
        this.scale = value;
        return this;
    }

    public String value() {
        return this.value;
    }

    @Override
    public boolean textInputActive() {
        return this.focused;
    }

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        this.contentWidth = 0;
        this.contentHeight = this.ctx != null && this.ctx.text != null ? this.ctx.text.glyphHeight(this.scale) : 0;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        // FOCUSED: A SOFT ACCENT GLOW BEHIND THE BOX PLUS AN ACCENT BORDER; IDLE: A PLAIN HAIRLINE
        if (this.focused) {
            canvas.glow(this.left, this.top, this.measuredWidth, this.measuredHeight, 0f, this.accent, Theme.GLOW_WEAK);
        }
        canvas.fill(this.left, this.top, this.measuredWidth, this.measuredHeight, AppTheme.alpha(AppTheme.BG_1, 200));
        canvas.stroke(this.left, this.top, this.measuredWidth, this.measuredHeight,
                this.focused ? this.accent : AppTheme.STROKE, this.focused ? 1.5f : 1f);

        final int avail = this.innerWidth();
        final boolean empty = this.value.isEmpty();
        final Color color = empty ? AppTheme.TEXT_FAINT : AppTheme.TEXT;
        String draw = empty ? this.placeholder : this.value;
        if (!draw.isEmpty() && canvas.textWidth(draw, this.scale, false) > avail) {
            draw = canvas.text().truncateToWidth(draw, avail, this.scale, Font.PLAIN);
        }
        final int th = canvas.textHeight(this.scale, false);
        final int ty = this.innerTop() + Math.max(0, (this.innerHeight() - th) / 2);
        if (!draw.isEmpty()) {
            canvas.text(draw, this.innerLeft(), ty, color, this.scale, false);
        }
        // BLINKING 1px CARET AFTER THE VISIBLE VALUE — HIDDEN FOR HALF OF EACH SECOND
        if (this.focused && (System.currentTimeMillis() / 500) % 2 == 0) {
            final int caretX = this.innerLeft() + (empty ? 0 : canvas.textWidth(draw, this.scale, false));
            canvas.fill(caretX, ty, 1, th, this.accent);
        }
    }

    @Override
    public boolean dispatchClick(final double mx, final double my) {
        if (this.contains(mx, my)) {
            this.focused = true;
            this.invalidate();
            return true;
        }
        this.focused = false;
        return false;
    }

    @Override
    public boolean dispatchKey(final int key, final int action) {
        if (!this.focused || action == GLFW_RELEASE) return false;
        if (key == GLFW_KEY_BACKSPACE) {
            if (!this.value.isEmpty()) {
                this.value = this.value.substring(0, this.value.length() - 1);
                this.invalidate();
                if (this.onChange != null) this.onChange.accept(this.value);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean dispatchChar(final int codepoint) {
        if (!this.focused) return false;
        // PRINTABLE ASCII ONLY, BOUNDED BY maxLength
        if (codepoint >= 32 && codepoint < 127 && this.value.length() < this.maxLength) {
            this.value += new String(Character.toChars(codepoint));
            this.invalidate();
            if (this.onChange != null) this.onChange.accept(this.value);
            return true;
        }
        return false;
    }
}
