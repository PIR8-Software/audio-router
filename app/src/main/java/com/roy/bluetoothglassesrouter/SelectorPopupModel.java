package com.roy.bluetoothglassesrouter;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure JVM render model for the custom Audio Router popup: maps rows enumerated
 * LIVE from the system media output dialog to view items for the chooser UI,
 * which visually follows the system media output selector (rounded sheet,
 * icon + label full-row destinations, bold section headers, white pill for the
 * active device). No Android types here so every honesty rule is unit-testable:
 * only real enumerated rows are ever rendered (no invented/demo rows), the
 * current output is clearly marked, stale snapshot rows stay visible but are
 * labeled "Revalidate & select" so no tap is ever presented as validated, and
 * loading/empty/error states stay visible and actionable.
 */
final class SelectorPopupModel {

    enum Kind { SECTION, CURRENT, SELECTABLE, UNSELECTABLE }

    enum Icon { DISPLAY, PHONE, HEADPHONES, SPEAKER, GENERIC }

    /** One rendered line of the chooser. title is the exact native row title. */
    static final class Item {
        final Kind kind;
        final String title;
        /** Persistent secondary label (current/stale/unselectable notes); "" when none. */
        final String secondary;
        final Icon icon;

        Item(Kind kind, String title, String secondary, Icon icon) {
            this.kind = kind;
            this.title = title;
            this.secondary = secondary == null ? "" : secondary;
            this.icon = icon;
        }
    }

    static final String EMPTY_MESSAGE =
            "No output rows enumerated (see Diagnostics). Tap Refresh outputs to reopen the idle native picker and load real outputs. No fake choices shown.";
    static final String NO_SELECTABLE_MESSAGE =
            "No selectable rows enumerated. No fake choices shown.";
    static final String LOADING_MESSAGE =
            "Loading outputs - reopening the idle native picker for live rows...";
    static final String CURRENT_NOW = "Current output - routing now";
    static final String CURRENT_STALE =
            "Current output at snapshot time - revalidate before relying on it";
    static final String UNSELECTABLE_LABEL =
            "Not selectable - active, blocked or section row";
    /** Persistent action label on stale rows: the tap revalidates live before any native row click. */
    static final String STALE_ACTION =
            SelectorPopupActivity.STALE_ACTION + " - tap revalidates live before any native row click";

    /** Compact floating dialog window bounds (dp): wrap-content card, never full width or
     *  full screen - especially on the 1920x1080 lapdock ("the popup does not need to be
     *  full width or screen"). Bounded height; only the device list scrolls inside. */
    static final int MAX_WIDTH_DP = 400;
    static final int MAX_HEIGHT_DP = 560;
    /** Android-dialog-like dim scrim behind the card; no full black shell takeover. */
    static final float SCRIM_DIM = 0.5f;

    /**
     * View items for the chooser, in the exact order the native picker presented
     * the rows. Current wins over selectable (the active row is never selectable);
     * section headers are never selectable; stale changes only the labels, never
     * which rows are shown.
     */
    static List<Item> items(List<SpikeMapper.Row> rows, boolean stale) {
        List<Item> out = new ArrayList<>();
        if (rows == null) return out;
        for (SpikeMapper.Row r : rows) {
            if (r == null || r.title == null) continue;
            if (r.sectionHeader) {
                out.add(new Item(Kind.SECTION, r.title, "", Icon.GENERIC));
            } else if (r.current) {
                out.add(new Item(Kind.CURRENT, r.title,
                        stale ? CURRENT_STALE : CURRENT_NOW, iconFor(r.title)));
            } else if (r.selectable) {
                out.add(new Item(Kind.SELECTABLE, r.title,
                        stale ? STALE_ACTION : "", iconFor(r.title)));
            } else {
                out.add(new Item(Kind.UNSELECTABLE, r.title,
                        UNSELECTABLE_LABEL, iconFor(r.title)));
            }
        }
        return out;
    }

    /**
     * Actionable empty/error message shown in the main chooser while nothing is
     * selectable: "" when at least one selectable destination exists. Loading is
     * covered by {@link #LOADING_MESSAGE} during refresh.
     */
    static String emptyMessage(List<SpikeMapper.Row> rows) {
        int choices = 0;
        if (rows != null)
            for (SpikeMapper.Row r : rows)
                if (r != null && r.selectable && !r.sectionHeader && !r.current) choices++;
        if (choices > 0) return "";
        return (rows == null || rows.isEmpty()) ? EMPTY_MESSAGE : NO_SELECTABLE_MESSAGE;
    }

    /**
     * Every title the user actually saw, in order - passed to the production
     * selection path so a tap can only ever resolve against the shown rows.
     */
    static List<String> titles(List<SpikeMapper.Row> rows) {
        List<String> out = new ArrayList<>();
        if (rows == null) return out;
        for (SpikeMapper.Row r : rows) if (r != null && r.title != null) out.add(r.title);
        return out;
    }

    /** Persistent role label shown on a real output row (never icon-only). */
    static String roleNote(String title, String preferred, String fallback) {
        if (title == null) return "";
        if (title.equals(preferred)) return "Preferred device";
        if (title.equals(fallback)) return "Fallback device";
        return "";
    }

    /**
     * The popup's transient status line: the last action outcome, with the
     * stale-snapshot marker first when the shown rows are unvalidated history.
     */
    static String statusText(String lastResult, boolean staleSnapshotShown) {
        String t = lastResult == null ? "" : lastResult;
        return staleSnapshotShown ? "STALE snapshot (rows not validated now). " + t : t;
    }

    /**
     * The popup's RETAINED outcome line (supersession notice): a separate,
     * persistent surface that survives status changes and popup refresh renders
     * until the user's next intentional action (F2: a superseded pending action
     * is never silently lost). "" when there is nothing retained.
     */
    static String retainedNotice(String retained) {
        return retained == null || retained.isEmpty() ? "" : retained;
    }

    /**
     * Single-line countdown "Auto-select in Ns: target (role)": partial seconds
     * round up so the visible ticks are 5..0; "" when not counting (remainingMs
     * < 0). One line of stable shape - no UI jump between ticks.
     */
    static String countdownLine(long remainingMs, String target, boolean targetIsPreferred) {
        if (remainingMs < 0) return "";
        long secs = (remainingMs + 999) / 1000;
        if (secs > 5) secs = 5;
        String t = (target == null || target.isEmpty())
                ? "no role set"
                : target + (targetIsPreferred ? " (preferred)" : " (fallback)");
        return "Auto-select in " + secs + "s: " + t;
    }

    /** Cosmetic device icon by generic device terminology; never invents a device type claim. */
    static Icon iconFor(String title) {
        String t = title == null ? "" : title.toLowerCase();
        if (t.contains("phone")) return Icon.PHONE;
        if (t.contains("glass") || t.contains("head") || t.contains("bud")
                || t.contains("headset") || t.contains("airpod")) return Icon.HEADPHONES;
        if (t.contains("external") || t.contains("display") || t.contains("monitor")
                || t.contains("screen") || t.contains("dock") || t.contains("tv")
                || t.contains("cast") || t.contains("hdmi")) return Icon.DISPLAY;
        if (t.contains("speaker") || t.contains("sound") || t.contains("audio")) return Icon.SPEAKER;
        return Icon.GENERIC;
    }
}