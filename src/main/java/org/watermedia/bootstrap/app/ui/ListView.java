package org.watermedia.bootstrap.app.ui;

import org.watermedia.bootstrap.app.render.RenderSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Scrollable selectable list with optional custom row renderer.
 */
public class ListView<T> extends Element {

    public interface RowRenderer<T> {
        void render(T item, Dimension bounds, boolean selected, TextRenderer text, int windowW, int windowH);
    }

    private final List<T> items = new ArrayList<>();
    private Function<T, String> labelProvider = Object::toString;
    private RowRenderer<T> rowRenderer;
    private Consumer<T> onSelected;
    private int selectedIndex;
    private int scroll;
    private int width = 360;
    private int height = 300;
    private int rowHeight = 42;

    public ListView<T> items(final List<T> items) {
        this.items.clear();
        this.items.addAll(items);
        this.selectedIndex = Math.min(this.selectedIndex, Math.max(0, this.items.size() - 1));
        return this;
    }

    public ListView<T> labelProvider(final Function<T, String> labelProvider) {
        this.labelProvider = labelProvider;
        return this;
    }

    public ListView<T> rowRenderer(final RowRenderer<T> rowRenderer) {
        this.rowRenderer = rowRenderer;
        return this;
    }

    public ListView<T> onSelected(final Consumer<T> onSelected) {
        this.onSelected = onSelected;
        return this;
    }

    public ListView<T> size(final int width, final int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    public ListView<T> rowHeight(final int rowHeight) {
        this.rowHeight = rowHeight;
        return this;
    }

    public void move(final int delta) {
        if (this.items.isEmpty()) return;
        this.selectedIndex = Math.max(0, Math.min(this.items.size() - 1, this.selectedIndex + delta));
        this.ensureVisible();
    }

    public void confirm() {
        if (this.onSelected != null && this.selectedIndex >= 0 && this.selectedIndex < this.items.size()) {
            this.onSelected.accept(this.items.get(this.selectedIndex));
        }
    }

    private void ensureVisible() {
        final int visible = Math.max(1, this.bounds.height() / this.rowHeight);
        if (this.selectedIndex < this.scroll) this.scroll = this.selectedIndex;
        if (this.selectedIndex >= this.scroll + visible) this.scroll = this.selectedIndex - visible + 1;
    }

    @Override
    public void render(final TextRenderer renderer, final int windowW, final int windowH) {
        RenderSystem.setupOrtho(windowW, windowH);
        RenderSystem.fill(this.bounds.x(), this.bounds.y(), this.bounds.width(), this.bounds.height(), AppTheme.alpha(AppTheme.BG_1, 180));
        RenderSystem.rect(this.bounds.x(), this.bounds.y(), this.bounds.width(), this.bounds.height(), AppTheme.STROKE_BRIGHT, 1f);
        RenderSystem.clip(this.bounds.x(), this.bounds.y(), this.bounds.width(), this.bounds.height(), windowH);

        final int visible = Math.max(1, this.bounds.height() / this.rowHeight);
        for (int i = 0; i < visible && i + this.scroll < this.items.size(); i++) {
            final int index = i + this.scroll;
            final Dimension rowBounds = new Dimension(this.bounds.x() + 6, this.bounds.y() + i * this.rowHeight + 4,
                    this.bounds.width() - 12, this.rowHeight - 8);
            if (this.rowRenderer != null) {
                this.rowRenderer.render(this.items.get(index), rowBounds, index == this.selectedIndex, renderer, windowW, windowH);
            } else {
                this.renderDefaultRow(this.items.get(index), rowBounds, index == this.selectedIndex, renderer, windowW, windowH);
            }
        }

        RenderSystem.clearClip();
        RenderSystem.restoreProjection();
    }

    private void renderDefaultRow(final T item, final Dimension row, final boolean selected,
                                  final TextRenderer renderer, final int windowW, final int windowH) {
        if (selected) {
            RenderSystem.fill(row.x(), row.y(), row.width(), row.height(),
                    AppTheme.NEON.getRed() / 255f, AppTheme.NEON.getGreen() / 255f, AppTheme.NEON.getBlue() / 255f, 0.12f);
            RenderSystem.rect(row.x(), row.y(), row.width(), row.height(), AppTheme.NEON, 1f);
        }
        renderer.render(renderer.truncateToWidth(this.labelProvider.apply(item), row.width() - 16, AppTheme.TEXT_BODY),
                row.x() + 8, row.y() + Math.max(0, (row.height() - renderer.glyphHeight(AppTheme.TEXT_BODY)) / 2),
                selected ? AppTheme.NEON_LIGHT : AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY);
    }

    @Override
    public Dimension calculateBounds(final TextRenderer text, final int startX, final int startY) {
        this.bounds = new Dimension(startX, startY, this.width, this.height);
        return this.bounds;
    }

    @Override
    public boolean handleClick(final double mx, final double my) {
        if (!this.contains(mx, my)) return false;
        final int row = (int) ((my - this.bounds.y()) / this.rowHeight);
        final int index = this.scroll + row;
        if (index >= 0 && index < this.items.size()) {
            this.selectedIndex = index;
            this.confirm();
        }
        return true;
    }
}
