package org.watermedia.bootstrap.app.screen;

import org.watermedia.bootstrap.app.element.Canvas;
import org.watermedia.bootstrap.app.element.Element;
import org.watermedia.bootstrap.app.ui.AppTheme;

/**
 * The CRT effect as a retained view: scanlines every 3px plus two time-animated neon bands travelling
 * down the box, clipped to it. It never consumes input. The on/off state is the global
 * {@link org.watermedia.bootstrap.app.AppContext#crt} flag driven by the {@code C} shortcut and the
 * Settings row.
 */
public final class CrtOverlay extends Element<CrtOverlay> {

    public CrtOverlay() {
        this.width = MAX_PARENT;
        this.height = MAX_PARENT;
        this.enabled = false; // NEVER HIT-TESTABLE — THE OVERLAY IS PURELY DECORATIVE
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        if (this.ctx == null || !this.ctx.crt) return;
        final int x = this.left;
        final int y = this.top;
        final int w = this.measuredWidth;
        final int h = this.measuredHeight;
        // SCANLINES + TWO TRAVELLING NEON BANDS, CLIPPED TO THE BOX
        canvas.pushClip(x, y, w, h);
        for (int sy = y; sy < y + h; sy += 3) {
            canvas.fill(x, sy + 2, w, 1, 0f, 0f, 0f, 0.22f);
        }
        final int bandH = Math.max(38, h / 9);
        final int travel = Math.max(1, h + bandH * 2);
        final int bandY = y - bandH * 2 + (int) ((System.currentTimeMillis() / 24L) % travel);
        for (int i = 0; i < 2; i++) {
            final int by = bandY + i * travel;
            canvas.gradientV(x, by, w, bandH,
                    AppTheme.NEON.getRed() / 255f, AppTheme.NEON.getGreen() / 255f, AppTheme.NEON.getBlue() / 255f, 0f,
                    AppTheme.NEON.getRed() / 255f, AppTheme.NEON.getGreen() / 255f, AppTheme.NEON.getBlue() / 255f, 0.14f);
            canvas.gradientV(x, by + bandH, w, bandH,
                    AppTheme.NEON.getRed() / 255f, AppTheme.NEON.getGreen() / 255f, AppTheme.NEON.getBlue() / 255f, 0.14f,
                    AppTheme.NEON.getRed() / 255f, AppTheme.NEON.getGreen() / 255f, AppTheme.NEON.getBlue() / 255f, 0f);
        }
        canvas.popClip();
    }
}
