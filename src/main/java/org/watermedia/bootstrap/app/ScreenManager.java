package org.watermedia.bootstrap.app;

import org.watermedia.bootstrap.app.screen.Screen;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages screen navigation.
 */
public class ScreenManager {

    private final Map<String, Screen> screens = new HashMap<>();
    private Screen current;
    private String currentName;

    public void register(final String name, final Screen screen) {
        this.screens.put(name, screen);
    }

    public void navigate(final String screenName) {
        if (screenName == null) {
            this.backToHome();
            return;
        }
        this.navigateTo(screenName);
    }

    private void navigateTo(final String screenName) {
        final Screen next = this.screens.get(screenName);
        if (next == null) return;

        if (this.current != null) {
            this.current.onExit();
        }

        this.currentName = screenName;
        this.current = next;
        this.current.onEnter();
    }

    public void backToHome() {
        if (this.current != null) {
            this.current.onExit();
        }
        this.currentName = "home";
        this.current = this.screens.get("home");
        if (this.current != null) {
            this.current.onEnter();
        }
    }

    public Screen current() {
        return this.current;
    }

    public String currentName() {
        return this.currentName;
    }

    public void render(final int windowW, final int windowH) {
        if (this.current != null) {
            this.current.render(windowW, windowH);
        }
    }

    public void handleKey(final int key, final int action) {
        if (this.current != null) {
            this.current.handleKey(key, action);
        }
    }

    public void handleMouseMove(final double mx, final double my) {
        if (this.current != null) {
            this.current.handleMouseMove(mx, my);
        }
    }

    public void handleMouseClick(final double mx, final double my) {
        if (this.current != null) {
            this.current.handleMouseClick(mx, my);
        }
    }

    public void handleScroll(final double yOffset) {
        if (this.current != null) {
            this.current.handleScroll(yOffset);
        }
    }

    public String currentInstructions() {
        return this.current != null ? this.current.instructions() : "";
    }
}
