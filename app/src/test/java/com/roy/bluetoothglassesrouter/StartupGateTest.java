package com.roy.bluetoothglassesrouter;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Realistic lifecycle harness for the background/boot hardening:
 * a pure state machine deciding when the selector popup is offered so startup is
 * idempotent - exactly one popup per dock session, never a duplicate after service
 * rebind/kill recovery, never reopened behind a user who closed it, and never
 * before unlock. Scenarios cover: Activity not open / task
 * swiped away (popup closed), service rebind/kill recovery, boot/unlock signals,
 * attach/reconnect deduplication.
 */
public class StartupGateTest {

    private static final long BOOT = 7;
    private static final long NOW = 1000;

    private StartupGate.State state() {
        return new StartupGate.State();
    }

    // ---- first run / install (never surprise the user at install or update) ----

    @Test public void firstRunWhileDockedIsConservativelySatisfied() {
        StartupGate.State s = state();
        assertEquals(StartupGate.Decision.SKIP_SATISFIED,
                StartupGate.decide(s, StartupGate.Trigger.START, BOOT, true, true, true, false, NOW));
        assertTrue(s.satisfied);
    }

    @Test public void firstRunWhileUndockedArmsNormally() {
        StartupGate.State s = state();
        assertEquals(StartupGate.Decision.SKIP_NOT_DOCKED,
                StartupGate.decide(s, StartupGate.Trigger.START, BOOT, false, true, true, false, NOW));
        assertFalse(s.satisfied);
    }

    // ---- boot startup (run on boot while docked: one offer per boot) ----

    @Test public void bootWhileDockedOffersExactlyOncePerBoot() {
        StartupGate.State s = state();
        StartupGate.decide(s, StartupGate.Trigger.START, BOOT, false, true, true, false, NOW); // first record undocked
        // reboot (new boot count) while docked: the popup must be offered...
        assertEquals(StartupGate.Decision.OFFER,
                StartupGate.decide(s, StartupGate.Trigger.START, BOOT + 1, true, true, true, false, NOW));
        StartupGate.markShown(s); // popup launched
        // ...exactly once: any later boot/unlock signal is idempotent.
        assertEquals(StartupGate.Decision.SKIP_SATISFIED,
                StartupGate.decide(s, StartupGate.Trigger.START, BOOT + 1, true, true, true, false, NOW + 5));
        assertEquals(StartupGate.Decision.SKIP_SATISFIED,
                StartupGate.decide(s, StartupGate.Trigger.START, BOOT + 1, true, true, true, false, NOW + 5000));
    }

    @Test public void bootResetClearsTheStaleDetachWindow() {
        StartupGate.State s = state();
        StartupGate.decide(s, StartupGate.Trigger.START, BOOT, false, true, true, false, NOW);
        StartupGate.decide(s, StartupGate.Trigger.DETACH, BOOT, false, true, true, true, NOW + 10); // detach recently
        // After a reboot the elapsed-realtime clock restarts: a stale detach timestamp
        // must not look like a fresh reconnect and must not suppress the boot offer.
        assertEquals(StartupGate.Decision.OFFER,
                StartupGate.decide(s, StartupGate.Trigger.START, BOOT + 1, true, true, true, false, NOW + 20));
    }

    // ---- service rebind / kill recovery (idempotent startup, no duplicates) ----

    @Test public void rebindAfterProcessKillDoesNotRepeatThePopup() {
        StartupGate.State s = state();
        StartupGate.decide(s, StartupGate.Trigger.START, BOOT, false, true, true, false, NOW);
        assertEquals(StartupGate.Decision.OFFER,
                StartupGate.decide(s, StartupGate.Trigger.START, BOOT, true, true, true, false, NOW + 30));
        StartupGate.markShown(s); // popup launched
        // process killed + system rebinds the accessibility service in the same boot:
        assertEquals(StartupGate.Decision.SKIP_SATISFIED,
                StartupGate.decide(s, StartupGate.Trigger.START, BOOT, true, true, true, false, NOW + 60));
    }

