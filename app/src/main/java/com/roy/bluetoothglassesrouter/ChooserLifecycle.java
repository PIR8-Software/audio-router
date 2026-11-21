package com.roy.bluetoothglassesrouter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure-JVM source lifecycle for the external selector. Real rows can only be
 * enumerated while the native output picker is OPEN, so this policy separates
 * three honest states: enumerate live now, auto-acquire the idle-capable native
 * picker first (volume panel -> verified output row, then enumerate), or fall
 * back to a clearly-marked STALE snapshot. A snapshot is never a validated
 * destination: selection from a closed/changed source must reacquire and
 * re-compare before any native row click. Fail closed everywhere; the app
 * never routes.
 */
final class ChooserLifecycle {

    enum Plan {
        /** Native picker open: enumerate live immediately. */
        ENUMERATE_LIVE,
        /** Picker closed: open it via the bounded opener, then enumerate. */
        ACQUIRE_THEN_ENUMERATE,
        /** Acquisition impossible: show snapshot rows marked stale. */
        FALLBACK_SNAPSHOT
    }

    enum Gate {
        /** Live list still matches what the user was shown: click may proceed. */
        CLICK_OK,
        /** Source closed: reacquire + revalidate before any click. */
        REVALIDATE_THEN_CLICK,
        /** Live list changed vs shown: refuse the click; refresh instead. */
        REFUSE_STALE
    }

    enum AcquireStep {
        /** Native picker already open: enumerate live now. */
        ENUMERATE_NOW,
        /** A verified panel row is already on screen: tap it, never launch another panel. */
        TAP_EXISTING_PANEL_ROW,
        /** No panel row: launch the panel once (OS places it), then tap the verified row. */
        LAUNCH_PANEL_THEN_TAP,
        /** No picker, no panel row, acquisition unavailable: fall back to the marked snapshot. */
        NO_SOURCE
    }

    /**
     * Acquisition-step seam. A verified panel row already present must be reused:
     * relaunching the panel stacks identical instances and the fail-closed row
     * guard then refuses every tap (the observed working->failed regression).
     */
    static AcquireStep acquireStep(boolean pickerOpen, boolean verifiedPanelRowPresent, boolean acquirePossible) {
        if (pickerOpen) return AcquireStep.ENUMERATE_NOW;
        if (verifiedPanelRowPresent) return AcquireStep.TAP_EXISTING_PANEL_ROW;
        return acquirePossible ? AcquireStep.LAUNCH_PANEL_THEN_TAP : AcquireStep.NO_SOURCE;
    }

    /**
     * Exact row selection among identity-verified panel rows. Each identity is
     * "title|summary" from the row's android:id/title + android:id/summary. One
     * distinct identity means every candidate is the same destination (stacked
     * duplicate panels are the same row): tap the topmost (index 0). Two distinct
     * identities are genuinely ambiguous: refuse (-1), fail closed. No guessing.
     */
    static int pickRow(List<String> identities) {
        if (identities == null || identities.isEmpty()) return -1;
        String first = identities.get(0);
        if (first == null || first.isEmpty()) return -1;
        for (String s : identities) {
            if (s == null || !first.equals(s)) return -1;
        }
        return 0;
    }

    static Plan plan(boolean chooserOpen, boolean acquirePossible) {
        if (chooserOpen) return Plan.ENUMERATE_LIVE;
        return acquirePossible ? Plan.ACQUIRE_THEN_ENUMERATE : Plan.FALLBACK_SNAPSHOT;
    }

    /** Selection gate. shownTitles==null means no rendered list to validate (synthetic entry). */
    static Gate gate(boolean chooserOpen, List<String> liveTitles, List<String> shownTitles) {
        if (!chooserOpen) return Gate.REVALIDATE_THEN_CLICK;
        if (shownTitles != null && !sameTitles(liveTitles, shownTitles)) return Gate.REFUSE_STALE;
        return Gate.CLICK_OK;
    }

    /** Order-insensitive title comparison (the stale guard). */
    static boolean sameTitles(List<String> a, List<String> b) {
        if (a == null || b == null) return a == b;
        if (a.size() != b.size()) return false;
        List<String> x = new ArrayList<>(a), y = new ArrayList<>(b);
        Collections.sort(x);
        Collections.sort(y);
        return x.equals(y);
    }

    // ---- Post-selection bounded route confirmation . Truth comes ONLY from the live native
    // source's current/active marker - never from the tap alone (no optimistic
    // marking); the honest unconfirmed outcome is reported as-is.

    /** True only when the live source reports EXACTLY this row as the current output. */
    static boolean selectionConfirmed(List<SpikeMapper.Row> rows, String label) {
        if (label == null || label.isEmpty() || rows == null) return false;
        for (SpikeMapper.Row r : rows)
            if (r != null && r.current && label.equals(r.title)) return true;
        return false;
    }

    /** The actual current output per the live source; "" when none or ambiguous (never guessed). */
    static String currentTitle(List<SpikeMapper.Row> rows) {
        String found = null;
        if (rows != null) for (SpikeMapper.Row r : rows) {
            if (r == null || !r.current || r.title == null || r.title.isEmpty()) continue;
            if (found != null) return ""; // two active rows: ambiguous, honest empty
            found = r.title;
        }
        return found == null ? "" : found;
    }

