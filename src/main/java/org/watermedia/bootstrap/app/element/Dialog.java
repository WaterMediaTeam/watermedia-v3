package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Gravity;
import org.watermedia.bootstrap.app.ui.Spacing;

import java.awt.Color;
import java.awt.Font;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

/**
 * A modal overlay: a dimmed, click-swallowing scrim with a centered panel on top. The panel is a skinned
 * stack of edge-to-edge bands that reproduces the legacy dialog chrome:
 *
 * <ol>
 *   <li><b>Title band</b> — a strip slightly darker than the panel with the title in the accent on the
 *       left and a red close (X) button on the right, closed off by a full-width hairline. Present
 *       whenever a {@link #title} is set; the X appears when an {@link #onDismiss} action exists and
 *       {@link #closable(boolean)} is not turned off.</li>
 *   <li><b>Header band</b> — an optional {@link #header(Element)} slot (e.g. a stage stepper), also
 *       separated by a full-width hairline. Absent when no header is set.</li>
 *   <li><b>Content</b> — the padded body that {@link #content(Element)} adds to.</li>
 *   <li><b>Footer band</b> — an optional right-aligned row of action {@link Button}s from
 *       {@link #actions(Button...)}, separated from the content by a full-width hairline above it.</li>
 * </ol>
 *
 * <p>Amber corner cubes sit in the panel's bottom corners. The whole dialog fills its parent so it can be
 * dropped into a screen's overlay layer. While visible it is truly modal: keys, characters and scroll are
 * always consumed after the panel content had its chance, ESC runs {@link #onDismiss} and ENTER runs
 * {@link #onPrimary}. An {@link #accent} tints the panel border, glow and title (default neon).
 *
 * <p>Becoming visible fades the whole dialog (scrim, panel and children) in with a short ease-in;
 * closing is instant.
 */
public final class Dialog extends Group<Dialog> {

    private static final int TITLE_BAND_H = 48;
    private static final Spacing BAND_PAD = Spacing.hv(Theme.SPACE_XL, Theme.SPACE_MD);
    private static final int CUBE = 8;
    private static final int FADE_MS = 180;

    private final Box scrim;
    private final Panel panel;
    private final Parent contentCol;
    private TitleBand titleBand;
    private Band headerBand;
    private Band footerBand;
    private Parent actionsRow;
    private Color accent;
    private boolean closable = true;
    private Runnable onDismiss;
    private Runnable onPrimary;
    // FADE-IN — fadeStart 0 MEANS IDLE; wasVisible TRACKS THE HIDDEN→VISIBLE TRANSITION ACROSS FRAMES
    private long fadeStart;
    private boolean wasVisible;
    // KEY WHOSE PRESS A CHILD SWALLOWED (E.G. A SPINNER EDIT) — ITS RELEASE MUST NOT DOUBLE-FIRE THE DIALOG ACTION
    private int childConsumedKey = -1;

    public Dialog() {
        this.width = MAX_PARENT;
        this.height = MAX_PARENT;
        this.scrim = new Box()
                .size(MAX_PARENT, MAX_PARENT)
                .background(AppTheme.alpha(AppTheme.BG_0, 153))
                .consumeTouch(true);
        // WRAP WIDTH SO THE PANEL CAN DERIVE ITS SIZE FROM THE CONTENT WHEN NO panelWidth IS PINNED; A
        // panelWidth STRETCHES THIS COLUMN (AND ANY MAX_PARENT CONTENT INSIDE IT) TO FILL THE PANEL
        this.contentCol = Parent.column()
                .spacing(Theme.SPACE_MD)
                .padding(Theme.SPACE_XL);
        this.panel = new Panel();
        this.panel.background(AppTheme.alpha(AppTheme.BG_1, 248)).shadow(Theme.SHADOW_DIALOG);
        this.applyAccent();
        this.layoutSlots();
        this.add(this.scrim);
        this.add(this.panel);
    }

    public Dialog title(final String text) {
        if (this.titleBand == null) {
            this.titleBand = new TitleBand();
            this.layoutSlots();
            this.refreshClose();
        }
        this.titleBand.title = text == null ? "" : text;
        return this;
    }

    /**
     * Sets the optional header band shown under the title (e.g. a stage stepper), separated by a
     * full-width hairline. Passing {@code null} removes the band.
     */
    public Dialog header(final Element<?> view) {
        if (view == null) {
            if (this.headerBand != null) {
                this.headerBand = null;
                this.layoutSlots();
            }
            return this;
        }
        if (this.headerBand == null) {
            this.headerBand = new Band(false);
            this.layoutSlots();
        }
        this.headerBand.clear();
        this.headerBand.add(view);
        return this;
    }

