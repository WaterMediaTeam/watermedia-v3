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

    private static final int UNBOUNDED = 1 << 20; // MEASURE CONTENT AT ITS NATURAL HEIGHT, NOT THE VIEWPORT'S
    private static final int SCROLL_STEP = 48;
    private static final float TAU = 70f; // MS — MOMENTUM DECAY TIME CONSTANT (~250MS SETTLE AFTER THE LAST NOTCH)

    private int scrollbarWidth = 6;
    private int scrollOffset;
    private int maxScroll;
    // WHEEL MOMENTUM — THE OFFSET EASES TOWARD target; IDLE INVARIANT: animPos == target == scrollOffset
    private float animPos;
    private int target;
    private long tick;

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
        content.measure(innerAvailWidth, UNBOUNDED);
        this.contentWidth = content.measuredWidth;
        // THE VIEWPORT HEIGHT COMES FROM THIS VIEW'S OWN SIZE PARAM, NOT THE CONTENT — REPORT THE OFFERED
        // HEIGHT SO A WRAP_CONTENT PARENTSCROLL STILL BOUNDS ITSELF RATHER THAN GROWING TO THE FULL CONTENT
        this.contentHeight = Math.min(content.measuredHeight, innerAvailHeight);
    }

    @Override
    protected void onUpdate() {
        // MOMENTUM: EXPONENTIAL APPROACH TOWARD THE ACCUMULATED WHEEL TARGET — SELF-SUSTAINED FRAMES;
        // THE APPROACH NEVER OVERSHOOTS, SO AT A CLAMPED TARGET THE MOTION DIES WITHOUT ANY BOUNCE
        if (this.animPos == this.target) return;
        final long now = System.currentTimeMillis();
        // dt CLAMPED TO [0,64]MS — A STALLED FRAME NEVER TELEPORTS AND A CLOCK JUMP NEVER DIVERGES
        final float dt = Math.max(0L, Math.min(64L, now - this.tick));
        this.tick = now;
        this.animPos += (this.target - this.animPos) * (1f - (float) Math.exp(-dt / TAU));
        if (Math.abs(this.target - this.animPos) < 0.5f) this.animPos = this.target;
        this.scrollOffset = Math.round(this.animPos);
        this.invalidate();
    }

    @Override
    protected void onLayout() {
        final Element<?> content = this.content();
        if (content == null) {
            this.maxScroll = 0;
            return;
        }
        this.maxScroll = Math.max(0, content.measuredHeight - this.innerHeight());
        this.scrollOffset = Math.max(0, Math.min(this.scrollOffset, this.maxScroll));
        // KEEP THE MOMENTUM STATE INSIDE THE NEW RANGE SO A RESIZE/REBUILD NEVER LEAVES A STALE TARGET
        this.target = Math.max(0, Math.min(this.target, this.maxScroll));
        this.animPos = Math.max(0f, Math.min(this.animPos, this.maxScroll));
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
        // MOMENTUM WHEEL: EACH NOTCH SHIFTS THE TARGET AND onUpdate EASES THE OFFSET TOWARD IT
        if (this.animPos == this.target) {
            // COAST START — RESYNC THE FLOAT MIRROR AND THE CLOCK FROM THE IDLE OFFSET
            this.animPos = this.scrollOffset;
            this.target = this.scrollOffset;
            this.tick = System.currentTimeMillis();
        }
        final int next = Math.max(0, Math.min(this.maxScroll, this.target - (int) (amount * SCROLL_STEP)));
        if (next != this.target) {
            this.target = next;
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
            for (final Element<?> child: this.children) child.clearHover();
            this.hovered = false;
            return false;
        }
        return super.dispatchHover(mx, my);
    }

    public ParentScroll scrollTo(final int offset) {
        // PROGRAMMATIC JUMPS ARE INSTANT AND KILL ANY WHEEL MOMENTUM — CALLERS EXPECT EXACT GEOMETRY
        this.scrollOffset = Math.max(0, offset);
        this.target = this.scrollOffset;
        this.animPos = this.scrollOffset;
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
        final int rel = child.top - this.innerTop() + this.scrollOffset;
        final int viewport = this.innerHeight();
        int next = this.scrollOffset;
        if (rel + child.measuredHeight > next + viewport) next = rel + child.measuredHeight - viewport;
        if (rel < next) next = rel;
        next = Math.max(0, Math.min(next, this.maxScroll));
        // INSTANT (NO ANIMATION) AND KILLS ANY WHEEL MOMENTUM — KEYBOARD NAV AND CALLERS RELY ON THE
        // CHILD BEING IN VIEW RIGHT AFTER THE CALL, WHICH A COASTING OFFSET WOULD DRIFT BACK OUT
        this.target = next;
        this.animPos = next;
        if (next != this.scrollOffset) {
            this.scrollOffset = next;
            this.invalidate();
        }
        return this;
    }

    public int scrollOffset() {
        return this.scrollOffset;
    }
}
