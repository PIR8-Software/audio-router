package com.roy.bluetoothglassesrouter;

import org.junit.Test;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Supplemental source contracts for the native audio-selector opener wiring;
 * not physical dock proof. The opener must launch the verified volume-panel
 * route INTERNAL-ONLY (the volume slider never appears on the external monitor),
 * tap only the identity-verified output row, leave the system picker open, keep
 * OUR popup external-first, and fire ONLY the opener (never the BT reset/reassert
 * or Remember/Restore paths) on display attach in this mode.
 *
 * CLASSIFICATION (F5): SOURCE-CONTRACT TRIPWIRE (wiring shape, not behavior).
 * Behavioral acquisition coverage lives in AcquireSequencerTest,
 * ServiceEntryConsequenceTest and SessionConsequenceWiringTest (real execution).
 */
public class SelectorWiringTest {

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    @Test public void volumePanelLaunchIsInternalOnlyAndPopupStaysExternal() throws Exception {
        String s = read("src/main/java/com/roy/bluetoothglassesrouter/BluetoothGlassesRoutingService.java");
        assertTrue(s.contains("SelectorOpener.PANEL_ACTION"));
        assertTrue(s.contains("SelectorOpener.pickDisplay")); // OUR popup target selection stays external-first
        assertTrue(s.contains("resolveActivity"));
        assertTrue(s.contains("openCustomPopupRun")); // attach fires the custom popup (acquisition rides the same panel path)
        // The volume slider must never appear on the external monitor: the native
        // volume panel is launched INTERNAL-ONLY with the
        // stable internal display constant - never OS-placed, never a rotating external id.
        assertTrue("panel launch must target the internal display",
                s.contains("setLaunchDisplayId(Display.DEFAULT_DISPLAY)"));
        int first = s.indexOf("setLaunchDisplayId(");
        assertTrue(first > 0);
        assertEquals("service must force only the internal display target",
                -1, s.indexOf("setLaunchDisplayId(", first + 1));
        assertFalse("panel launch must not stack tasks", s.contains("FLAG_ACTIVITY_MULTIPLE_TASK"));
        assertFalse(s.contains("dispatchGesture"));
        assertFalse(s.contains("input tap"));
        String opener = read("src/main/java/com/roy/bluetoothglassesrouter/SelectorOpener.java");
        assertFalse(opener.contains("getX()"));
        assertFalse(opener.contains("getY()"));
    }

    @Test public void rowSelectionIsIdentityGroupedWithActualDisplayEvidence() throws Exception {
        String s = read("src/main/java/com/roy/bluetoothglassesrouter/BluetoothGlassesRoutingService.java");
        assertTrue("verified rows carry their actual display identity", s.contains("w.getDisplayId()"));
        assertTrue("duplicate verified rows are grouped by identity", s.contains("ChooserLifecycle.pickRow"));
        // Tripwire adapted to the AcquireSequencer seam: the acquisition step plan
        // (reuse the open panel row, never relaunch) is orchestrated there now.
        String seq = read("src/main/java/com/roy/bluetoothglassesrouter/AcquireSequencer.java");
        assertTrue("acquisition reuses an open panel row instead of relaunching", seq.contains("ChooserLifecycle.acquireStep"));
        assertTrue(s.contains("SelectorOpener.rowValid"));
        String popup = read("src/main/java/com/roy/bluetoothglassesrouter/SelectorPopupActivity.java");
        assertTrue("OUR popup stays on the external display", popup.contains("setLaunchDisplayId"));
    }

    @Test public void attachFiresOnlyTheCustomPopupInSelectorMode() throws Exception {
        String s = read("src/main/java/com/roy/bluetoothglassesrouter/BluetoothGlassesRoutingService.java");
        int opener = s.indexOf("handler.postDelayed(openCustomPopupRun,DockMemory.SETTLE)");
        assertTrue("custom popup must be scheduled on attach", opener > 0);
        // Retired attach triggers must stay gone: BT reassert and Remember/Restore never fire.
        assertFalse("retired reassert attach trigger absent", s.contains("reassertRun"));
        assertFalse("retired Remember/Restore attach trigger absent", s.contains("restoreDock"));
        assertFalse("retired native-panel-only auto opener absent", s.contains("openSelectorRun"));
        assertTrue(s.contains("our popup opens, not the native panel"));
    }

