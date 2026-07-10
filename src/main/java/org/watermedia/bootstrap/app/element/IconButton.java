package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;

/**
 * An icon-only clickable control — the square sibling of {@link Button}. It reuses the exact button box
 * (background, border, hover glow via {@link Button#box}) but centers a single icon with no label or
 * chip: dialog closes, media transport keys, and other compact controls. A single accent drives the
 * whole look (border, glow, hover tint) so callers only pick a hue.
 */
public final class IconButton extends Element<IconButton> {

    private static final int DEFAULT_ICON = 14;

    private String icon = "";
    private int iconSize = DEFAULT_ICON;
    private Color accent = AppTheme.NEON;
    private Color iconColor = AppTheme.TEXT;
    private boolean filled;
    private boolean bordered = true;

    public IconButton() {
    }

    public IconButton(final String icon) {
        this.icon = icon == null ? "" : icon;
    }

    public IconButton icon(final String key) {
        this.icon = key == null ? "" : key;
        return this;
    }

    public IconButton iconSize(final int px) {
        this.iconSize = Math.max(1, px);
        return this;
    }

    public IconButton accent(final Color value) {
        this.accent = value == null ? AppTheme.NEON : value;
        return this;
    }

    public IconButton iconColor(final Color value) {
        this.iconColor = value;
        return this;
    }

    public IconButton filled(final boolean value) {
        this.filled = value;
        return this;
    }

    // WHEN false, DROPS THE BOX ENTIRELY (border=0, NO FILL/GLOW) — A BARE CENTERED ICON, E.G. TITLEBAR CONTROLS
    public IconButton bordered(final boolean value) {
        this.bordered = value;
        return this;
    }

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        // SQUARE BY DEFAULT: THE ICON PLUS EVEN PADDING ON EVERY SIDE
        this.contentWidth = this.iconSize + 12;
        this.contentHeight = this.iconSize + 12;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        render(canvas, this.left, this.top, this.measuredWidth, this.measuredHeight, this.icon, this.iconSize,
                this.accent, this.iconColor, this.filled, this.hovered, this.enabled, this.bordered);
    }

    /**
     * Paints an icon-only button (with the shared button box) into ({@code x,y,w,h}) — a single centered
     * icon, the one source of the icon-button look for both the view and the screens.
     */
    public static void render(final Canvas canvas, final int x, final int y, final int w, final int h, final String icon,
                              final int iconSize, final Color accent, final Color iconColor,
                              final boolean filled, final boolean hover, final boolean enabled) {
        render(canvas, x, y, w, h, icon, iconSize, accent, iconColor, filled, hover, enabled, true);
    }

    /**
     * As {@link #render}, but {@code bordered=false} draws only the centered icon (no box/border/glow) for
     * bare controls like the window titlebar.
     */
    public static void render(final Canvas canvas, final int x, final int y, final int w, final int h, final String icon,
                              final int iconSize, final Color accent, final Color iconColor,
                              final boolean filled, final boolean hover, final boolean enabled, final boolean bordered) {
        if (bordered) Button.box(canvas, x, y, w, h, accent, filled, hover, enabled);
        if (icon != null && !icon.isEmpty()) {
            final Color ic = !enabled ? AppTheme.TEXT_FAINT : bordered && filled ? AppTheme.BG_0 : iconColor;
            canvas.icon(icon, x + (w - iconSize) / 2, y + (h - iconSize) / 2, iconSize, ic);
        }
    }
}
