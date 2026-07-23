package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.ui.Gravity;
import org.watermedia.bootstrap.app.ui.Spacing;

import java.awt.Color;
import java.util.function.Consumer;

/**
 * Base of the retained view tree. A view carries its own layout parameters (size, margin, padding,
 * gravity, weight), decorator style (background, border, glow, shadow, corner radius) and interaction
 * state, and moves through a three-phase pipeline each time the tree is dirty:
 * {@link #measure} resolves the view's size from its params and content, {@link #layout} places it, and
 * {@link #draw} paints it. Screens compose trees instead of issuing raw draw calls, so hit-testing,
 * focus, scrolling and batching are handled once here rather than re-implemented per screen.
 *
 * <p>The class is self-typed ({@code T}) so the fluent setters return the concrete view type and trees
 * can be built without casts. Sizes use {@link #MAX_PARENT} / {@link #WRAP_CONTENT} or an exact pixel
 * value, mirroring the Android layout model.
 *
 * @param <T> the concrete view type, for fluent chaining
 */
public abstract class Element<T extends Element<T>> {

    /** Size param: fill the space the parent offers. */
    public static final int MAX_PARENT = -1;
    /** Size param: shrink to the content's intrinsic size. */
    public static final int WRAP_CONTENT = -2;

    // LAYOUT PARAMS
    protected int width = WRAP_CONTENT;
    protected int height = WRAP_CONTENT;
    protected Spacing margin = Spacing.ZERO;
    protected Spacing padding = Spacing.ZERO;
    protected Gravity gravity = Gravity.LEFT;
    protected float weight;

    // DECOR
    protected Color background;
    protected Color borderColor;
    protected float borderWidth;
    protected float radius;
    protected Color glowColor;
    protected float glowAlpha;
    protected float shadowAlpha;

    // STATE
    protected boolean visible = true;
    protected boolean enabled = true;
    protected boolean hovered;
    protected boolean focused;
    protected boolean pressed;
    protected boolean selected;
    protected boolean consumeTouch; // WHEN TRUE, A HIT IS SWALLOWED EVEN WITHOUT AN onClick (SCRIMS/MODALS)

    // COMPUTED GEOMETRY (VALID AFTER measure + layout)
    protected int left;
    protected int top;
    protected int measuredWidth;
    protected int measuredHeight;
    protected int contentWidth;
    protected int contentHeight;

    // WIRING
    protected Group<?> parent;
    protected AppContext ctx;
    protected Object tag;

    protected Consumer<T> onClick;
    protected Consumer<T> onHover;

    @SuppressWarnings("unchecked")
    protected final T self() {
        return (T) this;
    }

    // ==========================================================================
    // MEASURE / LAYOUT / DRAW PIPELINE
    // ==========================================================================

    /**
     * Pre-measure binding pass: refreshes this view from live state before the tree measures and draws.
     * Containers propagate it to every child (visible or not); leaves get their {@link #onUpdate} hook.
     */
    public void update() {
        this.onUpdate();
    }

    /**
     * Binding hook called by {@link #update} once per frame before measure. Subclasses read live state
     * (labels, progress, visibility) from their context here. Default: nothing.
     */
    protected void onUpdate() {
    }

    /** Resolves {@link #measuredWidth}/{@link #measuredHeight} from the size params, padding and content. */
    public final void measure(final int availWidth, final int availHeight) {
        final int boxW = this.width == MAX_PARENT || this.width == WRAP_CONTENT ? availWidth : this.width;
        final int boxH = this.height == MAX_PARENT || this.height == WRAP_CONTENT ? availHeight : this.height;
        this.onMeasure(Math.max(0, boxW - this.padding.horizontal()), Math.max(0, boxH - this.padding.vertical()));
        this.measuredWidth = switch (this.width) {
            case MAX_PARENT -> availWidth;
            case WRAP_CONTENT -> this.contentWidth + this.padding.horizontal();
            default -> this.width;
        };
        this.measuredHeight = switch (this.height) {
            case MAX_PARENT -> availHeight;
            case WRAP_CONTENT -> this.contentHeight + this.padding.vertical();
            default -> this.height;
        };
    }

