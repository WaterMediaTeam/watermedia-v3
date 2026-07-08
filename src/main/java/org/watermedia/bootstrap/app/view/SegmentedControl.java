package org.watermedia.bootstrap.app.view;

import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;
import java.util.function.Consumer;

/**
 * A row of equal-width, tab-like segments where exactly one is selected. Each segment measures to its
 * label plus padding (with a minimum width) and the row paints them at equal widths across its content
 * box: the selected segment is filled with the accent and labeled in the darkest background color, the
 * rest carry a faint accent wash and soft text, separated and outlined by a translucent accent hairline.
 * Clicking a segment selects it and reports the new index through {@code onSelect}.
 *
 * <p>The selected index is exposed as {@link #selectedIndex()} rather than {@code selected()} because the
 * base {@link View} already defines a boolean {@code selected()} state accessor.
 */
public final class SegmentedControl extends View<SegmentedControl> {

    private String[] segments = new String[0];
    private int selectedIndex;
    private Color accent = AppTheme.NEON;
    private Consumer<Integer> onSelect;
    private float scale = AppTheme.TEXT_BUTTON;

    public SegmentedControl() {
        this.height = Theme.BUTTON;
    }

    public SegmentedControl segments(final String[] values) {
        this.segments = values == null ? new String[0] : values;
        return this;
    }

    public SegmentedControl selected(final int index) {
        this.selectedIndex = index;
        return this;
    }

    public SegmentedControl accent(final Color color) {
        this.accent = color == null ? AppTheme.NEON : color;
        return this;
    }

    public SegmentedControl onSelect(final Consumer<Integer> handler) {
        this.onSelect = handler;
        return this;
    }

    public SegmentedControl scale(final float value) {
        this.scale = value;
        return this;
    }

    public int selectedIndex() {
        return this.selectedIndex;
    }

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        this.contentHeight = Theme.BUTTON;
        int total = 0;
        if (this.ctx != null && this.ctx.text != null) {
            for (final String seg : this.segments) {
                total += Math.max(64, this.ctx.text.width(seg, this.scale) + 24);
            }
        }
        this.contentWidth = total;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final int n = this.segments.length;
        if (n == 0) return;
        final int base = this.innerLeft();
        final int w = this.innerWidth();
        final int h = this.innerHeight();
        final int y = this.innerTop();
        for (int i = 0; i < n; i++) {
            // EQUAL WIDTHS VIA LONG MATH SO ROUNDING NEVER LEAVES A SEAM BETWEEN SEGMENTS
            final int x0 = base + (int) ((long) w * i / n);
            final int x1 = base + (int) ((long) w * (i + 1) / n);
            final int segW = x1 - x0;
            final boolean sel = i == this.selectedIndex;
            canvas.fill(x0, y, segW, h, AppTheme.alpha(this.accent, sel ? 210 : 22));
            final String label = this.segments[i];
            if (label != null && !label.isEmpty()) {
                final Color tc = sel ? AppTheme.BG_0 : AppTheme.TEXT_SOFT;
                final int tw = canvas.textWidth(label, this.scale, false);
                final int th = canvas.textHeight(this.scale, false);
                canvas.text(label, x0 + (segW - tw) / 2, y + (h - th) / 2, tc, this.scale, false);
            }
            if (i > 0) canvas.fill(x0, y, 1, h, AppTheme.alpha(this.accent, 120));
        }
        canvas.stroke(base, y, w, h, AppTheme.alpha(this.accent, 120), 1f);
    }

    @Override
    public boolean dispatchClick(final double mx, final double my) {
        final int n = this.segments.length;
        if (n == 0 || !this.contains(mx, my)) return false;
        final int segW = this.innerWidth() / n;
        int idx = segW > 0 ? (int) ((mx - this.innerLeft()) / segW) : 0;
        idx = Math.max(0, Math.min(n - 1, idx));
        if (idx != this.selectedIndex) {
            this.selectedIndex = idx;
            this.invalidate();
            if (this.onSelect != null) this.onSelect.accept(idx);
        }
        return true;
    }
}
