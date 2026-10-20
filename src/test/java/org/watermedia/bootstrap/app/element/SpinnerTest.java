package org.watermedia.bootstrap.app.element;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

/** Verifies that a {@link Spinner}'s typed edit commits, cancels and erases without leaking key releases to the screen. */
@DisplayName("Spinner")
public class SpinnerTest {

    @Nested
    @DisplayName("Typed edit")
    class TypedEdit {

        @Test
        @DisplayName("ENTER commits the typed number and its release stays in the spinner")
        void commits() {
            final List<Double> changes = new ArrayList<>();
            final Spinner spinner = editing(new Spinner().range(0, 100).onChange(changes::add));
            assertTrue(spinner.dispatchChar('4'));
            assertTrue(spinner.dispatchChar('2'));
            assertTrue(spinner.dispatchKey(GLFW_KEY_ENTER, GLFW_PRESS));
            assertTrue(spinner.dispatchKey(GLFW_KEY_ENTER, GLFW_RELEASE), "the release would activate the row again");
            assertFalse(spinner.textInputActive());
            assertEquals(List.of(42d), changes);
        }

        @Test
        @DisplayName("ESC cancels the edit and its release never reaches the screen's back shortcut")
        void cancels() {
            final Spinner spinner = editing(new Spinner().range(0, 100).value(7));
            assertTrue(spinner.dispatchChar('9'));
            assertTrue(spinner.dispatchKey(GLFW_KEY_ESCAPE, GLFW_PRESS));
            assertTrue(spinner.dispatchKey(GLFW_KEY_ESCAPE, GLFW_RELEASE), "the release would leave the screen");
            assertEquals(7d, spinner.value());
        }

        @Test
        @DisplayName("BACKSPACE and DELETE both erase the last typed character")
        void erases() {
            final List<Double> changes = new ArrayList<>();
            final Spinner spinner = editing(new Spinner().range(0, 1_000).onChange(changes::add));
            spinner.dispatchChar('5');
            spinner.dispatchChar('6');
            assertTrue(spinner.dispatchKey(GLFW_KEY_DELETE, GLFW_PRESS));
            assertTrue(spinner.dispatchKey(GLFW_KEY_DELETE, GLFW_RELEASE));
            assertTrue(spinner.dispatchKey(GLFW_KEY_BACKSPACE, GLFW_PRESS));
            assertTrue(spinner.dispatchKey(GLFW_KEY_BACKSPACE, GLFW_RELEASE));
            spinner.dispatchKey(GLFW_KEY_ENTER, GLFW_PRESS);
            assertEquals(List.of(), changes, "\"056\" erased down to \"0\" commits the same value");
        }

        @Test
        @DisplayName("keys reach the screen while the spinner is not editing")
        void idle() {
            final Spinner spinner = laidOut(new Spinner());
            assertFalse(spinner.dispatchKey(GLFW_KEY_ESCAPE, GLFW_PRESS));
            assertFalse(spinner.dispatchKey(GLFW_KEY_ESCAPE, GLFW_RELEASE));
            assertFalse(spinner.dispatchChar('1'));
        }
    }

    // UTILITY

    private static Spinner laidOut(final Spinner spinner) {
        spinner.size(200, 30);
        spinner.measure(200, 30);
        spinner.layout(0, 0);
        return spinner;
    }

    // A CLICK ON THE VALUE, BETWEEN THE 24 PX STEP BOXES, OPENS THE TYPED EDIT
    private static Spinner editing(final Spinner spinner) {
        laidOut(spinner).dispatchClick(100, 15);
        assertTrue(spinner.textInputActive());
        return spinner;
    }
}