    @Test public void killDuringAcquisitionRecoversWithExactlyOneOffer() {
        StartupGate.State s = state();
        StartupGate.decide(s, StartupGate.Trigger.START, BOOT, false, true, true, false, NOW);
        assertEquals(StartupGate.Decision.OFFER,
                StartupGate.decide(s, StartupGate.Trigger.START, BOOT, true, true, true, false, NOW + 30));
        // process died before the popup ever launched (mid acquisition): not satisfied,
        // so the rebind must recover and offer again - exactly one popup, not zero.
        assertEquals(StartupGate.Decision.OFFER,
                StartupGate.decide(s, StartupGate.Trigger.START, BOOT, true, true, true, false, NOW + 60));
        StartupGate.markShown(s);
        assertEquals(StartupGate.Decision.SKIP_SATISFIED,
                StartupGate.decide(s, StartupGate.Trigger.START, BOOT, true, true, true, false, NOW + 90));
    }

    // ---- lock deferral + unlock retry (dock while locked must not lose the popup) ----

    @Test public void dockWhileLockedDefersAndUnlockOffersExactlyOnce() {
        StartupGate.State s = state();
        StartupGate.decide(s, StartupGate.Trigger.START, BOOT, false, true, true, false, NOW);
        // docked while the keyguard is up: no popup now...
        assertEquals(StartupGate.Decision.SKIP_LOCKED,
                StartupGate.decide(s, StartupGate.Trigger.ATTACH, BOOT, true, true, false, false, NOW + 30));
        assertTrue(s.pending);
        // ...but the offer is not lost: the unlock signal retries it once.
        assertEquals(StartupGate.Decision.OFFER,
                StartupGate.decide(s, StartupGate.Trigger.START, BOOT, true, true, true, false, NOW + 600));
        StartupGate.markShown(s);
        assertEquals(StartupGate.Decision.SKIP_SATISFIED,
                StartupGate.decide(s, StartupGate.Trigger.START, BOOT, true, true, true, false, NOW + 700));
    }

    @Test public void pendingOfferSurvivesProcessKillBeforeUnlock() {
        StartupGate.State s = state();
        StartupGate.decide(s, StartupGate.Trigger.START, BOOT, false, true, true, false, NOW);
        StartupGate.decide(s, StartupGate.Trigger.ATTACH, BOOT, true, true, false, false, NOW + 30);
        // service restarts while still locked: still deferred, still pending.
        assertEquals(StartupGate.Decision.SKIP_LOCKED,
                StartupGate.decide(s, StartupGate.Trigger.START, BOOT, true, true, false, false, NOW + 40));
        assertTrue(s.pending);
        assertEquals(StartupGate.Decision.OFFER,
                StartupGate.decide(s, StartupGate.Trigger.START, BOOT, true, true, true, false, NOW + 500));
    }

    // ---- never reopen behind a user who closed the popup (Activity dismissed / task swiped) ----

    @Test public void closedPopupIsNeverReopenedByLaterSignals() {
        StartupGate.State s = state();
        StartupGate.decide(s, StartupGate.Trigger.START, BOOT, false, true, true, false, NOW);
        StartupGate.decide(s, StartupGate.Trigger.ATTACH, BOOT, true, true, true, false, NOW + 30);
        StartupGate.markShown(s); // popup launched and then closed by the user (Done / task swipe)
        assertEquals(StartupGate.Decision.SKIP_SATISFIED,
                StartupGate.decide(s, StartupGate.Trigger.START, BOOT, true, true, true, false, NOW + 600));
        assertEquals(StartupGate.Decision.SKIP_SATISFIED,
                StartupGate.decide(s, StartupGate.Trigger.ATTACH, BOOT, true, true, true, false, NOW + 700));
    }

    // ---- attach/reconnect deduplication (dock re-enumeration / id rotation) ----

