package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;

/**
 * A horizontal progress-of-N indicator: a row of square step dots joined by connector lines. It is
 * display-only, with no interaction. Completed steps are filled green, the active step is outlined in the
 * accent with a faint fill, and future steps are muted; each connector takes the color of the step to its
 * left so the completed run reads as one continuous bar.
 */
public final class Stepper extends Element<Stepper> {

    private static final int DOT = 22;
    private static final int GAP = 24;

    private int steps = 3;
    private int active;
    private int complete;

    public Stepper steps(final int value) {
        this.steps = Math.max(1, value);
        return this;
    }

    public Stepper active(final int value) {
        // REJECT NEGATIVES LIKE THE SIBLING CONTROLS — A NEGATIVE INDEX NEVER MATCHES A STEP (SILENT MISUSE)
        this.active = Math.max(0, value);
        return this;
    }

    public Stepper complete(final int value) {
        this.complete = Math.max(0, value);
        return this;
    }

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        this.contentWidth = this.steps * DOT + (this.steps - 1) * GAP;
        this.contentHeight = DOT;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final int y = this.innerTop();
        int x = this.innerLeft();
        for (int i = 0; i < this.steps; i++) {
            // CONNECTOR INTO THIS DOT — COLORED BY WHETHER THE STEP ON ITS LEFT IS COMPLETE
            if (i > 0) {
                final Color line = i - 1 < this.complete ? AppTheme.GREEN : AppTheme.alpha(AppTheme.NEON_DARK, 80);
                canvas.fill(x - GAP, y + DOT / 2 - 1, GAP, 2, line);
            }
            if (i < this.complete) {
                canvas.fill(x, y, DOT, DOT, AppTheme.GREEN);
            } else if (i == this.active) {
                canvas.fill(x, y, DOT, DOT, AppTheme.alpha(AppTheme.NEON, 40));
                canvas.stroke(x, y, DOT, DOT, AppTheme.NEON, 1.5f);
            } else {
                canvas.fill(x, y, DOT, DOT, AppTheme.alpha(AppTheme.NEON_DARK, 80));
            }
            x += DOT + GAP;
        }
    }
}
