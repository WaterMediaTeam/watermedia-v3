package org.watermedia.bootstrap.app;

/** How one main-loop iteration paints the window. */
enum FramePlan {
    IDLE, // NOTHING CHANGED AND NOTHING MOVES: NO FRAME
    DIRECT, // THE TREE DRAWS STRAIGHT INTO THE WINDOW, LIVE PAINTERS INLINE
    CACHE, // THE TREE DRAWS INTO THE CACHE LAYERS, WHICH THEN COMPOSITE WITH THE LIVE PAINTERS
    COMPOSITE; // ONLY THE CACHE LAYERS AND THE LIVE PAINTERS DRAW; THE TREE IS NOT TOUCHED

    // AN ANIMATING SCREEN REPAINTS WHOLE EVERY FRAME, A CHANGE REPAINTS THE TREE (INTO THE CACHE WHILE A CRT BAND MOVES),
    // AND A STILL TREE UNDER A MOVING BAND COMPOSITES ONCE PER BAND STEP, REBUILDING THE CACHE WHEN THE BACKEND DROPPED IT
    static FramePlan of(final boolean continuous, final boolean requested, final boolean crt, final boolean live,
                        final boolean visible, final boolean cacheValid, final boolean stepped) {
        if (continuous) return DIRECT;
        final boolean moving = crt && live;
        if (requested) return moving ? CACHE : DIRECT;
        if (!moving || !visible) return IDLE;
        if (!cacheValid) return CACHE;
        return stepped ? COMPOSITE : IDLE;
    }

    // RE-CHECKED AFTER beginFrame(): A SWAPCHAIN REBUILD THERE TAKES THE LAYERS, SO THAT COMPOSITE REPAINTS THE TREE
    FramePlan settle(final boolean cacheValid) {
        return this == COMPOSITE && !cacheValid ? CACHE : this;
    }
}
