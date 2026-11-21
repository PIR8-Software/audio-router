package com.roy.bluetoothglassesrouter;

/**
 * Production entry decisions for Refresh, popup dismissal, auto-select
 * expiry revalidation, and session-owned Done. {@link BluetoothGlassesRoutingService}
 * delegates these methods; unit tests execute the same methods against the production
 * {@link AcquireSequencer} with a controllable scheduler and Done adapter. This is the
 * service entry, not a copied decision helper and not a source-string contract.
 *
 * <p>Refresh ownership: an intentional Refresh must reconcile any pending
 * action before the chooser-open or cooldown return, retaining the
 * cancelled/replaced outcome, and must not launch another volume panel while
 * the acquisition cooldown is still active.
 */
final class ServiceEntry {

    /** Android-free adapter for {@code refreshOutputs} side effects. */
    interface RefreshHost {
        boolean initialized();
        boolean unlocked();
        /** Native picker window present ({@code BluetoothGlassesRoutingService.chooserOpen()}). */
        boolean chooserOpen();
        long now();
        /** Repopulate the popup from the live picker or the marked stale snapshot. */
        void popupFromLive();
        void reportStatus(String message);
    }

    /** Status mirror used by the expiry-revalidation arm (service writes status + lastResult). */
    interface StatusSink {
        void report(String message);
    }

    private ServiceEntry() {}

    /**
     * Production body of {@code BluetoothGlassesRoutingService.refreshOutputs}.
     * Clears any previously retained outcome (this tap is intentional), then
     * reconciles pending ownership before the chooser-open and cooldown
     * returns so a stale selection cannot run afterward. Cooldown still blocks
     * a new panel launch.
     */
    static String refreshOutputs(AcquireSequencer seq, RefreshHost host) {
        seq.beginIntentionalAction();
        if (!host.initialized()) return "Service not initialized.";
        if (!host.unlocked()) return "Locked; outputs not refreshed.";
        // Ownership before either return. Cooldown and an already-open picker
        // still skip a new panel launch; they must not leave the old action armed.
        seq.supersedePending("refresh outputs");
        if (host.chooserOpen()) {
            host.popupFromLive();
            return "Refreshed live from the open native picker.";
        }
        if (seq.acquirePossible(host.now())) {
            host.reportStatus("Refresh: native picker closed; reacquiring it before reloading rows.");
            seq.acquireThen("refresh outputs", host::popupFromLive);
            return "Native picker closed; reopening it automatically to reload real rows...";
        }
        host.popupFromLive();
        return "Native picker closed and reacquire unavailable; showing marked stale snapshot.";
    }

    /**
     * Production body of {@code onPopupUserClosed}: dismissal is intentional
     * (clears the retained outcome) and cancels pending acquisition so a
     * delayed callback cannot reopen or resurrect work. {@code serviceCancel}
     * is the service's remaining cancel bookkeeping.
     */
    static void onPopupUserClosed(AcquireSequencer seq, Runnable serviceCancel) {
        seq.beginIntentionalAction();
        seq.cancel();
        if (serviceCancel != null) serviceCancel.run();
    }

    /**
     * Production auto-select expiry arm: one bounded reacquire named
     * {@code auto-select revalidation} when the live list is unreadable, the
     * popup is still open, and the launch cooldown allows it. Returns true
     * when the acquire was armed (the caller sets the one-shot retry flag).
     */
    static boolean tryArmExpiryRevalidation(AcquireSequencer seq, boolean alreadyRetried,
            boolean popupOpen, long now, Runnable after, StatusSink sink) {
        if (alreadyRetried || !seq.acquirePossible(now) || !popupOpen) return false;
        sink.report("Auto-select: outputs not readable at expiry; reopening the idle picker once to revalidate (nothing selected yet).");
        seq.acquireThen("auto-select revalidation", after);
        return true;
    }

