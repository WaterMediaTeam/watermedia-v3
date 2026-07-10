package org.watermedia.bootstrap.app;

import org.watermedia.bootstrap.app.element.Element;
import org.watermedia.bootstrap.app.screen.Keybind;
import org.watermedia.bootstrap.app.screen.RootScreen;
import org.watermedia.bootstrap.app.screen.Screen;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Manages screen navigation. Every screen is a fully-retained {@link Screen} mounted directly into the
 * window shell's center slot; navigation runs the exit/enter lifecycle and mounts the target (optionally
 * stacked over the underlay a screen declares through {@link Screen#under()}).
 */
public class ScreenManager {

    private final Map<String, Screen> screens = new HashMap<>();
    private RootScreen root;
    private Screen current;
    private String currentName;
    private BiConsumer<String, String> onNavigate;

    public void register(final String name, final Screen screen) {
        this.screens.put(name, screen);
    }

    /** Hands the window shell over; every navigation mounts the target screen into its center slot. */
    public void root(final RootScreen root) {
        this.root = root;
    }

    /**
     * Registers a listener fired after every effective navigation with (previousName, nextName). The
     * window shell uses it to react to screen switches without owning the navigation itself.
     */
    public void onNavigate(final BiConsumer<String, String> listener) {
        this.onNavigate = listener;
    }

    public void navigate(final String screenName) {
        if (screenName == null) {
            this.backToHome();
            return;
        }
        final Screen next = this.screens.get(screenName);
        if (next == null) return;
        // NAVIGATING TO THE ALREADY-CURRENT SCREEN MUST NOT RE-RUN onExit/onEnter (WHICH RESET ITS STATE)
        if (screenName.equals(this.currentName)) return;

        final String previous = this.currentName;
        if (this.current != null) this.current.onExit();
        this.currentName = screenName;
        this.current = next;
        this.current.onEnter();
        this.mountCurrent();
        if (this.onNavigate != null) {
            this.onNavigate.accept(previous, screenName);
        }
    }

    public void backToHome() {
        final String previous = this.currentName;
        if (this.current != null) this.current.onExit();
        this.currentName = "home";
        this.current = this.screens.get("home");
        if (this.current != null) this.current.onEnter();
        this.mountCurrent();
        if (this.onNavigate != null && this.current != null) {
            this.onNavigate.accept(previous, this.currentName);
        }
    }

    private void mountCurrent() {
        if (this.root == null || this.current == null) return;
        // A SCREEN DECLARING under() KEEPS THAT SCREEN MOUNTED (AND ANIMATING) BENEATH IT; THE TOP
        // MOUNT'S FULL-SLOT HIT BOX BLOCKS EVERY POINTER EVENT FROM REACHING THE UNDERLAY
        final Screen under = this.current.under() != null ? this.screens.get(this.current.under()) : null;
        if (under != null && under != this.current) {
            this.root.mountUnder(under, this.current);
        } else {
            this.root.mount(this.current);
        }
    }

    public String currentName() {
        return this.currentName;
    }

    /** The footer shortcuts of the active screen; empty when no screen is active. */
    public List<Keybind> currentKeybinds() {
        return this.current != null ? this.current.keybinds() : List.of();
    }

    /** Runs the media-release hook on every registered screen (engine hot-swap support). */
    public void releaseMediaAll() {
        for (final Screen screen: this.screens.values()) screen.releaseMedia();
    }

    /** Whether the active screen animates every frame. */
    public boolean continuous() {
        return this.current != null && this.current.continuous();
    }

    /** Whether a text input on the active screen holds keyboard focus. */
    public boolean textInputActive() {
        return this.current != null && this.current.textInputActive();
    }
}
