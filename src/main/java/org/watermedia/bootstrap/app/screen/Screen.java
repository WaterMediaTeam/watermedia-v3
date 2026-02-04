package org.watermedia.bootstrap.app.screen;

import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.ui.TextRenderer;

import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

/**
 * Base class for application screens.
 */
public abstract class Screen {

    protected final TextRenderer text;
    protected final AppContext ctx;

    public Screen(final TextRenderer text, final AppContext ctx) {
        this.text = text;
        this.ctx = ctx;
    }

    public abstract void render(int windowW, int windowH);

    public abstract String instructions();

    public void onEnter() {
    }

    public void onExit() {
    }

    public void handleKey(final int key, final int action) {
        if (action == GLFW_RELEASE) {
            this.onKeyRelease(key);
        }
    }

    protected void onKeyRelease(final int key) {
    }

    public void handleMouseMove(final double mx, final double my) {
    }

    public void handleMouseClick(final double mx, final double my) {
    }

    public void handleScroll(final double yOffset) {
    }
}
