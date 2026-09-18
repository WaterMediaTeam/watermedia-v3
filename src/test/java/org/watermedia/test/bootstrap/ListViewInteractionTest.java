package org.watermedia.test.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.watermedia.bootstrap.app.element.Box;
import org.watermedia.bootstrap.app.element.ListView;
import org.watermedia.bootstrap.app.element.ParentFrame;
import org.watermedia.bootstrap.app.element.SegmentedControl;
import org.watermedia.bootstrap.app.element.Switch;
import org.watermedia.bootstrap.app.ui.Gravity;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListViewInteractionTest {

    @Test
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
    void hoverKeepsSelectionWhenHoverSelectionIsDisabled() {
        final List<String> activations = new ArrayList<>();
        final ListView<String> list = list(activations).selection(0);

        assertTrue(list.dispatchHover(20, 60));
        assertTrue(list.children().get(1).hovered());
        assertEquals("first", list.selectedItem());
        assertEquals(List.of(), activations);
    }

    @Test
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

    @Test
    void hoveringSettingsControlsDoesNotChangeTheirValues() {
        final List<String> changes = new ArrayList<>();
        final Switch toggle = new Switch().size(40, 20).gravity(Gravity.RIGHT)
                .onChange(value -> changes.add("toggle"));
        final SegmentedControl options = new SegmentedControl().segments(new String[]{"A", "B", "C"})
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

    @Test
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
        assertTrue(list.children().stream().noneMatch(row -> row.hovered()));
        assertFalse(list.dispatchClick(20, -10));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
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
        assertTrue(list.children().stream().noneMatch(row -> row.hovered()));
        assertEquals(List.of(), activations);
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
