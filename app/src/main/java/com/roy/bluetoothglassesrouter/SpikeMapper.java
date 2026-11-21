package com.roy.bluetoothglassesrouter;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Production row mapper for the live SystemUI media output dialog (the tested system image API37):
 * classify rows into real enumerated choices, and resolve an exact popup choice
 * to the ONE native row that may be clicked. Two live/RE-derived structures
 * are accepted for the row list: device_list -> item_layout and
 * list_result -> [device_container ->] item_layout; the caller refuses ambiguity.
 * Fail closed everywhere: exact text only, unique match only, section headers, rows
 * carrying active/blocking controls (volume slider, progress, checkbox, session
 * buttons) and the active (slider) row are never selectable, unknown structures are
 * refused. No coordinate math, no guessing that "connected" means "available": the
 * only truth is what the native picker shows.
 */
final class SpikeMapper {
    static final String DIALOG_ID = "media_output_dialog";
    static final String LIST_ID = "device_list";
    static final String LIST_ID_ALT = "list_result";
    static final String ITEM_ID = "item_layout";
    static final String CONTAINER_ID = "device_container";
    static final String MAIN_ID = "main_content";
    static final String TITLE_ID = "title";
    static final String TITLE_ID_ALT = "two_line_title";
    static final String SEP_ID = "top_separator";
    /** The active (currently routing) row carries its volume slider here. */
    static final String SLIDER_ID = "volume_seekbar";
    /** Row-level controls that mean the row is active/transitioning: never selectable. */
    static final Set<String> BLOCKER_IDS = new HashSet<>(Arrays.asList(
            "volume_seekbar", "volume_indeterminate_progress", "check_box",
            "loading_indicator", "group_button", "ongoing_session_button"));
    static final int MAX_NODES = 256;
    /** The picker footer's exact Done control (Button @id/done). Verified from the
     *  device's own SystemUIGoogle.apk res/layout/media_output_dialog.xml: the
     *  dialog_footer Flow declares constraint_referenced_ids="stop,done"; @id/done
     *  carries string/inline_done_button = "Done". Its sibling @id/stop is
     *  "stop casting" and is NEVER an acceptable target. */
    static final String DONE_ID = "done";
    /** Exact label of the picker's Done control (device locale en; anything else is refused). */
    static final String DONE_TEXT = "Done";

    /** One real enumerated row exactly as the native picker presented it. */
    static final class Row {
        final String title;
        final boolean selectable;
        final boolean sectionHeader;
        /** True when this is the currently routing output (the active row, carries its slider). */
        final boolean current;
        Row(String title, boolean selectable, boolean sectionHeader) {
            this(title, selectable, sectionHeader, false);
        }
        Row(String title, boolean selectable, boolean sectionHeader, boolean current) {
            this.title = title; this.selectable = selectable; this.sectionHeader = sectionHeader;
            this.current = current;
        }
    }

    /**
     * Classify one item_layout row from observed children. Exactly one bounded,
     * non-empty title is required. Observed live structures:
     *  - selectable output: has a clickable container (main_content or the item itself)
     *  - active output: has non-clickable main_content (volume slider shown)
     *  - section header: no main_content, carries top_separator
     *  - blocked row (slider/progress/checkbox/session controls present): shown but
     *    never selectable
     * Anything else returns null (refused; never guessed).
     */
    static Row row(String title, int titleCount, boolean hasMain, boolean mainClickable, boolean hasSeparator, boolean blocked) {
        return row(title, titleCount, hasMain, mainClickable, hasSeparator, blocked, false);
    }

    /** Same classification with an explicit current/active marker (slider-bearing row). */
    static Row row(String title, int titleCount, boolean hasMain, boolean mainClickable, boolean hasSeparator, boolean blocked, boolean current) {
        if (title == null || title.isEmpty() || title.length() > 128 || titleCount != 1) return null;
        if (hasMain && hasSeparator) return null;
        if (blocked && !hasMain) return null;
        if (blocked) return new Row(title, false, false, current);
        if (hasMain && mainClickable) return new Row(title, true, false, false);
        if (hasMain) return new Row(title, false, false, false);
        if (hasSeparator) return new Row(title, false, true, false);
        return null;
    }

    /** Exact-unique resolution of a popup choice to a SELECTABLE row; null on any deviation. */
    static Row resolve(String choice, List<Row> rows) {
        if (choice == null || choice.isEmpty() || rows == null) return null;
        Row found = null;
        for (Row r : rows) {
            if (r == null || !r.selectable || !choice.equals(r.title)) continue;
            if (found != null) return null;
            found = r;
        }
        return found;
    }

    /**
     * Row-list scoping from observed container-id counts inside ONE dialog:
     * exactly one known container -> scope to it; neither known container -> walk
     * the whole dialog for item_layout rows (covers unlabelled containers);
     * anything else (duplicates/mixed) -> null (refused; never guessed).
     */
    static String scope(int deviceListCount, int listResultCount) {
        if (deviceListCount + listResultCount == 1)
            return deviceListCount == 1 ? LIST_ID : LIST_ID_ALT;
        if (deviceListCount == 0 && listResultCount == 0) return "dialogwalk";
        return null;
    }
}
