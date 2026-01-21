package org.watermedia.bootstrap.app.ui;

import org.watermedia.tools.DrawTool;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * A selector component with support for sections/groups.
 *
 * @param <T> Type of items in the selector
 */
public class Selector<T> {

    private final List<Entry<T>> entries = new ArrayList<>();
    private int selectedIndex = 0;
    private int minWidth = 400;
    private int indent = 20;

    private Function<T, String> labelProvider;
    private Function<T, String> statusProvider;
    private BiConsumer<Integer, T> onSelect;
    private Runnable onBack;
    private Runnable onSelectionChanged;

    private String title;
    private String backLabel = "[BACK]";
    private boolean showBack = true;

    private Dimension bounds = Dimension.ZERO;
    private final List<Dimension> itemBounds = new ArrayList<>();

    public Selector() {
        this.labelProvider = Object::toString;
    }

    // ========================================
    // Building entries
    // ========================================

    public Selector<T> clear() {
        this.entries.clear();
        this.selectedIndex = 0;
        return this;
    }

    public Selector<T> section(final String header) {
        this.entries.add(new Entry<>(header, null, true));
        return this;
    }

    public Selector<T> item(final T item) {
        this.entries.add(new Entry<>(null, item, false));
        return this;
    }

    public Selector<T> item(final String label, final T item) {
        this.entries.add(new Entry<>(label, item, false));
        return this;
    }

    public Selector<T> items(final List<T> items) {
        for (final T item : items) {
            this.entries.add(new Entry<>(null, item, false));
        }
        return this;
    }

    // ========================================
    // Configuration
    // ========================================

    public Selector<T> title(final String t) {
        this.title = t;
        return this;
    }

    public Selector<T> backLabel(final String l) {
        this.backLabel = l;
        return this;
    }

    public Selector<T> showBack(final boolean s) {
        this.showBack = s;
        return this;
    }

    public Selector<T> minWidth(final int w) {
        this.minWidth = w;
        return this;
    }

    public Selector<T> indent(final int i) {
        this.indent = i;
        return this;
    }

    public Selector<T> labelProvider(final Function<T, String> p) {
        this.labelProvider = p;
        return this;
    }

    public Selector<T> statusProvider(final Function<T, String> p) {
        this.statusProvider = p;
        return this;
    }

    public Selector<T> onSelect(final BiConsumer<Integer, T> handler) {
        this.onSelect = handler;
        return this;
    }

    public Selector<T> onBack(final Runnable handler) {
        this.onBack = handler;
        return this;
    }

    public Selector<T> onSelectionChanged(final Runnable handler) {
        this.onSelectionChanged = handler;
        return this;
    }

    // ========================================
    // State queries
    // ========================================

    public int selectedIndex() {
        return this.selectedIndex;
    }

    public T selectedItem() {
        final int idx = this.getSelectableIndex(this.selectedIndex);
        if (idx >= 0 && idx < this.entries.size()) {
            return this.entries.get(idx).item;
        }
        return null;
    }

    public int selectableCount() {
        final int count = (int) this.entries.stream().filter(e -> !e.header).count();
        return count + (this.showBack ? 1 : 0);
    }

    public boolean isBackSelected() {
        final int selectables = (int) this.entries.stream().filter(e -> !e.header).count();
        return this.showBack && this.selectedIndex == selectables;
    }

    public Dimension bounds() {
        return this.bounds;
    }

    // ========================================
    // Navigation
    // ========================================

    public void selectIndex(final int index) {
        final int count = this.selectableCount();
        if (count == 0) return;
        final int newIndex = Math.max(0, Math.min(index, count - 1));
        if (newIndex != this.selectedIndex) {
            this.selectedIndex = newIndex;
            if (this.onSelectionChanged != null) this.onSelectionChanged.run();
        }
    }

    public void moveUp() {
        final int count = this.selectableCount();
        if (count == 0) return;
        final int newIndex = (this.selectedIndex - 1 + count) % count;
        if (newIndex != this.selectedIndex) {
            this.selectedIndex = newIndex;
            if (this.onSelectionChanged != null) this.onSelectionChanged.run();
        }
    }

    public void moveDown() {
        final int count = this.selectableCount();
        if (count == 0) return;
        final int newIndex = (this.selectedIndex + 1) % count;
        if (newIndex != this.selectedIndex) {
            this.selectedIndex = newIndex;
            if (this.onSelectionChanged != null) this.onSelectionChanged.run();
        }
    }

