package org.watermedia.bootstrap.app.element;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.watermedia.bootstrap.app.ui.Gravity;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how a {@link ListView} turns hover, click and keyboard input into selection and
 * activation: hover selects without activating, clicks activate, the keyboard scrolls the selection
 * into view, and hidden, disabled or clipped rows never receive events.
 */
@DisplayName("ListView interaction")
public class ListViewInteractionTest {

    @Nested
    @DisplayName("Hover and click")
    class HoverAndClick {

        @Test
        @DisplayName("hover selects rows without activating them, clicks activate the hovered row")
        void hoverSelectsRowsWithoutActivatingThem() {
            final List<String> activations = new ArrayList<>();
            final ListView<String> list = list(activations).selectOnHover(true);

            assertTrue(list.dispatchHover(20, 20));
            assertEquals("first", list.selectedItem());
            assertTrue(list.children().get(0).hovered());
            assertTrue(list.dispatchHover(20, 60));
            assertEquals("second", list.selectedItem());
            assertFalse(list.children().get(0).hovered());
            assertTrue(list.children().get(1).hovered());
            list.dispatchHover(20, 60);
            list.dispatchHover(20, 20);
            assertEquals(List.of(), activations);

            assertTrue(list.dispatchClick(20, 20));
            assertTrue(list.dispatchClick(20, 20));
            assertEquals(List.of("first", "first"), activations);
        }

        @Test
        @DisplayName("hover keeps the selection when hover selection is disabled")
        void hoverKeepsSelectionWhenHoverSelectionIsDisabled() {
            final List<String> activations = new ArrayList<>();
            final ListView<String> list = list(activations).selection(0);

            assertTrue(list.dispatchHover(20, 60));
            assertTrue(list.children().get(1).hovered());
            assertEquals("first", list.selectedItem());
            assertEquals(List.of(), activations);
        }

        @Test
        @DisplayName("hovering the controls inside a row does not change their values, clicking does")
        void hoveringSettingsControlsDoesNotChangeTheirValues() {
            final List<String> changes = new ArrayList<>();
            final Switch toggle = new Switch().size(40, 20).gravity(Gravity.RIGHT)
                    .onChange(value -> changes.add("toggle"));
            final SegmentedControl options = new SegmentedControl().segments(new String[] { "A", "B", "C" })
                    .size(120, 20).gravity(Gravity.RIGHT).onSelect(index -> changes.add("option"));
            final ListView<String> list = new ListView<String>().rowHeight(40).selectOnHover(true)
                    .rowFactory((item, index) -> new ParentFrame().add(index == 0 ? toggle : options))
                    .items(List.of("toggle", "option"))
                    .onSelect((item, index) -> {
                        changes.add(item);
                        if (index == 0) toggle.on(!toggle.on());
                        else options.selected((options.selectedIndex() + 1) % 3);
                    }).size(200, 80);
            list.measure(200, 80);
            list.layout(10, 10);

            list.dispatchHover(180, 30);
            list.dispatchHover(150, 70);
            assertFalse(toggle.on());
            assertEquals(0, options.selectedIndex());
            assertEquals(List.of(), changes);

            assertTrue(list.dispatchClick(180, 30));
            assertTrue(toggle.on());
            assertTrue(list.dispatchClick(150, 70));
            assertEquals(1, options.selectedIndex());
            assertEquals(List.of("toggle", "option"), changes);

            assertTrue(list.dispatchClick(20, 70));
            assertEquals(2, options.selectedIndex());
            assertEquals(List.of("toggle", "option", "option"), changes);
        }
    }

    @Nested
    @DisplayName("Keyboard")
    class Keyboard {

        @Test
        @DisplayName("keyboard selection scrolls the row into view without activating it")
        void keyboardSelectionScrollsWithoutActivatingRows() {
            final List<String> activations = new ArrayList<>();
            final ListView<String> list = list(activations).selection(0);

            list.moveSelection(2);
            list.layout(10, 10);
            assertEquals("third", list.selectedItem());
            assertTrue(list.children().get(2).top() >= list.innerTop());
            assertTrue(list.children().get(2).top() + list.children().get(2).measuredHeight()
                    <= list.innerTop() + list.innerHeight());
            list.moveSelection(1);
            assertEquals("third", list.selectedItem());
            assertEquals(List.of(), activations);

            assertTrue(list.dispatchClick(20, 60));
            assertEquals(List.of("third"), activations);
        }
    }

