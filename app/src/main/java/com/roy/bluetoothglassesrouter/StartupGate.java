package com.roy.bluetoothglassesrouter;

/**
 * Idempotent startup/attach policy for the background hardening. Pure JVM (fake-clock style) so the realistic lifecycle harness can
 * exercise service rebind/kill recovery, boot/unlock signals and attach/reconnect
 * deduplication without hardware.
 *
 * Contract: the selector popup is offered exactly once per dock session. The
 * state is persisted (prefs keys "gate_*") so it survives process death and the
 * Android-managed accessibility-service rebind; the boot count rotates the
 * session on reboot. Never reopened behind a user who closed it, never before
 * unlock, never while the opt-in switch is off, never a duplicate while it is
 * already on screen.
 */
final class StartupGate {

    /** Prefs prefix for all gate state; excluded from config-change handling. */
    static final String KEY_PREFIX = "gate_";
    static final String KEY_HAS_SESSION = KEY_PREFIX + "has_session";
    static final String KEY_BOOT = KEY_PREFIX + "boot";
    static final String KEY_DOCKED = KEY_PREFIX + "docked";
    static final String KEY_SATISFIED = KEY_PREFIX + "satisfied";
    static final String KEY_PENDING = KEY_PREFIX + "pending";
    static final String KEY_DETACH_AT = KEY_PREFIX + "detach_at";
    static final String KEY_POPUP_OPEN_AT_DETACH = KEY_PREFIX + "popup_open_at_detach";

    /**
     * A dock re-enumeration (USB-C id rotation: 77->85->88...) removes and
     * re-adds the same physical dock within this window. Such a "reconnect" is
     * the SAME session: the popup killed with its display is restored, but a
     * popup the user already closed is never reopened. A redock after the window
     * is a genuine new session.
     */
    static final long RECONNECT_WINDOW_MS = 4000;

    /** In-memory mirror of the persisted prefs state. */
    static final class State {
        boolean hasSession;      // a dock session has been started at least once
        long sessionBoot = Long.MIN_VALUE; // boot count the session belongs to
        boolean dockedNow;       // we believe an external display is present
        boolean satisfied;       // this session's popup has been launched (or opted out)
        boolean pending;         // attach seen while locked: offer owed after unlock
        long detachAt = Long.MIN_VALUE;
        boolean popupOpenAtDetach;
    }

    enum Trigger { START, ATTACH, DETACH }

    enum Decision {
        /** Schedule the popup (one settle later); caller marks shown on launch. */
        OFFER,
        /** No external display: nothing to do. */
        SKIP_NOT_DOCKED,
        /** Opt-in switch off: consent wins; the session is consumed silently. */
        SKIP_NO_SELECTOR,
        /** Popup already launched for this session (or user opted out). */
        SKIP_SATISFIED,
        /** Keyguard up: keep pending, retry on the unlock signal. */
        SKIP_LOCKED,
        /** Dock blip within the reconnect window after the user closed the popup. */
        SKIP_RECONNECT,
        /** The popup window is already on screen: never stack a second one. */
        SKIP_POPUP_OPEN
    }

    /** Prefs keys that are internal state, never user configuration: changing
     *  them must not cancel in-flight popup/acquisition flows. */
    static boolean internalStateKey(String key) {
        return key != null && (key.startsWith("chooser_cache") || key.startsWith(KEY_PREFIX));
    }

    /**
     * Decide for one lifecycle event. State is updated in place; the caller
     * persists it and, on OFFER, calls {@link #markShown(State)} when the popup
     * is actually launched. Parameters are observed facts (fake-clock friendly):
     * bootCount rotates only on a real reboot, now is elapsed-realtime style.
     */
    static Decision decide(State s, Trigger trigger, long bootCount, boolean docked,
                           boolean selectorOn, boolean unlocked, boolean popupOpen, long now) {
        // Boot tracking is anchored at first touch; a changed boot count is a
        // real reboot (even with the dock left plugged in) and rotates the
        // session for every trigger - a stale detach timestamp from before the
        // reboot must never masquerade as a fresh reconnect either.
        if (!s.hasSession) {
            s.sessionBoot = bootCount;
        } else if (bootCount != s.sessionBoot) {
            s.sessionBoot = bootCount;
            s.satisfied = false;
            s.pending = false;
            s.detachAt = Long.MIN_VALUE;
            s.popupOpenAtDetach = false;
        }
        switch (trigger) {
            case DETACH:
                rememberDetach(s, popupOpen, now);
                return Decision.SKIP_NOT_DOCKED;
            case START:
                return start(s, bootCount, docked, selectorOn, unlocked, popupOpen);
            case ATTACH:
                if (!docked) return Decision.SKIP_NOT_DOCKED;
                return attach(s, selectorOn, unlocked, popupOpen, now);
            default:
                return Decision.SKIP_NOT_DOCKED;
        }
    }