    @Test public void rowClickIsIdentityGatedAndLeavesPickerOpen() throws Exception {
        String s = read("src/main/java/com/roy/bluetoothglassesrouter/BluetoothGlassesRoutingService.java");
        assertTrue(s.contains("SelectorOpener.rowValid"));
        assertFalse(s.contains("dispatchGesture"));
        assertFalse(s.contains("input tap"));
        String opener = read("src/main/java/com/roy/bluetoothglassesrouter/SelectorOpener.java");
        assertFalse(opener.contains("getX()"));
        assertFalse(opener.contains("getY()"));
    }

    @Test public void expectedLaunchChurnNeverCancelsAndStopClearsOptIn() throws Exception {
        String s = read("src/main/java/com/roy/bluetoothglassesrouter/BluetoothGlassesRoutingService.java");
        assertTrue(s.contains("expected launch churn"));
        assertTrue(s.contains("putBoolean(\"selector_auto_open\",false)"));
        assertTrue(s.contains("prefs.getBoolean(\"selector_auto_open\",true)"));
    }

    @Test public void sourceLifecycleAutoAcquiresIdlePickerBeforeEnumeratingAndMarksStale() throws Exception {
        String s = read("src/main/java/com/roy/bluetoothglassesrouter/BluetoothGlassesRoutingService.java");
        // source initially closed -> native opened -> populated: acquisition must run BEFORE enumeration
        assertTrue("acquisition seam must exist", s.contains("acquireThen"));
        assertTrue("source lifecycle plan must gate enumeration", s.contains("ChooserLifecycle.plan"));
        assertTrue("selection must revalidate against live titles", s.contains("ChooserLifecycle.gate"));
        assertTrue("refresh must reacquire via the production path", s.contains("refreshOutputs"));
        assertTrue("route snapshot must be persisted (chooser_cache)", s.contains("chooser_cache"));
        assertTrue("stale snapshot rows must be marked, not hidden", s.contains("STALE snapshot"));
        String p = read("src/main/java/com/roy/bluetoothglassesrouter/SelectorPopupActivity.java");
        assertTrue("stale rows revalidate before any click", p.contains("Revalidate & select"));
    }

    @Test public void uiExposesOptInStopAndSameProductionTestButton() throws Exception {
        String s = read("src/main/java/com/roy/bluetoothglassesrouter/MainActivity.java");
        assertTrue(s.contains("Open audio selector"));
        assertTrue(s.contains("Test popup-on-attach in 5 seconds"));
        assertTrue(s.contains("selector_auto_open"));
        String strings = read("src/main/res/values/strings.xml");
        assertTrue(strings.contains("selector_switch"));
    }

    @Test public void rowBudgetExpiryFallsBackToTheDocumentedSystemPickerOpener() throws Exception {
        String s = read("src/main/java/com/roy/bluetoothglassesrouter/BluetoothGlassesRoutingService.java");
        // The verified row is inert in display-output
        // states, so after the row budget is burned the documented SystemUI picker
        // action opens the real dialog; enumeration and selection gates are unchanged.
        assertTrue("row budget must expire before the fallback fires", s.contains("selectorOpener.expired"));
        assertTrue("fallback must use the documented SystemUI picker action",
                s.contains("\"com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG\""));
        assertTrue("the documented action requires its package_name extra", s.contains("\"package_name\""));
        assertTrue("fallback sends the system broadcast, never a gesture", s.contains("sendBroadcast"));
        assertTrue(s.contains("openPickerDirect"));
        assertFalse(s.contains("dispatchGesture"));
    }

