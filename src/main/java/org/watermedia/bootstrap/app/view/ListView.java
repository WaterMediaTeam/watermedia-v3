package org.watermedia.bootstrap.app.view;

import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * A scrollable, selectable list of data items rendered through a row factory, like Android's
 * {@code ListView}. Each item becomes a row view {@code (item, index) -> View}; the list owns scrolling,
 * clipping, the scrollbar, and the selection/hover highlight so row views only render content. Keyboard
 * navigation is available via {@link #moveSelection}, which auto-scrolls to the selection only when it
 * actually changes (free wheel scrolling is never stolen back).
 *
 * @param <D> the item data type
 */
public final class ListView<D> extends ViewGroup<ListView<D>> {

    private static final int UNBOUNDED = 1 << 20;
    private static final int SCROLL_STEP = 48;

    private final List<D> items = new ArrayList<>();
    private BiFunction<D, Integer, View<?>> rowFactory;
    private BiConsumer<D, Integer> onSelect;
    private int rowHeight;      // 0 = EACH ROW'S NATURAL HEIGHT
    private int spacing;
    private int selectedIndex = -1;
    private int scrollOffset;
    private int maxScroll;
    private int totalHeight;
    private int scrollbarWidth = 6;
    private boolean selectOnHover;
    private Color selectionColor = AppTheme.alpha(AppTheme.NEON, 46);
    private Color hoverColor = AppTheme.alpha(AppTheme.NEON_DARK, 40);

    public ListView<D> items(final List<D> data) {
        this.items.clear();
        if (data != null) this.items.addAll(data);
        if (this.selectedIndex >= this.items.size()) this.selectedIndex = this.items.size() - 1;
        this.rebuild();
        return this;
    }

    public ListView<D> rowFactory(final BiFunction<D, Integer, View<?>> factory) {
        this.rowFactory = factory;
        this.rebuild();
        return this;
    }

    public ListView<D> rowHeight(final int height) {
        this.rowHeight = Math.max(0, height);
        return this;
    }

    public ListView<D> spacing(final int gap) {
        this.spacing = Math.max(0, gap);
        return this;
    }

    public ListView<D> onSelect(final BiConsumer<D, Integer> handler) {
        this.onSelect = handler;
        return this;
    }

    public ListView<D> selectionColor(final Color color) {
        this.selectionColor = color;
        return this;
    }

    public ListView<D> hoverColor(final Color color) {
        this.hoverColor = color;
        return this;
    }

    public ListView<D> scrollbarWidth(final int width) {
        this.scrollbarWidth = Math.max(2, width);
        return this;
    }

    public ListView<D> selectOnHover(final boolean value) {
        this.selectOnHover = value;
        return this;
    }

    public ListView<D> selection(final int index) {
        this.selectedIndex = Math.max(-1, Math.min(index, this.items.size() - 1));
        return this;
    }

    public int selectedIndex() {
        return this.selectedIndex;
    }

    public D selectedItem() {
        return this.selectedIndex >= 0 && this.selectedIndex < this.items.size() ? this.items.get(this.selectedIndex) : null;
    }

    public ListView<D> moveSelection(final int delta) {
        if (this.items.isEmpty()) return this;
        final int base = this.selectedIndex < 0 ? 0 : this.selectedIndex;
        final int next = Math.max(0, Math.min(base + delta, this.items.size() - 1));
        if (next != this.selectedIndex) {
            this.selectedIndex = next;
            this.ensureVisible(next);
            this.invalidate();
        }
        return this;
    }

    private void rebuild() {
        this.children.clear();
        if (this.rowFactory == null) return;
        for (int i = 0; i < this.items.size(); i++) {
            final int index = i;
            final View<?> row = this.rowFactory.apply(this.items.get(i), index);
            if (row == null) continue;
            row.width(MATCH_PARENT);
            if (this.rowHeight > 0) row.height(this.rowHeight);
            row.onClick(v -> this.select(index));
            this.add(row);
        }
    }

    private void select(final int index) {
        this.selectedIndex = index;
        this.invalidate();
        if (this.onSelect != null && index >= 0 && index < this.items.size()) {
            this.onSelect.accept(this.items.get(index), index);
        }
    }

    private void ensureVisible(final int index) {
        int y = 0;
        for (int i = 0; i < index && i < this.children.size(); i++) {
            y += this.children.get(i).measuredHeight + this.spacing;
        }
        final int rowH = index < this.children.size() ? this.children.get(index).measuredHeight : this.rowHeight;
        final int viewport = this.innerHeight();
        if (y < this.scrollOffset) {
            this.scrollOffset = y;
        } else if (y + rowH > this.scrollOffset + viewport) {
            this.scrollOffset = y + rowH - viewport;
        }
    }

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        int used = 0;
        int maxW = 0;
        int visibleCount = 0;
        for (final View<?> row: this.children) {
            if (!row.visible) continue;
            visibleCount++;
            row.measure(innerAvailWidth, this.rowHeight > 0 ? this.rowHeight : UNBOUNDED);
            if (this.rowHeight > 0) row.measuredHeight = this.rowHeight;
            used += row.measuredHeight;
            maxW = Math.max(maxW, row.measuredWidth);
        }
        this.totalHeight = used + this.spacing * Math.max(0, visibleCount - 1);
        this.contentWidth = maxW;
        this.contentHeight = Math.min(this.totalHeight, innerAvailHeight);
    }

    @Override
    protected void onLayout() {
        this.maxScroll = Math.max(0, this.totalHeight - this.innerHeight());
        this.scrollOffset = Math.max(0, Math.min(this.scrollOffset, this.maxScroll));
        int y = this.innerTop() - this.scrollOffset;
        boolean first = true;
        for (final View<?> row: this.children) {
            if (!row.visible) continue;
            if (!first) y += this.spacing;
            first = false;
            row.layout(this.innerLeft(), y);
            y += row.measuredHeight;
        }
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        canvas.pushClip(this.left, this.top, this.measuredWidth, this.measuredHeight);
        final int viewTop = this.top;
        final int viewBottom = this.top + this.measuredHeight;
        for (int i = 0; i < this.children.size(); i++) {
            final View<?> row = this.children.get(i);
            if (!row.visible) continue;
            // TELL THE ROW ITS SELECTION STATE SO CUSTOM ROWS CAN SELF-STYLE; ALSO PAINT THE DEFAULT HIGHLIGHT
            row.selected(i == this.selectedIndex);
            // CULL ROWS FULLY OUTSIDE THE VIEWPORT IN LOGIC — DON'T RELY ONLY ON THE SCISSOR, SO A SCROLLED
            // ROW CAN NEVER BLEED OVER THE CHROME EVEN IF A BACKEND'S CLIP MISBEHAVES
            if (row.top + row.measuredHeight <= viewTop || row.top >= viewBottom) continue;
            if (i == this.selectedIndex) {
                canvas.fill(row.left, row.top, row.measuredWidth, row.measuredHeight, this.selectionColor);
            } else if (row.hovered) {
                canvas.fill(row.left, row.top, row.measuredWidth, row.measuredHeight, this.hoverColor);
            }
            row.draw(canvas);
        }
        canvas.popClip();

        if (this.maxScroll > 0) {
            final int trackH = this.measuredHeight;
            final int thumbH = Math.max(24, (int) ((long) trackH * trackH / (trackH + this.maxScroll)));
            final int thumbY = this.top + (int) ((long) (trackH - thumbH) * this.scrollOffset / this.maxScroll);
            final int barX = this.left + this.measuredWidth - this.scrollbarWidth;
            canvas.fill(barX, thumbY, this.scrollbarWidth, thumbH, AppTheme.alpha(AppTheme.NEON, 130));
        }
    }

    @Override
    public boolean dispatchClick(final double mx, final double my) {
        if (!this.contains(mx, my)) return false;
        return super.dispatchClick(mx, my);
    }

    @Override
    public boolean dispatchHover(final double mx, final double my) {
        if (!this.contains(mx, my)) {
            for (final View<?> row: this.children) row.dispatchHover(-1, -1);
            this.hovered = false;
            return false;
        }
        final boolean result = super.dispatchHover(mx, my);
        if (this.selectOnHover) {
            for (int i = 0; i < this.children.size(); i++) {
                if (this.children.get(i).hovered && i != this.selectedIndex) {
                    this.selectedIndex = i;
                    this.invalidate();
                    break;
                }
            }
        }
        return result;
    }

    @Override
    public boolean dispatchScroll(final double mx, final double my, final double amount) {
        if (!this.contains(mx, my) || this.maxScroll <= 0) return false;
        final int next = Math.max(0, Math.min(this.maxScroll, this.scrollOffset - (int) (amount * SCROLL_STEP)));
        if (next != this.scrollOffset) {
            this.scrollOffset = next;
            this.invalidate();
        }
        return true;
    }
}