    @Test public void reconnectWithinWindowRestoresThePopupTheDisplayRemovalKilled() {
        StartupGate.State s = state();
        StartupGate.decide(s, StartupGate.Trigger.START, BOOT, false, true, true, false, NOW);
        StartupGate.decide(s, StartupGate.Trigger.ATTACH, BOOT, true, true, true, false, NOW + 30);
        StartupGate.markShown(s);
        // display removed while the popup was ON it (the Activity dies with its display):
        assertEquals(StartupGate.Decision.SKIP_NOT_DOCKED,
                StartupGate.decide(s, StartupGate.Trigger.DETACH, BOOT, false, true, true, true, NOW + 40));
        // quick re-enumeration (same dock session): restore exactly one popup.
        assertEquals(StartupGate.Decision.OFFER,
                StartupGate.decide(s, StartupGate.Trigger.ATTACH, BOOT, true, true, true, false, NOW + 41 + StartupGate.RECONNECT_WINDOW_MS / 2));
    }

    @Test public void reconnectWithinWindowNeverReopensAClosedPopup() {
        StartupGate.State s = state();
        StartupGate.decide(s, StartupGate.Trigger.START, BOOT, false, true, true, false, NOW);
        StartupGate.decide(s, StartupGate.Trigger.ATTACH, BOOT, true, true, true, false, NOW + 30);
        StartupGate.markShown(s);
        // popup already closed by the user when the display blipped:
        assertEquals(StartupGate.Decision.SKIP_NOT_DOCKED,
                StartupGate.decide(s, StartupGate.Trigger.DETACH, BOOT, false, true, true, false, NOW + 40));
        assertEquals(StartupGate.Decision.SKIP_RECONNECT,
                StartupGate.decide(s, StartupGate.Trigger.ATTACH, BOOT, true, true, true, false, NOW + 41 + StartupGate.RECONNECT_WINDOW_MS / 2));
    }

    @Test public void reconnectWithPopupStillOpenDoesNothing() {
        StartupGate.State s = state();
        StartupGate.decide(s, StartupGate.Trigger.START, BOOT, false, true, true, false, NOW);
        StartupGate.decide(s, StartupGate.Trigger.ATTACH, BOOT, true, true, true, false, NOW + 30);
        StartupGate.markShown(s);
        StartupGate.decide(s, StartupGate.Trigger.DETACH, BOOT, false, true, true, true, NOW + 40);
        assertEquals(StartupGate.Decision.SKIP_POPUP_OPEN,
                StartupGate.decide(s, StartupGate.Trigger.ATTACH, BOOT, true, true, true, true, NOW + 41 + StartupGate.RECONNECT_WINDOW_MS / 2));
    }

    @Test public void quickBlipDuringSettleRecoversExactlyOnePopup() {
        StartupGate.State s = state();
        StartupGate.decide(s, StartupGate.Trigger.START, BOOT, false, true, true, false, NOW);
        assertEquals(StartupGate.Decision.OFFER,
                StartupGate.decide(s, StartupGate.Trigger.ATTACH, BOOT, true, true, true, false, NOW + 30));
        // display blipped before the popup launched (nothing satisfied yet): the
        // reconnect must re-offer rather than swallow the pending popup.
        StartupGate.decide(s, StartupGate.Trigger.DETACH, BOOT, false, true, true, false, NOW + 35);
        assertEquals(StartupGate.Decision.OFFER,
                StartupGate.decide(s, StartupGate.Trigger.ATTACH, BOOT, true, true, true, false, NOW + 36 + StartupGate.RECONNECT_WINDOW_MS / 2));
        StartupGate.markShown(s);
        assertEquals(StartupGate.Decision.SKIP_SATISFIED,
                StartupGate.decide(s, StartupGate.Trigger.START, BOOT, true, true, true, false, NOW + 600));
    }

