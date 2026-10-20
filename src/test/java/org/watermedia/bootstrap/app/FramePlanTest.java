package org.watermedia.bootstrap.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies how the main loop picks a frame: animating screens repaint whole, changes repaint the tree, and a moving
 * CRT band over a still tree only composites the cached layers.
 */
@DisplayName("Frame plan")
public class FramePlanTest {

    @Nested
    @DisplayName("Repaints")
    class Repaints {

        @Test
        @DisplayName("an animating screen draws straight to the window whatever else holds")
        void continuous() {
            assertEquals(FramePlan.DIRECT, FramePlan.of(true, false, true, true, true, true));
            assertEquals(FramePlan.DIRECT, FramePlan.of(true, true, true, true, false, false));
        }

        @Test
        @DisplayName("a change repaints the tree into the cache only while a CRT band moves")
        void requested() {
            assertEquals(FramePlan.CACHE, FramePlan.of(false, true, true, true, true, true));
            assertEquals(FramePlan.DIRECT, FramePlan.of(false, true, true, false, true, false));
            assertEquals(FramePlan.DIRECT, FramePlan.of(false, true, false, true, true, true), "CRT just turned off");
        }
    }

    @Nested
    @DisplayName("Still tree")
    class StillTree {

        @Test
        @DisplayName("a moving band over a valid cache only composites, and rebuilds a dropped cache")
        void composites() {
            assertEquals(FramePlan.COMPOSITE, FramePlan.of(false, false, true, true, true, true));
            assertEquals(FramePlan.CACHE, FramePlan.of(false, false, true, true, true, false));
        }

        @Test
        @DisplayName("nothing to show sleeps: no band, CRT off or a minimized window")
        void idle() {
            assertEquals(FramePlan.IDLE, FramePlan.of(false, false, true, false, true, true));
            assertEquals(FramePlan.IDLE, FramePlan.of(false, false, false, true, true, true));
            assertEquals(FramePlan.IDLE, FramePlan.of(false, false, true, true, false, true));
        }
    }
}
