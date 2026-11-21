package com.roy.bluetoothglassesrouter;

import org.junit.Test;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** Pure-JVM contracts for the native selector opener. No physical dock proof. */
public class SelectorOpenerTest {

    @Test public void externalDisplayWinsWithInternalFallback() {
        assertEquals(154, SelectorOpener.pickDisplay(setOf(154)));
        assertEquals(42, SelectorOpener.pickDisplay(setOf(154, 42))); // deterministic lowest id
        assertEquals(0, SelectorOpener.pickDisplay(setOf()));
        assertEquals(0, SelectorOpener.pickDisplay(null));
        assertEquals(0, SelectorOpener.pickDisplay(setOf(-1, 0)));
    }

    @Test public void rowIdentityGateAcceptsObservedShapes() {
        assertTrue(SelectorOpener.rowValid("com.android.settings", "Audio will play on", "This phone", true, true, true));
        assertTrue(SelectorOpener.rowValid("com.android.settings", "Play Sample Cast on", "Sample Headset", true, true, true));
        assertTrue(SelectorOpener.rowValid("com.android.settings", "Audio will play on", "External Device", true, true, true));
    }

    @Test public void rowIdentityGateFailsClosed() {
        assertFalse(SelectorOpener.rowValid("com.android.systemui", "Audio will play on", "X", true, true, true));
        assertFalse(SelectorOpener.rowValid("com.android.settings", "Media volume", "X", true, true, true));
        assertFalse(SelectorOpener.rowValid("com.android.settings", "Call volume", "X", true, true, true));
        assertFalse(SelectorOpener.rowValid("com.android.settings", "Play on", "X", true, true, true));
        assertFalse(SelectorOpener.rowValid("com.android.settings", "Audio will play on", "", true, true, true));
        assertFalse(SelectorOpener.rowValid("com.android.settings", "", "X", true, true, true));
        assertFalse(SelectorOpener.rowValid("com.android.settings", null, "X", true, true, true));
        assertFalse(SelectorOpener.rowValid("com.android.settings", "Audio will play on", null, true, true, true));
        assertFalse(SelectorOpener.rowValid("com.android.settings", "Play Sample Cast on", "X", false, true, true));
        assertFalse(SelectorOpener.rowValid("com.android.settings", "Play Sample Cast on", "X", true, false, true));
        assertFalse(SelectorOpener.rowValid("com.android.settings", "Play Sample Cast on", "X", true, true, false));
    }

    @Test public void rowLabelShapeRecognizesPanelOutputRowsEvenWhenInert() {
        assertTrue(SelectorOpener.rowLabel("Audio will play on"));
        assertTrue(SelectorOpener.rowLabel("Play Sample Cast on"));
        assertTrue(SelectorOpener.rowLabel("Audio will play on")); // inert-row observation gate
        assertFalse(SelectorOpener.rowLabel("Media volume"));
        assertFalse(SelectorOpener.rowLabel("Play on"));
        assertFalse(SelectorOpener.rowLabel(""));
        assertFalse(SelectorOpener.rowLabel(null));
    }

    @Test public void stepBudgetBoundsRowSearch() {
        SelectorOpener o = new SelectorOpener();
        assertFalse(o.stepping(0));
        o.begin(1000);
        assertTrue(o.stepping(1000));
        assertTrue(o.stepping(1000 + SelectorOpener.STEP_BUDGET_MS));
        assertFalse(o.stepping(1001 + SelectorOpener.STEP_BUDGET_MS));
        o.end();
        assertFalse(o.stepping(1500));
    }

    @Test public void rowBudgetExpiryGatesThePickerFallback() {
        SelectorOpener o = new SelectorOpener();
        assertFalse("no budget armed: fallback never due", o.expired(5000));
        o.begin(1000);
        assertFalse(o.expired(1000));
        assertFalse(o.expired(1000 + SelectorOpener.STEP_BUDGET_MS)); // still in budget
        assertTrue("budget burned while armed: fallback due exactly once", o.expired(1001 + SelectorOpener.STEP_BUDGET_MS));
        o.end();
        assertFalse("ended (row tapped): fallback never due", o.expired(99999));
    }

    private static Set<Integer> setOf(Integer... ids) {
        return ids.length == 0 ? new LinkedHashSet<Integer>() : new LinkedHashSet<>(Arrays.asList(ids));
    }
}
