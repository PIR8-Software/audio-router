package com.roy.bluetoothglassesrouter;

import org.junit.Test;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * REAL-execution behavioral coverage of the production acquisition orchestration
 * ({@link AcquireSequencer} - the actual class the service runs, not a copied
 * helper) with a controllable scheduler/adapter. Covers:
 *
 *  - overlapping callback acquisition (stale-select -> Refresh) has deterministic
 *    action ownership: the newer action supersedes the pending one, the
 *    superseded continuation NEVER runs, and the supersession outcome is a
 *    RETAINED visible result (not a transient status that is overwritten);
 *  - a delayed old poll callback can never execute a superseded continuation or
 *    resurrect one after a newer action already ran (generation ownership);
 *  - controls: an unchanged valid acquire still runs exactly once; cancel,
 *    timeout and the panel-launch fallback paths behave exactly as before
 *    (no wrong click anywhere - the orchestrator only ever runs the one named
 *    continuation, and every production click stays behind the identity gates).
 *
 * CLASSIFICATION (F5): behavioral (real execution) - not a source-string tripwire.
 */
public class AcquireSequencerTest {

    /** Controllable scheduler: tests decide when every callback fires (and can
     *  force-fire a captured handle to simulate a delayed old callback). */
    static final class FakeScheduler implements AcquireSequencer.Scheduler {
        final FakeHost host;
        private final List<Object[]> q = new ArrayList<>(); // {Runnable, Long dueAt}
        FakeScheduler(FakeHost host) { this.host = host; }
        @Override public void postDelayed(Runnable r, long delayMs) {
            q.add(new Object[]{r, host.clock + delayMs});
        }
        @Override public void removeCallbacks(Runnable r) {
            for (Iterator<Object[]> it = q.iterator(); it.hasNext();)
                if (it.next()[0] == r) it.remove();
        }
        int pendingCount() { return q.size(); }
        /** Most recently scheduled callback handle (to capture an old poll). */
        Runnable lastScheduled() { return q.isEmpty() ? null : (Runnable) q.get(q.size() - 1)[0]; }
        /** Advance the fake clock and run every callback due at or before it
         *  (including callbacks the run itself schedules). */
        void runDue(long until) {
            host.clock = until;
            boolean moved = true;
            while (moved) {
                moved = false;
                Object[] best = null;
                for (Object[] e : q)
                    if ((Long) e[1] <= until && (best == null || (Long) e[1] < (Long) best[1])) best = e;
                if (best != null) { q.remove(best); ((Runnable) best[0]).run(); moved = true; }
            }
        }
    }

    /** Controllable production adapter: records every orchestrated side effect. */
    static final class FakeHost implements AcquireSequencer.Host {
        long clock = 1000;
        boolean pickerOpen = false;
        boolean rowPresent = true;
        boolean panelLaunchOk = true;
        boolean fallbackFlag = false;
        int openPickerDirectCalls, beginSelectorCalls, scheduleSelectorStepCalls,
                cancelSelectorStepCalls, launchPanelCalls;
        final List<String> statusLog = new ArrayList<>();
        final List<String> retainedLog = new ArrayList<>();
        @Override public long now() { return clock; }
        @Override public boolean pickerOpen() { return pickerOpen; }
        @Override public boolean verifiedRowPresent() { return rowPresent; }
        @Override public boolean launchVolumePanel() { launchPanelCalls++; return panelLaunchOk; }
        @Override public void openPickerDirect() { openPickerDirectCalls++; }
        @Override public void setFallbackSent(boolean sent) { fallbackFlag = sent; }
        @Override public void beginSelector(long now) { beginSelectorCalls++; }
        @Override public void scheduleSelectorStep() { scheduleSelectorStepCalls++; }
        @Override public void cancelSelectorStep() { cancelSelectorStepCalls++; }
        @Override public void reportStatus(String message) { statusLog.add(message); }
        @Override public void reportRetained(String message) { retainedLog.add(message); }
        String lastStatus() { return statusLog.isEmpty() ? "" : statusLog.get(statusLog.size() - 1); }
        String lastRetained() { return retainedLog.isEmpty() ? "" : retainedLog.get(retainedLog.size() - 1); }
    }

