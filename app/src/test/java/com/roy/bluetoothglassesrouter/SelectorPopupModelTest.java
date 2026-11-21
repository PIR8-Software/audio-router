package com.roy.bluetoothglassesrouter;

import org.junit.Test;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pure-JVM contracts for the popup render model: selection rows, current row
 * marking, stale-row safety labels, loading/empty states and render wiring data.
 * The model only maps REAL enumerated rows - no invented/demo destinations.
 */
public class SelectorPopupModelTest {

    private static SpikeMapper.Row row(String title, boolean selectable, boolean section, boolean current) {
        return new SpikeMapper.Row(title, selectable, section, current);
    }

    @Test public void compactWindowBoundsStayDialogSized() {
        //  - the window is a
        // compact floating dialog (360-420dp width, bounded scroll height, dim scrim).
        assertTrue("width stays in the requested 360-420dp dialog range",
                SelectorPopupModel.MAX_WIDTH_DP >= 360 && SelectorPopupModel.MAX_WIDTH_DP <= 420);
        assertTrue("height is bounded (list scrolls inside), never full screen",
                SelectorPopupModel.MAX_HEIGHT_DP > 0 && SelectorPopupModel.MAX_HEIGHT_DP <= 640);
        assertTrue("dim scrim is dialog-like, not a full black takeover",
                SelectorPopupModel.SCRIM_DIM > 0f && SelectorPopupModel.SCRIM_DIM <= 0.6f);
    }

    @Test public void selectableRowsAreFullRowDestinationItems() {
        List<SelectorPopupModel.Item> items = SelectorPopupModel.items(
                Collections.singletonList(row("This phone", true, false, false)), false);
        assertEquals(1, items.size());
        assertEquals(SelectorPopupModel.Kind.SELECTABLE, items.get(0).kind);
        assertEquals("This phone", items.get(0).title);
    }

    @Test public void currentRowIsClearlyMarkedAndNeverSelectable() {
        // current wins even if a mapper ever marks the active row selectable too
        List<SelectorPopupModel.Item> items = SelectorPopupModel.items(
                Collections.singletonList(row("Desk Headset", true, false, true)), false);
        assertEquals(SelectorPopupModel.Kind.CURRENT, items.get(0).kind);
        assertNotEquals(SelectorPopupModel.Kind.SELECTABLE, items.get(0).kind);
        assertEquals(SelectorPopupModel.CURRENT_NOW, items.get(0).secondary);
    }

    @Test public void currentRowNoteDistinguishesLiveFromSnapshot() {
        SelectorPopupModel.Item live = SelectorPopupModel.items(
                Collections.singletonList(row("Desk Headset", false, false, true)), false).get(0);
        SelectorPopupModel.Item stale = SelectorPopupModel.items(
                Collections.singletonList(row("Desk Headset", false, false, true)), true).get(0);
        assertTrue(live.secondary.contains("routing now"));
        assertTrue(stale.secondary.contains("revalidate before relying on it"));
    }

    @Test public void staleRowsStayVisibleWithRevalidateActionLabel() {
        List<SpikeMapper.Row> rows = Collections.singletonList(row("External Device", true, false, false));
        SelectorPopupModel.Item stale = SelectorPopupModel.items(rows, true).get(0);
        SelectorPopupModel.Item fresh = SelectorPopupModel.items(rows, false).get(0);
        assertEquals("stale rows are marked, never hidden",
                SelectorPopupModel.items(rows, false).size(), SelectorPopupModel.items(rows, true).size());
        assertEquals(SelectorPopupModel.Kind.SELECTABLE, stale.kind);
        assertTrue("stale tap revalidates before any native row click",
                stale.secondary.contains("Revalidate & select"));
        assertTrue("fresh rows carry no revalidate label", fresh.secondary.isEmpty());
    }

    @Test public void emptyRowsGiveActionableEmptyMessage() {
        String msg = SelectorPopupModel.emptyMessage(Collections.<SpikeMapper.Row>emptyList());
        assertTrue(msg.contains("No output rows enumerated"));
        assertTrue(msg.contains("Tap Refresh outputs"));
        assertTrue(msg.contains("No fake choices shown"));
    }

    @Test public void rowsWithoutSelectableRowsGiveNoSelectableMessage() {
        String msg = SelectorPopupModel.emptyMessage(Arrays.asList(
                row("Speakers & Displays", false, true, false),
                row("Desk Headset", false, false, true),
                row("Busy row", false, false, false)));
        assertTrue(msg.contains("No selectable rows enumerated"));
        assertTrue(msg.contains("No fake choices shown"));
    }

    @Test public void selectableRowsSuppressEmptyMessage() {
        assertEquals("", SelectorPopupModel.emptyMessage(
                Collections.singletonList(row("This phone", true, false, false))));
    }

    @Test public void loadingStateMessageIsActionable() {
        assertNotNull(SelectorPopupModel.LOADING_MESSAGE);
        assertTrue(SelectorPopupModel.LOADING_MESSAGE.contains("Loading outputs"));
        assertTrue(SelectorPopupModel.LOADING_MESSAGE.contains("native picker"));
    }

    @Test public void unselectableRowsKeepPersistentNotSelectableLabel() {
        SelectorPopupModel.Item item = SelectorPopupModel.items(
                Collections.singletonList(row("Busy row", false, false, false)), false).get(0);
        assertEquals(SelectorPopupModel.Kind.UNSELECTABLE, item.kind);
        assertTrue(item.secondary.contains("Not selectable"));
    }

    @Test public void sectionHeadersAreSectionsAndNeverSelectable() {
        SelectorPopupModel.Item item = SelectorPopupModel.items(
                Collections.singletonList(row("Speakers & Displays", false, true, false)), false).get(0);
        assertEquals(SelectorPopupModel.Kind.SECTION, item.kind);
    }

    @Test public void titlesIncludeEveryShownRowInOrder() {
        List<String> titles = SelectorPopupModel.titles(Arrays.asList(
                row("External Device", true, false, false),
                row("Speakers & Displays", false, true, false),
                row("This phone", true, false, false),
                row("Desk Headset", false, false, true)));
        assertEquals(Arrays.asList("External Device", "Speakers & Displays", "This phone", "Desk Headset"), titles);
    }

    @Test public void nullInputsAreToleratedWithoutInventedRows() {
        assertTrue(SelectorPopupModel.items(null, false).isEmpty());
        assertTrue(SelectorPopupModel.titles(null).isEmpty());
        assertEquals(SelectorPopupModel.EMPTY_MESSAGE, SelectorPopupModel.emptyMessage(null));
        assertTrue(SelectorPopupModel.items(
                Arrays.asList(null, row("This phone", true, false, false)), false).size() == 1);
    }

    @Test public void iconMappingFollowsGenericDeviceTerminology() {
        assertEquals(SelectorPopupModel.Icon.PHONE, SelectorPopupModel.iconFor("This phone"));
        assertEquals(SelectorPopupModel.Icon.HEADPHONES, SelectorPopupModel.iconFor("Desk Headset"));
        assertEquals(SelectorPopupModel.Icon.DISPLAY, SelectorPopupModel.iconFor("External Device"));
        assertEquals(SelectorPopupModel.Icon.SPEAKER, SelectorPopupModel.iconFor("Kitchen speaker"));
        assertEquals(SelectorPopupModel.Icon.GENERIC, SelectorPopupModel.iconFor("Mystery Box 9000"));
        assertEquals(SelectorPopupModel.Icon.GENERIC, SelectorPopupModel.iconFor(null));
    }
}