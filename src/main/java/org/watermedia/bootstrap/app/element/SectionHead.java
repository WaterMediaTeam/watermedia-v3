package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Font;
import java.util.Locale;

/**
 * A section heading: a glowing neon tab, a bold uppercase label and an optional faint count or detail after it.
 * The static {@link #draw} paints the same heading inside composites that lay out by hand.
 */
public final class SectionHead extends Element<SectionHead> {
    /** Height of the heading row. */
    public static final int HEIGHT = 30;
    private static final int TAB_TOP = 3;
    private static final int TAB_W = 4;
    private static final int TAB_H = 21;
    private static final int LABEL_X = 18;
    private static final int COUNT_GAP = 12;

    private String label = "";
    private String count = "";
    // THE TEXTS AS LAST GIVEN: SCREENS RE-BIND EVERY FRAME, AND AN UNCHANGED TEXT SKIPS THE UPPERCASE COPY
    private String labelSource = "";
    private String countSource = "";

    public SectionHead() {
        this.width = MAX_PARENT;
        this.height = HEIGHT;
    }

    public SectionHead(final String label) {
        this();
        this.label(label);
    }

    public SectionHead label(final String value) {
        final String source = value == null ? "" : value;
        if (!source.equals(this.labelSource)) {
            this.labelSource = source;
            this.label = source.toUpperCase(Locale.ROOT);
        }
        return this;
    }

    public SectionHead count(final String value) {
        final String source = value == null ? "" : value;
        if (!source.equals(this.countSource)) {
            this.countSource = source;
            this.count = source.toUpperCase(Locale.ROOT);
        }
        return this;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        draw(canvas, this.label, this.count, this.left, this.top, this.measuredWidth);
    }

    /** Paints a heading whose row starts at ({@code x}, {@code y}); the count is cut to fit {@code width}. */
    public static void draw(final Canvas canvas, final String label, final String count, final int x, final int y, final int width) {
        final int tabY = y + TAB_TOP;
        canvas.fill(x, tabY, TAB_W, TAB_H, AppTheme.NEON);
        canvas.glow(x, tabY, TAB_W, TAB_H, 0f, AppTheme.NEON, Theme.GLOW_WEAK);
        canvas.text(label, x + LABEL_X, tabY + Math.max(0, (TAB_H - canvas.textHeight(AppTheme.TEXT_SECTION, true)) / 2),
                AppTheme.NEON, AppTheme.TEXT_SECTION, true);
        if (count == null || count.isEmpty()) return;
        final int countX = x + LABEL_X + canvas.textWidth(label, AppTheme.TEXT_SECTION, true) + COUNT_GAP;
        final String shown = canvas.text().truncateToWidth(count, x + width - countX, AppTheme.TEXT_BODY, Font.PLAIN);
        canvas.text(shown, countX, tabY + Math.max(0, (TAB_H - canvas.textHeight(AppTheme.TEXT_BODY, false)) / 2),
                AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY, false);
    }
}
