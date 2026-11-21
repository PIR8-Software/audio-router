package com.roy.bluetoothglassesrouter;

/**
 * Pure-JVM acquisition orchestration for the idle native picker (the production
 * `acquireThen`/poll loop of {@link BluetoothGlassesRoutingService}, extracted so
 * the REAL orchestration is executable in unit tests with a controllable
 * scheduler/adapter - not a copied helper, not a source-string contract).
 *
 * <p>Ownership is explicit and deterministic: every acquisition continuation is a
 * named {@link Action}; at most ONE action is pending at a time. Side effects
 * (panel launch, picker opener broadcast, selector-step scheduling, status text)
 * stay in the service behind the {@link Host} adapter; timing goes through the
 * injected {@link Scheduler} (a Handler adapter in production, a fake clock in
 * tests). Bounded: if the picker does not appear within the poll budget the
 * pending action runs anyway (it falls back to the marked snapshot; nothing is
 * invented). The app never selects a route.
 */
final class AcquireSequencer {

    /** Timing adapter: production wraps the service Handler; tests control it. */
    interface Scheduler {
        void postDelayed(Runnable r, long delayMs);
        void removeCallbacks(Runnable r);
    }

    /** Production side effects orchestrated here (implemented by the service). */
    interface Host {
        /** Monotonic clock (SystemClock.elapsedRealtime in production). */
        long now();
        /** True when the native output picker is on screen right now (window identity). */
        boolean pickerOpen();
        /** True when a tappable identity-verified volume-panel row is on screen. */
        boolean verifiedRowPresent();
        /** Launch the internal-only volume panel; false when unavailable. */
        boolean launchVolumePanel();
        /** The documented SystemUI picker opener (broadcast; never clicks/routes). */
        void openPickerDirect();
        /** Phase-2 marker: the fallback picker opener has been sent for this acquisition. */
        void setFallbackSent(boolean sent);
        /** Start the verified-row tap budget. */
        void beginSelector(long now);
        /** Schedule the next verified-row tap attempt. */
        void scheduleSelectorStep();
        /** Drop pending selector-step callbacks (supersede/cancel). */
        void cancelSelectorStep();
        /** Transient status line (the service `status` field). */
        void reportStatus(String message);
        /** Retained visible outcome mirror (empty clears). */
        void reportRetained(String message);
    }

    /** Cooldown between acquisition attempts (repeat-tap guard). */
    static final long ACQUIRE_COOLDOWN_MS = 5000;
    /** Phase-2 picker-opener window after the verified-row budget is burned (bounded). */
    static final long PICKER_FALLBACK_POLL_MS = 4000;
    /** Poll cadence while waiting for the native picker to appear. */
    static final long POLL_INTERVAL_MS = 400;

    /** One pending acquisition continuation with its explicit action name. */
    static final class Action {
        final String name;
        final Runnable run;
        Action(String name, Runnable run) {
            this.name = name;
            this.run = run;
        }
    }

    private final Scheduler sched;
    private final Host host;
    private Action pending;
    /** Ownership generation: every pending action/poll chain carries its generation;
     *  a delayed old callback from a superseded/cancelled generation is inert. */
    private long generation;
    private long acquireNextAt = Long.MIN_VALUE;
    private long pollUntil = -1;
    private String retained = "";
    private Runnable scheduledPoll;

    AcquireSequencer(Scheduler sched, Host host) {
        this.sched = sched;
        this.host = host;
    }

    /** Cooldown gate between acquisition attempts. */
    boolean acquirePossible(long now) {
        return now >= acquireNextAt;
    }

    /** The retained supersession outcome ("" when none). */
    String retainedOutcome() {
        return retained;
    }

    /** The pending action, or null. Deterministic ownership visibility. */
    Action pendingAction() {
        return pending;
    }

    /** The user's next intentional action: clear the retained supersession outcome. */
    void beginIntentionalAction() {
        retained = "";
        host.reportRetained("");
    }

