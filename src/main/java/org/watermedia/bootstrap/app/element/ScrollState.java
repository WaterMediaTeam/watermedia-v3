package org.watermedia.bootstrap.app.element;

// SHARED WHEEL-MOMENTUM SCROLL STATE FOR THE VERTICAL SCROLLERS (ListView, ParentScroll): THE OFFSET
// EASES TOWARD AN ACCUMULATED WHEEL target WITH EXPONENTIAL DECAY. IDLE INVARIANT: animPos == target == offset.
final class ScrollState {

    static final int UNBOUNDED = 1 << 20; // MEASURE CONTENT AT ITS NATURAL HEIGHT, NOT THE VIEWPORT'S
    static final int SCROLL_STEP = 48;
    private static final float TAU = 70f; // MS — MOMENTUM DECAY TIME CONSTANT (~250MS SETTLE AFTER THE LAST NOTCH)

    int offset;
    int max;
    private float animPos;
    private int target;
    private long tick;

    // EXPONENTIAL APPROACH TOWARD THE WHEEL TARGET — NEVER OVERSHOOTS, SO AT A CLAMPED TARGET THE MOTION
    // DIES WITHOUT BOUNCE. RETURNS true WHILE STILL MOVING SO THE CALLER KEEPS REQUESTING FRAMES.
    boolean animate() {
        if (this.animPos == this.target) return false;
        final long now = System.currentTimeMillis();
        // dt CLAMPED TO [0,64]MS — A STALLED FRAME NEVER TELEPORTS AND A CLOCK JUMP NEVER DIVERGES
        final float dt = Math.max(0L, Math.min(64L, now - this.tick));
        this.tick = now;
        this.animPos += (this.target - this.animPos) * (1f - (float) Math.exp(-dt / TAU));
        if (Math.abs(this.target - this.animPos) < 0.5f) this.animPos = this.target;
        this.offset = Math.round(this.animPos);
        return true;
    }

    // CLAMP THE OFFSET AND MOMENTUM STATE INTO [0,max] AFTER A RESIZE/REBUILD SO NO STALE TARGET SURVIVES
    void clamp(final int maxScroll) {
        this.max = Math.max(0, maxScroll);
        this.offset = Math.max(0, Math.min(this.offset, this.max));
        this.target = Math.max(0, Math.min(this.target, this.max));
        this.animPos = Math.max(0f, Math.min(this.animPos, this.max));
    }

    // WHEEL NOTCH: START COASTING FROM THE IDLE OFFSET IF NEEDED, THEN SHIFT THE TARGET. RETURNS true IF MOVED.
    boolean scroll(final double amount) {
        if (this.animPos == this.target) {
            this.animPos = this.offset;
            this.target = this.offset;
            this.tick = System.currentTimeMillis();
        }
        final int next = Math.max(0, Math.min(this.max, this.target - (int) (amount * SCROLL_STEP)));
        if (next == this.target) return false;
        this.target = next;
        return true;
    }

    // INSTANT JUMP THAT KILLS ANY MOMENTUM — KEYBOARD NAV AND PROGRAMMATIC SCROLLS EXPECT EXACT GEOMETRY
    void jump(final int newOffset) {
        this.offset = Math.max(0, newOffset);
        this.target = this.offset;
        this.animPos = this.offset;
    }

    // SCROLLBAR THUMB HEIGHT FOR A TRACK OF trackH PX (CALLERS ONLY DRAW WHEN max > 0)
    int thumbHeight(final int trackH) {
        return Math.max(24, (int) ((long) trackH * trackH / (trackH + this.max)));
    }

    // TOP Y OF THE SCROLLBAR THUMB WITHIN A TRACK STARTING AT trackTop
    int thumbY(final int trackTop, final int trackH, final int thumbH) {
        return trackTop + (int) ((long) (trackH - thumbH) * this.offset / this.max);
    }
}