    public Dialog panelWidth(final int width) {
        this.panel.width(width);
        return this;
    }

    public Dialog content(final Element<?> view) {
        this.contentCol.add(view);
        return this;
    }

    /** The dialog panel (the skinned band stack) — screens may make it swallow or handle its own clicks. */
    public Group<?> panel() {
        return this.panel;
    }

    public Dialog dismissOnScrim(final Runnable action) {
        // A CLICK ON THE SCRIM (OUTSIDE THE PANEL) RUNS THE ACTION; THE PANEL STILL SWALLOWS ITS OWN CLICKS
        final Consumer<Box> handler = action == null ? null : box -> action.run();
        this.scrim.onClick(handler);
        return this;
    }

    /** Action run when ESC is released or the close (X) button is clicked (typically hides the dialog). */
    public Dialog onDismiss(final Runnable action) {
        this.onDismiss = action;
        this.refreshClose();
        return this;
    }

    /** Primary action run when ENTER is released while the dialog is visible (optional). */
    public Dialog onPrimary(final Runnable action) {
        this.onPrimary = action;
        return this;
    }

    /** Whether the title band shows a close (X) button; the X still needs an {@link #onDismiss}. Default true. */
    public Dialog closable(final boolean value) {
        this.closable = value;
        this.refreshClose();
        return this;
    }

    /**
     * Tints the panel border, glow and title with the given accent color. {@code null} restores the
     * default look (bright stroke border, neon glow, plain title). The global error dialog uses red.
     */
    public Dialog accent(final Color color) {
        this.accent = color;
        this.applyAccent();
        return this;
    }

    /**
     * Sets the footer row of action buttons, right-aligned at the bottom of the panel. Calling it again
     * replaces the previous row. Buttons keep their own accents and handlers; a button without an
     * explicit height gets the standard button height.
     */
    public Dialog actions(final Button... buttons) {
        if (this.actionsRow == null) {
            this.actionsRow = Parent.row().spacing(Theme.SPACE_SM).gravity(Gravity.RIGHT);
        } else {
            this.actionsRow.clear();
        }
        if (buttons != null) {
            for (final Button button: buttons) {
                if (button == null) continue;
                if (button.height == WRAP_CONTENT) button.height(Theme.BUTTON);
                this.actionsRow.add(button);
            }
        }
        if (this.footerBand == null) {
            this.footerBand = new Band(true);
            this.footerBand.add(this.actionsRow);
            this.layoutSlots();
        }
        return this;
    }

    // REBUILDS THE PANEL CHILD LIST IN BAND ORDER (SKIPPING ABSENT SLOTS) SO DRAW/DISPATCH MATCH THE STACK
    private void layoutSlots() {
        this.panel.clear();
        if (this.titleBand != null) this.panel.add(this.titleBand);
        if (this.headerBand != null) this.panel.add(this.headerBand);
        this.panel.add(this.contentCol);
        if (this.footerBand != null) this.panel.add(this.footerBand);
    }

    private void applyAccent() {
        this.panel.border(this.accent == null ? AppTheme.STROKE_BRIGHT : this.accent, Theme.BORDER_ACTIVE)
                .glow(this.accent == null ? AppTheme.NEON : this.accent, Theme.GLOW_MED);
    }

    private void refreshClose() {
        if (this.titleBand != null) this.titleBand.close.visible(this.closable && this.onDismiss != null);
    }

