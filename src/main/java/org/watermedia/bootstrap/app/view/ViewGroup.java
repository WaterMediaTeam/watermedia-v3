package org.watermedia.bootstrap.app.view;

import org.watermedia.bootstrap.app.AppContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Base container: holds child views, draws them, and fans events out to them topmost-first (later
 * children paint on top, so they claim a hit before earlier ones). Concrete layouts
 * ({@link LinearLayout}, {@link FrameLayout}, {@link ScrollView}) decide how children are measured and
 * positioned by overriding {@link #onMeasure} and {@link #onLayout}.
 *
 * @param <T> the concrete container type, for fluent chaining
 */
public abstract class ViewGroup<T extends ViewGroup<T>> extends View<T> {

    protected final List<View<?>> children = new ArrayList<>();

    public T add(final View<?> child) {
        if (child != null) {
            child.parent = this;
            if (this.ctx != null) child.attach(this.ctx);
            this.children.add(child);
        }
        return this.self();
    }

    public T remove(final View<?> child) {
        this.children.remove(child);
        return this.self();
    }

    public T clear() {
        this.children.clear();
        return this.self();
    }

    public List<View<?>> children() {
        return this.children;
    }

    @Override
    void attach(final AppContext context) {
        super.attach(context);
        for (final View<?> child: this.children) child.attach(context);
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        for (final View<?> child: this.children) child.draw(canvas);
    }

    @Override
    public boolean dispatchClick(final double mx, final double my) {
        if (!this.visible || !this.enabled) return false;
        for (int i = this.children.size() - 1; i >= 0; i--) {
            if (this.children.get(i).dispatchClick(mx, my)) return true;
        }
        if (!this.contains(mx, my)) return false;
        if (this.onClick != null) {
            this.onClick.accept(this.self());
            return true;
        }
        return this.consumeTouch;
    }

    @Override
    public boolean dispatchHover(final double mx, final double my) {
        if (!this.visible) return false;
        boolean any = false;
        // VISIT EVERY CHILD SO ONES NO LONGER UNDER THE CURSOR CLEAR THEIR HOVER STATE
        for (final View<?> child: this.children) {
            if (child.dispatchHover(mx, my)) any = true;
        }
        final boolean inside = this.enabled && this.contains(mx, my);
        this.hovered = inside;
        if (inside && this.onHover != null) this.onHover.accept(this.self());
        return any || inside;
    }

    @Override
    public View<?> dispatchPress(final double mx, final double my) {
        if (!this.visible || !this.enabled) return null;
        for (int i = this.children.size() - 1; i >= 0; i--) {
            final View<?> hit = this.children.get(i).dispatchPress(mx, my);
            if (hit != null) return hit;
        }
        return this.contains(mx, my) && this.onPress(mx, my) ? this : null;
    }

    @Override
    public boolean textInputActive() {
        for (final View<?> child: this.children) {
            if (child.textInputActive()) return true;
        }
        return false;
    }

    @Override
    public boolean dispatchScroll(final double mx, final double my, final double amount) {
        for (int i = this.children.size() - 1; i >= 0; i--) {
            if (this.children.get(i).dispatchScroll(mx, my, amount)) return true;
        }
        return false;
    }

    @Override
    public boolean dispatchKey(final int key, final int action) {
        for (int i = this.children.size() - 1; i >= 0; i--) {
            if (this.children.get(i).dispatchKey(key, action)) return true;
        }
        return false;
    }

    @Override
    public boolean dispatchChar(final int codepoint) {
        for (int i = this.children.size() - 1; i >= 0; i--) {
            if (this.children.get(i).dispatchChar(codepoint)) return true;
        }
        return false;
    }
}
