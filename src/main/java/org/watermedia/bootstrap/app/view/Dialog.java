package org.watermedia.bootstrap.app.view;

import org.watermedia.bootstrap.app.ui.AppTheme;

import java.util.function.Consumer;

/**
 * A modal overlay: a dimmed, click-swallowing scrim with a centered panel on top. Content is added to
 * the panel (a vertical {@link LinearLayout}); an optional bold title sits at the top. The whole dialog
 * fills its parent so it can be dropped into a screen's overlay layer, and clicking the scrim can be
 * wired to dismiss it.
 */
public final class Dialog extends ViewGroup<Dialog> {

    private final Box scrim;
    private final LinearLayout panel;
    private TextView titleView;

    public Dialog() {
        this.width = MATCH_PARENT;
        this.height = MATCH_PARENT;
        this.scrim = new Box()
                .size(MATCH_PARENT, MATCH_PARENT)
                .background(AppTheme.alpha(AppTheme.BG_0, 153))
                .consumeTouch(true);
        this.panel = LinearLayout.column()
                .spacing(Theme.SPACE_MD)
                .padding(Theme.SPACE_XL)
                .background(AppTheme.BG_1)
                .border(AppTheme.STROKE_BRIGHT, Theme.BORDER_ACTIVE)
                .glow(AppTheme.NEON, Theme.GLOW_WEAK)
                .shadow(Theme.SHADOW_DIALOG);
        this.add(this.scrim);
        this.add(this.panel);
    }

    public Dialog title(final String text) {
        if (this.titleView == null) {
            this.titleView = new TextView().bold(true).scale(AppTheme.TEXT_SECTION).color(AppTheme.TEXT);
            this.titleView.parent = this.panel;
            if (this.ctx != null) this.titleView.attach(this.ctx);
            this.panel.children().add(0, this.titleView);
        }
        this.titleView.text(text);
        return this;
    }

    public Dialog panelWidth(final int width) {
        this.panel.width(width);
        return this;
    }

    public Dialog content(final View<?> view) {
        this.panel.add(view);
        return this;
    }

    public LinearLayout panel() {
        return this.panel;
    }

    public Dialog dismissOnScrim(final Runnable action) {
        // A CLICK ON THE SCRIM (OUTSIDE THE PANEL) RUNS THE ACTION; THE PANEL STILL SWALLOWS ITS OWN CLICKS
        final Consumer<Box> handler = action == null ? null : box -> action.run();
        this.scrim.onClick(handler);
        return this;
    }

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        this.scrim.measure(innerAvailWidth, innerAvailHeight);
        this.panel.measure(innerAvailWidth, innerAvailHeight);
        this.contentWidth = innerAvailWidth;
        this.contentHeight = innerAvailHeight;
    }

    @Override
    protected void onLayout() {
        this.scrim.layout(this.innerLeft(), this.innerTop());
        final int px = this.innerLeft() + Math.max(0, (this.innerWidth() - this.panel.measuredWidth) / 2);
        final int py = this.innerTop() + Math.max(0, (this.innerHeight() - this.panel.measuredHeight) / 2);
        this.panel.layout(px, py);
    }
}
