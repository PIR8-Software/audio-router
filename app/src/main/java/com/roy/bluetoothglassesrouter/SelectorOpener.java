package com.roy.bluetoothglassesrouter;

import java.util.Set;

/**
 * Bounded once-per-acquisition opener for the native audio selector. The route
 * is: launch the system volume panel ("Sound &amp;
 * vibration", action android.settings.panel.action.VOLUME, exported
 * com.android.settings/.panel.SettingsPanelActivity), tap its ONE verified output
 * row, and let the system output picker open at its OS-placed display. The app
 * never selects a route, never toggles Bluetooth and never changes volume.
 * Pure-JVM fake-clock style. The
 * native volume slider must NEVER appear on the external monitor - the volume
 * panel is launched INTERNAL-ONLY with an explicit DEFAULT_DISPLAY target (the
 * stable internal display id, never the rotating external ids). The panel/picker
 * displays are OBSERVED window identity (see the service). This opener feeds the
 * custom external popup's idle-picker acquisition (the old native-panel-only
 * auto-opener UX that left the picker open for a manual choice is retired).
 */
final class SelectorOpener {
    static final long STEP_BUDGET_MS = 6000;
    static final String PANEL_ACTION = "android.settings.panel.action.VOLUME";
    static final String SETTINGS_PKG = "com.android.settings";

    private long deadline = -1;

    /** Row-click budget that opens right after the panel launch. */
    void begin(long now) { deadline = now + STEP_BUDGET_MS; }
    void end() { deadline = -1; }
    boolean stepping(long now) { return deadline >= 0 && now <= deadline; }

    /** Row budget burned while still armed (deadline passed, not ended): the
     *  verified row is not tappable in this state (observed on display-output
     *  rows) and the phase-2 picker opener is due. */
    boolean expired(long now) { return deadline >= 0 && now > deadline; }

    /** External-first display targeting; internal (DEFAULT_DISPLAY = 0) fallback. */
    static int pickDisplay(Set<Integer> externalIds) {
        int best = -1;
        if (externalIds != null) {
            for (int id : externalIds) {
                if (id > 0 && (best < 0 || id < best)) best = id;
            }
        }
        return best < 0 ? 0 : best;
    }

    /**
     * Output-row label shape ("Audio will play on" / "Play &lt;app&gt; on"),
     * gate-independent: used to OBSERVE the volume-panel window identity even
     * when the row itself is inert (disabled/not clickable) and therefore
     * refused by the click gate below.
     */
    static boolean rowLabel(String title) {
        return title != null && ("Audio will play on".equals(title)
                || (title.startsWith("Play ") && title.endsWith(" on") && title.length() > "Play  on".length()));
    }

    /**
     * Exact output-row identity gate, fail closed. Verified panel rows carry
     * android:id/title = "Audio will play on" or "Play &lt;app&gt; on" plus a
     * non-empty android:id/summary device name. Anything else is refused so no
     * unknown control is ever clicked.
     */
    static boolean rowValid(String pkg, String title, String summary, boolean visible, boolean enabled, boolean clickable) {
        if (!SETTINGS_PKG.equals(pkg)) return false;
        if (title == null || summary == null) return false;
        if (title.isEmpty() || title.length() > 128 || summary.isEmpty() || summary.length() > 128) return false;
        return rowLabel(title) && visible && enabled && clickable;
    }
}
