package org.watermedia.bootstrap.app.view;

import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Spacing;

/**
 * A keyboard key-cap that renders a single shortcut key such as {@code "C"} or {@code "ESC"}. It sizes to
 * the key text plus a tight padding and draws a dark neon-tinted cap with a hairline neon outline and a
 * centered bold label, matching the look of an inline keyboard hint.
 */
public final class KeyChip extends View<KeyChip> {

    private static final float SCALE = AppTheme.TEXT_TINY;

    private String key = "";

    public KeyChip() {
        this.padding = Spacing.hv(6, 2);
    }

    public KeyChip(final String key) {
        this();
        this.key = key == null ? "" : key;
    }

    public KeyChip key(final String value) {
        this.key = value == null ? "" : value;
        return this;
    }

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        if (this.ctx == null || this.ctx.text == null) {
            this.contentWidth = 0;
            this.contentHeight = 0;
            return;
        }
        this.contentWidth = this.ctx.text.widthBold(this.key, SCALE);
        this.contentHeight = this.ctx.text.glyphHeightBold(SCALE);
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        canvas.fill(this.left, this.top, this.measuredWidth, this.measuredHeight, AppTheme.alpha(AppTheme.NEON_DARK, 60));
        canvas.stroke(this.left, this.top, this.measuredWidth, this.measuredHeight, AppTheme.alpha(AppTheme.NEON, 110), 1f);
        if (!this.key.isEmpty()) {
            final int tw = canvas.textWidth(this.key, SCALE, true);
            final int th = canvas.textHeight(SCALE, true);
            canvas.text(this.key, this.left + (this.measuredWidth - tw) / 2,
                    this.top + (this.measuredHeight - th) / 2, AppTheme.TEXT_SOFT, SCALE, true);
        }
    }
}