    /**
     * Computes the intrinsic content size into {@link #contentWidth}/{@link #contentHeight} given the
     * space available inside this view's padding. Leaves report their content; containers measure their
     * children. The default reports nothing (an empty leaf).
     */
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        this.contentWidth = 0;
        this.contentHeight = 0;
    }

    /** Places this view's border box top-left at ({@code x},{@code y}) and lays out any children. */
    public final void layout(final int x, final int y) {
        this.left = x;
        this.top = y;
        this.onLayout();
    }

    /** Positions children within the content box. No-op for leaves. */
    protected void onLayout() {
    }

    /** Paints decor, then content, then the border on top. Skipped when the view is hidden. */
    public final void draw(final Canvas canvas) {
        if (!this.visible) return;
        if (this.shadowAlpha > 0f) {
            canvas.shadow(this.left, this.top, this.measuredWidth, this.measuredHeight, this.radius, this.shadowAlpha);
        }
        if (this.glowColor != null && this.glowAlpha > 0f) {
            canvas.glow(this.left, this.top, this.measuredWidth, this.measuredHeight, this.radius, this.glowColor, this.glowAlpha);
        }
        if (this.background != null) {
            canvas.fillRound(this.left, this.top, this.measuredWidth, this.measuredHeight, this.radius, this.background);
        }
        this.onDraw(canvas);
        if (this.borderColor != null && this.borderWidth > 0f) {
            canvas.strokeRound(this.left, this.top, this.measuredWidth, this.measuredHeight, this.radius, this.borderColor, this.borderWidth);
        }
    }

    /** Paints the view's own content (text, image, children). No-op for a bare view. */
    protected void onDraw(final Canvas canvas) {
    }

    // ==========================================================================
    // EVENT DISPATCH — RETURN true WHEN THE EVENT IS CONSUMED
    // ==========================================================================

    public boolean dispatchClick(final double mx, final double my) {
        if (!this.visible || !this.enabled || !this.contains(mx, my)) return false;
        if (this.onClick != null) {
            this.onClick.accept(this.self());
            return true;
        }
        return this.consumeTouch;
    }

    public boolean dispatchHover(final double mx, final double my) {
        if (!this.visible) return false;
        final boolean inside = this.enabled && this.contains(mx, my);
        this.hovered = inside;
        if (inside && this.onHover != null) this.onHover.accept(this.self());
        return inside;
    }

    /**
     * Force-clears the hover state without a pointer position — used when a topmost sibling consumed
     * the hover, so layers underneath stop reacting. Invalidates only when the state actually changed.
     */
    public void clearHover() {
        if (this.hovered) {
            this.hovered = false;
            this.invalidate();
        }
    }

    /**
     * Pointer-press hit test for drag interactions. A view that returns itself captures the pointer
     * until release and then receives {@link #onDrag}/{@link #onRelease}; returning {@code null} lets
     * the press fall through. Most views only need clicks (release); override {@link #onPress} to drag.
     */
    public Element<?> dispatchPress(final double mx, final double my) {
        return this.visible && this.enabled && this.contains(mx, my) && this.onPress(mx, my) ? this : null;
    }

    protected boolean onPress(final double mx, final double my) {
        return false;
    }

    public void onDrag(final double mx, final double my) {
    }

    public void onRelease(final double mx, final double my) {
    }

    public boolean dispatchScroll(final double mx, final double my, final double amount) {
        return false;
    }

    public boolean dispatchKey(final int key, final int action) {
        return false;
    }

    public boolean dispatchChar(final int codepoint) {
        return false;
    }

    /**
     * Whether this view or a descendant is a focused text input — drives caret blink and global-shortcut
     * suppression. {@link TextField} overrides it; {@link Group} aggregates over its children.
     */
    public boolean textInputActive() {
        return false;
    }

    /**
     * Force-clears any text-input focus/edit in this subtree — called on the layers a consumed click did
     * not land in, so a focused field blurs even when a sibling swallowed the click. Text editors override.
     */
    public void clearFocus() {
    }

    /** Half-open hit test — shared borders belong to exactly one view and a zero-size view never matches. */
    public boolean contains(final double mx, final double my) {
        return mx >= this.left && mx < this.left + this.measuredWidth
                && my >= this.top && my < this.top + this.measuredHeight;
    }

    // ==========================================================================
    // WIRING
    // ==========================================================================

    /** Requests a repaint of the window this view belongs to. */
    public T invalidate() {
        if (this.ctx != null) this.ctx.requestRender();
        return this.self();
    }

    /**
     * Attaches the shared context to this view (and, in {@link Group}, its subtree). Public so the window
     * host ({@code RootScreen}) can bind the root tree from outside this package.
     */
    public void attach(final AppContext context) {
        this.ctx = context;
    }

    // CONTENT BOX HELPERS (INSIDE PADDING) — VALID AFTER layout
    public int innerLeft() {
        return this.left + this.padding.left();
    }

    public int innerTop() {
        return this.top + this.padding.top();
    }

    public int innerWidth() {
        return Math.max(0, this.measuredWidth - this.padding.horizontal());
    }

    public int innerHeight() {
        return Math.max(0, this.measuredHeight - this.padding.vertical());
    }

    // ==========================================================================
    // ACCESSORS
    // ==========================================================================

    public int left() {
        return this.left;
    }

    public int top() {
        return this.top;
    }

    public int measuredWidth() {
        return this.measuredWidth;
    }

    public int measuredHeight() {
        return this.measuredHeight;
    }

    public Spacing margin() {
        return this.margin;
    }

    public float weight() {
        return this.weight;
    }

    public Gravity gravity() {
        return this.gravity;
    }

    public boolean visible() {
        return this.visible;
    }

    public boolean enabled() {
        return this.enabled;
    }

    public boolean hovered() {
        return this.hovered;
    }

    public boolean focused() {
        return this.focused;
    }

    public boolean selected() {
        return this.selected;
    }

    public Object tag() {
        return this.tag;
    }

    // ==========================================================================
    // FLUENT SETTERS
    // ==========================================================================

    public T size(final int w, final int h) {
        this.width = w;
        this.height = h;
        return this.self();
    }

    public T width(final int w) {
        this.width = w;
        return this.self();
    }

    public T height(final int h) {
        this.height = h;
        return this.self();
    }

    public T margin(final Spacing m) {
        this.margin = m == null ? Spacing.ZERO : m;
        return this.self();
    }

    public T margin(final int all) {
        this.margin = Spacing.all(all);
        return this.self();
    }

    public T padding(final Spacing p) {
        this.padding = p == null ? Spacing.ZERO : p;
        return this.self();
    }

    public T padding(final int all) {
        this.padding = Spacing.all(all);
        return this.self();
    }

    public T gravity(final Gravity g) {
        this.gravity = g == null ? Gravity.LEFT : g;
        return this.self();
    }

    public T weight(final float w) {
        this.weight = w;
        return this.self();
    }

    public T background(final Color color) {
        this.background = color;
        return this.self();
    }

    public T border(final Color color, final float lineWidth) {
        this.borderColor = color;
        this.borderWidth = lineWidth;
        return this.self();
    }

    public T radius(final float r) {
        this.radius = r;
        return this.self();
    }

    public T glow(final Color color, final float alpha) {
        this.glowColor = color;
        this.glowAlpha = alpha;
        return this.self();
    }

    public T shadow(final float alpha) {
        this.shadowAlpha = alpha;
        return this.self();
    }

    public T visible(final boolean v) {
        this.visible = v;
        return this.self();
    }

    public T enabled(final boolean e) {
        this.enabled = e;
        return this.self();
    }

    public T selected(final boolean s) {
        this.selected = s;
        return this.self();
    }

    public T focused(final boolean f) {
        this.focused = f;
        return this.self();
    }

    public T consumeTouch(final boolean consume) {
        this.consumeTouch = consume;
        return this.self();
    }

    public T onClick(final Consumer<T> handler) {
        this.onClick = handler;
        return this.self();
    }

    public T onHover(final Consumer<T> handler) {
        this.onHover = handler;
        return this.self();
    }

    public T tag(final Object value) {
        this.tag = value;
        return this.self();
    }
}
