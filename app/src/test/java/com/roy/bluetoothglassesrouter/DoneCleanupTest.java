package com.roy.bluetoothglassesrouter;

import org.junit.Test;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Session-owned Done cleanup regression . OUR custom popup's Done must
 * request the bounded, exact Done action of the native PHONE media output picker ONLY
 * when that picker is verified to belong to the current Audio Router
 * acquisition/session; then our popup closes. Every other case - picker already
 * absent, a Done-looking control in a wrong/foreign window, a stale session, a
 * repeated Done tap, or a dismissal while an async refresh/acquisition is pending -
 * must close OUR popup and leave native UI untouched. Never a blind KEYCODE_BACK,
 * never an arbitrary window dismiss, never the picker's stop-casting control.
 *
 * Exact native Done identity ground truth (system media-output dialog layout, API 37 structure): the
 * dialog_footer Flow declares constraint_referenced_ids="stop,done"; Button @id/done
 * carries string/inline_done_button = "Done"; sibling @id/stop is "stop casting" and
 * must NEVER be an acceptable target.
 *
 * CLASSIFICATION (F5): HYBRID. The ChooserLifecycle truth-table tests in this
 * file are behavioral (real execution); the wiring-contract methods are
 * SOURCE-CONTRACT TRIPWIRES (explicitly labeled per method). Behavioral
 * Done/cancellation consequence coverage lives in SessionConsequenceWiringTest.
 */
public class DoneCleanupTest {

    // ---- behavioral: native Done exists -> exactly one exact click ----

    @Test public void nativeDoneExistsIsClickedExactlyOnce() {
        assertEquals(ChooserLifecycle.DonePlan.CLICK_OWNED_PICKER_DONE,
                ChooserLifecycle.donePlan(true, false, true, true));
    }

    // ---- behavioral: picker already absent -> close own popup normally ----

    @Test public void pickerAlreadyAbsentClosesOwnPopupNormally() {
        assertEquals(ChooserLifecycle.DonePlan.CLOSE_ONLY_PICKER_ABSENT,
                ChooserLifecycle.donePlan(true, false, false, false));
    }

    // ---- behavioral: wrongwindow -> Done control outside the verified picker
    //      dialog (foreign dialog / our own popup's Done) is never clicked ----

    @Test public void wrongWindowDoneIsNeverClicked() {
        // A Done-looking control exists only in some other window: picker absent to us.
        assertEquals(ChooserLifecycle.DonePlan.CLOSE_ONLY_PICKER_ABSENT,
                ChooserLifecycle.donePlan(true, false, false, false));
        // Picker window present but its exact Done control not verified there
        // (ambiguous, wrong window, or not the exact identity): touch nothing.
        assertEquals(ChooserLifecycle.DonePlan.CLOSE_ONLY_UNVERIFIED,
                ChooserLifecycle.donePlan(true, false, true, false));
    }

    // ---- behavioral: stale session -> even a fully verified picker is untouched ----

    @Test public void staleSessionNeverTouchesNative() {
        assertEquals(ChooserLifecycle.DonePlan.CLOSE_ONLY_STALE_SESSION,
                ChooserLifecycle.donePlan(false, false, true, true));
        assertEquals(ChooserLifecycle.DonePlan.CLOSE_ONLY_STALE_SESSION,
                ChooserLifecycle.donePlan(false, false, false, false));
    }

    // ---- behavioral: repeated Done -> idempotent, one exact click per session ----

    @Test public void repeatedDoneIsIdempotent() {
        // Second request in the same session never clicks again, even with the
        // picker and its exact Done still on screen.
        assertEquals(ChooserLifecycle.DonePlan.CLOSE_ONLY_ALREADY_DONE,
                ChooserLifecycle.donePlan(true, true, true, true));
    }

    // ---- behavioral: only the exact verified combination may ever click native ----

    @Test public void onlyTheExactVerifiedCombinationMayClickNative() {
        for (boolean session : new boolean[]{true, false})
            for (boolean consumed : new boolean[]{true, false})
                for (boolean picker : new boolean[]{true, false})
                    for (boolean done : new boolean[]{true, false}) {
                        ChooserLifecycle.DonePlan p =
                                ChooserLifecycle.donePlan(session, consumed, picker, done);
                        if (!(session && !consumed && picker && done))
                            assertNotEquals("must not click native: session=" + session
                                            + " consumed=" + consumed + " picker=" + picker
                                            + " done=" + done,
                                    ChooserLifecycle.DonePlan.CLICK_OWNED_PICKER_DONE, p);
                    }
    }

    // ---- behavioral: dismiss while async refresh/acquisition pending ->
    //      the dismissed popup never reopens itself and the native helper is
    //      never restarted for it ----

    @Test public void dismissedWhileAsyncPendingNeverReopens() {
        assertTrue("a live open popup may be refreshed",
                ChooserLifecycle.continuationAllowed(true, false));
        assertFalse("a user-dismissed popup is never reopened by a continuation",
                ChooserLifecycle.continuationAllowed(false, true));
        assertFalse("a closed popup is never reopened by a continuation",
                ChooserLifecycle.continuationAllowed(false, false));
        assertFalse("fail closed even on inconsistent state",
                ChooserLifecycle.continuationAllowed(true, true));
    }

