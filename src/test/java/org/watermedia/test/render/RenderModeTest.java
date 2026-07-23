package org.watermedia.test.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.bootstrap.app.PlayerTarget;
import org.watermedia.bootstrap.app.render.RenderMode;
import org.watermedia.bootstrap.app.render.RenderSystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Headless tests for {@link RenderMode#of}: the popup target wins over the in-app engine choice, and
 * every constant round-trips through its own {@code engine()}/{@code target()}. Pure mapping — no GPU.
 */
@DisplayName("RenderMode mapping")
class RenderModeTest {

    @Test
    @DisplayName("in-app modes follow the engine")
    void inAppFollowsEngine() {
        assertSame(RenderMode.OPENGL, RenderMode.of(RenderSystem.Engine.OPENGL, PlayerTarget.IN_APP));
        assertSame(RenderMode.VULKAN, RenderMode.of(RenderSystem.Engine.VULKAN, PlayerTarget.IN_APP));
    }

    @Test
    @DisplayName("a popup target wins over the engine, even a GL downgrade")
    void popupWinsOverEngine() {
        // AWT/JFX ALWAYS RUN THE UI ON VULKAN AND POP PLAYBACK OUT, REGARDLESS OF THE PASSED ENGINE
        assertSame(RenderMode.VULKAN_AWT, RenderMode.of(RenderSystem.Engine.OPENGL, PlayerTarget.AWT));
        assertSame(RenderMode.VULKAN_AWT, RenderMode.of(RenderSystem.Engine.VULKAN, PlayerTarget.AWT));
        assertSame(RenderMode.VULKAN_JFX, RenderMode.of(RenderSystem.Engine.OPENGL, PlayerTarget.JFX));
        assertSame(RenderMode.VULKAN_JFX, RenderMode.of(RenderSystem.Engine.VULKAN, PlayerTarget.JFX));
    }

    @Test
    @DisplayName("every mode round-trips through its own engine/target")
    void everyModeRoundTrips() {
        for (final RenderMode mode: RenderMode.values()) {
            assertSame(mode, RenderMode.of(mode.engine(), mode.target()),
                    "of(engine,target) must reconstruct " + mode);
        }
    }

    @Test
    @DisplayName("labels are stable")
    void labelsAreStable() {
        assertEquals("GL", RenderMode.OPENGL.label());
        assertEquals("VK", RenderMode.VULKAN.label());
        assertEquals("VK+AWT", RenderMode.VULKAN_AWT.label());
        assertEquals("VK+FX", RenderMode.VULKAN_JFX.label());
    }
}
