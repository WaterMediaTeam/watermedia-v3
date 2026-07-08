package org.watermedia.bootstrap.app.view;

import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.bootstrap.app.ui.TextRenderer;

/**
 * Hosts one screen's view tree: it establishes the window ortho, runs the measure → layout → draw
 * pipeline and routes input into the tree. A screen builds its {@link View} tree once and hands the root
 * here; the framework does the rest, so screens never issue raw draw calls or hand-roll hit-testing.
 *
 * <p>Layout runs every render — which only happens on demand (the loop repaints when something
 * invalidates), so this recomputes exactly when the tree may have changed, and geometry is otherwise
 * reused across the frame.
 */
public final class Root {

    private final AppContext ctx;
    private final Canvas canvas;
    private View<?> content;
    private View<?> captured; // VIEW THAT CLAIMED THE CURRENT PRESS AND RECEIVES DRAG/RELEASE

    public Root(final AppContext ctx, final TextRenderer text) {
        this.ctx = ctx;
        this.canvas = new Canvas(text);
    }

    public Root content(final View<?> root) {
        this.content = root;
        if (root != null) root.attach(this.ctx);
        return this;
    }

    public View<?> content() {
        return this.content;
    }

    public void render(final int windowWidth, final int windowHeight) {
        this.render(0, 0, windowWidth, windowHeight, windowWidth, windowHeight);
    }

    /**
     * Lays out and paints the tree inside the content rect ({@code x,y,w,h}) while the ortho projection
     * and clip use the full window ({@code windowWidth,windowHeight}). Screens use this to place their
     * view content below the window chrome (titlebar/header) and above the footer.
     */
    public void render(final int x, final int y, final int w, final int h,
                       final int windowWidth, final int windowHeight) {
        if (this.content == null) return;
        RenderSystem.setupOrtho(windowWidth, windowHeight);
        this.content.measure(w, h);
        this.content.layout(x, y);
        this.canvas.viewport(windowWidth, windowHeight);
        this.content.draw(this.canvas);
    }

    public boolean textInputFocused() {
        return this.content != null && this.content.textInputActive();
    }

    public boolean press(final double mx, final double my) {
        this.captured = this.content != null ? this.content.dispatchPress(mx, my) : null;
        return this.captured != null;
    }

    public void drag(final double mx, final double my) {
        if (this.captured != null) this.captured.onDrag(mx, my);
    }

    public void release(final double mx, final double my) {
        if (this.captured != null) {
            this.captured.onRelease(mx, my);
            this.captured = null;
        }
    }

    public boolean dragging() {
        return this.captured != null;
    }

    public boolean click(final double mx, final double my) {
        return this.content != null && this.content.dispatchClick(mx, my);
    }

    public void hover(final double mx, final double my) {
        if (this.content != null) this.content.dispatchHover(mx, my);
    }

    public boolean scroll(final double mx, final double my, final double amount) {
        return this.content != null && this.content.dispatchScroll(mx, my, amount);
    }

    public boolean key(final int key, final int action) {
        return this.content != null && this.content.dispatchKey(key, action);
    }

    public boolean character(final int codepoint) {
        return this.content != null && this.content.dispatchChar(codepoint);
    }
}
