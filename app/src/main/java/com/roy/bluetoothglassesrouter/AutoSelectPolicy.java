package com.roy.bluetoothglassesrouter;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-JVM policy for the Preferred/Fallback role assignment and the optional
 * five-second auto-select countdown.
 *
 * Roles are two mutually exclusive single-choice groups (one preferred, one
 * fallback) over the REAL output rows the native chooser offered. Roles must be
 * distinct: assigning the same handle to the other role deterministically clears
 * the previous one and explains. A saved role identity that is absent from the
 * live list is PRESERVED (it can return later) and simply counts as unavailable.
 *
 * Expiry is fail-closed: preferred else fallback is selected ONLY when its exact
 * title resolves to exactly one live usable row; absent/unusable falls through,
 * ambiguous (duplicate titles) / unreadable lists leave the picker open with a
 * clear reason - never a guessed route, never a silently redefined fallback.
 *
 * The countdown is one-shot per popup session, anchored on a caller-supplied
 * monotonic clock (fake-clock testable): it arms only on a fresh popup session
 * or an explicit user enable, only once usable live rows are ready, expires
 * exactly once, and is cancelled by manual selection / Done / OFF / role edits.
 * After the automatic selection ran, nothing rearms in that session.
 */
final class AutoSelectPolicy {

    /** Countdown duration: five seconds. */
    static final long COUNTDOWN_MS = 5000;

    // ---- role assignment (pure) ----

    /** The persisted role identities (exact native row titles; null = unset). */
    static final class Roles {
        final String preferred;
        final String fallback;
        Roles(String preferred, String fallback) {
            this.preferred = emptyToNull(preferred);
            this.fallback = emptyToNull(fallback);
        }
        boolean isEmpty() { return preferred == null && fallback == null; }
    }

    /** Outcome of one role edit: the new roles, a user-facing explanation, and whether anything changed. */
    static final class RoleEdit {
        final Roles roles;
        final String message;
        final boolean changed;
        RoleEdit(Roles roles, String message, boolean changed) {
            this.roles = roles; this.message = message; this.changed = changed;
        }
    }

    /**
     * Tap on a row's role control: assign the role when unset, clear it when it
     * already points at this row (toggle). Same-handle assignment clears the
     * OTHER role and explains (roles must be distinct). Never touches the other
     * role otherwise. Never silently redefine the other role.
     */
    static RoleEdit toggle(Roles current, String title, boolean asPreferred) {
        Roles cur = current == null ? new Roles(null, null) : current;
        String t = emptyToNull(title);
        if (t == null) return new RoleEdit(cur, "Role not set: empty device name.", false);
        String role = asPreferred ? "Preferred" : "Fallback";
        String own = asPreferred ? cur.preferred : cur.fallback;
        if (t.equals(own)) {
            Roles next = asPreferred ? new Roles(null, cur.fallback) : new Roles(cur.preferred, null);
            return new RoleEdit(next, "\"" + t + "\" removed as " + role + " (role cleared).", true);
        }
        String other = asPreferred ? cur.fallback : cur.preferred;
        if (t.equals(other)) {
            Roles next = asPreferred ? new Roles(t, null) : new Roles(null, t);
            return new RoleEdit(next, "\"" + t + "\" set as " + role + "; it was cleared as "
                    + (asPreferred ? "Fallback" : "Preferred") + " (roles must be different).", true);
        }
        Roles next = asPreferred ? new Roles(t, cur.fallback) : new Roles(cur.preferred, t);
        return new RoleEdit(next, "\"" + t + "\" set as " + role + ".", true);
    }

    /** Explicit role removal (the only removal path; absent devices are never auto-cleared). */
    static RoleEdit clear(Roles current, boolean preferred) {
        Roles cur = current == null ? new Roles(null, null) : current;
        String old = preferred ? cur.preferred : cur.fallback;
        if (old == null) return new RoleEdit(cur, "No " + (preferred ? "Preferred" : "Fallback") + " set.", false);
        Roles next = preferred ? new Roles(null, cur.fallback) : new Roles(cur.preferred, null);
        return new RoleEdit(next, "\"" + old + "\" removed as " + (preferred ? "Preferred" : "Fallback")
                + "; the other role is untouched.", true);
    }

