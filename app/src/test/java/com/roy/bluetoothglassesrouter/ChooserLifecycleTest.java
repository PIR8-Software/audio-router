package com.roy.bluetoothglassesrouter;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Source-close lifecycle regression for the external selector: the popup can only
 * enumerate REAL rows while the native output picker is open, so (1) an initially
 * closed source must auto-acquire the picker BEFORE enumeration, (2) a stale
 * snapshot may be shown but never as validated destinations, and (3) selection
 * against a closed/changed source must revalidate (reacquire + compare) before any
 * click. Idle-picker fixture (tested system image API 37): items=4
 * rows=3 -> Desk Headset (current, slider), Speakers &amp; Displays (refused, no
 * main/separator), This phone + External Device (selectable).
 */
public class ChooserLifecycleTest {

    // ---- plan: closed source -> acquire first, never enumerate blind ----

    @Test public void openSourceEnumeratesLiveImmediately() {
        assertEquals(ChooserLifecycle.Plan.ENUMERATE_LIVE, ChooserLifecycle.plan(true, true));
        assertEquals(ChooserLifecycle.Plan.ENUMERATE_LIVE, ChooserLifecycle.plan(true, false));
    }

    @Test public void initiallyClosedSourceAcquiresBeforeEnumerating() {
        // Regression "source initially closed -> native open -> list populated":
        // the plan must be ACQUIRE_THEN_ENUMERATE so enumeration runs after the
        // native picker is open, not before.
        assertEquals(ChooserLifecycle.Plan.ACQUIRE_THEN_ENUMERATE, ChooserLifecycle.plan(false, true));
    }

    @Test public void acquisitionImpossibleFallsBackToMarkedSnapshot() {
        assertEquals(ChooserLifecycle.Plan.FALLBACK_SNAPSHOT, ChooserLifecycle.plan(false, false));
    }

    // ---- gate: stale selection safety ----

    @Test public void closedSourceSelectionMustRevalidateBeforeAnyClick() {
        assertEquals(ChooserLifecycle.Gate.REVALIDATE_THEN_CLICK,
                ChooserLifecycle.gate(false, Arrays.asList("This phone"), Arrays.asList("This phone")));
    }

    @Test public void reopenedWithSameListAllowsTheClick() {
        assertEquals(ChooserLifecycle.Gate.CLICK_OK,
                ChooserLifecycle.gate(true, Arrays.asList("A", "B"), Arrays.asList("B", "A")));
    }

    @Test public void reopenedWithChangedListRefusesStaleSelection() {
        // Closed/reopened stale selection safety: titles moved on -> refuse, never
        // click a row the user was not shown.
        assertEquals(ChooserLifecycle.Gate.REFUSE_STALE,
                ChooserLifecycle.gate(true, Arrays.asList("This phone"), Arrays.asList("This phone", "External Device")));
        assertEquals(ChooserLifecycle.Gate.REFUSE_STALE,
                ChooserLifecycle.gate(true, Arrays.asList("External Device", "This phone"), Arrays.asList("This phone")));
    }

    @Test public void nullShownTitlesFallsBackToResolveOnlyWhenOpen() {
        assertEquals(ChooserLifecycle.Gate.CLICK_OK, ChooserLifecycle.gate(true, Arrays.asList("A"), null));
        assertEquals(ChooserLifecycle.Gate.REVALIDATE_THEN_CLICK, ChooserLifecycle.gate(false, Arrays.asList("A"), null));
    }

    // ---- snapshot codec: marked-stale rows must round-trip exactly ----

    @Test public void snapshotRowsRoundTripWithFlags() {
        List<SpikeMapper.Row> rows = Arrays.asList(
                new SpikeMapper.Row("Desk Headset", false, false, true),
                new SpikeMapper.Row("This phone", true, false, false),
                new SpikeMapper.Row("External Device", true, false, false));
        List<SpikeMapper.Row> back = ChooserLifecycle.decode(ChooserLifecycle.encode(rows));
        assertEquals(3, back.size());
        assertEquals("Desk Headset", back.get(0).title);
        assertFalse(back.get(0).selectable);
        assertTrue(back.get(0).current);
        assertTrue(back.get(1).selectable);
        assertFalse(back.get(1).current);
        assertEquals("External Device", back.get(2).title);
    }

    @Test public void malformedSnapshotIsRefusedFailClosed() {
        assertTrue(ChooserLifecycle.decode("").isEmpty());
        assertTrue(ChooserLifecycle.decode(null).isEmpty());
        assertTrue(ChooserLifecycle.decode("no-tokens").isEmpty());
        assertTrue(ChooserLifecycle.decode("title\u0001notabool\u0001false\u0001false").isEmpty());
    }