    @Test public void genuineRedockAfterTheWindowStartsANewSession() {
        StartupGate.State s = state();
        StartupGate.decide(s, StartupGate.Trigger.START, BOOT, false, true, true, false, NOW);
        StartupGate.decide(s, StartupGate.Trigger.ATTACH, BOOT, true, true, true, false, NOW + 30);
        StartupGate.markShown(s);
        StartupGate.decide(s, StartupGate.Trigger.DETACH, BOOT, false, true, true, false, NOW + 40);
        // Undock and redock later than the reconnect window starts one popup
        // for the new dock session.
        assertEquals(StartupGate.Decision.OFFER,
                StartupGate.decide(s, StartupGate.Trigger.ATTACH, BOOT, true, true, true, false, NOW + 41 + StartupGate.RECONNECT_WINDOW_MS));
    }

    // ---- opt-in consent (the selector switch is the single enable/disable gate) ----

    @Test public void selectorOptOutConsumesTheSessionWithoutAnyPopup() {
        StartupGate.State s = state();
        StartupGate.decide(s, StartupGate.Trigger.START, BOOT, false, true, true, false, NOW);
        assertEquals(StartupGate.Decision.SKIP_NO_SELECTOR,
                StartupGate.decide(s, StartupGate.Trigger.ATTACH, BOOT, true, false, true, false, NOW + 30));
        assertTrue(s.satisfied);
        assertEquals(StartupGate.Decision.SKIP_SATISFIED,
                StartupGate.decide(s, StartupGate.Trigger.START, BOOT, true, false, true, false, NOW + 60));
    }

    @Test public void optOutToggledAfterALockedAttachNeverPopsLater() {
        StartupGate.State s = state();
        StartupGate.decide(s, StartupGate.Trigger.START, BOOT, false, true, true, false, NOW);
        StartupGate.decide(s, StartupGate.Trigger.ATTACH, BOOT, true, true, false, false, NOW + 30);
        // user turns the selector off before unlocking: consent wins, no popup.
        assertEquals(StartupGate.Decision.SKIP_NO_SELECTOR,
                StartupGate.decide(s, StartupGate.Trigger.START, BOOT, true, false, true, false, NOW + 600));
        assertTrue(s.satisfied);
        assertFalse(s.pending);
    }

    // ---- general idempotency ----

    @Test public void nothingEverHappensWhileNotDocked() {
        StartupGate.State s = state();
        assertEquals(StartupGate.Decision.SKIP_NOT_DOCKED,
                StartupGate.decide(s, StartupGate.Trigger.START, BOOT, false, true, true, false, NOW));
        assertEquals(StartupGate.Decision.SKIP_NOT_DOCKED,
                StartupGate.decide(s, StartupGate.Trigger.START, BOOT, false, true, true, false, NOW + 10));
    }

    @Test public void popupAlreadyOpenSuppressesDuplicateOffers() {
        StartupGate.State s = state();
        StartupGate.decide(s, StartupGate.Trigger.START, BOOT, false, true, true, false, NOW);
        assertEquals(StartupGate.Decision.OFFER,
                StartupGate.decide(s, StartupGate.Trigger.ATTACH, BOOT, true, true, true, false, NOW + 30));
        // a late signal while the popup window is already up: never a second one.
        assertEquals(StartupGate.Decision.SKIP_POPUP_OPEN,
                StartupGate.decide(s, StartupGate.Trigger.START, BOOT, true, true, true, true, NOW + 40));
    }

    @Test public void markShownConsumesBothPendingAndSatisfied() {
        StartupGate.State s = state();
        s.pending = true;
        StartupGate.markShown(s);
        assertTrue(s.satisfied);
        assertFalse(s.pending);
    }

    // ---- internal prefs keys never cancel flows (the prefs listener contract) ----

    @Test public void internalStateKeysAreExcludedFromConfigChangeHandling() {
        assertTrue(StartupGate.internalStateKey("chooser_cache"));
        assertTrue(StartupGate.internalStateKey("chooser_cache_at"));
        assertTrue(StartupGate.internalStateKey("gate_boot"));
        assertTrue(StartupGate.internalStateKey("gate_satisfied"));
        assertFalse(StartupGate.internalStateKey("selector_auto_open"));
        assertFalse(StartupGate.internalStateKey(null));
        assertFalse(StartupGate.internalStateKey("target_address"));
    }
}
