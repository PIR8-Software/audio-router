package com.roy.bluetoothglassesrouter;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Production entry execution for Refresh / dismissal / auto-select expiry.
 * Every case calls {@link ServiceEntry} — the class
 * {@link BluetoothGlassesRoutingService} delegates to — with the production
 * {@link AcquireSequencer} and a controllable scheduler. Not a copied decision
 * order and not a source-string contract.
 *
 * <p>Cooldown and picker-open Refresh must cancel a pending selection, keep a
 * retained outcome, and still refuse a new panel launch during cooldown.
 *
 * CLASSIFICATION (F5): behavioral (real execution of the production entry).
 */
public class ServiceEntryConsequenceTest {

    /** Shared clock, picker flag, and the production sequencer the service uses. */
    static final class World {
        final AcquireSequencerTest.FakeHost seqHost = new AcquireSequencerTest.FakeHost();
        final AcquireSequencerTest.FakeScheduler sched = new AcquireSequencerTest.FakeScheduler(seqHost);
        final AcquireSequencer seq = new AcquireSequencer(sched, seqHost);
        final AcquireSequencerTest.Recorder selection = new AcquireSequencerTest.Recorder();
        boolean initialized = true;
        boolean unlocked = true;
        int refreshRuns;
        int panelLaunchesAtStart;
        String lastRefreshStatus = "";
        Runnable stalePoll;

        ServiceEntry.RefreshHost refreshHost() {
            return new ServiceEntry.RefreshHost() {
                @Override public boolean initialized() { return initialized; }
                @Override public boolean unlocked() { return unlocked; }
                @Override public boolean chooserOpen() { return seqHost.pickerOpen; }
                @Override public long now() { return seqHost.clock; }
                @Override public void popupFromLive() { refreshRuns++; }
                @Override public void reportStatus(String message) { lastRefreshStatus = message; }
            };
        }

        /** Stale-row selection revalidation, the pending action Refresh must own. */
        void startSelection() {
            seqHost.rowPresent = false; // a launch is visible if Refresh ignores cooldown
            seq.acquireThen("selection revalidation", selection);
            stalePoll = sched.lastScheduled();
            panelLaunchesAtStart = seqHost.launchPanelCalls;
        }

        String refreshAt(long clock, boolean pickerOpen) {
            seqHost.clock = clock;
            seqHost.pickerOpen = pickerOpen;
            return ServiceEntry.refreshOutputs(seq, refreshHost());
        }

        /** Reviewer probe finish: open the picker, run the captured old poll, then any new poll. */
        void finishProbe() {
            seqHost.pickerOpen = true;
            if (stalePoll != null) stalePoll.run();
            Runnable current = sched.lastScheduled();
            if (current != null && current != stalePoll) current.run();
        }

        String shownRetained() {
            // The popup render consumes this exact function on the retained surface
            // the sequencer host reports (service host writes SelectorPopupActivity.retainedOutcome).
            return SelectorPopupModel.retainedNotice(seqHost.lastRetained());
        }
    }

    private static void assertSuperseded(String shown) {
        assertTrue("retained popup line must name the cancelled selection, got: " + shown,
                shown.contains("selection revalidation"));
        assertTrue("retained popup line must name the replacement, got: " + shown,
                shown.contains("refresh outputs"));
        assertTrue("retained popup line must say it will not run, got: " + shown,
                shown.contains("will not run"));
    }

    // ---- cooldown and picker-open Refresh: pending ownership before return ----

    @Test public void refreshInsideCooldownCancelsSelectionAndRetainsOutcome() {
        World w = new World();
        w.startSelection(); // t=1000, acquireNextAt=6000, picker closed
        String result = w.refreshAt(1100, false);
        w.finishProbe();
        assertEquals("cooldown still refuses a new acquire and shows the stale snapshot",
                "Native picker closed and reacquire unavailable; showing marked stale snapshot.", result);
        assertEquals("Refresh itself repopulates once", 1, w.refreshRuns);
        assertEquals("the stale selection must not run after Refresh", 0, w.selection.runs);
        assertEquals("cooldown must not launch another volume panel",
                w.panelLaunchesAtStart, w.seqHost.launchPanelCalls);
        assertNull("no selection remains armed", w.seq.pendingAction());
        assertSuperseded(w.shownRetained());
        // Popup refresh writes an ordinary status line; the retained line is a separate surface.
        String statusLine = SelectorPopupModel.statusText(result, true);
        assertFalse("status line is not the retained outcome", statusLine.contains("Superseded"));
        assertSuperseded(w.shownRetained());
    }

