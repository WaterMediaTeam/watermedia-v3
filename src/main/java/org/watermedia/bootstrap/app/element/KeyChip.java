package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Spacing;

/**
 * A keyboard key-cap that renders a single shortcut key such as {@code "C"} or {@code "ESC"}. It sizes to
 * the key text plus a tight padding and draws a dark neon-tinted cap with a hairline neon outline and a
 * centered bold label, matching the look of an inline keyboard hint.
 *
 * <p>With an {@link #icon} set it instead renders the footer key-cap: a fixed 22px band with a 12px pixel
 * icon on the left and the uppercase key beside it — the exact look of the imperative bindings bar.
 * {@link #keyIcon} maps a key name to its footer icon.
 */
public final class KeyChip extends Element<KeyChip> {

    private static final float SCALE = AppTheme.TEXT_TINY;

    private String key = "";
    private String icon = "";

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

    /**
     * Sets the optional pixel icon; non-empty switches the chip to the footer key-cap style. The footer
     * cap is self-padded (fixed 22px band), so the text-only padding is dropped in icon mode.
     */
    public KeyChip icon(final String name) {
        this.icon = name == null ? "" : name;
        if (!this.icon.isEmpty()) this.padding = Spacing.ZERO;
        return this;
    }

    /**
     * Maps a key name to its footer icon: arrows for directional keys, play for ENTER, x for ESC,
     * pause for SPACE, info otherwise — the exact mapping of the imperative bindings bar.
     */
    public static String keyIcon(final String key) {
        final String k = key == null ? "" : key.toUpperCase();
        if (k.contains("ARROW") || k.contains("UP") || k.contains("DOWN") || k.contains("LEFT") || k.contains("RIGHT")) return "arrows";
        if (k.contains("ENTER")) return "play";
        if (k.contains("ESC")) return "x";
        if (k.contains("SPACE")) return "pause";
        return "info";
    }

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        if (this.ctx == null || this.ctx.text == null) {
            this.contentWidth = 0;
            this.contentHeight = 0;
            return;
        }
        if (!this.icon.isEmpty()) {
            // FOOTER KEY-CAP — max(36, textWidth + 36) x 22
            this.contentWidth = Math.max(36, this.ctx.text.width(this.key.toUpperCase(), AppTheme.TEXT_BODY) + 36);
            this.contentHeight = 22;
            return;
        }
        this.contentWidth = this.ctx.text.widthBold(this.key, SCALE);
        this.contentHeight = this.ctx.text.glyphHeightBold(SCALE);
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        if (!this.icon.isEmpty()) {
            // KEY CHIP — FILL, OUTLINE, 12px ICON AT +7, KEY AT +24
            canvas.fill(this.left, this.top, this.measuredWidth, this.measuredHeight, AppTheme.alpha(AppTheme.BG_2, 220));
            canvas.stroke(this.left, this.top, this.measuredWidth, this.measuredHeight, AppTheme.STROKE_BRIGHT, 1f);
            canvas.icon(this.icon, this.left + 7, this.top + (this.measuredHeight - 12) / 2, 12, AppTheme.TEXT_FAINT);
            if (!this.key.isEmpty()) {
                canvas.text(this.key.toUpperCase(), this.left + 24,
                        this.top + (this.measuredHeight - canvas.textHeight(AppTheme.TEXT_BODY, false)) / 2,
                        AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY, false);
            }
            return;
        }
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