    /** Boot / unlock / package-replaced signal, or any (re)bind of the service. */
    private static Decision start(State s, long bootCount, boolean docked,
                                  boolean selectorOn, boolean unlocked, boolean popupOpen) {
        if (!s.hasSession) {
            // First contact after install/update: the dock may have been active
            // long before us. Never surprise the user with a popup at install;
            // consume silently and let the next real attach or reboot offer it.
            s.hasSession = true;
            s.dockedNow = docked;
            s.satisfied = docked;
            s.pending = false;
            return docked ? Decision.SKIP_SATISFIED : Decision.SKIP_NOT_DOCKED;
        }
        s.dockedNow = docked;
        if (!docked) return Decision.SKIP_NOT_DOCKED;
        return offerOrSkip(s, selectorOn, unlocked, popupOpen);
    }

    /** Physical attach transition (first no-display -> display). */
    private static Decision attach(State s, boolean selectorOn, boolean unlocked,
                                   boolean popupOpen, long now) {
        boolean reconnect = s.hasSession && s.detachAt != Long.MIN_VALUE
                && now - s.detachAt <= RECONNECT_WINDOW_MS;
        if (reconnect && s.satisfied) {
            // Same dock re-enumerated. Restore the popup the display removal
            // killed; a popup the user closed stays closed.
            if (popupOpen) return Decision.SKIP_POPUP_OPEN;
            if (!s.popupOpenAtDetach || !selectorOn) return Decision.SKIP_RECONNECT;
            if (!unlocked) { s.pending = true; return Decision.SKIP_LOCKED; }
            return Decision.OFFER;
        }
        if (s.dockedNow && s.satisfied) {
            // Duplicate attach signal within one continuous dock presence
            // (no session end observed): never reopen a finished session.
            return Decision.SKIP_SATISFIED;
        }
        // Genuine new session (real redock past the window, recovery while an
        // offer was still owed, or the first dock after arming while undocked).
        s.hasSession = true;
        s.dockedNow = true;
        s.satisfied = false;
        s.pending = false;
        s.detachAt = Long.MIN_VALUE;
        s.popupOpenAtDetach = false;
        return offerOrSkip(s, selectorOn, unlocked, popupOpen);
    }

    private static Decision offerOrSkip(State s, boolean selectorOn,
                                        boolean unlocked, boolean popupOpen) {
        if (s.satisfied) return Decision.SKIP_SATISFIED;
        if (!selectorOn) {
            // The single enable/disable switch is consent: consume the session
            // silently so flipping it on later never pops at a random moment.
            s.satisfied = true;
            s.pending = false;
            return Decision.SKIP_NO_SELECTOR;
        }
        if (popupOpen) return Decision.SKIP_POPUP_OPEN;
        if (!unlocked) {
            // Never before unlock (sensitive state is credential-encrypted);
            // keep the offer owed and let the unlock signal retry it.
            s.pending = true;
            return Decision.SKIP_LOCKED;
        }
        return Decision.OFFER;
    }

    /** Popup actually launched (or explicitly consumed): one per session. */
    static void markShown(State s) {
        s.satisfied = true;
        s.pending = false;
    }

    private static void rememberDetach(State s, boolean popupOpen, long now) {
        s.dockedNow = false;
        s.detachAt = now;
        s.popupOpenAtDetach = popupOpen;
        // satisfied/pending survive the blip: the session continues across a
        // reconnect and a locked attach keeps owing its offer.
    }
}