    /** Honest post-selection status. "Confirmed" is said only when the live source agrees;
     *  otherwise state exactly what the source reports and defer to the user's ear. */
    static String confirmStatus(boolean confirmed, String label, String actualCurrent, boolean sourceReadable) {
        if (confirmed) return "Selection confirmed by the live system dialog: \"" + label
                + "\" is the current output now. List refreshed from the live source; the system routed, the app never routes.";
        if (!sourceReadable) return "Selection applied; the live system dialog is not readable right now - list shown as-is from the last real source state. Confirm sound by ear.";
        return actualCurrent.isEmpty()
            ? "Selection applied; the live system dialog does not report a current output after the bounded wait - list refreshed from the live source. Confirm sound by ear."
            : "Selection applied; the live system dialog still reports \"" + actualCurrent
                + "\" as the current output after the bounded wait - list refreshed from the live source. Confirm sound by ear.";
    }

    /**
     * Snapshot codec: one row per line "title\u0001selectable\u0001section\u0001current".
     * Decode is fail-closed: any malformed line drops that row; empty input -> empty.
     */
    static String encode(List<SpikeMapper.Row> rows) {
        if (rows == null || rows.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (SpikeMapper.Row r : rows) {
            if (r == null || r.title == null || r.title.isEmpty()) continue;
            if (r.title.indexOf('\u0001') >= 0 || r.title.indexOf('\n') >= 0) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(r.title).append('\u0001').append(r.selectable).append('\u0001')
              .append(r.sectionHeader).append('\u0001').append(r.current);
        }
        return sb.toString();
    }

    static List<SpikeMapper.Row> decode(String snapshot) {
        List<SpikeMapper.Row> out = new ArrayList<>();
        if (snapshot == null || snapshot.isEmpty()) return out;
        for (String line : snapshot.split("\n")) {
            String[] t = line.split("\u0001", -1);
            if (t.length != 4) return Collections.emptyList(); // malformed snapshot: refuse all
            if (t[0].isEmpty() || t[0].length() > 128) return Collections.emptyList();
            if (!bool(t[1]) || !bool(t[2]) || !bool(t[3])) return Collections.emptyList();
            out.add(new SpikeMapper.Row(t[0], Boolean.parseBoolean(t[1]),
                    Boolean.parseBoolean(t[2]), Boolean.parseBoolean(t[3])));
        }
        return out;
    }

    private static boolean bool(String s) {
        return "true".equals(s) || "false".equals(s);
    }

    // ---- Session-owned Done cleanup . OUR popup's Done may request
    // the exact Done action of the native PHONE media output picker ONLY when that
    // picker is verified to belong to the current Audio Router acquisition/session.
    // Everything else (absent picker, wrong/foreign window, stale session, repeated
    // Done) closes OUR popup and leaves native UI untouched. Fail closed everywhere.

    /** No live acquisition/session. */
    static final long NO_SESSION = 0L;

    /** What the custom popup's Done may do on the native side (fail closed). */
    enum DonePlan {
        /** Verified session-owned picker + its exact Done control: click it once, then close our popup. */
        CLICK_OWNED_PICKER_DONE,
        /** No session-owned picker on screen: close our popup normally. */
        CLOSE_ONLY_PICKER_ABSENT,
        /** Picker window/control not exactly verified (ambiguous, wrong window): leave native alone. */
        CLOSE_ONLY_UNVERIFIED,
        /** The request's session is not the live one: never touch native. */
        CLOSE_ONLY_STALE_SESSION,
        /** This session's Done was already handled: idempotent, never a second click. */
        CLOSE_ONLY_ALREADY_DONE
    }

    /**
     * The one native-side decision for our popup's Done. Exact click is allowed only
     * for a live, unconsumed session whose verified picker carries the exact Done
     * control. Precedence: repeated Done (idempotence) > stale session (ownership) >
     * picker absent > control unverified. Never guesses.
     */
    static DonePlan donePlan(boolean sessionCurrent, boolean doneAlreadyConsumed,
                             boolean verifiedPickerPresent, boolean exactDoneInVerifiedPicker) {
        if (doneAlreadyConsumed) return DonePlan.CLOSE_ONLY_ALREADY_DONE;
        if (!sessionCurrent) return DonePlan.CLOSE_ONLY_STALE_SESSION;
        if (!verifiedPickerPresent) return DonePlan.CLOSE_ONLY_PICKER_ABSENT;
        if (!exactDoneInVerifiedPicker) return DonePlan.CLOSE_ONLY_UNVERIFIED;
        return DonePlan.CLICK_OWNED_PICKER_DONE;
    }

    /**
     * Reopen gate for async continuations (post-selection auto-refresh, reacquire
     * continuations) that would repopulate/relaunch the popup: a dismissed popup
     * never reopens itself and never restarts the native helper for it.
     */
    static boolean continuationAllowed(boolean popupOpen, boolean userDismissed) {
        return popupOpen && !userDismissed;
    }

    /** The popup's Done may touch the picker ONLY for the live popup session
     *  (attach/explicit open). Ownership is stable across route auto-refresh, picker
     *  reacquire and unrelated async cancels - never a per-enumeration generation
     *  that cancel()/refresh churn can stale or falsely reuse. */
    static boolean doneSessionCurrent(long requestSession, long popupSession) {
        return requestSession != NO_SESSION && requestSession == popupSession;
    }

    /**
     * Automatic success-close : ONLY a CONFIRMED automatic selection/already-current may close OUR
     * popup and the session-owned picker - and only while that popup is still up
     * (never behind a user who dismissed it). Manual selections keep the established
     * auto-refresh; failures/unconfirmed keep the picker open with the reason visible.
     */
    static boolean autoSuccessClose(boolean automaticFlow, boolean confirmed,
                                    boolean popupOpen, boolean userDismissed) {
        return automaticFlow && confirmed && continuationAllowed(popupOpen, userDismissed);
    }
}
