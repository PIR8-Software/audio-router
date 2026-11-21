package com.roy.bluetoothglassesrouter;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Production session-owned Done consequence execution. Every case calls
 * {@link ServiceEntry#closeOwnedPicker} — the method
 * {@link BluetoothGlassesRoutingService#closeOwnedPicker} delegates to — with
 * the production {@link AcquireSequencer} and a controllable verified-picker
 * adapter. Not a {@link ChooserLifecycle#donePlan} truth table and not a copy
 * of the service method.
 *
 * <p>The one-shot write {@code memory.consumedSession = requestSession} is what
 * stops a second native click. Removing it must fail the repeat / refusal /
 * wrong-window / unverified / absent cases below (click count rises, or a
 * later clickable lookup is reached). Dismissal bookkeeping is
 * {@link ServiceEntry#onPopupUserClosed}; it must not overwrite the native
 * outcome or resurrect a pending acquisition.
 *
 * CLASSIFICATION (F5): behavioral (real execution of the production entry).
 */
public class DoneSessionConsequenceTest {

    private static final String CLICKED =
            "Done: the session-owned native picker's exact Done was clicked (verified window/identity); our popup closed. The app never routes.";
    private static final String REJECTED =
            "Done: the session-owned native picker's exact Done rejected the click; nothing else was touched; our popup closed.";
    private static final String ALREADY =
            "Done: the native picker's Done was already handled for this session (repeat tap ignored); our popup closed.";
    private static final String STALE =
            "Done: this popup's session is not the live one (stale/no session); native UI left untouched; our popup closed.";
    private static final String UNVERIFIED =
            "Done: the native picker is present but its exact Done control was not verified as clickable (absent, disabled, ambiguous or wrong window); native UI left untouched; our popup closed.";
    private static final String ABSENT =
            "Done: no session-owned native picker on screen; our popup closed normally.";

    static final class FakeDone implements ServiceEntry.DoneControl {
        int refreshCalls, clickCalls, recycleCalls;
        boolean refreshOk = true, visible = true, enabled = true, clickable = true, clickOk = true;

        @Override public boolean refresh() { refreshCalls++; return refreshOk; }
        @Override public boolean visibleToUser() { return visible; }
        @Override public boolean enabled() { return enabled; }
        @Override public boolean clickable() { return clickable; }
        @Override public boolean performClick() { clickCalls++; return clickOk; }
        @Override public void recycle() { recycleCalls++; }
    }

    static final class Lookup implements ServiceEntry.DoneLookup {
        int calls;
        ServiceEntry.VerifiedPicker result = ServiceEntry.VerifiedPicker.NONE;

        @Override public ServiceEntry.VerifiedPicker lookup() {
            calls++;
            return result;
        }
    }

    static final class World {
        final AcquireSequencerTest.FakeHost seqHost = new AcquireSequencerTest.FakeHost();
        final AcquireSequencerTest.FakeScheduler sched = new AcquireSequencerTest.FakeScheduler(seqHost);
        final AcquireSequencer seq = new AcquireSequencer(sched, seqHost);
        final ServiceEntry.DoneMemory memory = new ServiceEntry.DoneMemory();
        final Lookup lookup = new Lookup();
        final StringBuilder status = new StringBuilder();
        long popupSession = 7L;
        boolean initialized = true;

        String done(long requestSession) {
            return ServiceEntry.closeOwnedPicker(seq, requestSession, memory, popupSession, initialized,
                    lookup, message -> {
                        status.setLength(0);
                        status.append(message);
                    });
        }

        void offerClickable(FakeDone control) {
            lookup.result = new ServiceEntry.VerifiedPicker(true, control);
        }
    }

    @Test public void sameSessionRepeatClicksNativeDoneOnce() {
        World w = new World();
        FakeDone control = new FakeDone();
        w.offerClickable(control);
        assertEquals(CLICKED, w.done(7L));
        assertEquals(CLICKED, w.memory.lastOutcome);
        assertEquals(ALREADY, w.done(7L));
        assertEquals("one native click for two same-session Done requests", 1, control.clickCalls);
        assertEquals("the repeat is refused before any window lookup", 1, w.lookup.calls);
        assertEquals("the control is recycled with the one click", 1, control.recycleCalls);
        assertEquals(7L, w.memory.consumedSession);
        assertEquals("the latest Done result replaces the stored outcome; dismissal must not",
                ALREADY, w.memory.lastOutcome);
        assertEquals(ALREADY, w.status.toString());
    }

    @Test public void staleSessionNeverLooksUpOrClicks() {
        World w = new World();
        FakeDone control = new FakeDone();
        w.offerClickable(control);
        assertEquals(STALE, w.done(6L));
        assertEquals(0, control.clickCalls);
        assertEquals(0, w.lookup.calls);
        assertEquals("a stale request must not consume the live session",
                ChooserLifecycle.NO_SESSION, w.memory.consumedSession);
        assertEquals(STALE, w.memory.lastOutcome);
    }

    @Test public void absentTokenNeverClicksOrConsumes() {
        World w = new World();
        FakeDone control = new FakeDone();
        w.offerClickable(control);
        assertEquals(STALE, w.done(ChooserLifecycle.NO_SESSION));
        assertEquals(0, control.clickCalls);
        assertEquals(0, w.lookup.calls);
        assertEquals(ChooserLifecycle.NO_SESSION, w.memory.consumedSession);
    }

    @Test public void wrongWindowConsumesWithoutClickAndBlocksLaterClick() {
        World w = new World();
        // Production verifiedPickerDone treats a foreign/ambiguous window as absent to us.
        w.lookup.result = ServiceEntry.VerifiedPicker.NONE;
        assertEquals(ABSENT, w.done(7L));
        FakeDone later = new FakeDone();
        w.offerClickable(later);
        assertEquals(ALREADY, w.done(7L));
        assertEquals("a later clickable control must not run after the session was consumed",
                0, later.clickCalls);
        assertEquals("the repeat must not look up again", 1, w.lookup.calls);
        assertEquals(7L, w.memory.consumedSession);
    }

    @Test public void unverifiedConsumesWithoutClickAndBlocksLaterClick() {
        World w = new World();
        w.lookup.result = new ServiceEntry.VerifiedPicker(true, null);
        assertEquals(UNVERIFIED, w.done(7L));
        assertEquals(UNVERIFIED, w.memory.lastOutcome);
        FakeDone later = new FakeDone();
        w.offerClickable(later);
        assertEquals(ALREADY, w.done(7L));
        assertEquals(0, later.clickCalls);
        assertEquals(1, w.lookup.calls);
        assertEquals(7L, w.memory.consumedSession);
        assertEquals(ALREADY, w.memory.lastOutcome);
    }

    @Test public void refusedClickConsumesAndBlocksRepeat() {
        World w = new World();
        FakeDone control = new FakeDone();
        control.clickOk = false;
        w.offerClickable(control);
        assertEquals(REJECTED, w.done(7L));
        assertEquals(REJECTED, w.memory.lastOutcome);
        assertEquals(1, control.clickCalls);
        assertEquals(1, control.recycleCalls);
        control.clickOk = true;
        assertEquals(ALREADY, w.done(7L));
        assertEquals("refusal still consumes: no second click", 1, control.clickCalls);
        assertEquals(1, w.lookup.calls);
        assertEquals(7L, w.memory.consumedSession);
        assertEquals(ALREADY, w.memory.lastOutcome);
    }

    @Test public void refreshRefusalConsumesWithoutPerformClick() {
        World w = new World();
        FakeDone control = new FakeDone();
        control.refreshOk = false;
        w.offerClickable(control);
        assertEquals(REJECTED, w.done(7L));
        assertEquals("the production predicate must not click when refresh fails", 0, control.clickCalls);
        assertEquals(1, control.refreshCalls);
        assertEquals(1, control.recycleCalls);
        FakeDone later = new FakeDone();
        w.offerClickable(later);
        assertEquals(ALREADY, w.done(7L));
        assertEquals(0, later.clickCalls);
        assertEquals(7L, w.memory.consumedSession);
    }

    @Test public void freshSessionResetPermitsOneNewClickOnTheSameToken() {
        World w = new World();
        FakeDone first = new FakeDone();
        w.offerClickable(first);
        assertEquals(CLICKED, w.done(7L));
        assertEquals(ALREADY, w.done(7L));
        assertEquals(1, first.clickCalls);
        // The reset openCustomPopup delegates to. Same token may click once more.
        ServiceEntry.beginPopupSession(w.memory);
        assertEquals(ChooserLifecycle.NO_SESSION, w.memory.consumedSession);
        FakeDone again = new FakeDone();
        w.offerClickable(again);
        assertEquals(CLICKED, w.done(7L));
        assertEquals(ALREADY, w.done(7L));
        assertEquals(1, again.clickCalls);
        assertEquals(7L, w.memory.consumedSession);
    }

    @Test public void nativeOutcomeSurvivesDismissalAndPendingAcquisitionStaysDead() {
        World w = new World();
        AcquireSequencerTest.Recorder selection = new AcquireSequencerTest.Recorder();
        w.seqHost.rowPresent = false;
        w.seq.acquireThen("selection revalidation", selection);
        Runnable stale = w.sched.lastScheduled();
        FakeDone control = new FakeDone();
        w.offerClickable(control);
        assertEquals(CLICKED, w.done(7L));
        String nativeOutcome = w.memory.lastOutcome;
        String[] dismissalStatus = {""};
        ServiceEntry.onPopupUserClosed(w.seq, () ->
                dismissalStatus[0] = "Popup closed by user; pending acquisition/refresh cancelled (no reopen, no native helper restart).");
        stale.run();
        w.seqHost.pickerOpen = true;
        stale.run();
        w.sched.runDue(w.seqHost.clock + 20000);
        assertEquals("pending acquisition must not run after Done dismissal", 0, selection.runs);
        assertNull(w.seq.pendingAction());
        assertEquals("native Done outcome survives dismissal bookkeeping", nativeOutcome, w.memory.lastOutcome);
        assertEquals(CLICKED, w.memory.lastOutcome);
        assertNotEquals(dismissalStatus[0], w.memory.lastOutcome);
        assertTrue(dismissalStatus[0].contains("pending acquisition/refresh cancelled"));
        assertEquals("dismissal clears the supersession surface, not the native outcome",
                "", w.seq.retainedOutcome());
    }

    @Test public void uninitializedSkipsLookupConsumptionAndOutcome() {
        World w = new World();
        w.initialized = false;
        FakeDone control = new FakeDone();
        w.offerClickable(control);
        assertEquals("Service not initialized; native picker untouched; popup closed.", w.done(7L));
        assertEquals(0, control.clickCalls);
        assertEquals(0, w.lookup.calls);
        assertEquals(ChooserLifecycle.NO_SESSION, w.memory.consumedSession);
        assertEquals("none yet", w.memory.lastOutcome);
        assertEquals("", w.status.toString());
    }
}