    /**
     * One-shot Done consumption and the native close result. The service owns
     * one instance. Dismissal bookkeeping must not overwrite {@link #lastOutcome}.
     */
    static final class DoneMemory {
        long consumedSession = ChooserLifecycle.NO_SESSION;
        String lastOutcome = "none yet";
    }

    /** Android-free stand-in for the verified picker's exact Done node. */
    interface DoneControl {
        boolean refresh();
        boolean visibleToUser();
        boolean enabled();
        boolean clickable();
        boolean performClick();
        void recycle();
    }

    /** What {@code verifiedPickerDone} reports, without Android node discovery. */
    static final class VerifiedPicker {
        static final VerifiedPicker NONE = new VerifiedPicker(false, null);
        final boolean pickerPresent;
        final DoneControl done;

        VerifiedPicker(boolean pickerPresent, DoneControl done) {
            this.pickerPresent = pickerPresent;
            this.done = done;
        }
    }

    /** Called only for a live, not-yet-consumed session. Stale/repeat skip it. */
    interface DoneLookup {
        VerifiedPicker lookup();
    }

    /** Fresh popup session: clear the one-shot so the new token may click once. */
    static void beginPopupSession(DoneMemory memory) {
        memory.consumedSession = ChooserLifecycle.NO_SESSION;
    }

    /**
     * Production body of {@code BluetoothGlassesRoutingService.closeOwnedPicker}.
     * Clicks the verified session-owned Done at most once, writes that consumption
     * even when the click is refused, and stores the native outcome separately
     * from later dismissal status. Does not rediscover Android nodes.
     */
    static String closeOwnedPicker(AcquireSequencer seq, long requestSession, DoneMemory memory,
            long popupSession, boolean initialized, DoneLookup lookup, StatusSink sink) {
        seq.beginIntentionalAction(); // intentional user action: clear retained outcome
        if (!initialized) return "Service not initialized; native picker untouched; popup closed.";
        boolean sessionCurrent = ChooserLifecycle.doneSessionCurrent(requestSession, popupSession);
        boolean consumed = requestSession != ChooserLifecycle.NO_SESSION
                && requestSession == memory.consumedSession;
        // Stale/consumed requests are refused before any window work (bounded, no side effects).
        VerifiedPicker found = sessionCurrent && !consumed ? lookup.lookup() : VerifiedPicker.NONE;
        ChooserLifecycle.DonePlan plan = ChooserLifecycle.donePlan(
                sessionCurrent, consumed, found.pickerPresent, found.done != null);
        String out;
        if (plan == ChooserLifecycle.DonePlan.CLICK_OWNED_PICKER_DONE) {
            boolean clicked = false;
            try {
                DoneControl d = found.done;
                clicked = d.refresh() && d.visibleToUser() && d.enabled() && d.clickable()
                        && d.performClick();
            } finally {
                found.done.recycle();
            }
            out = clicked
                    ? "Done: the session-owned native picker's exact Done was clicked (verified window/identity); our popup closed. The app never routes."
                    : "Done: the session-owned native picker's exact Done rejected the click; nothing else was touched; our popup closed.";
        } else {
            if (found.done != null) found.done.recycle();
            switch (plan) {
                case CLOSE_ONLY_ALREADY_DONE:
                    out = "Done: the native picker's Done was already handled for this session (repeat tap ignored); our popup closed.";
                    break;
                case CLOSE_ONLY_STALE_SESSION:
                    out = "Done: this popup's session is not the live one (stale/no session); native UI left untouched; our popup closed.";
                    break;
                case CLOSE_ONLY_UNVERIFIED:
                    out = "Done: the native picker is present but its exact Done control was not verified as clickable (absent, disabled, ambiguous or wrong window); native UI left untouched; our popup closed.";
                    break;
                default:
                    out = "Done: no session-owned native picker on screen; our popup closed normally.";
                    break;
            }
        }
        if (sessionCurrent) memory.consumedSession = requestSession; // one-shot per session regardless of outcome
        memory.lastOutcome = out;
        if (sink != null) sink.report(out);
        return out;
    }
}
