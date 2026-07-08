package org.watermedia.bootstrap.app.screen;

import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.ui.AppChrome;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.bootstrap.app.view.Root;
import org.watermedia.bootstrap.app.view.View;

/**
 * Bridges the imperative {@link Screen}/{@code ScreenManager} contract to a retained {@link View} tree.
 * A subclass builds its content tree once in {@link #build()} and (optionally) paints the window chrome
 * in {@link #renderChrome}; this class runs the tree's measure → layout → draw inside the content rect
 * and routes every input event (press/drag/click/hover/scroll/key/char) into the tree, so screens stop
 * hand-rolling hit-testing and draw calls.
 *
 * <p>Pointer handling reconciles the app's press/move/release/click model with the view pointer-capture:
 * a press may capture a draggable view, moves feed it drag, release ends the capture, and a click is
 * suppressed when it was really the end of a drag.
 */
public abstract class ViewScreen extends Screen {

    protected final Root root;
    private boolean built;
    private boolean wasDragging;

    public ViewScreen(final TextRenderer text, final AppContext ctx) {
        super(text, ctx);
        this.root = new Root(ctx, text);
    }

    /** Builds this screen's content view tree. Called once, lazily, then rebuilt via {@link #rebuild}. */
    protected abstract View<?> build();

    /** Paints the window frame (background, titlebar, header) around the content. Default: nothing. */
    protected void renderChrome(final int windowW, final int windowH) {
    }

    // CONTENT RECT — DEFAULT: FULL WIDTH BETWEEN THE CHROME HEADER AND THE FOOTER
    protected int contentX(final int windowW, final int windowH) {
        return 0;
    }

    protected int contentY(final int windowW, final int windowH) {
        return AppChrome.contentTop();
    }

    protected int contentW(final int windowW, final int windowH) {
        return windowW;
    }

    protected int contentH(final int windowW, final int windowH) {
        return Math.max(0, AppChrome.contentBottom(windowH) - AppChrome.contentTop());
    }

    protected final void rebuild() {
        this.root.content(this.build());
        this.built = true;
    }

    protected final void ensureBuilt() {
        if (!this.built) this.rebuild();
    }

    @Override
    public void onEnter() {
        this.ensureBuilt();
    }

    @Override
    public void render(final int windowW, final int windowH) {
        this.ensureBuilt();
        this.renderChrome(windowW, windowH);
        this.root.render(this.contentX(windowW, windowH), this.contentY(windowW, windowH),
                this.contentW(windowW, windowH), this.contentH(windowW, windowH), windowW, windowH);
    }

    @Override
    public void handleMousePress(final double mx, final double my) {
        this.root.press(mx, my);
    }

    @Override
    public void handleMouseMove(final double mx, final double my) {
        this.root.hover(mx, my);
        this.root.drag(mx, my);
    }

    @Override
    public void handleMouseRelease(final double mx, final double my) {
        this.wasDragging = this.root.dragging();
        this.root.release(mx, my);
    }

    @Override
    public void handleMouseClick(final double mx, final double my) {
        if (!this.wasDragging) this.root.click(mx, my);
        this.wasDragging = false;
    }

    @Override
    public void handleScroll(final double yOffset) {
        this.root.scroll(this.ctx.mouseX, this.ctx.mouseY, yOffset);
    }

    @Override
    public void handleChar(final int codepoint) {
        this.root.character(codepoint);
    }

    @Override
    public void handleKey(final int key, final int action) {
        // LET THE TREE (E.G. A FOCUSED TextField) CONSUME THE KEY FIRST; OTHERWISE FALL BACK TO SCREEN NAV
        if (!this.root.key(key, action)) super.handleKey(key, action);
    }

    @Override
    public boolean textInputFocused() {
        return this.root.textInputFocused();
    }

    @Override
    public boolean wantsContinuousRender() {
        return this.textInputFocused();
    }
}
