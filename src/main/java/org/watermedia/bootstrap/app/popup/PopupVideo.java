package org.watermedia.bootstrap.app.popup;

import org.watermedia.api.media.engines.GFXEngine;

import javax.swing.JComponent;

/**
 * The video surface and its engine lifecycle for a popup. A {@code MediaPlayer} owns (and releases) its
 * engine, and engines are player-exclusive, so each source needs a fresh one — {@link #newEngine()}
 * builds it and makes it the displayed engine while the Swing {@link #component()} stays stable.
 */
interface PopupVideo {
    /** Builds a fresh player-exclusive engine, makes it the displayed one, and returns it. */
    GFXEngine newEngine();

    /** The Swing component that shows the video — stable across source switches. */
    JComponent component();

    /** Called each tick on the EDT to present the current engine's frame. */
    void render();
}
