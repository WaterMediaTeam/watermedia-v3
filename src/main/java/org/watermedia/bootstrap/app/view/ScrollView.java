package org.watermedia.bootstrap.app.view;

import org.watermedia.bootstrap.app.ui.AppTheme;

/**
 * Clips its content to its own bounds and scrolls it vertically, like Android's {@code ScrollView}. It
 * wraps a single content view (typically a {@link LinearLayout} column) measured at its natural height;
 * the wheel adjusts the offset and a thin scrollbar shows the position when the content overflows.
 */
public final class ScrollView extends ViewGroup<ScrollView> {

    private static final int UNBOUNDED = 1 << 20; // MEASURE CONTENT AT ITS NATURAL HEIGHT, NOT THE VIEWPORT'S
    private static final int SCROLL_STEP = 48;

    private int scrollbarWidth = 6;
    private int scrollOffset;
    private int maxScroll;

    public ScrollView scrollbarWidth(final int width) {
        this.scrollbarWidth = Math.max(2, width);
        return this;
    }

    private View<?> content() {
        for (final View<?> child: this.children) {
            if (child.visible) return child;
        }
        return null;
    }

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        final View<?> content = this.content();
        if (content == null) {
            this.contentWidth = 0;
            this.contentHeight = 0;
            return;
        }
        content.measure(innerAvailWidth, UNBOUNDED);
        this.contentWidth = content.measuredWidth;
        // THE VIEWPORT HEIGHT COMES FROM THIS VIEW'S OWN SIZE PARAM, NOT THE CONTENT — REPORT THE OFFERED
        // HEIGHT SO A WRAP_CONTENT SCROLLVIEW STILL BOUNDS ITSELF RATHER THAN GROWING TO THE FULL CONTENT
        this.contentHeight = Math.min(content.measuredHeight, innerAvailHeight);
    }

    @Override
    protected void onLayout() {
        final View<?> content = this.content();
        if (content == null) {
            this.maxScroll = 0;
            return;
        }
        this.maxScroll = Math.max(0, content.measuredHeight - this.innerHeight());
        this.scrollOffset = Math.max(0, Math.min(this.scrollOffset, this.maxScroll));
        content.layout(this.innerLeft(), this.innerTop() - this.scrollOffset);
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        // CLIP TO THE VIEWPORT SO SCROLLED-OUT CONTENT IS NOT PAINTED, THEN DRAW THE (OFFSET) CONTENT
        canvas.pushClip(this.left, this.top, this.measuredWidth, this.measuredHeight);
        super.onDraw(canvas);
        canvas.popClip();

        if (this.maxScroll > 0) {
            final int trackH = this.measuredHeight;
            final int thumbH = Math.max(24, (int) ((long) trackH * trackH / (trackH + this.maxScroll)));
            final int thumbY = this.top + (int) ((long) (trackH - thumbH) * this.scrollOffset / this.maxScroll);
            final int barX = this.left + this.measuredWidth - this.scrollbarWidth - 2;
            canvas.fill(barX, thumbY, this.scrollbarWidth, thumbH, AppTheme.alpha(AppTheme.NEON, 130));
        }
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

    @Override
    public boolean dispatchClick(final double mx, final double my) {
        // ONLY ROUTE CLICKS THAT LAND INSIDE THE VIEWPORT — SCROLLED-OUT CHILDREN MUST NOT BE CLICKABLE
        if (!this.contains(mx, my)) return false;
        return super.dispatchClick(mx, my);
    }

    @Override
    public boolean dispatchHover(final double mx, final double my) {
        if (!this.contains(mx, my)) {
            for (final View<?> child: this.children) child.dispatchHover(-1, -1);
            this.hovered = false;
            return false;
        }
        return super.dispatchHover(mx, my);
    }

    public ScrollView scrollTo(final int offset) {
        this.scrollOffset = Math.max(0, offset);
        return this;
    }

    public int scrollOffset() {
        return this.scrollOffset;
    }
}
