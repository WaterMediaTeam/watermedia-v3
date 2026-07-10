package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;
import java.awt.Font;

/**
 * A single line of text, like Android's {@code Text}. It measures to the text's intrinsic size at
 * its scale/weight, truncates with an ellipsis when its box is narrower, aligns horizontally by the
 * view {@link Element#gravity} and centers vertically within a taller box. Text is measured through the
 * shared {@code TextRenderer} reached via the attached context.
 */
public final class Text extends Element<Text> {

    // SUBTEXT CHIP METRICS — SAME GEOMETRY AS Button.render'S TRAILING CHIP
    private static final int CHIP_PAD = 6;
    private static final int CHIP_H = 20;
    private static final int CHIP_GAP = 12;

    private String text = "";
    private String subText = "";
    private float scale = AppTheme.TEXT_BODY;
    private Color color = AppTheme.TEXT;
    private boolean bold;
    private int letterSpacing;

    public Text() {
    }

    public Text(final String text) {
        this.text = text == null ? "" : text;
    }

    public Text text(final String value) {
        this.text = value == null ? "" : value;
        return this;
    }

    /**
     * Sets a trailing chip rendered after the main text (e.g. an inline keybind or count), using the
     * same chip geometry as {@code Button}'s subtext chip. A blank value hides the chip entirely.
     */
    public Text subText(final String value) {
        this.subText = value == null ? "" : value;
        return this;
    }

    public Text scale(final float value) {
        this.scale = value;
        return this;
    }

    public Text color(final Color value) {
        this.color = value;
        return this;
    }

    public Text bold(final boolean value) {
        this.bold = value;
        return this;
    }

    /**
     * Sets extra pixel spacing inserted between consecutive characters. A spaced run is measured and
     * drawn glyph by glyph, with every character metered as its own single-char run plus the spacing
     * between neighbors, and it never truncates with an ellipsis. The default {@code 0} keeps the
     * regular whole-run path untouched.
     */
    public Text letterSpacing(final int value) {
        this.letterSpacing = Math.max(0, value);
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
        } else {
            this.contentWidth = this.letterSpacing > 0 ? this.spacedWidth(this.text)
                    : this.bold ? this.ctx.text.widthBold(this.text, this.scale) : this.ctx.text.width(this.text, this.scale);
            this.contentHeight = this.textHeight();
        }
        // TRAILING SUBTEXT CHIP — ONLY WHEN NON-BLANK, SAME GEOMETRY AS THE BUTTON CHIP
        if (this.ctx != null && this.ctx.text != null && !this.subText.isBlank()) {
            this.contentWidth += CHIP_GAP + this.ctx.text.width(this.subText, AppTheme.TEXT_SUBTITLE) + CHIP_PAD * 2;
            this.contentHeight = Math.max(this.contentHeight, CHIP_H);
        }
    }

    private int textHeight() {
        return this.bold ? this.ctx.text.glyphHeightBold(this.scale) : this.ctx.text.glyphHeight(this.scale);
    }

    // SPACED-RUN METRIC — EACH CHAR IS MEASURED AS ITS OWN RUN (PER-CHAR CEIL, NO RUN TRACKING) AND THE
    // EXTRA SPACING IS ADDED BETWEEN NEIGHBORS ONLY, EXACTLY LIKE THE LEGACY SPLASH TITLE
    private int spacedWidth(final String value) {
        int width = 0;
        for (int i = 0; i < value.length(); i++) {
            final String c = String.valueOf(value.charAt(i));
            width += this.bold ? this.ctx.text.widthBold(c, this.scale) : this.ctx.text.width(c, this.scale);
            if (i + 1 < value.length()) width += this.letterSpacing;
        }
        return width;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final boolean chip = !this.subText.isBlank();
        if (this.text.isEmpty() && !chip) return;
        final int avail = this.innerWidth();
        final int chipW = chip ? canvas.textWidth(this.subText, AppTheme.TEXT_SUBTITLE, false) + CHIP_PAD * 2 : 0;
        final int textAvail = chip ? Math.max(0, avail - CHIP_GAP - chipW) : avail;
        String draw = this.text;
        if (this.letterSpacing <= 0 && canvas.textWidth(this.text, this.scale, this.bold) > textAvail) {
            draw = canvas.text().truncateToWidth(this.text, textAvail, this.scale, this.bold ? Font.BOLD : Font.PLAIN);
        }
        final int tw = this.letterSpacing > 0 ? this.spacedWidth(draw) : canvas.textWidth(draw, this.scale, this.bold);
        final int th = canvas.textHeight(this.scale, this.bold);
        final int blockW = tw + (chip ? CHIP_GAP + chipW : 0);
        final int tx = switch (this.gravity) {
            case CENTER, FILL -> this.innerLeft() + (avail - blockW) / 2;
            case RIGHT -> this.innerLeft() + avail - blockW;
            default -> this.innerLeft();
        };
        final int ty = this.innerTop() + Math.max(0, (this.innerHeight() - th) / 2);
        if (this.letterSpacing > 0) {
            // SPACED RUN — DRAWN GLYPH BY GLYPH WITH EACH CHAR ADVANCED BY ITS OWN RUN WIDTH PLUS THE SPACING
            float cursor = tx;
            for (int i = 0; i < draw.length(); i++) {
                final String c = String.valueOf(draw.charAt(i));
                canvas.text(c, cursor, ty, this.color, this.scale, this.bold);
                cursor += canvas.textWidth(c, this.scale, this.bold) + this.letterSpacing;
            }
        } else {
            canvas.text(draw, tx, ty, this.color, this.scale, this.bold);
        }
        if (chip) {
            // CHIP BOX CENTERED ON THE LABEL LINE; CHIP TEXT SHARES THE LABEL'S BASELINE (SAME RULE AS
            // Button.render — CELL-CENTERING DRIFTS BECAUSE THE TWO SIZES HAVE DIFFERENT DESCENDER SPACE)
            final int chipX = tx + tw + CHIP_GAP;
            final float cy = ty + th / 2f;
            canvas.stroke(chipX, cy - CHIP_H / 2f, chipW, CHIP_H, AppTheme.STROKE, 1f);
            final float baseline = ty + (this.bold
                    ? canvas.text().baselineOffsetBold(this.scale)
                    : canvas.text().baselineOffset(this.scale));
            canvas.text(this.subText, chipX + CHIP_PAD,
                    baseline - canvas.text().baselineOffset(AppTheme.TEXT_SUBTITLE),
                    AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE, false);
        }
    }
}