    public void confirm() {
        if (this.isBackSelected()) {
            if (this.onBack != null) this.onBack.run();
        } else if (this.onSelect != null) {
            final T item = this.selectedItem();
            if (item != null) {
                this.onSelect.accept(this.selectedIndex, item);
            }
        }
    }

    // ========================================
    // Rendering
    // ========================================

    public void render(final TextRenderer renderer, final int windowW, final int windowH) {
        this.itemBounds.clear();
        DrawTool.setupOrtho(windowW, windowH);

        int y = this.bounds.y();
        final int lineH = renderer.lineHeight();

        // Title
        if (this.title != null && !this.title.isEmpty()) {
            renderer.render(this.title, this.bounds.x(), y, Colors.BLUE);
            y += lineH + 10;
        }

        // Entries
        int selectableIdx = 0;
        int itemNumber = 1;
        for (final Entry<T> entry : this.entries) {
            if (entry.header) {
                // Section header
                y += 5;
                renderer.render(entry.label, this.bounds.x(), y, Colors.BLUE);
                y += lineH;
            } else {
                // Selectable item
                final boolean selected = (selectableIdx == this.selectedIndex);
                final String prefix = selected ? "> " : "  ";
                final String label = entry.label != null ? entry.label : this.labelProvider.apply(entry.item);
                final String status = this.statusProvider != null ? " " + this.statusProvider.apply(entry.item) : "";
                final String text = prefix + itemNumber + ". " + label + status;

                renderer.render(text, this.bounds.x() + this.indent, y, selected ? Colors.YELLOW : Colors.GRAY);

                final int itemW = Math.max(renderer.width(text) + this.indent, this.minWidth);
                this.itemBounds.add(new Dimension(this.bounds.x(), y, itemW, lineH));

                y += lineH;
                selectableIdx++;
                itemNumber++;
            }
        }

        // Back option
        if (this.showBack) {
            y += 10;
            final boolean selected = this.isBackSelected();
            final String text = (selected ? "> " : "  ") + this.backLabel;
            renderer.render(text, this.bounds.x(), y, selected ? Colors.RED : Colors.GRAY);
            this.itemBounds.add(new Dimension(this.bounds.x(), y, Math.max(renderer.width(text), this.minWidth), lineH));
        }

        DrawTool.restoreProjection();
    }

    public Dimension calculateBounds(final TextRenderer text, final int startX, final int startY) {
        final int lineH = text.lineHeight();
        int height = 0;

        if (this.title != null) height += lineH + 10;

        for (final Entry<T> entry : this.entries) {
            if (entry.header) {
                height += lineH + 5;
            } else {
                height += lineH;
            }
        }

        if (this.showBack) height += lineH + 10;

        int width = this.minWidth;
        int itemNumber = 1;
        for (final Entry<T> entry : this.entries) {
            if (!entry.header) {
                final String label = entry.label != null ? entry.label : this.labelProvider.apply(entry.item);
                final String status = this.statusProvider != null ? " " + this.statusProvider.apply(entry.item) : "";
                width = Math.max(width, text.width("> " + itemNumber + ". " + label + status) + this.indent);
                itemNumber++;
            }
        }

        this.bounds = new Dimension(startX, startY, width, height);
        return this.bounds;
    }

    // ========================================
    // Mouse handling
    // ========================================

    public boolean handleClick(final double mx, final double my) {
        for (int i = 0; i < this.itemBounds.size(); i++) {
            if (this.itemBounds.get(i).contains(mx, my)) {
                this.selectIndex(i);
                this.confirm();
                return true;
            }
        }
        return false;
    }

    public boolean handleHover(final double mx, final double my) {
        for (int i = 0; i < this.itemBounds.size(); i++) {
            if (this.itemBounds.get(i).contains(mx, my)) {
                if (this.selectedIndex != i) {
                    this.selectIndex(i);
                    return true;
                }
                break;
            }
        }
        return false;
    }

    // ========================================
    // Internal
    // ========================================

    private int getSelectableIndex(final int selectablePosition) {
        int count = 0;
        for (int i = 0; i < this.entries.size(); i++) {
            if (!this.entries.get(i).header) {
                if (count == selectablePosition) return i;
                count++;
            }
        }
        return -1;
    }

    private record Entry<T>(String label, T item, boolean header) {
    }
}