    // ---- wiring contracts: the service command binding actually exists ----

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    @Test public void doneBindsToTheSessionScopedServiceCommandThenClosesOurPopup() throws Exception {
        String p = read("src/main/java/com/roy/bluetoothglassesrouter/SelectorPopupActivity.java");
        assertTrue("Done must use the existing service command binding",
                p.contains("s.closeOwnedPicker(sessionToken)"));
        assertTrue("the session token travels via the launch Intent (never static cross-process state)",
                p.contains("getLongExtra(EXTRA_SESSION"));
        assertTrue("our popup closes after the bounded native request", p.contains("finish()"));
        String s = read("src/main/java/com/roy/bluetoothglassesrouter/BluetoothGlassesRoutingService.java");
        String entry = read("src/main/java/com/roy/bluetoothglassesrouter/ServiceEntry.java");
        assertTrue("bounded one-shot native Done command exists", s.contains("closeOwnedPicker"));
        assertTrue("the service delegates the Done entry to the production seam",
                s.contains("ServiceEntry.closeOwnedPicker"));
        // CLASSIFICATION (F5): source-contract tripwire. The plan/click strings moved
        // into ServiceEntry with the production body. Behavioral proof is
        // DoneSessionConsequenceTest, which executes that seam.
        assertTrue("the click is gated on the pure plan", entry.contains("ChooserLifecycle.donePlan"));
        assertTrue("only the exact verified plan clicks",
                entry.contains("ChooserLifecycle.DonePlan.CLICK_OWNED_PICKER_DONE"));
        assertTrue("the one-shot consumption write lives in the seam the service calls",
                entry.contains("memory.consumedSession = requestSession"));
    }

    // CLASSIFICATION (F5): the assertions in this method are SOURCE-CONTRACT
    // TRIPWIRES pinning cancellation wiring shape - not behavioral proof. The
    // behavior (a cancelled/superseded continuation never executes, no
    // resurrection, delayed old polls run nothing) is executed for real in
    // AcquireSequencerTest / SessionConsequenceWiringTest.
    @Test public void userDismissalCancelsPendingAcquisitionSoNothingReopens() throws Exception {
        String p = read("src/main/java/com/roy/bluetoothglassesrouter/SelectorPopupActivity.java");
        assertTrue("a user finish (Done/back) must notify the service", p.contains("s.onPopupUserClosed()"));
        String s = read("src/main/java/com/roy/bluetoothglassesrouter/BluetoothGlassesRoutingService.java");
        String seq = read("src/main/java/com/roy/bluetoothglassesrouter/AcquireSequencer.java");
        assertTrue("dismissal handler exists", s.contains("onPopupUserClosed"));
        // Tripwire adapted to the AcquireSequencer seam (the acquire poll/continuation
        // moved into the production-used orchestrator): dismissal must cancel it there.
        assertTrue("pending acquire poll is cancelled", s.contains("acquireSequencer.cancel()"));
        assertTrue("pending selector step is cancelled", s.contains("handler.removeCallbacks(selectorStep)"));
        assertTrue("pending confirm/auto-refresh poll is cancelled", s.contains("handler.removeCallbacks(confirmPoll)"));
        assertTrue("the acquisition continuation is dropped", seq.contains("pending = null"));
        assertTrue("a fresh popup session resets the one-shot Done consumption",
                s.contains("ServiceEntry.beginPopupSession(doneMemory)"));
        String entry = read("src/main/java/com/roy/bluetoothglassesrouter/ServiceEntry.java");
        assertTrue("the reset the service calls clears the consumed session",
                entry.contains("consumedSession = ChooserLifecycle.NO_SESSION"));
        assertFalse("Done ownership is the popup session and survives refresh/reacquire/cancel churn",
                s.contains("sessionGen"));
        assertTrue("async continuations reopen only through the pure gate",
                s.contains("ChooserLifecycle.continuationAllowed"));
    }

    @Test public void noBlindBackOrArbitraryWindowDismissalAnywhere() throws Exception {
        String[] files = {"BluetoothGlassesRoutingService.java", "SelectorPopupActivity.java",
                "ChooserLifecycle.java", "SpikeMapper.java", "SelectorOpener.java", "ServiceEntry.java"};
        for (String f : files) {
            String src = read("src/main/java/com/roy/bluetoothglassesrouter/" + f);
            assertFalse(f + ": no KEYCODE_BACK", src.contains("KEYCODE_BACK"));
            assertFalse(f + ": no global back action", src.contains("GLOBAL_ACTION_BACK"));
            assertFalse(f + ": no performGlobalAction", src.contains("performGlobalAction"));
            assertFalse(f + ": no gesture injection", src.contains("dispatchGesture"));
            assertFalse(f + ": no shell input injection", src.contains("input keyevent") || src.contains("input tap"));
        }
    }

    @Test public void nativeDoneIdentityIsExactAndScopedToTheVerifiedDialog() throws Exception {
        String m = read("src/main/java/com/roy/bluetoothglassesrouter/SpikeMapper.java");
        assertTrue("exact Done control id from SystemUIGoogle ground truth",
                m.contains("DONE_ID = \"done\""));
        assertTrue("exact Done label", m.contains("DONE_TEXT = \"Done\""));
        String s = read("src/main/java/com/roy/bluetoothglassesrouter/BluetoothGlassesRoutingService.java");
        assertTrue("identity gates use the exact id and label",
                s.contains("SpikeMapper.DONE_ID") && s.contains("SpikeMapper.DONE_TEXT"));
        assertTrue("the Done control must be the Button class", s.contains("isButtonClass"));
        assertFalse("the stop-casting control is never targeted", s.contains("SpikeMapper.STOP_ID"));
        assertTrue("the finder is scoped to the verified picker dialog subtree",
                s.contains("verifiedPickerDone"));
    }
}
