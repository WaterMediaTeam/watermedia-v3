package org.watermedia.bootstrap.app.view;

import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;
import java.util.function.Consumer;

/**
 * A track with a sliding knob that toggles an on/off state on click. The track fills with the accent
 * color when on and a dark neutral when off, framed by a hairline stroke; the square knob rests at the
 * left edge when off and the right edge when on. A click flips the state, repaints, and notifies the
 * {@code onChange} listener with the new value.
 */
public final class Switch extends View<Switch> {

    private static final int TRACK_W = 40;
    private static final int TRACK_H = 20;
    private static final int KNOB = 16;
    private static final int INSET = 2;

    private boolean on;
    private Color accent = AppTheme.NEON;
    private Consumer<Boolean> onChange;

    public Switch on(final boolean value) {
        this.on = value;
        return this;
    }

    public Switch accent(final Color value) {
        this.accent = value == null ? AppTheme.NEON : value;
        return this;
    }

    public Switch onChange(final Consumer<Boolean> handler) {
        this.onChange = handler;
        return this;
    }

    public boolean on() {
        return this.on;
    }

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        this.contentWidth = TRACK_W;
        this.contentHeight = TRACK_H;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final int trackX = this.innerLeft();
        final int trackY = this.top + (this.measuredHeight - TRACK_H) / 2;
        canvas.fill(trackX, trackY, TRACK_W, TRACK_H,
                this.on ? AppTheme.alpha(this.accent, 190) : AppTheme.alpha(AppTheme.BG_3, 220));
        canvas.stroke(trackX, trackY, TRACK_W, TRACK_H, AppTheme.STROKE, 1f);
        final int knobX = this.on ? trackX + TRACK_W - KNOB - INSET : trackX + INSET;
        canvas.fill(knobX, trackY + INSET, KNOB, KNOB, AppTheme.TEXT);
    }

    @Override
    public boolean dispatchClick(final double mx, final double my) {
        if (!this.visible || !this.enabled || !this.contains(mx, my)) return false;
        this.on = !this.on;
        this.invalidate();
        if (this.onChange != null) this.onChange.accept(this.on);
        return true;
    }
}