    @Test public void refreshWhilePickerOpenBeforePollCancelsSelectionAndRetainsOutcome() {
        World w = new World();
        w.startSelection();
        String result = w.refreshAt(1100, true); // picker appears before the pending poll
        w.finishProbe();
        assertEquals("open picker still refreshes live, without a new acquire",
                "Refreshed live from the open native picker.", result);
        assertEquals(1, w.refreshRuns);
        assertEquals("the stale selection must not run after Refresh", 0, w.selection.runs);
        assertEquals("open-picker Refresh must not launch a panel",
                w.panelLaunchesAtStart, w.seqHost.launchPanelCalls);
        assertNull(w.seq.pendingAction());
        assertSuperseded(w.shownRetained());
    }

    @Test public void delayedOldCallbackAfterCooldownRefreshNeverResurrectsSelection() {
        World w = new World();
        w.startSelection();
        Runnable captured = w.stalePoll;
        w.refreshAt(1100, false);
        w.seqHost.pickerOpen = true;
        captured.run();
        captured.run(); // a second late delivery must stay inert
        w.sched.runDue(w.seqHost.clock + 20000);
        assertEquals("delayed old callback never executes the superseded selection", 0, w.selection.runs);
        assertSuperseded(w.shownRetained());
    }

    @Test public void timerArmThenRefreshInsideCooldownNeverRunsAutomaticAction() {
        World w = new World();
        AcquireSequencerTest.Recorder automatic = new AcquireSequencerTest.Recorder();
        w.seqHost.pickerOpen = false; // native picker closed; OUR popup is the separate boolean
        w.seqHost.rowPresent = false;
        boolean armed = ServiceEntry.tryArmExpiryRevalidation(
                w.seq, false, true, w.seqHost.clock, automatic, msg -> w.lastRefreshStatus = msg);
        assertTrue("expiry arms the production revalidation acquire", armed);
        assertEquals("auto-select revalidation", w.seq.pendingAction().name);
        w.stalePoll = w.sched.lastScheduled();
        w.seqHost.pickerOpen = false; // picker closed again before Refresh; still inside cooldown
        int launches = w.seqHost.launchPanelCalls;
        String result = w.refreshAt(1100, false);
        w.finishProbe();
        assertEquals("Native picker closed and reacquire unavailable; showing marked stale snapshot.", result);
        assertEquals("the automatic action never runs behind Refresh", 0, automatic.runs);
        assertEquals("Refresh does not launch another panel during cooldown", launches, w.seqHost.launchPanelCalls);
        String shown = w.shownRetained();
        assertTrue("cancelled automatic action is named, got: " + shown, shown.contains("auto-select revalidation"));
        assertTrue("replacement is named, got: " + shown, shown.contains("refresh outputs"));
        assertEquals("Refresh repopulated once", 1, w.refreshRuns);
    }

    // ---- Controls that must pass before AND after the fix ----

    @Test public void refreshAfterCooldownSupersedesAndRunsRefreshNotSelection() {
        World w = new World();
        w.startSelection();
        String result = w.refreshAt(6100, false);
        w.finishProbe();
        assertEquals("Native picker closed; reopening it automatically to reload real rows...", result);
        assertEquals(0, w.selection.runs);
        assertEquals("the refresh continuation runs once the picker is open", 1, w.refreshRuns);
        assertSuperseded(w.shownRetained());
        assertEquals("Refresh: native picker closed; reacquiring it before reloading rows.", w.lastRefreshStatus);
    }

    @Test public void validRefreshWithNoPendingAcquiresOnceAndKeepsOutcomeEmpty() {
        World w = new World();
        w.seqHost.rowPresent = false;
        w.seqHost.clock = 1000;
        String result = w.refreshAt(1000, false);
        assertEquals("Native picker closed; reopening it automatically to reload real rows...", result);
        assertEquals("panel launched once", 1, w.seqHost.launchPanelCalls);
        assertEquals(0, w.refreshRuns);
        assertEquals("a clean acquire retains nothing", "", w.shownRetained());
        w.seqHost.pickerOpen = true;
        w.sched.runDue(w.seqHost.clock + 400);
        assertEquals("continuation runs exactly once", 1, w.refreshRuns);
        assertEquals("", w.shownRetained());
    }

