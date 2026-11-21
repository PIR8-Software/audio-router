package com.roy.bluetoothglassesrouter;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** FEASIBILITY spike mapper: real-row classification and fail-closed exact resolution. */
public class SpikeMapperTest {

    @Test public void selectableRowHasClickableMain() {
        SpikeMapper.Row r = SpikeMapper.row("This phone", 1, true, true, false, false);
        assertNotNull(r);
        assertTrue(r.selectable);
        assertFalse(r.sectionHeader);
    }

    @Test public void activeRowIsShownButNeverSelectable() {
        SpikeMapper.Row r = SpikeMapper.row("Paired output", 1, true, false, false, false);
        assertNotNull(r);
        assertFalse(r.selectable);
        assertFalse(r.sectionHeader);
    }

    @Test public void sliderRowIsMarkedCurrentButNeverSelectable() {
        // The active (routing) row carries its volume slider: identified as current, never a choice.
        SpikeMapper.Row r = SpikeMapper.row("Desk Headset", 1, true, false, false, true, true);
        assertNotNull(r);
        assertFalse(r.selectable);
        assertTrue(r.current);
    }

    @Test public void selectableRowIsNeverCurrent() {
        // A selectable (clickable) choice is never the active routing row.
        SpikeMapper.Row r = SpikeMapper.row("This phone", 1, true, true, false, false, true);
        assertNotNull(r);
        assertTrue(r.selectable);
        assertFalse(r.current);
    }

    @Test public void sectionHeaderIsLabeledAndNeverSelectable() {
        SpikeMapper.Row r = SpikeMapper.row("Speakers & Displays", 1, false, false, true, false);
        assertNotNull(r);
        assertFalse(r.selectable);
        assertTrue(r.sectionHeader);
    }

    @Test public void blockedRowIsShownButNeverSelectable() {
        // Slider/progress/checkbox/session controls present: real row, never a choice.
        SpikeMapper.Row r = SpikeMapper.row("Connecting output", 1, true, true, false, true);
        assertNotNull(r);
        assertFalse(r.selectable);
        assertFalse(r.sectionHeader);
    }

    @Test public void blockedRowWithoutContainerIsRefused() {
        assertNull(SpikeMapper.row("x", 1, false, false, true, true));
        assertNull(SpikeMapper.row("x", 1, false, true, false, true));
    }

    @Test public void itemClickableWithoutMainContentIsSelectable() {
        // Alternative verified structure: clickable item_layout carries the title itself.
        SpikeMapper.Row r = SpikeMapper.row("External Device", 1, true, true, false, false);
        assertNotNull(r);
        assertTrue(r.selectable);
    }

    @Test public void unknownOrMalformedStructuresAreRefused() {
        assertNull(SpikeMapper.row(null, 1, true, true, false, false));
        assertNull(SpikeMapper.row("", 1, true, true, false, false));
        assertNull(SpikeMapper.row("x", 2, true, true, false, false)); // ambiguous titles
        assertNull(SpikeMapper.row("x", 1, true, true, true, false));  // mixed structure
        assertNull(SpikeMapper.row("x", 1, false, false, false, false)); // neither main nor separator
        assertNull(SpikeMapper.row(new String(new char[129]).replace('\0', 'a'), 1, true, true, false, false));
    }

    @Test public void resolveRequiresExactUniqueSelectableMatch() {
        List<SpikeMapper.Row> rows = Arrays.asList(
                SpikeMapper.row("Paired output", 1, true, false, false, false),
                SpikeMapper.row("Speakers & Displays", 1, false, false, true, false),
                SpikeMapper.row("This phone", 1, true, true, false, false),
                SpikeMapper.row("External Device", 1, true, true, false, false),
                SpikeMapper.row("Connecting output", 1, true, true, false, true));
        assertEquals("This phone", SpikeMapper.resolve("This phone", rows).title);
        assertEquals("External Device", SpikeMapper.resolve("External Device", rows).title);
        // active row, header and blocked row are not choices
        assertNull(SpikeMapper.resolve("Paired output", rows));
        assertNull(SpikeMapper.resolve("Speakers & Displays", rows));
        assertNull(SpikeMapper.resolve("Connecting output", rows));
        // fail closed on unknown, empty, null
        assertNull(SpikeMapper.resolve("Connect Device", rows));
        assertNull(SpikeMapper.resolve("", rows));
        assertNull(SpikeMapper.resolve(null, rows));
        assertNull(SpikeMapper.resolve("This phone", Collections.<SpikeMapper.Row>emptyList()));
    }

    @Test public void duplicateLabelsAreAmbiguousAndRefused() {
        List<SpikeMapper.Row> rows = Arrays.asList(
                SpikeMapper.row("This phone", 1, true, true, false, false),
                SpikeMapper.row("This phone", 1, true, true, false, false));
        assertNull(SpikeMapper.resolve("This phone", rows));
    }

    @Test public void scopePicksTheSingleKnownContainer() {
        assertEquals("device_list", SpikeMapper.scope(1, 0));
        assertEquals("list_result", SpikeMapper.scope(0, 1));
    }

    @Test public void scopeWalksWholeDialogWhenNoKnownContainer() {
        assertEquals("dialogwalk", SpikeMapper.scope(0, 0));
    }

    @Test public void scopeRefusesDuplicateOrMixedContainers() {
        assertNull(SpikeMapper.scope(1, 1));
        assertNull(SpikeMapper.scope(2, 0));
        assertNull(SpikeMapper.scope(0, 2));
        assertNull(SpikeMapper.scope(2, 2));
    }
}
