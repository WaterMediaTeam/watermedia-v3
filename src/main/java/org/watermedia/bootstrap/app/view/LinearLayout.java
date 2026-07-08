package org.watermedia.bootstrap.app.view;

import org.watermedia.bootstrap.app.ui.Gravity;

/**
 * Stacks its children in a single direction, like Android's {@code LinearLayout}. Non-weighted children
 * take their measured size; children with a positive {@link View#weight} share the leftover main-axis
 * space in proportion (which only applies when this layout's main axis is bounded). A child's own
 * {@link View#gravity} aligns it on the cross axis; {@code MATCH_PARENT} on the cross axis fills it.
 */
public final class LinearLayout extends ViewGroup<LinearLayout> {

    public enum Orientation {
        VERTICAL,
        HORIZONTAL
    }

    private final Orientation orientation;
    private int spacing;

    public LinearLayout(final Orientation orientation) {
        this.orientation = orientation == null ? Orientation.VERTICAL : orientation;
    }

    public static LinearLayout column() {
        return new LinearLayout(Orientation.VERTICAL);
    }

    public static LinearLayout row() {
        return new LinearLayout(Orientation.HORIZONTAL);
    }

    public LinearLayout spacing(final int gap) {
        this.spacing = Math.max(0, gap);
        return this;
    }

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        int usedMain = 0;
        int maxCross = 0;
        int visibleCount = 0;
        float totalWeight = 0f;

        // FIRST PASS — MEASURE THE NON-WEIGHTED CHILDREN AND TALLY THE SPACE THEY TAKE
        for (final View<?> child: this.children) {
            if (!child.visible) continue;
            visibleCount++;
            if (child.weight > 0f) {
                totalWeight += child.weight;
                continue;
            }
            if (this.orientation == Orientation.VERTICAL) {
                child.measure(innerAvailWidth - child.margin.horizontal(), innerAvailHeight);
                usedMain += child.measuredHeight + child.margin.vertical();
                maxCross = Math.max(maxCross, child.measuredWidth + child.margin.horizontal());
            } else {
                child.measure(innerAvailWidth, innerAvailHeight - child.margin.vertical());
                usedMain += child.measuredWidth + child.margin.horizontal();
                maxCross = Math.max(maxCross, child.measuredHeight + child.margin.vertical());
            }
        }

        final int gaps = this.spacing * Math.max(0, visibleCount - 1);
        final int mainAvail = this.orientation == Orientation.VERTICAL ? innerAvailHeight : innerAvailWidth;
        final int leftover = Math.max(0, mainAvail - usedMain - gaps);

        // SECOND PASS — WEIGHTED CHILDREN SPLIT THE LEFTOVER MAIN-AXIS SPACE; WEIGHT FORCES THAT SIZE
        for (final View<?> child: this.children) {
            if (!child.visible || child.weight <= 0f) continue;
            final int share = totalWeight > 0f ? Math.round(leftover * (child.weight / totalWeight)) : 0;
            if (this.orientation == Orientation.VERTICAL) {
                child.measure(innerAvailWidth - child.margin.horizontal(), share);
                child.measuredHeight = share;
                usedMain += share + child.margin.vertical();
                maxCross = Math.max(maxCross, child.measuredWidth + child.margin.horizontal());
            } else {
                child.measure(share, innerAvailHeight - child.margin.vertical());
                child.measuredWidth = share;
                usedMain += share + child.margin.horizontal();
                maxCross = Math.max(maxCross, child.measuredHeight + child.margin.vertical());
            }
        }

        if (this.orientation == Orientation.VERTICAL) {
            this.contentHeight = usedMain + gaps;
            this.contentWidth = maxCross;
        } else {
            this.contentWidth = usedMain + gaps;
            this.contentHeight = maxCross;
        }
    }

    @Override
    protected void onLayout() {
        final int cx = this.innerLeft();
        final int cy = this.innerTop();
        final int cw = this.innerWidth();
        final int ch = this.innerHeight();
        boolean first = true;

        if (this.orientation == Orientation.VERTICAL) {
            int y = cy;
            for (final View<?> child: this.children) {
                if (!child.visible) continue;
                if (!first) y += this.spacing;
                first = false;
                y += child.margin.top();
                final int childX = switch (child.gravity) {
                    case CENTER, FILL -> cx + (cw - child.measuredWidth) / 2;
                    case RIGHT -> cx + cw - child.measuredWidth - child.margin.right();
                    default -> cx + child.margin.left();
                };
                child.layout(childX, y);
                y += child.measuredHeight + child.margin.bottom();
            }
        } else {
            int x = cx;
            for (final View<?> child: this.children) {
                if (!child.visible) continue;
                if (!first) x += this.spacing;
                first = false;
                x += child.margin.left();
                final int childY = switch (child.gravity) {
                    case CENTER, FILL -> cy + (ch - child.measuredHeight) / 2;
                    case BOTTOM -> cy + ch - child.measuredHeight - child.margin.bottom();
                    default -> cy + child.margin.top();
                };
                child.layout(x, childY);
                x += child.measuredWidth + child.margin.right();
            }
        }
    }
}