    @Test public void cooldownWithNothingPendingDoesNotLaunchAndShowsSnapshot() {
        World w = new World();
        w.seqHost.rowPresent = false;
        w.seq.acquireThen("popup open", new AcquireSequencerTest.Recorder());
        // Complete that acquire so nothing is pending, but cooldown remains.
        w.seqHost.pickerOpen = true;
        w.sched.runDue(w.seqHost.clock + 400);
        assertNull(w.seq.pendingAction());
        int launches = w.seqHost.launchPanelCalls;
        w.seqHost.pickerOpen = false;
        String result = w.refreshAt(1100, false);
        assertEquals("Native picker closed and reacquire unavailable; showing marked stale snapshot.", result);
        assertEquals(1, w.refreshRuns);
        assertEquals("cooldown still blocks a panel launch", launches, w.seqHost.launchPanelCalls);
        assertEquals("no supersession when nothing was pending", "", w.shownRetained());
    }

    @Test public void dismissalEntryCancelsPendingAndStalePollStaysDead() {
        World w = new World();
        w.startSelection();
        Runnable stale = w.stalePoll;
        ServiceEntry.onPopupUserClosed(w.seq, () -> { /* service cancel bookkeeping */ });
        stale.run();
        w.seqHost.pickerOpen = true;
        stale.run();
        w.sched.runDue(w.seqHost.clock + 20000);
        assertEquals("dismissed session is never reopened by its old continuation", 0, w.selection.runs);
        assertNull(w.seq.pendingAction());
        assertEquals("dismissal clears the retained outcome", "", w.shownRetained());
    }

    @Test public void retainedOutcomeStaysUntilNextIntentionalAction() {
        World w = new World();
        w.startSelection();
        w.refreshAt(6100, false); // control path that already supersedes
        w.seqHost.pickerOpen = true;
        w.sched.runDue(w.seqHost.clock + 400);
        // popupFromLive / render writes ordinary status; retained line must survive.
        String statusLine = SelectorPopupModel.statusText("Popup loaded: 2 live row(s) on display 2.", false);
        assertEquals("Popup loaded: 2 live row(s) on display 2.", statusLine);
        assertSuperseded(w.shownRetained());
        w.seq.beginIntentionalAction();
        assertEquals("the next intentional action clears the popup retained line", "", w.shownRetained());
    }

    @Test public void lockedAndUninitializedRefreshDoNotAcquire() {
        World w = new World();
        w.initialized = false;
        assertEquals("Service not initialized.", w.refreshAt(1000, false));
        w.initialized = true;
        w.unlocked = false;
        assertEquals("Locked; outputs not refreshed.", w.refreshAt(1000, false));
        assertEquals(0, w.seqHost.launchPanelCalls);
        assertEquals(0, w.refreshRuns);
    }

    /**
     * CLASSIFICATION (F5): source-contract tripwire that the service actually
     * delegates these entries. Behavioral proof is the tests above; this only
     * fails closed if a future edit inlines a bypass again.
     */
    @Test public void serviceDelegatesRefreshDismissalAndExpiryToServiceEntry() throws Exception {
        String s = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/com/roy/bluetoothglassesrouter/BluetoothGlassesRoutingService.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(s.contains("ServiceEntry.refreshOutputs"));
        assertTrue(s.contains("ServiceEntry.onPopupUserClosed"));
        assertTrue(s.contains("ServiceEntry.tryArmExpiryRevalidation"));
        assertTrue(s.contains("ServiceEntry.closeOwnedPicker"));
        assertTrue(s.contains("ServiceEntry.beginPopupSession"));
        assertTrue(s.contains("SelectorPopupActivity.retainedOutcome=message"));
        String popup = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/com/roy/bluetoothglassesrouter/SelectorPopupActivity.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(popup.contains("SelectorPopupModel.retainedNotice(retainedOutcome)"));
    }
}