    @Nested
    @DisplayName("Inactive rows")
    class Inactive {

        @Test
        @DisplayName("leaving the viewport clears the hover without sending events to clipped rows")
        void leavingViewportClearsHoverWithoutSendingEventsToClippedRows() {
            final List<String> activations = new ArrayList<>();
            final List<String> hovered = new ArrayList<>();
            final ListView<String> list = list(activations).selectOnHover(true);
            list.rowFactory((item, index) -> new Box().onHover(row -> hovered.add(item)));
            list.measure(200, 80);
            list.moveSelection(2);
            list.layout(-10, 0);
            list.dispatchHover(20, 20);
            assertTrue(list.children().get(1).hovered());
            hovered.clear();

            assertFalse(list.dispatchHover(300, 300));
            assertEquals(List.of(), hovered);
            assertTrue(list.children().stream().noneMatch(Element::hovered));
            assertFalse(list.dispatchClick(20, -10));
        }

        @ParameterizedTest(name = "hidden={0}")
        @ValueSource(booleans = { true, false })
        @DisplayName("hidden and disabled lists ignore hover and click")
        void inactiveListsIgnoreHoverAndClick(final boolean hidden) {
            final List<String> activations = new ArrayList<>();
            final ListView<String> list = list(activations).selectOnHover(true);
            list.dispatchHover(20, 20);
            list.selection(1);
            activations.clear();
            if (hidden) list.visible(false);
            else list.enabled(false);

            assertFalse(list.dispatchHover(20, 20));
            assertFalse(list.dispatchClick(20, 20));
            assertEquals("second", list.selectedItem());
            assertTrue(list.children().stream().noneMatch(Element::hovered));
            assertEquals(List.of(), activations);
        }
    }

    @Nested
    @DisplayName("Long lists")
    class LongLists {

        @Test
        @DisplayName("only the rows near the viewport are children, and scrolling moves that window")
        void onlyRowsNearTheViewportAreChildren() {
            final List<String> items = new ArrayList<>();
            for (int i = 0; i < 500; i++) items.add("item" + i);
            final List<Integer> updated = new ArrayList<>();
            final ListView<String> list = new ListView<String>().rowHeight(40)
                    .rowFactory((item, index) -> new BoundRow(() -> updated.add(index)))
                    .items(items).size(200, 80);
            list.measure(200, 80);
            list.layout(0, 0);
            list.update();
            assertTrue(list.children().size() <= 4, "children: " + list.children().size());
            assertTrue(updated.stream().allMatch(index -> index < 4), "updated: " + updated);

            list.selection(0).moveSelection(300);
            list.update();
            list.measure(200, 80);
            list.layout(0, 0);
            final Element<?> selected = list.children().stream().filter(row -> row.top() >= 0 && row.top() < 80)
                    .reduce((first, second) -> second).orElseThrow();
            assertTrue(selected.top() + selected.measuredHeight() <= 80);
            assertTrue(list.children().size() <= 5, "children: " + list.children().size());

            final List<String> activations = new ArrayList<>();
            list.onSelect((item, index) -> activations.add(item));
            assertTrue(list.dispatchClick(20, 60));
            assertEquals(List.of("item300"), activations);
        }
    }

    // UTILITY

    private static final class BoundRow extends Element<BoundRow> {
        private final Runnable binder;

        private BoundRow(final Runnable binder) {
            this.binder = binder;
        }

        @Override
        protected void onUpdate() {
            this.binder.run();
        }
    }

    private static ListView<String> list(final List<String> activations) {
        final ListView<String> list = new ListView<String>().rowHeight(40)
                .rowFactory((item, index) -> new Box())
                .items(List.of("first", "second", "third"))
                .onSelect((item, index) -> activations.add(item))
                .size(200, 80);
        list.measure(200, 80);
        list.layout(10, 10);
        return list;
    }
}