    @Override
    protected void onUpdate() {
        // FADE-IN ARMS ON THE HIDDEN→VISIBLE TRANSITION — COVERS visible(true) FROM syncShell/SCREENS AND
        // FRESHLY MOUNTED DIALOGS ALIKE (update RUNS FOR HIDDEN CHILDREN TOO); CLOSE IS INSTANT, NO FADE-OUT
        if (this.visible && !this.wasVisible) {
            this.fadeStart = System.currentTimeMillis();
        } else if (!this.visible) {
            this.fadeStart = 0L; // HIDDEN MID-FADE — DROP THE ANIMATION SO THE LOOP CAN GO BACK TO IDLE
        }
        this.wasVisible = this.visible;
        if (this.fadeStart != 0L) {
            if (System.currentTimeMillis() - this.fadeStart >= FADE_MS) {
                this.fadeStart = 0L; // DONE — STOP SELF-SUSTAINING
            } else {
                this.invalidate();
            }
        }
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        if (this.fadeStart == 0L) {
            super.onDraw(canvas);
            return;
        }
        // EASE-IN FADE OVER THE WHOLE DIALOG (SCRIM + PANEL + CHILDREN) VIA THE CANVAS ALPHA MULTIPLIER
        final float t = Math.min(1f, (System.currentTimeMillis() - this.fadeStart) / (float) FADE_MS);
        canvas.pushAlpha(t * t);
        super.onDraw(canvas);
        canvas.popAlpha();
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

    // ==========================================================================
    // MODAL INPUT — WHILE VISIBLE, EVERYTHING IS CONSUMED AFTER THE CONTENT HAD ITS CHANCE
    // ==========================================================================

    @Override
    public boolean dispatchKey(final int key, final int action) {
        if (!this.visible) return false;
        final boolean childHandled = super.dispatchKey(key, action);
        if (action == GLFW_PRESS) {
            // REMEMBER A CHILD-CONSUMED PRESS (E.G. A SPINNER ESC/ENTER, WHICH ACTS ON PRESS) SO ITS RELEASE
            // BELOW IS IGNORED — OTHERWISE ONE KEYSTROKE BOTH COMMITS THE EDITOR AND DISMISSES THE DIALOG
            if (childHandled) this.childConsumedKey = key;
        } else if (action == GLFW_RELEASE) {
            final boolean paired = this.childConsumedKey == key;
            this.childConsumedKey = -1;
            if (!childHandled && !paired) {
                if (key == GLFW_KEY_ESCAPE) {
                    if (this.onDismiss != null) this.onDismiss.run();
                } else if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) {
                    if (this.onPrimary != null) this.onPrimary.run();
                }
            }
        }
        return true;
    }

    @Override
    public boolean dispatchChar(final int codepoint) {
        if (!this.visible) return false;
        super.dispatchChar(codepoint);
        return true;
    }

    @Override
    public boolean dispatchScroll(final double mx, final double my, final double amount) {
        if (!this.visible) return false;
        super.dispatchScroll(mx, my, amount);
        return true;
    }

    @Override
    public Element<?> dispatchPress(final double mx, final double my) {
        if (!this.visible) return null;
        final Element<?> hit = super.dispatchPress(mx, my);
        if (hit != null) return hit;
        // MODAL: A PRESS NOBODY INSIDE CLAIMED IS CAPTURED BY THE DIALOG ITSELF, SO DRAGGABLES UNDER
        // THE SCRIM (SEEKBARS, TITLEBAR) NEVER SEE IT; CLICKS SURVIVE — ONLY REAL DRAGS SUPPRESS THEM
        return this;
    }

    // ==========================================================================
    // PANEL — A VERTICAL BAND STACK STRETCHED TO ONE WIDTH, WITH AMBER CORNER CUBES
    // ==========================================================================

    private final class Panel extends Group<Panel> {

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            // WIDTH IS THE FIXED panelWidth WHEN SET, ELSE THE WIDEST OF THE CONTENT/HEADER/FOOTER (THE
            // TITLE BAND ONLY STRETCHES — IT NEVER DRIVES THE WIDTH), CLAMPED TO THE AVAILABLE SPACE
            int width;
            if (this.width > 0) {
                width = innerAvailWidth;
            } else {
                width = bandWidth(Dialog.this.contentCol, innerAvailWidth, innerAvailHeight);
                width = Math.max(width, bandWidth(Dialog.this.headerBand, innerAvailWidth, innerAvailHeight));
                width = Math.max(width, bandWidth(Dialog.this.footerBand, innerAvailWidth, innerAvailHeight));
                width = Math.min(width, innerAvailWidth);
            }
            int totalH = 0;
            totalH += this.stretch(Dialog.this.titleBand, width, innerAvailHeight);
            totalH += this.stretch(Dialog.this.headerBand, width, innerAvailHeight);
            totalH += this.stretch(Dialog.this.contentCol, width, innerAvailHeight);
            totalH += this.stretch(Dialog.this.footerBand, width, innerAvailHeight);
            this.contentWidth = width;
            this.contentHeight = totalH;
        }

        // MEASURES A BAND AT THE PANEL WIDTH AND FORCES IT TO FILL THAT WIDTH; RETURNS ITS HEIGHT (0 IF ABSENT)
        private int stretch(final Element<?> band, final int width, final int availHeight) {
            if (band == null || !band.visible) return 0;
            band.measure(width, availHeight);
            band.measuredWidth = width;
            return band.measuredHeight;
        }

