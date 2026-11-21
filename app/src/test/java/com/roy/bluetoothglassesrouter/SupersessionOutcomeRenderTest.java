package com.roy.bluetoothglassesrouter;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Behavioral coverage of the popup's outcome surfaces ({@link SelectorPopupModel},
 * the exact pure functions SelectorPopupActivity.render() composes): the transient
 * status line and the SEPARATE retained outcome line. F2 requires the retained
 * supersession outcome to survive popup refresh/re-render (ordinary status writes)
 * until the user's next intentional action.
 *
 * CLASSIFICATION (F5): behavioral (real execution) - not a source-string tripwire.
 */
public class SupersessionOutcomeRenderTest {

    @Test public void statusTextMarksStaleSnapshotRowsFirst() {
        assertEquals("STALE snapshot (rows not validated now). Popup loaded: 3 row(s).",
                SelectorPopupModel.statusText("Popup loaded: 3 row(s).", true));
        assertEquals("Popup loaded: 3 row(s).",
                SelectorPopupModel.statusText("Popup loaded: 3 row(s).", false));
        assertEquals("", SelectorPopupModel.statusText(null, false));
    }

    @Test public void retainedNoticeIsEmptyWhenNothingIsRetained() {
        assertEquals("", SelectorPopupModel.retainedNotice(""));
        assertEquals("", SelectorPopupModel.retainedNotice(null));
    }

    @Test public void retainedOutcomeSurvivesPopupRefreshRenders() {
        // The retained line is a SEPARATE surface from the status line, so the
        // production overwrite pattern (status = "Popup loaded: ...") can never
        // destroy it - visible until the next intentional action clears it.
        String retained = "Superseded: pending \"selection revalidation\" cancelled and "
                + " replaced by \"refresh outputs\"; it will not run (fail closed, no stale click). "
                + "Shown until your next action.";
        // Popup refresh re-render (popupFromLive): ordinary status changes twice.
        assertEquals("Popup loaded: 2 live row(s) on display 2.",
                SelectorPopupModel.statusText("Popup loaded: 2 live row(s) on display 2.", false));
        assertTrue("retained outcome still rendered after the refresh",
                SelectorPopupModel.retainedNotice(retained).contains("selection revalidation"));
        // Stale-snapshot render: status grows a prefix; retained is untouched.
        String staleStatus = SelectorPopupModel.statusText("Popup loaded: 2 live row(s).", true);
        assertTrue(staleStatus.startsWith("STALE snapshot"));
        assertTrue("retained outcome still rendered on the stale render",
                SelectorPopupModel.retainedNotice(retained).contains("refresh outputs"));
    }
}