    static final class Recorder implements Runnable {
        int runs;
        @Override public void run() { runs++; }
    }

    private static FakeScheduler sched(FakeHost host) { return new FakeScheduler(host); }

    // ---- Controls: unchanged valid acquire behavior ----

    @Test public void controlValidAcquireRunsContinuationExactlyOnce() {
        FakeHost host = new FakeHost();
        FakeScheduler s = sched(host);
        AcquireSequencer seq = new AcquireSequencer(s, host);
        host.rowPresent = false; // no reusable panel row: the normal first-run path
        Recorder next = new Recorder();
        seq.acquireThen("popup open", next);
        assertEquals("continuation waits for the picker", 0, next.runs);
        assertEquals("the panel was launched once", 1, host.launchPanelCalls);
        assertEquals("acquisition status is honest", host.lastStatus(),
                "Auto-acquiring idle native picker: system volume panel launching on the phone display only (never the external monitor); verified output row tap follows.");
        host.pickerOpen = true;
        s.runDue(host.clock + 400);
        assertEquals("continuation runs exactly once", 1, next.runs);
        assertNull("slot is empty after completion", seq.pendingAction());
        assertEquals("no supersession outcome on a clean acquire", "", seq.retainedOutcome());
    }

    @Test public void controlPickerAlreadyOpenRunsContinuationImmediately() {
        FakeHost host = new FakeHost();
        FakeScheduler s = sched(host);
        AcquireSequencer seq = new AcquireSequencer(s, host);
        host.pickerOpen = true;
        Recorder next = new Recorder();
        seq.acquireThen("popup open", next);
        assertEquals("open picker: run now, no polling", 1, next.runs);
        assertEquals("nothing scheduled", 0, s.pendingCount());
    }

    @Test public void controlPollKeepsWaitingWhilePickerClosedBeforeBudget() {
        FakeHost host = new FakeHost();
        FakeScheduler s = sched(host);
        AcquireSequencer seq = new AcquireSequencer(s, host);
        Recorder next = new Recorder();
        seq.acquireThen("refresh outputs", next);
        s.runDue(host.clock + 2000); // several poll ticks, budget (10s) not burned
        assertEquals("not completed before the budget", 0, next.runs);
        assertNotNull("still pending", seq.pendingAction());
    }

    @Test public void controlTimeoutFailsClosedWithHonestFallbackStatus() {
        FakeHost host = new FakeHost();
        FakeScheduler s = sched(host);
        AcquireSequencer seq = new AcquireSequencer(s, host);
        Recorder next = new Recorder();
        seq.acquireThen("selection revalidation", next);
        s.runDue(host.clock + 20000); // picker never appears: budget expires
        assertEquals("continuation still runs (marked-snapshot fallback)", 1, next.runs);
        assertEquals("honest timeout status (no invented rows, no click claim)",
                "Auto-acquire: native picker did not appear in time; falling back to marked snapshot.",
                host.lastStatus());
        assertNull("slot empty", seq.pendingAction());
    }

    @Test public void controlCancelDropsPendingActionAndScheduledPolls() {
        FakeHost host = new FakeHost();
        FakeScheduler s = sched(host);
        AcquireSequencer seq = new AcquireSequencer(s, host);
        Recorder next = new Recorder();
        seq.acquireThen("selection revalidation", next);
        seq.cancel();
        assertNull("pending continuation dropped", seq.pendingAction());
        assertEquals("polls removed", 0, s.pendingCount());
        s.runDue(host.clock + 20000);
        assertEquals("cancelled continuation never runs (no resurrection)", 0, next.runs);
    }

