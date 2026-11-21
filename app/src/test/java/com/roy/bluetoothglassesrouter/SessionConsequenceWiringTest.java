package com.roy.bluetoothglassesrouter;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * REAL-execution behavioral coverage of sessionDone / timer / refresh
 * consequences through {@link ServiceEntry} — the production entry
 * {@link BluetoothGlassesRoutingService} delegates to — plus the production
 * {@link AcquireSequencer}. Not a copied decision order.
 *
 * Gate-only assertions on {@link ChooserLifecycle} are classified as gate
 * controls. They are not the consequence proof. Session-owned Done click
 * consumption is executed in {@link DoneSessionConsequenceTest}. Refresh,
 * timer, and dismissal proof is the ServiceEntry executions below and in
 * {@link ServiceEntryConsequenceTest}.
 *
 * CLASSIFICATION (F5): behavioral where a test calls ServiceEntry; gate-control
 * where a test only calls ChooserLifecycle booleans.
 */
public class SessionConsequenceWiringTest {

    private static AcquireSequencerTest.FakeHost host() {
        return new AcquireSequencerTest.FakeHost();
    }

    // ---- sessionDone consequence wiring ----

    @Test public void doneDismissalCancelsPendingAcquisitionAndStalePollsStayDead() {
        AcquireSequencerTest.FakeHost host = host();
        AcquireSequencerTest.FakeScheduler s = new AcquireSequencerTest.FakeScheduler(host);
        AcquireSequencer seq = new AcquireSequencer(s, host);
        AcquireSequencerTest.Recorder pending = new AcquireSequencerTest.Recorder();
        seq.acquireThen("selection revalidation", pending);
        Runnable stalePoll = s.lastScheduled();
        // Production dismissal entry (service onPopupUserClosed -> ServiceEntry).
        ServiceEntry.onPopupUserClosed(seq, () -> { /* remaining service cancel bookkeeping */ });
        stalePoll.run();
        assertEquals("a finished popup session can never be reopened by its old continuation",
                0, pending.runs);
        assertNull("no pending work survives Done", seq.pendingAction());
        assertEquals("the dismissal itself leaves no retained outcome", "", seq.retainedOutcome());
    }

    /** CLASSIFICATION (F5): gate-control, not entry-consequence proof. */
    @Test public void sessionDoneConsumptionBlocksASecondNativeDoneClick() {
        // Production Done decision order (service closeOwnedPicker -> ChooserLifecycle):
        // idempotence first, then ownership, then presence, then exact control.
        assertEquals("first Done of a live session may click the owned picker",
                ChooserLifecycle.DonePlan.CLICK_OWNED_PICKER_DONE,
                ChooserLifecycle.donePlan(true, false, true, true));
        assertEquals("a repeat Done is idempotent - never a second click",
                ChooserLifecycle.DonePlan.CLOSE_ONLY_ALREADY_DONE,
                ChooserLifecycle.donePlan(true, true, true, true));
        assertFalse("a superseded session token can never own the picker again",
                ChooserLifecycle.doneSessionCurrent(6L, 7L));
    }

    /** CLASSIFICATION (F5): gate-control, not entry-consequence proof. */
    @Test public void confirmedAutomaticCloseIsTheOnlyAutoClosePath() {
        // Production autoSuccessClose gate wiring: ONLY a confirmed AUTOMATIC
        // selection with the popup still up (never behind a dismissed one) may
        // close both popups; manual/failed/unconfirmed paths keep it open.
        assertTrue(ChooserLifecycle.autoSuccessClose(true, true, true, false));
        assertFalse("manual selections never auto-close",
                ChooserLifecycle.autoSuccessClose(false, true, true, false));
        assertFalse("unconfirmed selections never auto-close",
                ChooserLifecycle.autoSuccessClose(true, false, true, false));
        assertFalse("nothing closes behind a dismissed popup",
                ChooserLifecycle.autoSuccessClose(true, true, true, true));
        assertFalse("nothing closes when the popup is already gone",
                ChooserLifecycle.autoSuccessClose(true, true, false, false));
    }

    // ---- timer consequence wiring ----

    @Test public void timerExpiryRevalidationSupersededByRefreshNeverAutoSelects() {
        ServiceEntryConsequenceTest.World w = new ServiceEntryConsequenceTest.World();
        AcquireSequencerTest.Recorder automatic = new AcquireSequencerTest.Recorder();
        w.seqHost.pickerOpen = false;
        w.seqHost.rowPresent = false;
        assertTrue(ServiceEntry.tryArmExpiryRevalidation(
                w.seq, false, true, w.seqHost.clock, automatic, msg -> { }));
        w.stalePoll = w.sched.lastScheduled();
        // Refresh inside the launch cooldown — the production entry, not a direct acquireThen.
        String result = w.refreshAt(1100, false);
        w.finishProbe();
        assertEquals("Native picker closed and reacquire unavailable; showing marked stale snapshot.", result);
        assertEquals("the automatic action NEVER runs behind the user's refresh", 0, automatic.runs);
        String shown = w.shownRetained();
        assertTrue("the cancelled automatic action is honestly named, got: " + shown,
                shown.contains("auto-select revalidation"));
        assertTrue("the replacement action is honestly named, got: " + shown,
                shown.contains("refresh outputs"));
    }

    @Test public void refreshCompletionLeavesSupersessionOutcomeVisible() {
        ServiceEntryConsequenceTest.World w = new ServiceEntryConsequenceTest.World();
        w.startSelection();
        // Both production bypasses the previous fix missed.
        String cooldown = w.refreshAt(1100, false);
        w.finishProbe();
        assertEquals("Native picker closed and reacquire unavailable; showing marked stale snapshot.", cooldown);
        assertEquals("the superseded selection never executes", 0, w.selection.runs);
        String shown = w.shownRetained();
        assertTrue("selection cancelled/replaced is still visible after the popup refresh, got: " + shown,
                shown.contains("selection revalidation") && shown.contains("refresh outputs"));
        assertNotEquals(SelectorPopupModel.statusText(cooldown, true), shown);
        w.seq.beginIntentionalAction();
        assertEquals("next intentional action clears the retained outcome",
                "", SelectorPopupModel.retainedNotice(w.seq.retainedOutcome()));
    }

    /** CLASSIFICATION (F5): gate-control, not entry-consequence proof. */
    @Test public void autoRefreshContinuationNeverReopensADismissedPopup() {
        // Production reopen gate (ChooserLifecycle.continuationAllowed), consulted
        // by every async continuation before repopulating the popup.
        assertTrue(ChooserLifecycle.continuationAllowed(true, false));
        assertFalse("a dismissed popup is never reopened by a continuation",
                ChooserLifecycle.continuationAllowed(false, true));
        assertFalse("a closed popup is never reopened by a continuation",
                ChooserLifecycle.continuationAllowed(false, false));
        assertFalse("fail closed even on inconsistent state",
                ChooserLifecycle.continuationAllowed(true, true));
    }
}
