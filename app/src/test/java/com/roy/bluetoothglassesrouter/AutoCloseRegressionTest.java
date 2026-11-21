package com.roy.bluetoothglassesrouter;

import org.junit.Test;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Close-both regression and five-second confirmed-success dismissal.
 *
 * (1) Done ownership is the POPUP session and must survive route auto-refresh,
 *     picker reacquire and unrelated async cancels. Generation reuse or cancel
 *     churn must not refuse Done or leave the phone picker open.
 * (2) ONLY a confirmed AUTOMATIC selection (or an already-current target) may
 *     close BOTH popups; manual selections keep the established auto-refresh;
 *     failures keep the picker open with the reason visible; nothing ever acts
 *     behind a dismissed popup.
 *
 * CLASSIFICATION (F5): HYBRID. ChooserLifecycle/AutoSelectPolicy truth-table
 * methods are behavioral (real execution); the wiring-contract methods are
 * SOURCE-CONTRACT TRIPWIRES (explicitly labeled per method). Behavioral
 * supersession/timer consequence coverage lives in AcquireSequencerTest and
 * SessionConsequenceWiringTest.
 */
public class AutoCloseRegressionTest {

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    // ---- (1) real session refresh/reacquire mismatch ----

    @Test public void doneOwnershipIsExactAndSurvivesRefreshReacquireAndCancel() {
        assertTrue("the live popup session owns its picker",
                ChooserLifecycle.doneSessionCurrent(7L, 7L));
        assertFalse("a superseded popup session must never click",
                ChooserLifecycle.doneSessionCurrent(6L, 7L));
        assertFalse("NO_SESSION never owns a picker",
                ChooserLifecycle.doneSessionCurrent(ChooserLifecycle.NO_SESSION,
                        ChooserLifecycle.NO_SESSION));
        assertFalse("a tokenless request is always refused",
                ChooserLifecycle.doneSessionCurrent(ChooserLifecycle.NO_SESSION, 7L));
    }

    @Test public void repeatedDoneRefusesOnlyWithinTheSameSession() {
        assertEquals("one exact click per popup session",
                ChooserLifecycle.DonePlan.CLICK_OWNED_PICKER_DONE,
                ChooserLifecycle.donePlan(true, false, true, true));
        assertEquals("a repeat tap in the same session is idempotent",
                ChooserLifecycle.DonePlan.CLOSE_ONLY_ALREADY_DONE,
                ChooserLifecycle.donePlan(true, true, true, true));
    }

    @Test public void wiringDoneOwnershipIsThePopupSessionNotAPerEnumerationGeneration() throws Exception {
        String s = read("src/main/java/com/roy/bluetoothglassesrouter/BluetoothGlassesRoutingService.java");
        String entry = read("src/main/java/com/roy/bluetoothglassesrouter/ServiceEntry.java");
        // CLASSIFICATION (F5): source-contract tripwire. Ownership check and the
        // preserved native outcome moved into ServiceEntry with the production body.
        // Behavioral proof is DoneSessionConsequenceTest.
        assertTrue("Done checks the popup-session token",
                entry.contains("ChooserLifecycle.doneSessionCurrent(requestSession, popupSession)"));
        assertTrue("the service passes the live popup session into that check",
                s.contains("doneMemory,popupSessionGen"));
        assertFalse("no per-enumeration generation: ownership survives refresh/reacquire/cancel churn",
                s.contains("sessionGen"));
        assertTrue("a fresh popup session resets the one-shot consumption",
                s.contains("ServiceEntry.beginPopupSession(doneMemory)"));
        assertTrue("the exact Done result is preserved separately from popup-close bookkeeping",
                entry.contains("memory.lastOutcome = out"));
    }

    // ---- (2) successful confirmed auto -> both popups close ----

    @Test public void confirmedAutomaticSelectionClosesBothPopups() {
        assertTrue("a confirmed 5s auto selection closes OUR popup and the owned picker",
                ChooserLifecycle.autoSuccessClose(true, true, true, false));
    }

    // ---- (3) already-current auto closes after the 5s ----

    @Test public void alreadyCurrentTargetClosesBothPopupsAfterFiveSeconds() {
        // ALREADY_CURRENT is a confirmed success state (the fresh live list at
        // expiry reports the target as current): same close gate, no route click.
        assertTrue(ChooserLifecycle.autoSuccessClose(true, true, true, false));
    }

    @Test public void wiringBothSuccessPathsReachTheDoubleClose() throws Exception {
        String s = read("src/main/java/com/roy/bluetoothglassesrouter/BluetoothGlassesRoutingService.java");
        assertTrue("the confirmed-auto gate is consulted", s.contains("ChooserLifecycle.autoSuccessClose("));
        assertTrue("the auto close reuses the bounded session-owned picker Done path",
                s.contains("closeOwnedPicker(popupSessionGen)"));
        assertTrue("the auto close also closes OUR popup", s.contains("SelectorPopupActivity.closeAll()"));
        assertTrue("already-current expiry routes to the same double close",
                s.contains("AutoSelectPolicy.ExpiryAction.ALREADY_CURRENT"));
        String p = read("src/main/java/com/roy/bluetoothglassesrouter/SelectorPopupActivity.java");
        assertTrue("our popup exposes a bounded close hook", p.contains("static void closeAll()"));
    }

    // ---- (4) failure / manual / unconfirmed never auto-close ----

    @Test public void failuresAndManualSelectionsNeverAutoClose() {
        assertFalse("unconfirmed: leave open with the visible reason",
                ChooserLifecycle.autoSuccessClose(true, false, true, false));
        assertFalse("manual selection keeps the established auto-refresh",
                ChooserLifecycle.autoSuccessClose(false, true, true, false));
        assertFalse("no random close when the popup is gone",
                ChooserLifecycle.autoSuccessClose(true, true, false, false));
    }

    // ---- (5) delayed confirmation dismissal guard ----

    @Test public void delayedConfirmationNeverActsBehindTheUser() {
        assertFalse("a dismissed popup is never closed/reopened behind the user",
                ChooserLifecycle.autoSuccessClose(true, true, true, true));
        assertFalse(ChooserLifecycle.autoSuccessClose(true, true, false, true));
        assertTrue("only a live popup with a confirmed automatic success may close both",
                ChooserLifecycle.autoSuccessClose(true, true, true, false));
    }
}
