package org.watermedia.bootstrap.app.render;

import org.watermedia.bootstrap.app.PlayerTarget;

/**
 * The single render-mode selector: one choice pairing the app's GPU engine with where a player opens.
 * OpenGL/Vulkan play in-app; the AWT/JavaFX modes run the app UI on Vulkan and pop playback out into a
 * native window. Backed internally by {@code engine.cfg} (engine, read by the launcher) plus
 * {@code playermode.cfg} (target) — one selector, no separate engine×target vector.
 */
public enum RenderMode {
    OPENGL(RenderSystem.Engine.OPENGL, PlayerTarget.IN_APP, "GL"),
    VULKAN(RenderSystem.Engine.VULKAN, PlayerTarget.IN_APP, "VK"),
    VULKAN_AWT(RenderSystem.Engine.VULKAN, PlayerTarget.AWT, "VK+AWT"),
    VULKAN_JFX(RenderSystem.Engine.VULKAN, PlayerTarget.JFX, "VK+FX");

    private final RenderSystem.Engine engine;
    private final PlayerTarget target;
    private final String label;

    RenderMode(final RenderSystem.Engine engine, final PlayerTarget target, final String label) {
        this.engine = engine;
        this.target = target;
        this.label = label;
    }

    public RenderSystem.Engine engine() { return this.engine; }

    public PlayerTarget target() { return this.target; }

    public String label() { return this.label; }

    /** The mode for an engine + player target — the popup choice wins over a runtime engine downgrade. */
    public static RenderMode of(final RenderSystem.Engine engine, final PlayerTarget target) {
        if (target == PlayerTarget.AWT) return VULKAN_AWT;
        if (target == PlayerTarget.JFX) return VULKAN_JFX;
        return engine == RenderSystem.Engine.VULKAN ? VULKAN : OPENGL;
    }
}
