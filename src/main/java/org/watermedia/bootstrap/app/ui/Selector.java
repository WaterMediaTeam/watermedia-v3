package org.watermedia.bootstrap.app.ui;

import org.watermedia.tools.DrawTool;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.lwjgl.opengl.GL11.*;

/**
 * A selector component with support for sections/groups and scrolling.
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

    // SCROLLING SUPPORT
    private int maxHeight = -1;
    private int scrollOffset = 0;
    private int totalContentHeight = 0;
    private int visibleHeight = 0;
    private int cachedLineHeight = 24;
    private static final int SCROLLBAR_WIDTH = 8;
    private static final int SCROLL_AMOUNT = 30;

    public Selector() {
        this.labelProvider = Object::toString;
    }

    // BUILDING ENTRIES
    public Selector<T> clear() {
        this.entries.clear();
        this.selectedIndex = 0;
        this.scrollOffset = 0;
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
        for (final T item: items) {
            this.entries.add(new Entry<>(null, item, false));
        }
        return this;
    }

    // CONFIGURATION
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

    public Selector<T> maxHeight(final int h) {
        this.maxHeight = h;
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

    // STATE QUERIES
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

    // NAVIGATION
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
            this.ensureSelectedVisible();
        }
    }

    public void moveDown() {
        final int count = this.selectableCount();
        if (count == 0) return;
        final int newIndex = (this.selectedIndex + 1) % count;
        if (newIndex != this.selectedIndex) {
            this.selectedIndex = newIndex;
            if (this.onSelectionChanged != null) this.onSelectionChanged.run();
            this.ensureSelectedVisible();
        }
    }

    public void scrollBy(final int delta) {
        if (!this.needsScrollbar()) return;
        final int maxScroll = this.totalContentHeight - this.visibleHeight;
        this.scrollOffset = Math.max(0, Math.min(maxScroll, this.scrollOffset + delta));
    }

    public void handleScroll(final double yOffset) {
        this.scrollBy((int) (-yOffset * SCROLL_AMOUNT));
    }

    public boolean needsScrollbar() {
        return this.maxHeight > 0 && this.totalContentHeight > this.visibleHeight;
    }

    private void ensureSelectedVisible() {
        if (this.maxHeight <= 0 || this.totalContentHeight <= this.maxHeight) return;
        if (this.selectedIndex < 0) return;

        // IF FIRST ITEM IS SELECTED, RESET SCROLL TO SHOW TITLE
        if (this.selectedIndex == 0) {
            this.scrollOffset = 0;
            return;
        }

        // CALCULATE Y POSITION OF SELECTED ITEM RELATIVE TO CONTENT START
        final int lineH = this.cachedLineHeight;
        int y = 0;

        // TITLE
        if (this.title != null && !this.title.isEmpty()) {
            y += lineH + 10;
        }

        // FIND THE SELECTED ITEM'S POSITION
        int selectableIdx = 0;
        for (final Entry<T> entry: this.entries) {
            if (entry.header) {
                y += 5 + lineH;
            } else {
                if (selectableIdx == this.selectedIndex) {
                    // FOUND THE SELECTED ITEM
                    final int itemTop = y;
                    final int itemBottom = y + lineH;

                    // CHECK IF WE NEED TO SCROLL UP
                    if (itemTop < this.scrollOffset) {
                        this.scrollOffset = Math.max(0, itemTop - 5);
                    }
                    // CHECK IF WE NEED TO SCROLL DOWN
                    else if (itemBottom > this.scrollOffset + this.visibleHeight) {
                        this.scrollOffset = itemBottom - this.visibleHeight + 5;
                    }
                    return;
                }
                y += lineH;
                selectableIdx++;
            }
        }

        // CHECK FOR BACK BUTTON
        if (this.showBack && this.isBackSelected()) {
            y += 10;
            final int itemTop = y;
            final int itemBottom = y + lineH;

            if (itemTop < this.scrollOffset) {
                this.scrollOffset = Math.max(0, itemTop - 5);
            } else if (itemBottom > this.scrollOffset + this.visibleHeight) {
                this.scrollOffset = itemBottom - this.visibleHeight + 5;
            }
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

    // RENDERING
    public void render(final TextRenderer renderer, final int windowW, final int windowH) {
        this.itemBounds.clear();
        DrawTool.setupOrtho(windowW, windowH);

        final int lineH = renderer.lineHeight();
        final boolean useScissor = this.maxHeight > 0 && this.totalContentHeight > this.maxHeight;

        // CALCULATE VISIBLE AREA
        this.visibleHeight = this.maxHeight > 0 ? Math.min(this.maxHeight, this.totalContentHeight) : this.totalContentHeight;

        // CLAMP SCROLL OFFSET
        if (useScissor) {
            final int maxScroll = this.totalContentHeight - this.visibleHeight;
            this.scrollOffset = Math.max(0, Math.min(maxScroll, this.scrollOffset));
        } else {
            this.scrollOffset = 0;
        }

        // ENABLE SCISSOR TEST FOR CLIPPING
        if (useScissor) {
            glEnable(GL_SCISSOR_TEST);
            // OPENGL SCISSOR USES BOTTOM-LEFT ORIGIN, SO CONVERT COORDINATES
            final int scissorY = windowH - this.bounds.y() - this.visibleHeight;
            glScissor(0, scissorY, windowW - SCROLLBAR_WIDTH - 10, this.visibleHeight);
        }

        int y = this.bounds.y() - this.scrollOffset;

        // TITLE
        if (this.title != null && !this.title.isEmpty()) {
            renderer.render(this.title, this.bounds.x(), y, Colors.BLUE);
            y += lineH + 10;
        }

        // ENTRIES
        int selectableIdx = 0;
        int itemNumber = 1;
        for (final Entry<T> entry: this.entries) {
            if (entry.header) {
                // SECTION HEADER
                y += 5;
                renderer.render(entry.label, this.bounds.x(), y, Colors.BLUE);
                y += lineH;
            } else {
                // SELECTABLE ITEM
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

        // BACK OPTION
        if (this.showBack) {
            y += 10;
            final boolean selected = this.isBackSelected();
            final String text = (selected ? "> " : "  ") + this.backLabel;
            renderer.render(text, this.bounds.x(), y, selected ? Colors.RED : Colors.GRAY);
            this.itemBounds.add(new Dimension(this.bounds.x(), y, Math.max(renderer.width(text), this.minWidth), lineH));
        }

        // DISABLE SCISSOR TEST
        if (useScissor) {
            glDisable(GL_SCISSOR_TEST);
        }

        // DRAW SCROLLBAR IF NEEDED
        if (useScissor) {
            this.renderScrollbar(windowW, windowH);
        }

        DrawTool.restoreProjection();
    }

    private void renderScrollbar(final int windowW, final int windowH) {
        final int scrollbarX = windowW - SCROLLBAR_WIDTH - 10;
        final int scrollbarY = this.bounds.y();
        final int scrollbarHeight = this.visibleHeight;

        // SCROLLBAR TRACK (DARK BACKGROUND)
        DrawTool.disableTextures();
        DrawTool.fill(scrollbarX, scrollbarY, SCROLLBAR_WIDTH, scrollbarHeight, 0.15f, 0.15f, 0.15f, 0.8f);

        // SCROLLBAR THUMB
        final float thumbRatio = (float) this.visibleHeight / this.totalContentHeight;
        final int thumbHeight = Math.max(20, (int) (scrollbarHeight * thumbRatio));
        final float scrollRatio = (float) this.scrollOffset / (this.totalContentHeight - this.visibleHeight);
        final int thumbY = scrollbarY + (int) ((scrollbarHeight - thumbHeight) * scrollRatio);

        DrawTool.fill(scrollbarX, thumbY, SCROLLBAR_WIDTH, thumbHeight, 0.31f, 0.71f, 1f, 0.9f);
        DrawTool.enableTextures();
    }

    public Dimension calculateBounds(final TextRenderer text, final int startX, final int startY) {
        final int lineH = text.lineHeight();
        this.cachedLineHeight = lineH;
        int height = 0;

        if (this.title != null) height += lineH + 10;

        for (final Entry<T> entry: this.entries) {
            if (entry.header) {
                height += lineH + 5;
            } else {
                height += lineH;
            }
        }

        if (this.showBack) height += lineH + 10;

        int width = this.minWidth;
        int itemNumber = 1;
        for (final Entry<T> entry: this.entries) {
            if (!entry.header) {
                final String label = entry.label != null ? entry.label : this.labelProvider.apply(entry.item);
                final String status = this.statusProvider != null ? " " + this.statusProvider.apply(entry.item) : "";
                width = Math.max(width, text.width("> " + itemNumber + ". " + label + status) + this.indent);
                itemNumber++;
            }
        }

        // STORE TOTAL CONTENT HEIGHT FOR SCROLLING
        this.totalContentHeight = height;
        this.visibleHeight = this.maxHeight > 0 ? Math.min(this.maxHeight, height) : height;

        this.bounds = new Dimension(startX, startY, width, height);
        return this.bounds;
    }

    // MOUSE HANDLING
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

    // INTERNAL
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
