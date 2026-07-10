package org.watermedia.bootstrap.app.screen;

import org.watermedia.bootstrap.app.element.Canvas;
import org.watermedia.bootstrap.app.element.Element;

/**
 * The app backdrop as a retained view — the pixel-exact port of the imperative chrome background:
 * a deep vertical gradient, a soft glow band across the top, and 1px scanlines every 3px. Fills its
 * parent by default and never consumes input.
 */
public final class Background extends Element<Background> {

    public Background() {
        this.width = MAX_PARENT;
        this.height = MAX_PARENT;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final int x = this.left;
        final int y = this.top;
        final int w = this.measuredWidth;
        final int h = this.measuredHeight;
        // BASE GRADIENT, TOP GLOW BAND, SCANLINES EVERY 3px
        canvas.gradientV(x, y, w, h,
                12f / 255f, 18f / 255f, 44f / 255f, 1f,
                4f / 255f, 6f / 255f, 22f / 255f, 1f);
        canvas.gradientV(x, y, w, Math.min(150, h / 4),
                31f / 255f, 46f / 255f, 92f / 255f, 0.42f,
                4f / 255f, 6f / 255f, 22f / 255f, 0f);
        for (int sy = 0; sy < h; sy += 3) {
            canvas.fill(x, y + sy + 2, w, 1, 0f, 0f, 0f, 0.15f);
        }
    }
}
