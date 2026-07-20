package org.watermedia.bootstrap.app;

/**
 * Where the standalone app renders a media player when an MRL is opened. A user-selectable choice
 * (the unified render-mode selector in Settings) that applies to the next media opened, not a fallback.
 */
public enum PlayerTarget {
    /** Play inside the app on its active GPU engine (GLEngine/VKEngine) — the default. */
    IN_APP,
    /** Pop out a native AWT/Swing window driven by an {@code AWTEngine}. */
    AWT,
    /** Pop out a JavaFX window driven by a {@code JFXEngine}. */
    JFX
}
