package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;
import java.util.function.Supplier;

/**
 * An 8x8 status pip with a soft glow — the retained port of the imperative chrome pip. The color is
 * derived from a {@link Status} that is resolved on every draw (via a {@link Supplier} or a fixed
 * value), so live state such as a loading backend is always current without rebuilding the tree.
 * {@code PENDING} pulses its alpha with the same sine wave the imperative footer used.
 */
public final class StatusSquare extends Element<StatusSquare> {

    /** Semantic state of the pip; each value maps to a fixed theme color. */
    public enum Status {
        OK,
        WARN,
        ERROR,
        PENDING,
        OFF
    }

    private static final int SIZE = 8;

    private Supplier<Status> status = () -> Status.OFF;

    public StatusSquare() {
    }

    public StatusSquare(final Status value) {
        this.status(value);
    }

    /** Sets a fixed status. */
    public StatusSquare status(final Status value) {
        final Status fixed = value == null ? Status.OFF : value;
        this.status = () -> fixed;
        return this;
    }

    /** Sets a live status source, re-read on every draw. */
    public StatusSquare status(final Supplier<Status> supplier) {
        this.status = supplier == null ? () -> Status.OFF : supplier;
        return this;
    }

    /** The status resolved right now. */
    public Status status() {
        final Status value = this.status.get();
        return value == null ? Status.OFF : value;
    }

    /**
     * Resolves the pip color for a status: {@code ERROR} red, {@code WARN} amber, {@code OK} green,
     * {@code PENDING}/{@code OFF} faint — with {@code PENDING} pulsing its alpha over time exactly like
     * the imperative backend strip did.
     */
    public static Color tint(final Status status) {
        final Status s = status == null ? Status.OFF : status;
        final Color base = switch (s) {
            case ERROR -> AppTheme.RED;
            case WARN -> AppTheme.AMBER;
            case OK -> AppTheme.GREEN;
            default -> AppTheme.TEXT_FAINT; // PENDING / OFF
        };
        if (s != Status.PENDING) return base;
        // SINE ALPHA PULSE — FIXED PHASE AND RANGE
        final float pulse = 0.42f + 0.35f * (float) ((Math.sin(System.currentTimeMillis() / 180.0) + 1.0) * 0.5);
        return AppTheme.alpha(base, Math.max(70, Math.min(255, (int) (255 * pulse))));
    }

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        this.contentWidth = SIZE;
        this.contentHeight = SIZE;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        // SOLID SQUARE PLUS GLOW IN THE STATUS HUE
        final Color c = tint(this.status.get());
        canvas.fill(this.left, this.top, this.measuredWidth, this.measuredHeight, c);
        canvas.glow(this.left, this.top, this.measuredWidth, this.measuredHeight, 0f, c, 0.42f);
    }
}