    // ---- idle-picker fixture (structure pin) ----

    @Test public void idleVolumePanelPickerRealCaptureClassifiesRows() {
        // Idle-picker fixture (no media session):
        // dialog=1 scope=list_result items=4 rows=3
        // flags=[t1,m1,c0,bi,s0] [t1,m0,c0,b-,s0] [t1,m1,c1,b-,s0] [t1,m1,c1,b-,s0]
        SpikeMapper.Row current = SpikeMapper.row("Desk Headset", 1, true, false, false, true, true);
        SpikeMapper.Row section  = SpikeMapper.row("Speakers & Displays", 1, false, false, false, false, false);
        SpikeMapper.Row phone    = SpikeMapper.row("This phone", 1, true, true, false, false, false);
        SpikeMapper.Row external = SpikeMapper.row("External Device", 1, true, true, false, false, false);
        assertNotNull(current);
        assertTrue(current.current);
        assertFalse(current.selectable);
        assertNull("section item refused fail-closed (no main, no separator)", section);
        assertTrue(phone.selectable);
        assertTrue(external.selectable);
        List<SpikeMapper.Row> rows = Arrays.asList(current, phone, external);
        assertEquals("This phone", SpikeMapper.resolve("This phone", rows).title);
        assertEquals("External Device", SpikeMapper.resolve("External Device", rows).title);
        assertNull(SpikeMapper.resolve("Desk Headset", rows)); // current row is never a choice
    }

    @Test public void emptySnapshotEncodeDecodeIsEmpty() {
        assertEquals("", ChooserLifecycle.encode(Collections.<SpikeMapper.Row>emptyList()));
        assertTrue(ChooserLifecycle.decode("").isEmpty());
    }

    // ---- acquireStep: never stack a second panel; reuse the verified row on screen ----

    @Test public void openPickerEnumeratesImmediately() {
        assertEquals(ChooserLifecycle.AcquireStep.ENUMERATE_NOW, ChooserLifecycle.acquireStep(true, true, true));
        assertEquals(ChooserLifecycle.AcquireStep.ENUMERATE_NOW, ChooserLifecycle.acquireStep(true, false, true));
    }

    @Test public void verifiedPanelRowOnScreenIsReusedNeverRelaunched() {
        // live regression: relaunching the panel stacked identical instances and
        // the fail-closed row guard then refused every tap. With a verified row already
        // on screen the step must be TAP_EXISTING_PANEL_ROW (no second panel launch).
        assertEquals(ChooserLifecycle.AcquireStep.TAP_EXISTING_PANEL_ROW, ChooserLifecycle.acquireStep(false, true, true));
        assertEquals(ChooserLifecycle.AcquireStep.TAP_EXISTING_PANEL_ROW, ChooserLifecycle.acquireStep(false, true, false));
    }

    @Test public void noPanelRowLaunchesPanelThenTaps() {
        assertEquals(ChooserLifecycle.AcquireStep.LAUNCH_PANEL_THEN_TAP, ChooserLifecycle.acquireStep(false, false, true));
    }

    @Test public void acquisitionUnavailableIsNoSource() {
        assertEquals(ChooserLifecycle.AcquireStep.NO_SOURCE, ChooserLifecycle.acquireStep(false, false, false));
    }

    // ---- pickRow: identity-grouped exact selection among verified panel rows ----

    @Test public void singleVerifiedRowIsTheExactDestination() {
        assertEquals(0, ChooserLifecycle.pickRow(Collections.singletonList("Audio will play on|Desk Headset")));
    }

    @Test public void stackedIdenticalPanelRowsAreOneDestinationTapTopmost() {
        // The three stacked com.android.settings panels all carry the same row
        // identity: same destination. Tap the topmost verified row, never a guess.
        assertEquals(0, ChooserLifecycle.pickRow(Arrays.asList(
                "Audio will play on|Desk Headset",
                "Audio will play on|Desk Headset",
                "Audio will play on|Desk Headset")));
    }

    @Test public void distinctIdentitiesAreAmbiguousAndRefused() {
        assertEquals(-1, ChooserLifecycle.pickRow(Arrays.asList(
                "Audio will play on|Desk Headset",
                "Play Sample Show on|Desk Headset")));
        assertEquals(-1, ChooserLifecycle.pickRow(Arrays.asList(
                "Audio will play on|Desk Headset",
                "Audio will play on|External Device")));
    }

