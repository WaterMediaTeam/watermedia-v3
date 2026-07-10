package org.watermedia.bootstrap.app.screen;

import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.element.Canvas;
import org.watermedia.bootstrap.app.element.Element;
import org.watermedia.bootstrap.app.element.Parent;
import org.watermedia.bootstrap.app.element.ParentFrame;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.bootstrap.app.ui.TextRenderer;

import java.util.List;
import java.util.function.Supplier;

/**
 * Hosts the whole window as one self-contained retained tree: a frame stacking the {@link Background},
 * a column of {@link TitleBar} / center slot / {@link KeybindsBar}, and an overlay frame for global
 * dialogs. Screens are mounted into the center slot (optionally stacked over an underlay); every frame
 * runs {@code update → measure → layout → draw} under the logical-pixel ortho, and all input enters
 * through the entry points below with the same pointer-capture and click-after-drag reconciliation the
 * framework needs. The CRT scanline effect is NOT global here — it is scoped to media previews and the
 * player's video area, drawn by their own local {@link CrtOverlay}s (still gated by {@code ctx.crt}).
 */
public final class RootScreen {

    private final AppContext ctx;
    private final Canvas canvas;
    private final ParentFrame tree;
    private final TitleBar titleBar;
    private final ParentFrame centerSlot;
    private final KeybindsBar keybindsBar;
    private final ParentFrame dialogs;
    private Element<?> captured; // VIEW THAT CLAIMED THE CURRENT PRESS AND RECEIVES DRAG/RELEASE
    private boolean wasDragging;
    private boolean dragMoved;
    private double pressX;
    private double pressY;

    public RootScreen(final AppContext ctx, final TextRenderer text, final Supplier<List<Keybind>> keybinds) {
        this.ctx = ctx;
        this.canvas = new Canvas(text);
        this.titleBar = new TitleBar();
        this.centerSlot = new ParentFrame().width(Element.MAX_PARENT).weight(1f);
        this.keybindsBar = new KeybindsBar(keybinds);
        final Parent column = Parent.column()
                .size(Element.MAX_PARENT, Element.MAX_PARENT)
                .add(this.titleBar)
                .add(this.centerSlot)
                .add(this.keybindsBar);
        // GLOBAL DIALOGS LIVE IN THEIR OWN TOP LAYER, ABOVE THE SCREEN COLUMN
        this.dialogs = new ParentFrame().size(Element.MAX_PARENT, Element.MAX_PARENT);
        this.tree = new ParentFrame()
                .size(Element.MAX_PARENT, Element.MAX_PARENT)
                .add(new Background())
                .add(column)
                .add(this.dialogs);
        this.tree.attach(ctx);
    }

    /**
     * Runs the frame pipeline (update, measure, layout, draw) under the logical-pixel ortho. The caller
     * passes LOGICAL dimensions ({@code physical / uiScale}); geometry scales to physical through the
     * ortho projection while the viewport stays physical, so the whole tree lives in logical pixels.
     */
    public void render(final int logicalW, final int logicalH) {
        // BINDINGS FIRST — onUpdate HOOKS PULL LIVE STATE INTO THE TREE BEFORE IT IS MEASURED
        this.tree.update();
        RenderSystem.setupOrtho(logicalW, logicalH);
        this.tree.measure(logicalW, logicalH);
        this.tree.layout(0, 0);
        this.canvas.viewport(logicalW, logicalH);
        this.tree.draw(this.canvas);
    }

    /** Mounts a screen element into the center slot, replacing whatever was there. */
    public void mount(final Element<?> screenContent) {
        this.releaseCapture();
        this.centerSlot.clear();
        if (screenContent != null) this.centerSlot.add(screenContent);
        this.ctx.requestRender();
    }

    /** Mounts a stacked pair into the center slot: {@code under} below, {@code top} above. */
    public void mountUnder(final Element<?> under, final Element<?> top) {
        this.releaseCapture();
        this.centerSlot.clear();
        if (under != null) this.centerSlot.add(under);
        if (top != null) this.centerSlot.add(top);
        this.ctx.requestRender();
    }

    /** The layer hosting global dialogs (below the CRT overlay). */
    public ParentFrame overlay() {
        return this.dialogs;
    }

    /** The window titlebar. */
    public TitleBar titleBar() {
        return this.titleBar;
    }

    /** The footer keybinds/status bar. */
    public KeybindsBar keybindsBar() {
        return this.keybindsBar;
    }

    /** Whether a text input anywhere in the tree has keyboard focus. */
    public boolean textInputFocused() {
        return this.tree.textInputActive();
    }

    /**
     * Whether the window needs continuous repaints: an active screen that animates every frame (video,
     * a running local CRT preview, thumbnail loads).
     */
    public boolean continuous() {
        for (final Element<?> child: this.centerSlot.children()) {
            if (!child.visible()) continue;
            if (child instanceof Screen screen && screen.continuous()) return true;
        }
        return false;
    }

    /**
     * Whether a pointer capture (a slider drag, the titlebar window move, a modal hold) is in progress.
     * The main loop paces such a frame like a continuous screen so the pointer is sampled right before
     * the frame it drives, keeping drags tight instead of trailing the cursor by a whole frame.
     */
    public boolean dragging() {
        return this.captured != null;
    }

    // ==========================================================================
    // INPUT ENTRY POINTS — SAME CAPTURE/CLICK-AFTER-DRAG RECONCILIATION THE TREE NEEDS
    // ==========================================================================

    public boolean press(final double mx, final double my) {
        this.captured = this.tree.dispatchPress(mx, my);
        this.dragMoved = false;
        this.pressX = mx;
        this.pressY = my;
        return this.captured != null;
    }

    public void drag(final double mx, final double my) {
        if (this.captured != null) {
            // A CAPTURE ONLY COUNTS AS A DRAG ONCE THE POINTER TRAVELS A FEW PIXELS — SUB-PIXEL JITTER
            // BETWEEN PRESS AND RELEASE MUST NOT SUPPRESS THE CLICK (MODAL SCRIMS CAPTURE EVERY PRESS)
            if (Math.abs(mx - this.pressX) + Math.abs(my - this.pressY) > 3) this.dragMoved = true;
            this.captured.onDrag(mx, my);
        }
    }

    public void release(final double mx, final double my) {
        this.wasDragging = this.captured != null && this.dragMoved;
        if (this.captured != null) {
            this.captured.onRelease(mx, my);
            this.captured = null;
        }
        this.dragMoved = false;
    }

    public boolean click(final double mx, final double my) {
        // THE PRESS-CAPTURE SUPPRESSES THE CLICK WHEN IT ENDED A REAL DRAG (TITLEBAR MOVE, SLIDER, ...)
        final boolean handled = !this.wasDragging && this.tree.dispatchClick(mx, my);
        this.wasDragging = false;
        return handled;
    }

    public void hover(final double mx, final double my) {
        this.tree.dispatchHover(mx, my);
    }

    public boolean scroll(final double mx, final double my, final double amount) {
        return this.tree.dispatchScroll(mx, my, amount);
    }

    public boolean key(final int key, final int action) {
        return this.tree.dispatchKey(key, action);
    }

    public boolean character(final int codepoint) {
        return this.tree.dispatchChar(codepoint);
    }

    private void releaseCapture() {
        // A SLOT SWAP MUST NOT LEAVE A DEAD ELEMENT HOLDING THE POINTER — END THE CAPTURE IN PLACE
        if (this.captured != null) {
            this.captured.onRelease(this.ctx.mouseX, this.ctx.mouseY);
            this.captured = null;
        }
        this.wasDragging = false;
    }
}
