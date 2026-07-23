package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.ui.AppTheme;

/**
 * Clips its content to its own bounds and scrolls it vertically, like Android's {@code ParentScroll}. It
 * wraps a single content view (typically a {@link Parent} column) measured at its natural height;
 * the wheel adjusts the offset with momentum (consecutive notches accumulate and the motion decays
 * smoothly after the last one) and a thin scrollbar shows the position when the content overflows.
 * Programmatic scrolls ({@link #scrollTo}, {@link #ensureVisible}) are instant.
 */
public final class ParentScroll extends Group<ParentScroll> {

    private int scrollbarWidth = 6;
    private final ScrollState scroll = new ScrollState();

    public ParentScroll scrollbarWidth(final int width) {
        this.scrollbarWidth = Math.max(2, width);
        return this;
    }

    private Element<?> content() {
        for (final Element<?> child: this.children) {
            if (child.visible) return child;
        }
        return null;
    }

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        final Element<?> content = this.content();
        if (content == null) {
            this.contentWidth = 0;
            this.contentHeight = 0;
            return;
        }
        content.measure(innerAvailWidth, ScrollState.UNBOUNDED);
        this.contentWidth = content.measuredWidth;
        // THE VIEWPORT HEIGHT COMES FROM THIS VIEW'S OWN SIZE PARAM, NOT THE CONTENT — REPORT THE OFFERED
        // HEIGHT SO A WRAP_CONTENT PARENTSCROLL STILL BOUNDS ITSELF RATHER THAN GROWING TO THE FULL CONTENT
        this.contentHeight = Math.min(content.measuredHeight, innerAvailHeight);
    }

    @Override
    protected void onUpdate() {
        if (this.scroll.animate()) this.invalidate();
    }

    @Override
    protected void onLayout() {
        final Element<?> content = this.content();
        if (content == null) {
            this.scroll.clamp(0);
            return;
        }
        // CLAMP THE OFFSET + MOMENTUM STATE INTO THE NEW RANGE SO A RESIZE/REBUILD NEVER LEAVES A STALE TARGET
        this.scroll.clamp(content.measuredHeight - this.innerHeight());
        content.layout(this.innerLeft(), this.innerTop() - this.scroll.offset);
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        // CLIP TO THE VIEWPORT SO SCROLLED-OUT CONTENT IS NOT PAINTED, THEN DRAW THE (OFFSET) CONTENT
        canvas.pushClip(this.left, this.top, this.measuredWidth, this.measuredHeight);
        super.onDraw(canvas);
        canvas.popClip();

        if (this.scroll.max > 0) {
            final int trackH = this.measuredHeight;
            final int thumbH = this.scroll.thumbHeight(trackH);
            final int thumbY = this.scroll.thumbY(this.top, trackH, thumbH);
            final int barX = this.left + this.measuredWidth - this.scrollbarWidth - 2;
            canvas.fill(barX, thumbY, this.scrollbarWidth, thumbH, AppTheme.alpha(AppTheme.NEON, 130));
        }
    }

    @Override
    public boolean dispatchScroll(final double mx, final double my, final double amount) {
        if (!this.contains(mx, my) || this.scroll.max <= 0) return false;
        // MOMENTUM WHEEL: EACH NOTCH SHIFTS THE TARGET AND onUpdate EASES THE OFFSET TOWARD IT
        if (this.scroll.scroll(amount)) this.invalidate();
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
            for (final Element<?> child: this.children) child.clearHover();
            this.hovered = false;
            return false;
        }
        return super.dispatchHover(mx, my);
    }

    public ParentScroll scrollTo(final int offset) {
        // PROGRAMMATIC JUMPS ARE INSTANT AND KILL ANY WHEEL MOMENTUM — CALLERS EXPECT EXACT GEOMETRY
        this.scroll.jump(offset);
        return this;
    }

    /**
     * Scrolls the minimum amount needed to bring {@code child} (a direct or deep descendant of the
     * content) fully into the viewport. Uses the post-layout geometry, so it must be called after the
     * tree has been laid out; a child taller than the viewport aligns to the top.
     */
    public ParentScroll ensureVisible(final Element<?> child) {
        if (child == null) return this;
        // CHILD TOP IN CONTENT SPACE — layout PLACED IT AT ABSOLUTE COORDS SHIFTED BY THE CURRENT OFFSET
        final int rel = child.top - this.innerTop() + this.scroll.offset;
        final int viewport = this.innerHeight();
        int next = this.scroll.offset;
        if (rel + child.measuredHeight > next + viewport) next = rel + child.measuredHeight - viewport;
        if (rel < next) next = rel;
        next = Math.max(0, Math.min(next, this.scroll.max));
        final boolean moved = next != this.scroll.offset;
        // INSTANT (NO ANIMATION) AND KILLS ANY WHEEL MOMENTUM — KEYBOARD NAV AND CALLERS RELY ON THE
        // CHILD BEING IN VIEW RIGHT AFTER THE CALL, WHICH A COASTING OFFSET WOULD DRIFT BACK OUT
        this.scroll.jump(next);
        if (moved) this.invalidate();
        return this;
    }

    public int scrollOffset() {
        return this.scroll.offset;
    }
}