    // CLASSIFICATION (F5): source-contract tripwire. The behavioral version of
    // this guarantee (panel-launch failure fires the documented picker opener and
    // acquisition stays live/polling until the budget) is executed for real in
    // AcquireSequencerTest.panelLaunchFailureOpensDocumentedPickerAndKeepsPolling.
    @Test public void panelLaunchFailureFallsBackToTheDocumentedPickerOpener() throws Exception {
        // Tripwire adapted to the AcquireSequencer seam: the acquire fallback branch
        // moved into the production-used orchestrator.
        String s = read("src/main/java/com/roy/bluetoothglassesrouter/AcquireSequencer.java");
        // If the internal panel launch is unavailable, acquisition must stay LIVE via the
        // documented SystemUI picker opener and keep polling - never dead-end to the
        // cached snapshot alone (the snapshot stays the last resort only).
        int failureBranch = s.indexOf("!host.launchVolumePanel()");
        assertTrue(failureBranch > 0);
        String after = s.substring(failureBranch, Math.min(s.length(), failureBranch + 700));
        assertTrue("panel-launch failure must fire the picker opener", after.contains("host.openPickerDirect()"));
        assertTrue("panel-launch failure must keep polling for live rows", after.contains("schedulePoll()"));
    }

    @Test public void popupIsACompactFloatingDialogNotFullScreenShell() throws Exception {
        String p = read("src/main/java/com/roy/bluetoothglassesrouter/SelectorPopupActivity.java");
        String m = read("src/main/java/com/roy/bluetoothglassesrouter/SelectorPopupModel.java");
        assertTrue("bounded window width from the model", p.contains("SelectorPopupModel.MAX_WIDTH_DP"));
        assertTrue("bounded scroll height from the model", p.contains("SelectorPopupModel.MAX_HEIGHT_DP"));
        assertTrue("dialog-like dim scrim behind the card", p.contains("FLAG_DIM_BEHIND"));
        assertTrue("wrap-content window height", p.contains("WindowManager.LayoutParams.WRAP_CONTENT"));
        assertTrue(m.contains("MAX_WIDTH_DP = 400"));
        assertFalse("no full black shell background", p.contains("setBackgroundColor(Color.BLACK)"));
        assertFalse("no fullscreen window layout", p.contains("setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)"));
    }

    @Test public void popupTaskIsIsolatedSoTheAppActivityIsNeverRaised() throws Exception {
        // With the default package affinity, the popup's NEW_TASK launch reuses the
        // MainActivity task, and finishing the popup resumes that activity.
        String m = read("src/main/AndroidManifest.xml");
        int popup = m.indexOf(".SelectorPopupActivity\"");
        assertTrue(popup > 0);
        String block = m.substring(popup, Math.min(m.length(), popup + 400));
        assertTrue("popup must live in its own task, never the app task",
                block.contains("android:taskAffinity=\":selector\""));
        assertTrue("single instance per relaunch (no duplicates)",
                block.contains("android:launchMode=\"singleTop\""));
        assertTrue(block.contains("android:excludeFromRecents=\"true\""));
        String p = read("src/main/java/com/roy/bluetoothglassesrouter/SelectorPopupActivity.java");
        assertTrue("service-launchable (NEW_TASK) but single-instance",
                p.contains("Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP"));
        String s = read("src/main/java/com/roy/bluetoothglassesrouter/BluetoothGlassesRoutingService.java");
        assertFalse("the service must never launch or raise the app Activity",
                s.contains("MainActivity"));
    }

    @Test public void selectionAutoRefreshesAndConfirmsFromTheLiveSource() throws Exception {
        // After an exact row click, a bounded async confirmation
        // re-reads the live native source and the popup auto-refreshes rows + the actual
        // active-route indicator. Never optimistic (no marking from the tap alone), no
        // manual Refresh/reopen, no reopen after the user closed the popup.
        String s = read("src/main/java/com/roy/bluetoothglassesrouter/BluetoothGlassesRoutingService.java");
        assertTrue("bounded confirm poll must exist", s.contains("confirmPoll"));
        assertTrue("confirmation truth comes from the live source",
                s.contains("ChooserLifecycle.selectionConfirmed"));
        assertTrue("active-route indicator is the source's actual state",
                s.contains("ChooserLifecycle.currentTitle"));
        assertTrue("auto-refresh after selection", s.contains("refreshAfterSelection"));
        assertTrue("the popup is never reopened behind the user",
                s.contains("SelectorPopupActivity.isOpen()"));
        assertTrue("one confirm loop at a time (no duplicates)",
                s.contains("handler.removeCallbacks(confirmPoll)"));
        String c = read("src/main/java/com/roy/bluetoothglassesrouter/ChooserLifecycle.java");
        assertTrue("honest outcome text seam", c.contains("confirmStatus"));
    }
}