    @Test public void emptyOrNullIdentitiesRefuseFailClosed() {
        assertEquals(-1, ChooserLifecycle.pickRow(null));
        assertEquals(-1, ChooserLifecycle.pickRow(Collections.<String>emptyList()));
        assertEquals(-1, ChooserLifecycle.pickRow(Collections.<String>singletonList(null)));
        assertEquals(-1, ChooserLifecycle.pickRow(Collections.<String>singletonList("")));
    }

    @Test public void repeatedUseClosedSourceThenExactPhoneSelection() {
        // repeat-use regression: a second use with the source closed again
        // must reacquire before any click (never click the stale list), then the
        // exact phone row resolves against the matching reacquired live list.
        assertEquals(ChooserLifecycle.Plan.ACQUIRE_THEN_ENUMERATE, ChooserLifecycle.plan(false, true));
        List<String> shown = Arrays.asList("This phone", "External Device");
        assertEquals(ChooserLifecycle.Gate.REVALIDATE_THEN_CLICK,
                ChooserLifecycle.gate(false, shown, shown));
        assertEquals("same live list after reacquire: the exact click is allowed",
                ChooserLifecycle.Gate.CLICK_OK, ChooserLifecycle.gate(true, shown, shown));
        assertEquals("changed live list: refused, refresh instead of a wrong-route click",
                ChooserLifecycle.Gate.REFUSE_STALE,
                ChooserLifecycle.gate(true, Arrays.asList("This phone"), shown));
        // The phone destination resolves exactly among identity-grouped rows.
        assertEquals(0, ChooserLifecycle.pickRow(Arrays.asList(
                "Audio will play on|External Device", "Audio will play on|External Device")));
    }

    // ---- post-selection bounded route confirmation: truth only from the live source ----

    @Test public void selectionConfirmedOnlyWhenTheLiveSourceAgrees() {
        List<SpikeMapper.Row> rows = Arrays.asList(
                new SpikeMapper.Row("Desk Headset", false, false, true),
                new SpikeMapper.Row("This phone", true, false, false));
        assertTrue(ChooserLifecycle.selectionConfirmed(rows, "Desk Headset"));
        assertFalse("the tap alone never confirms (no optimistic marking)",
                ChooserLifecycle.selectionConfirmed(rows, "This phone"));
        assertFalse(ChooserLifecycle.selectionConfirmed(rows, "External Device"));
        assertFalse(ChooserLifecycle.selectionConfirmed(rows, ""));
        assertFalse(ChooserLifecycle.selectionConfirmed(rows, null));
        assertFalse(ChooserLifecycle.selectionConfirmed(null, "Desk Headset"));
        assertFalse(ChooserLifecycle.selectionConfirmed(
                Collections.<SpikeMapper.Row>emptyList(), "Desk Headset"));
    }

    @Test public void currentTitleIsTheActualSourceStateNeverGuessed() {
        assertEquals("Desk Headset", ChooserLifecycle.currentTitle(Arrays.asList(
                new SpikeMapper.Row("Desk Headset", false, false, true),
                new SpikeMapper.Row("This phone", true, false, false))));
        assertEquals("no current marker at all: honest empty", "",
                ChooserLifecycle.currentTitle(Collections.singletonList(
                        new SpikeMapper.Row("This phone", true, false, false))));
        assertEquals("two active rows: ambiguous, never pick one", "",
                ChooserLifecycle.currentTitle(Arrays.asList(
                        new SpikeMapper.Row("A", false, false, true),
                        new SpikeMapper.Row("B", false, false, true))));
        assertEquals("", ChooserLifecycle.currentTitle(Collections.<SpikeMapper.Row>emptyList()));
        assertEquals("", ChooserLifecycle.currentTitle(null));
    }

    @Test public void confirmStatusNeverClaimsSuccessWithoutSourceConfirmation() {
        String ok = ChooserLifecycle.confirmStatus(true, "This phone", "This phone", true);
        assertTrue(ok.startsWith("Selection confirmed"));
        assertTrue(ok.contains("\"This phone\""));
        String stillOld = ChooserLifecycle.confirmStatus(false, "This phone", "Desk Headset", true);
        assertFalse("unconfirmed outcome must not claim confirmation",
                stillOld.startsWith("Selection confirmed"));
        assertTrue(stillOld.contains("\"Desk Headset\""));
        assertTrue(stillOld.contains("Confirm sound by ear"));
        String none = ChooserLifecycle.confirmStatus(false, "This phone", "", true);
        assertFalse(none.startsWith("Selection confirmed"));
        assertTrue(none.contains("Confirm sound by ear"));
        String unreadable = ChooserLifecycle.confirmStatus(false, "This phone", "", false);
        assertFalse(unreadable.startsWith("Selection confirmed"));
        assertTrue(unreadable.contains("Confirm sound by ear"));
    }
}