    // ---- expiry decision over the LIVE row list (pure) ----

    enum ExpiryAction {
        /** Click this exact live row (via the bounded production selection path). */
        SELECT,
        /** The target is already the current output: show that, never a needless click. */
        ALREADY_CURRENT,
        /** Nothing selectable safely: leave the picker open with the message. */
        LEAVE_OPEN
    }

    static final class Expiry {
        final ExpiryAction action;
        final String targetTitle;
        final String message;
        Expiry(ExpiryAction action, String targetTitle, String message) {
            this.action = action; this.targetTitle = targetTitle; this.message = message;
        }
    }

    /**
     * The one expiry decision: preferred first, fallback only when preferred is
     * absent or not usable right now ("available" = offered as a usable route,
     * not merely paired/remembered). Ambiguity (duplicate titles) anywhere in
     * the resolution chain fails closed to LEAVE_OPEN - never a guessed pick and
     * never a silent fallback behind a possibly-present preferred.
     */
    static Expiry expiry(Roles roles, List<SpikeMapper.Row> rows) {
        if (rows == null || rows.isEmpty())
            return new Expiry(ExpiryAction.LEAVE_OPEN, null,
                    "Auto-select: no live outputs readable at expiry; nothing selected (popup stays open).");
        Roles r = roles == null ? new Roles(null, null) : roles;
        if (r.isEmpty())
            return new Expiry(ExpiryAction.LEAVE_OPEN, null,
                    "Auto-select: no preferred or fallback set; nothing selected (popup stays open).");
        if (r.preferred != null) {
            int m = match(rows, r.preferred);
            if (m == MATCH_AMBIGUOUS) return ambiguous(r.preferred);
            if (m >= 0) {
                SpikeMapper.Row row = rows.get(m);
                if (row.current) return new Expiry(ExpiryAction.ALREADY_CURRENT, r.preferred,
                        "Auto-select: preferred \"" + r.preferred + "\" is already the current output; no route change.");
                if (row.selectable) return new Expiry(ExpiryAction.SELECT, r.preferred,
                        "Auto-select: preferred \"" + r.preferred + "\" is live and selectable now.");
                // Preferred offered but not usable right now: fall through to fallback.
            }
            // Preferred absent or unusable: fall through to fallback (config preserved).
        }
        if (r.fallback != null) {
            int m = match(rows, r.fallback);
            if (m == MATCH_AMBIGUOUS) return ambiguous(r.fallback);
            if (m >= 0) {
                SpikeMapper.Row row = rows.get(m);
                if (row.current) return new Expiry(ExpiryAction.ALREADY_CURRENT, r.fallback,
                        "Auto-select: fallback \"" + r.fallback + "\" is already the current output; no route change.");
                if (row.selectable) return new Expiry(ExpiryAction.SELECT, r.fallback,
                        (r.preferred == null ? "Auto-select: selecting fallback \"" : "Auto-select: preferred \"" + r.preferred
                                + "\" is not available now; selecting fallback \"") + r.fallback + "\" (live-validated).");
                return new Expiry(ExpiryAction.LEAVE_OPEN, null,
                        "Auto-select: fallback \"" + r.fallback + "\" is offered but not a usable route right now; nothing selected (popup stays open).");
            }
            return new Expiry(ExpiryAction.LEAVE_OPEN, null,
                    (r.preferred == null ? "Auto-select: fallback \"" + r.fallback + "\" is not in the live list"
                            : "Auto-select: neither preferred \"" + r.preferred + "\" nor fallback \"" + r.fallback + "\"")
                            + "; nothing selected (popup stays open).");
        }
        return new Expiry(ExpiryAction.LEAVE_OPEN, null,
                "Auto-select: preferred \"" + r.preferred + "\" is not available and no fallback is set; nothing selected (popup stays open).");
    }