    /**
     * Cancel a pending action in favor of {@code replacementName} without starting
     * a new acquisition and without touching the launch cooldown. The superseded
     * continuation is never executed (generation-owned polls are inert) and the
     * outcome is retained until the next intentional action. Returns false when
     * nothing is pending (retained outcome is left unchanged).
     */
    boolean supersedePending(String replacementName) {
        if (pending == null) return false;
        retained = "Superseded: pending \"" + pending.name + "\" cancelled and replaced by \""
                + replacementName + "\"; it will not run (fail closed, no stale click). Shown until your next action.";
        host.reportRetained(retained);
        host.reportStatus("previous pending action superseded by " + replacementName);
        host.cancelSelectorStep();
        pending = null;
        generation++;
        if (scheduledPoll != null) {
            sched.removeCallbacks(scheduledPoll);
            scheduledPoll = null;
        }
        return true;
    }

    /**
     * Run {@code next} once the native picker is open (or immediately if already
     * open). Overlapping calls have deterministic ownership: the newer action
     * SUPERSEDES a pending one - the superseded continuation is NEVER executed
     * (not even by a delayed old poll callback) and its outcome is retained as a
     * persistent visible result until the user's next intentional action (F2:
     * never a silently lost pending action).
     */
    void acquireThen(String actionName, Runnable next) {
        long now = host.now();
        supersedePending(actionName);
        pending = new Action(actionName, next);
        generation++;
        if (host.pickerOpen()) {
            Action run = pending;
            pending = null;
            generation++;
            run.run.run();
            return;
        }
        acquireNextAt = now + ACQUIRE_COOLDOWN_MS;
        pollUntil = now + SelectorOpener.STEP_BUDGET_MS + PICKER_FALLBACK_POLL_MS;
        host.setFallbackSent(false);
        boolean rowPresent = host.verifiedRowPresent();
        ChooserLifecycle.AcquireStep step = ChooserLifecycle.acquireStep(false, rowPresent, true);
        if (step == ChooserLifecycle.AcquireStep.LAUNCH_PANEL_THEN_TAP && !host.launchVolumePanel()) {
            // Panel unavailable: fire the documented picker opener directly (the same
            // action the verified row sends) and keep polling - acquisition stays LIVE;
            // the marked snapshot remains only the last resort if the picker never appears.
            host.setFallbackSent(true);
            host.openPickerDirect();
            host.reportStatus("Auto-acquire: volume panel unavailable; opening the system output picker directly (documented SystemUI action). The app never selects a route.");
            schedulePoll();
            return;
        }
        host.beginSelector(now);
        host.reportStatus(rowPresent
                ? "Auto-acquiring idle native picker: reusing the open system volume panel on its actual display; tapping its verified output row (no relaunch)."
                : "Auto-acquiring idle native picker: system volume panel launching on the phone display only (never the external monitor); verified output row tap follows.");
        host.scheduleSelectorStep();
        schedulePoll();
    }

    /**
     * Cancel any pending action and its polls (user dismissal, config change,
     * display teardown, cleanup). The pending continuation NEVER runs
     * (cancellation preserved; no resurrection). The retained outcome is kept -
     * only the user's next intentional action clears it.
     */
    void cancel() {
        pending = null;
        generation++;
        if (scheduledPoll != null) {
            sched.removeCallbacks(scheduledPoll);
            scheduledPoll = null;
        }
    }

    private void schedulePoll() {
        if (scheduledPoll != null) sched.removeCallbacks(scheduledPoll);
        final long gen = generation;
        scheduledPoll = () -> poll(gen);
        sched.postDelayed(scheduledPoll, POLL_INTERVAL_MS);
    }

    private void poll(long gen) {
        if (gen != generation || pending == null) return; // delayed old callback: inert (never a stale run)
        Action next = pending;
        if (host.pickerOpen() || host.now() >= pollUntil) {
            pending = null;
            if (scheduledPoll != null) {
                sched.removeCallbacks(scheduledPoll);
                scheduledPoll = null;
            }
            if (!host.pickerOpen()) {
                host.reportStatus("Auto-acquire: native picker did not appear in time; falling back to marked snapshot.");
            }
            next.run.run();
            return;
        }
        schedulePoll();
    }
}