    @Test public void controlStalePollAfterCancelRunsNothing() {
        FakeHost host = new FakeHost();
        FakeScheduler s = sched(host);
        AcquireSequencer seq = new AcquireSequencer(s, host);
        Recorder next = new Recorder();
        seq.acquireThen("selection revalidation", next);
        Runnable stalePoll = s.lastScheduled();
        seq.cancel();
        stalePoll.run(); // delayed old callback that removeCallbacks could not stop
        assertEquals("stale poll after cancel runs nothing", 0, next.runs);
        host.pickerOpen = true;
        stalePoll.run();
        assertEquals("still nothing - the action is gone, not merely hidden", 0, next.runs);
    }

    @Test public void controlPanelLaunchFailureOpensDocumentedPickerAndKeepsPolling() {
        FakeHost host = new FakeHost();
        FakeScheduler s = sched(host);
        AcquireSequencer seq = new AcquireSequencer(s, host);
        host.rowPresent = false;
        host.panelLaunchOk = false;
        Recorder next = new Recorder();
        seq.acquireThen("popup open", next);
        assertEquals("documented picker opener fired", 1, host.openPickerDirectCalls);
        assertTrue("phase-2 fallback marker set", host.fallbackFlag);
        assertEquals("honest fallback status",
                "Auto-acquire: volume panel unavailable; opening the system output picker directly (documented SystemUI action). The app never selects a route.",
                host.lastStatus());
        assertTrue("acquisition stays live (polling continues)", s.pendingCount() > 0);
        host.pickerOpen = true;
        s.runDue(host.clock + 400);
        assertEquals("continuation completes when the picker appears", 1, next.runs);
    }

    @Test public void controlVerifiedRowPresentReusesPanelWithoutRelaunch() {
        FakeHost host = new FakeHost();
        FakeScheduler s = sched(host);
        AcquireSequencer seq = new AcquireSequencer(s, host);
        host.rowPresent = true;
        seq.acquireThen("popup open", new Recorder());
        assertEquals("verified row reused: no panel relaunch", 0, host.launchPanelCalls);
        assertEquals("selector budget started", 1, host.beginSelectorCalls);
        assertEquals("row tap scheduled", 1, host.scheduleSelectorStepCalls);
        assertEquals("honest reuse status", host.lastStatus(),
                "Auto-acquiring idle native picker: reusing the open system volume panel on its actual display; tapping its verified output row (no relaunch).");
    }

    @Test public void controlPlainSupersedeNeverRunsTheOldContinuation() {
        FakeHost host = new FakeHost();
        FakeScheduler s = sched(host);
        AcquireSequencer seq = new AcquireSequencer(s, host);
        Recorder oldAction = new Recorder();
        Recorder newAction = new Recorder();
        seq.acquireThen("selection revalidation", oldAction);
        seq.acquireThen("refresh outputs", newAction);
        host.pickerOpen = true;
        s.runDue(host.clock + 400);
        assertEquals("superseded continuation never runs", 0, oldAction.runs);
        assertEquals("newer action runs exactly once", 1, newAction.runs);
    }

    // ---- persistent visible outcome + deterministic ownership ----

    @Test public void overlappingAcquireRecordsPersistentSupersessionOutcome() {
        FakeHost host = new FakeHost();
        FakeScheduler s = sched(host);
        AcquireSequencer seq = new AcquireSequencer(s, host);
        seq.acquireThen("selection revalidation", new Recorder());
        seq.acquireThen("refresh outputs", new Recorder());
        String outcome = seq.retainedOutcome();
        assertTrue("retained outcome must name the cancelled pending action, got: " + outcome,
                outcome.contains("selection revalidation"));
        assertTrue("retained outcome must name the replacement action, got: " + outcome,
                outcome.contains("refresh outputs"));
        assertEquals("the outcome is mirrored to the visible UI surface",
                outcome, host.lastRetained());
    }