    private static Expiry ambiguous(String title) {
        return new Expiry(ExpiryAction.LEAVE_OPEN, null,
                "Auto-select: saved role \"" + title + "\" matches more than one live row (ambiguous); nothing selected (popup stays open).");
    }

    private static final int MATCH_AMBIGUOUS = -2;
    private static final int MATCH_ABSENT = -1;

    /** Exact-title match among real (non-section) rows: absent / index / ambiguous (never guessed). */
    private static int match(List<SpikeMapper.Row> rows, String title) {
        int found = MATCH_ABSENT;
        for (int i = 0; i < rows.size(); i++) {
            SpikeMapper.Row row = rows.get(i);
            if (row == null || row.sectionHeader || row.title == null || !title.equals(row.title)) continue;
            if (found != MATCH_ABSENT) return MATCH_AMBIGUOUS;
            found = i;
        }
        return found;
    }

    // ---- countdown timer state machine (pure; caller supplies monotonic now) ----

    /**
     * One-shot countdown per popup session. Lifecycle contract:
     * - {@link #newSession} on every fresh popup session (attach / explicit open);
     * - {@link #enable} on the user's explicit ON toggle (and only there);
     * - {@link #onRowsReady} consumes a pending enable once usable live rows
     *   exist (the timer starts then - never while native acquisition runs);
     * - {@link #cancel} from manual selection / Done / OFF / role edits / close;
     * - {@link #expireOnce} fires exactly once at the deadline.
     * Route auto-refreshes and re-renders never arm or restart anything, and
     * after the automatic selection ran nothing rearms in that session.
     */
    static final class Countdown {
        private final long durationMs;
        private long session = ChooserLifecycle.NO_SESSION;
        private long deadline = Long.MIN_VALUE;
        private boolean armed;
        private boolean pendingArm;
        private long firedSession = ChooserLifecycle.NO_SESSION;

        Countdown() { this(COUNTDOWN_MS); }
        Countdown(long durationMs) { this.durationMs = durationMs; }

        long session() { return session; }
        boolean isArmed() { return armed; }
        boolean hasPending() { return pendingArm; }
        boolean firedThisSession(long s) { return s != ChooserLifecycle.NO_SESSION && firedSession == s; }
        /** Remaining ms while armed (0 once due or not armed). */
        long remaining(long now) {
            if (!armed) return 0;
            long left = deadline - now;
            return left > 0 ? left : 0;
        }

        /** Fresh popup session: reset one-shot state; never arms by itself. */
        void newSession(long session) {
            this.session = session;
            armed = false;
            pendingArm = false;
            deadline = Long.MIN_VALUE;
        }

        /**
         * Explicit user enable (the persisted opt-in is the caller's check).
         * readyNow = a usable live route list is already loaded. Refused after
         * the automatic selection ran this session (post-selection no rearm) and
         * while already armed (an enable never extends or duplicates a timer).
         */
        boolean enable(long session, boolean readyNow, long now) {
            if (session != this.session) newSession(session);
            if (firedThisSession(session)) return false;
            if (armed) return false;
            if (!readyNow) { pendingArm = true; return false; }
            armAt(now);
            return true;
        }

        /** Usable live rows arrived (load/refresh). Arms only for a pending explicit enable. */
        boolean onRowsReady(long session, long now) {
            if (session != this.session) return false;
            if (!pendingArm || armed || firedThisSession(session)) return false;
            pendingArm = false;
            armAt(now);
            return true;
        }

        /** Cancel armed countdown AND any pending enable (manual/Done/OFF/role-edit/close). */
        boolean cancel() {
            boolean was = armed || pendingArm;
            armed = false;
            pendingArm = false;
            deadline = Long.MIN_VALUE;
            return was;
        }

        /** True exactly once when the deadline passed; marks the session as fired. */
        boolean expireOnce(long now) {
            if (!armed || now < deadline) return false;
            armed = false;
            deadline = Long.MIN_VALUE;
            firedSession = session;
            return true;
        }

        private void armAt(long now) {
            armed = true;
            deadline = now + durationMs;
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
