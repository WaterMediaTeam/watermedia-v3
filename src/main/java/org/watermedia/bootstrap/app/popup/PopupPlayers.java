package org.watermedia.bootstrap.app.popup;

import org.watermedia.api.media.MRL;
import org.watermedia.api.media.engines.SFXEngine;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.bootstrap.app.AppContext;

import javax.swing.SwingUtilities;
import java.util.function.Supplier;

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

    // OPENS THE SHARED SWING PLAYER OVER video FOR THE APP'S SELECTED MRL, REPLACING ANY OPEN POPUP. THE AWT
    // AND JAVAFX ENTRY POINTS DIFFER ONLY IN THAT SURFACE, WHICH IS BUILT ON THE EDT (JFXPanel MUST BE).
    static void open(final AppContext ctx, final Supplier<PopupVideo> video) {
        final MRL mrl = ctx.selectedMRL;
        if (mrl == null) return;
        final String title = ctx.selectedMRLName;
        final int sourceIndex = ctx.sourceSelectorIndex;
        final int sourceCount = ctx.availableSources != null ? ctx.availableSources.length : 1;
        final MediaQuality quality = ctx.selectedQuality;
        final Supplier<SFXEngine> audio = ctx.audioEngine.supplier();
        SwingUtilities.invokeLater(() ->
                new SwingPlayerWindow(video.get(), mrl, title, sourceIndex, sourceCount, quality, audio));
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
