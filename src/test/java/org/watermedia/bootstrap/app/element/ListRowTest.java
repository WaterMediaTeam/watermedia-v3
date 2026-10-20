package org.watermedia.bootstrap.app.element;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies how a {@link ListRow} places its children, binds live state and decides whether it draws as selected. */
@DisplayName("ListRow")
public class ListRowTest {

    @Nested
    @DisplayName("Placement")
    class Placement {

        @Test
        @DisplayName("children sit from the left edge, from the right edge when negative, or centered")
        void places() {
            final Box left = new Box().size(10, 10);
            final Box right = new Box().size(26, 26);
            final ListRow row = new ListRow().place(left, 12, ListRow.CENTER).place(right, -44, 14).size(300, 56);
            row.measure(300, 56);
            row.layout(100, 20);
            assertEquals(112, left.left());
            assertEquals(20 + 23, left.top(), "a 10 px child centers in a 56 px row");
            assertEquals(100 + 300 - 44, right.left());
            assertEquals(34, right.top());
        }
    }

    @Nested
    @DisplayName("State")
    class State {

        @Test
        @DisplayName("the binder runs on every update, before measure")
        void binds() {
            final List<String> calls = new ArrayList<>();
            final ListRow row = new ListRow().binder(r -> calls.add("bind"));
            row.update();
            row.update();
            assertEquals(List.of("bind", "bind"), calls);
        }

        @Test
        @DisplayName("a picked supplier overrides the list's selection stamp")
        void picked() {
            final ListRow listed = new ListRow();
            assertFalse(listed.isPicked());
            listed.selected(true);
            assertTrue(listed.isPicked());

            final boolean[] lod = {false};
            final ListRow free = new ListRow().picked(() -> lod[0]);
            free.selected(true);
            assertFalse(free.isPicked(), "a row outside a list ignores a stale stamp");
            lod[0] = true;
            assertTrue(free.isPicked());
        }
    }
}
