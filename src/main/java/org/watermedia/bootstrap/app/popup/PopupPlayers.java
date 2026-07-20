package org.watermedia.bootstrap.app.popup;

/**
 * Shared state and helpers for the popped-out player windows (AWT / JavaFX). Keeps a single popup
 * alive at a time — opening a new MRL closes the current window instead of stacking players.
 */
public final class PopupPlayers {
    private PopupPlayers() {}

    // THE ONE LIVE POPUP; OPENING ANOTHER CLOSES IT
    private static volatile Closer current;

    /** A popped-out player that can be torn down. */
    public interface Closer {
        void close();
    }

    /** Adopts a new popup as the exclusive player, closing whatever was open before it. */
    public static void adopt(final Closer next) {
        final Closer prev = current;
        current = next;
        if (prev != null && prev != next) prev.close();
    }

    /** Clears the reference when a popup closes itself, so it is not double-closed. */
    public static void forget(final Closer who) {
        if (current == who) current = null;
    }

    /** Formats milliseconds as {@code m:ss} (or {@code h:mm:ss} past an hour). */
    public static String fmtTime(final long ms) {
        if (ms <= 0) return "0:00";
        final long total = ms / 1000;
        final long h = total / 3600;
        final long m = (total % 3600) / 60;
        final long s = total % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }
}