        @Override
        protected void onLayout() {
            int y = this.innerTop();
            y = this.place(Dialog.this.titleBand, y);
            y = this.place(Dialog.this.headerBand, y);
            y = this.place(Dialog.this.contentCol, y);
            this.place(Dialog.this.footerBand, y);
        }

        private int place(final Element<?> band, final int y) {
            if (band == null || !band.visible) return y;
            band.layout(this.innerLeft(), y);
            return y + band.measuredHeight;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            super.onDraw(canvas);
            // AMBER CORNER CUBES IN THE PANEL'S BOTTOM CORNERS
            final int by = this.top + this.measuredHeight - CUBE - 4;
            amberCube(canvas, this.left + 4, by);
            amberCube(canvas, this.left + this.measuredWidth - CUBE - 4, by);
        }
    }

    private static int bandWidth(final Element<?> band, final int availW, final int availH) {
        if (band == null || !band.visible) return 0;
        band.measure(availW, availH);
        return band.measuredWidth;
    }

    private static void amberCube(final Canvas canvas, final int x, final int y) {
        canvas.glow(x, y, CUBE, CUBE, 0f, AppTheme.AMBER, 0.32f);
        canvas.fill(x, y, CUBE, CUBE, AppTheme.AMBER);
    }

    // ==========================================================================
    // TITLE BAND — DARKER STRIP: ACCENT TITLE LEFT, RED X RIGHT, FULL-WIDTH HAIRLINE UNDER IT
    // ==========================================================================

    private final class TitleBand extends Group<TitleBand> {

        private final IconButton close;
        private String title = "";

        private TitleBand() {
            this.width = WRAP_CONTENT;
            this.height = TITLE_BAND_H;
            this.background = AppTheme.alpha(AppTheme.BG_2, 244);
            this.close = new IconButton("x").accent(AppTheme.RED).iconColor(AppTheme.RED).iconSize(14);
            this.close.visible(false);
            this.close.onClick(b -> {
                if (Dialog.this.onDismiss != null) Dialog.this.onDismiss.run();
            });
            this.add(this.close);
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            this.close.measure(innerAvailWidth, innerAvailHeight);
            final int titleW = this.ctx != null && this.ctx.text != null
                    ? this.ctx.text.widthBold(this.title, AppTheme.TEXT_BUTTON) : 0;
            this.contentWidth = Theme.SPACE_XL + titleW + Theme.SPACE_MD + this.close.measuredWidth + Theme.SPACE_SM;
            this.contentHeight = TITLE_BAND_H;
        }

        @Override
        protected void onLayout() {
            final int size = this.close.measuredHeight;
            this.close.layout(this.left + this.measuredWidth - size - 10, this.top + (this.measuredHeight - size) / 2);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final Color tc = Dialog.this.accent == null ? AppTheme.TEXT : Dialog.this.accent;
            final int th = canvas.textHeight(AppTheme.TEXT_BUTTON, true);
            final int ty = this.top + (this.measuredHeight - th) / 2;
            final int reserve = this.close.visible() ? this.close.measuredWidth + Theme.SPACE_MD : Theme.SPACE_XL;
            final int avail = this.measuredWidth - Theme.SPACE_XL - reserve;
            String draw = this.title;
            if (avail > 0 && canvas.textWidth(this.title, AppTheme.TEXT_BUTTON, true) > avail) {
                draw = canvas.text().truncateToWidth(this.title, avail, AppTheme.TEXT_BUTTON, Font.BOLD);
            }
            canvas.text(draw, this.left + Theme.SPACE_XL, ty, tc, AppTheme.TEXT_BUTTON, true);
            super.onDraw(canvas); // THE CLOSE BUTTON
            canvas.lineH(this.left, this.top + this.measuredHeight - 1, this.measuredWidth, AppTheme.STROKE_BRIGHT, 1f);
        }
    }

    // ==========================================================================
    // HEADER / FOOTER BAND — A PADDED SINGLE-CHILD COLUMN WITH A FULL-WIDTH HAIRLINE (TOP OR BOTTOM)
    // ==========================================================================

    private static final class Band extends Parent {

        private final boolean hairlineTop;

        private Band(final boolean hairlineTop) {
            super(Orientation.VERTICAL);
            this.hairlineTop = hairlineTop;
            this.width = WRAP_CONTENT;
            this.padding(BAND_PAD);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            super.onDraw(canvas);
            final int hy = this.hairlineTop ? this.top : this.top + this.measuredHeight - 1;
            canvas.lineH(this.left, hy, this.measuredWidth, AppTheme.STROKE_BRIGHT, 1f);
        }
    }
}