    @Test public void overlappingAcquireOutcomeSurvivesStatusWritesAndCompletion() {
        FakeHost host = new FakeHost();
        FakeScheduler s = sched(host);
        AcquireSequencer seq = new AcquireSequencer(s, host);
        seq.acquireThen("selection revalidation", new Recorder());
        seq.acquireThen("refresh outputs", new Recorder());
        // The transient status line moves on (acquire progress, then popup load) -
        // exactly the production overwrite pattern that destroyed the old message.
        host.pickerOpen = true;
        s.runDue(host.clock + 400);
        host.reportStatus("Popup loaded: 2 live row(s) on display 2.");
        String outcome = seq.retainedOutcome();
        assertTrue("outcome still visible after later status writes, got: " + outcome,
                outcome.contains("selection revalidation"));
        assertTrue("outcome still visible after completion, got: " + outcome,
                outcome.contains("refresh outputs"));
    }

    @Test public void delayedOldPollAfterFastPathOverlapNeverRunsTheSupersededAction() {
        FakeHost host = new FakeHost();
        FakeScheduler s = sched(host);
        AcquireSequencer seq = new AcquireSequencer(s, host);
        Recorder oldAction = new Recorder();
        Recorder newAction = new Recorder();
        seq.acquireThen("selection revalidation", oldAction);
        Runnable oldPoll = s.lastScheduled(); // the poll chain of the PENDING action
        host.pickerOpen = true;
        seq.acquireThen("refresh outputs", newAction); // newer action takes over (picker already open)
        assertEquals("newer action runs immediately", 1, newAction.runs);
        oldPoll.run(); // delayed old callback arrives after the newer action already ran
        assertEquals("superseded continuation must NEVER run (no resurrection)", 0, oldAction.runs);
        s.runDue(host.clock + 20000);
        assertEquals("still never - later polls must not revive it either", 0, oldAction.runs);
    }

    @Test public void fastPathOverlapRecordsOutcomeAndDropsThePendingAction() {
        FakeHost host = new FakeHost();
        FakeScheduler s = sched(host);
        AcquireSequencer seq = new AcquireSequencer(s, host);
        seq.acquireThen("selection revalidation", new Recorder());
        host.pickerOpen = true;
        seq.acquireThen("refresh outputs", new Recorder());
        assertNull("pending action is owned by the newer call (old one dropped)",
                seq.pendingAction());
        String outcome = seq.retainedOutcome();
        assertTrue("fast-path overlap outcome is retained, got: " + outcome,
                outcome.contains("selection revalidation") && outcome.contains("refresh outputs"));
    }

    @Test public void supersedeTwiceKeepsTheLatestOutcome() {
        FakeHost host = new FakeHost();
        FakeScheduler s = sched(host);
        AcquireSequencer seq = new AcquireSequencer(s, host);
        seq.acquireThen("selection revalidation", new Recorder());
        seq.acquireThen("refresh outputs", new Recorder());
        seq.acquireThen("auto-select revalidation", new Recorder());
        String outcome = seq.retainedOutcome();
        assertTrue("latest supersession names the replaced action, got: " + outcome,
                outcome.contains("refresh outputs"));
        assertTrue("latest supersession names the replacement, got: " + outcome,
                outcome.contains("auto-select revalidation"));
    }

    @Test public void beginIntentionalActionClearsRetainedOutcome() {
        FakeHost host = new FakeHost();
        FakeScheduler s = sched(host);
        AcquireSequencer seq = new AcquireSequencer(s, host);
        seq.acquireThen("selection revalidation", new Recorder());
        seq.acquireThen("refresh outputs", new Recorder());
        assertFalse("precondition: an outcome is retained",
                seq.retainedOutcome().isEmpty());
        seq.beginIntentionalAction();
        assertEquals("the user's next intentional action clears it", "", seq.retainedOutcome());
        assertEquals("and the visible surface is cleared with it", "", host.lastRetained());
    }
}
