package com.roy.bluetoothglassesrouter;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Custom app popup placed on the external display while docked using ordinary
 * ActivityOptions.setLaunchDisplayId (no overlay permission). It lists ONLY rows
 * enumerated live from the system media output dialog by the accessibility service;
 * when enumeration is unavailable it says exactly that and shows no choices (never
 * invented rows, never a claim of ALL outputs). The current (routing) output is
 * identified. A choice maps to the exact native picker row click via the service
 * (stale/ambiguous lists are refused); the app itself never selects a route, never
 * touches Bluetooth and never changes volume. One instance per external display.
 *
 * The chooser UI visually follows the system media output selector
 * opaque rounded card, bold section
 * headers, thin dividers, icon + label full-row destinations, the active device
 * as a white pill with a check, an outlined action pill and a white Done pill.
 * The window itself is a compact Android-style floating dialog (wrap-content
 * card, dim scrim, bounded scroll) - never a full-width/full-screen shell.
 * Debug/provenance detail lives in a collapsible Diagnostics area; the
 * main chooser keeps only actionable loading/empty/error states.
 * The Done pill performs shared session-owned cleanup: a bounded request for the
 * exact Done action of the native picker this popup's session enumerated from
 * (verified identity only; absent/stale/unverified/repeat cases touch nothing),
 * then closes this popup. A user finish also cancels any pending acquisition or
 * async refresh so a dismissed popup never reopens itself.
 */
public final class SelectorPopupActivity extends Activity {
    static List<SpikeMapper.Row> rows = Collections.emptyList();
    static String provenance = "no enumeration";
    /** Last action outcome, survives re-renders (render() rebuilds the whole view). */
    static volatile String lastResult = "";
    /** RETAINED outcome (supersession notice): SEPARATE from lastResult so ordinary
     *  status writes (popup load/refresh) can never clobber it. Set/cleared only by
     *  the service's acquisition ownership (AcquireSequencer via reportRetained);
     *  kept visible until the user's next intentional action (F2). */
    static volatile String retainedOutcome = "";
    /** True when rows come from the marked stale snapshot rather than a live enumeration. */
    static volatile boolean staleRows = false;

    /** Live-instance tracker: the service's post-selection auto-refresh never reopens the
     *  popup behind a user who closed it (no surprise UI, no duplicate windows). */
    private static volatile int instances = 0;

    static boolean isOpen() { return instances > 0; }

    /** Live instances (at most one per external display) for the service's confirmed
     *  auto-success close: finish every open copy, nothing else. */
    private static final java.util.List<SelectorPopupActivity> live =
            new java.util.ArrayList<>();

    /** Bounded close hook (confirmed automatic success only): finish our popup copies. */
    static void closeAll() {
        for (SelectorPopupActivity a : new java.util.ArrayList<>(live)) a.finish();
    }

    /** True when the popup shows a usable LIVE route list (stale/empty snapshots don't count:
     *  the auto-select countdown must never start before real rows are ready). */
    static boolean hasLiveRows() { return !staleRows && !rows.isEmpty(); }

    /** How the last popup ended: true after a user finish (Done/back), false while open
     *  or when the system destroyed it with its display. The startup gate restores only
     *  a popup the user never closed (reconnect semantics). */
    private static volatile boolean lastCloseWasUser = true;

    static boolean userClosedLastPopup() { return lastCloseWasUser; }

    /** Persistent action label on stale rows; the tap REVALIDATES live before any native row click. */
    static final String STALE_ACTION = "Revalidate & select";

    /** Session token of the live enumeration this popup was launched from. It travels via
     *  the launch Intent (never static cross-process state); the Done cleanup is refused
     *  unless the service still owns exactly this acquisition session. */
    static final String EXTRA_SESSION = "session";
    private long sessionToken = ChooserLifecycle.NO_SESSION;

    // Surfaces sampled from the native media output selector capture (opaque, dark).
    private static final int SHEET = 0xFF303030;
    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_SECONDARY = 0xFFC2C2C2;
    private static final int TEXT_MUTED = 0xFF9E9E9E;
    private static final int PILL_TEXT = 0xFF1A1A1A;
    /** Role chip fills (clear, labeled, never icon-only): Preferred/Fallback are distinct. */
    private static final int PREF_FILL = 0xFF2E7D32;
    private static final int FALL_FILL = 0xFF1565C0;

    /** Visible auto-select countdown line; updated in place every 200ms while counting
     *  (single stable line, 5..0 - no UI jump, no re-render per tick). */
    private TextView countdownView;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable countdownTick = new Runnable() {
        @Override public void run() {
            BluetoothGlassesRoutingService s = BluetoothGlassesRoutingService.connected;
            if (countdownView == null || isFinishing()) return;
            String line = s == null ? "" : s.autoCountdownText();
            countdownView.setText(line);
            countdownView.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
            if (s != null && s.autoArmed()) uiHandler.postDelayed(this, 200);
        }
    };

    static void launch(Context ctx, List<SpikeMapper.Row> r, String prov, long session) {
        launch(ctx, r, prov, false, session);
    }

    static void launch(Context ctx, List<SpikeMapper.Row> r, String prov, boolean stale, long session) {
        rows = r == null ? Collections.<SpikeMapper.Row>emptyList() : r;
        provenance = prov == null ? "" : prov;
        staleRows = stale;
        Intent i = new Intent(ctx, SelectorPopupActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_SESSION, session);
        ctx.startActivity(i, ActivityOptions.makeBasic()
                .setLaunchDisplayId(SelectorOpener.pickDisplay(externalIds(ctx))).toBundle());
    }

    static Set<Integer> externalIds(Context ctx) {
        Set<Integer> ids = new HashSet<>();
        for (Display d : ctx.getSystemService(android.hardware.display.DisplayManager.class).getDisplays())
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY) ids.add(d.getDisplayId());
        return ids;
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        instances++;
        live.add(this);
        lastCloseWasUser = false; // open now: only a user finish may count as "closed"
        sessionToken = getIntent().getLongExtra(EXTRA_SESSION, ChooserLifecycle.NO_SESSION);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        render();
    }

    @Override protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        live.remove(this);
        if (--instances < 0) instances = 0;
        // isFinishing(): Done/back (user) vs system teardown with its display (kill).
        lastCloseWasUser = isFinishing();
        if (lastCloseWasUser) {
            // User dismissal cancels any pending acquisition/async refresh so this
            // dismissed popup never reopens itself and the native helper (panel/picker
            // acquisition) is never restarted for it.
            BluetoothGlassesRoutingService s = BluetoothGlassesRoutingService.connected;
            if (s != null) s.onPopupUserClosed();
        }
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent); // keep the session token authoritative across recreation
        sessionToken = intent.getLongExtra(EXTRA_SESSION, ChooserLifecycle.NO_SESSION);
        render();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private ImageView iconView(SelectorPopupModel.Icon kind, int tint) {
        ImageView iv = new ImageView(this);
        int res;
        switch (kind) {
            case PHONE: res = R.drawable.ic_device_phone; break;
            case HEADPHONES: res = R.drawable.ic_device_headphones; break;
            case DISPLAY: res = R.drawable.ic_device_display; break;
            case SPEAKER: res = R.drawable.ic_device_speaker; break;
            default: res = R.drawable.ic_device_generic; break;
        }
        iv.setImageResource(res);
        if (tint != 0) iv.setColorFilter(tint);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(24), dp(24));
        lp.setMarginEnd(dp(16));
        iv.setLayoutParams(lp);
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return iv;
    }

    private TextView label(String text, float sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    /** Labeled role control (never icon-only): filled when the row holds the role,
     *  outlined otherwise. Tap toggles that role group for this row. */
    private TextView roleChip(String text, boolean selected, int fill) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(10);
        chip.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        if (selected) {
            bg.setColor(fill);
            chip.setTextColor(Color.WHITE);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            bg.setColor(Color.TRANSPARENT);
            bg.setStroke(dp(1), TEXT_MUTED);
            chip.setTextColor(TEXT_SECONDARY);
        }
        chip.setBackground(bg);
        chip.setPadding(dp(10), dp(6), dp(10), dp(6));
        chip.setMinHeight(dp(40));
        chip.setFocusable(true);
        chip.setClickable(true);
        return chip;
    }

    /** Preferred/Fallback role controls for one real output row (two single-choice groups). */
    private LinearLayout roleChips(final String title, boolean isPref, boolean isFb) {
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        TextView cp = roleChip("Preferred", isPref, PREF_FILL);
        cp.setContentDescription((isPref ? "Remove preferred role from " : "Set preferred role for ") + title);
        cp.setOnClickListener(v -> {
            BluetoothGlassesRoutingService s = BluetoothGlassesRoutingService.connected;
            String msg = s == null ? "Service disconnected; role unchanged." : s.toggleRole(title, true);
            lastResult = msg;
            render();
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.setMarginEnd(dp(4));
        chips.addView(cp, plp);
        TextView cf = roleChip("Fallback", isFb, FALL_FILL);
        cf.setContentDescription((isFb ? "Remove fallback role from " : "Set fallback role for ") + title);
        cf.setOnClickListener(v -> {
            BluetoothGlassesRoutingService s = BluetoothGlassesRoutingService.connected;
            String msg = s == null ? "Service disconnected; role unchanged." : s.toggleRole(title, false);
            lastResult = msg;
            render();
        });
        chips.addView(cf);
        return chips;
    }

    /** Rebuild the whole view from the current static rows/provenance (single instance). */
    private void render() {
        // One countdown ticker per render: a rebuild can never duplicate countdown callbacks.
        uiHandler.removeCallbacks(countdownTick);
        AutoSelectPolicy.Roles curRoles;
        {
            BluetoothGlassesRoutingService autoSvc = BluetoothGlassesRoutingService.connected;
            curRoles = autoSvc != null ? autoSvc.roles() : new AutoSelectPolicy.Roles(null, null);
        }
        // Compact Android-style floating dialog: wrap-content card with a bounded
        // scroll area and a dim scrim behind it - NOT a full-width/full-screen
        // shell ,
        // especially on the 1920x1080 lapdock. The approved 0.26 card styling is
        // unchanged; only the window frame policy differs.
        FrameLayout root = new FrameLayout(this);
        root.setClickable(true); // taps in the window background never dismiss

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(SHEET);
        sheetBg.setCornerRadius(dp(24));
        sheet.setBackground(sheetBg);
        sheet.setClickable(true); // taps inside the sheet never dismiss
        int side = dp(20);
        sheet.setPadding(side, dp(16), side, dp(12));

        ScrollView scroll = new BoundedScrollView(this, dp(SelectorPopupModel.MAX_HEIGHT_DP));
        scroll.addView(sheet, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        WindowManager.LayoutParams wlp = window.getAttributes();
        wlp.width = Math.min(dp(SelectorPopupModel.MAX_WIDTH_DP),
                getResources().getDisplayMetrics().widthPixels);
        wlp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        wlp.gravity = Gravity.CENTER;
        wlp.dimAmount = SelectorPopupModel.SCRIM_DIM;
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setAttributes(wlp);

        // Header: app identity + outlined action pill (like the native sheet's outlined button).
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("Audio Router", 20, TEXT_PRIMARY, true);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button refresh = new Button(this);
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(Color.TRANSPARENT);
        outline.setCornerRadius(dp(24));
        outline.setStroke(dp(2), TEXT_PRIMARY);
        refresh.setBackground(outline);
        refresh.setForeground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null));
        refresh.setAllCaps(false);
        refresh.setTextColor(TEXT_PRIMARY);
        refresh.setTextSize(14);
        refresh.setMinHeight(dp(48));
        refresh.setPadding(dp(20), 0, dp(20), 0);
        refresh.setText("Refresh outputs");
        header.addView(refresh);
        sheet.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Actionable status line (last outcome; stale snapshot stays visible, not hidden).
        final TextView status = label("", 12, TEXT_SECONDARY, false);
        String statusText = SelectorPopupModel.statusText(lastResult, staleRows && !rows.isEmpty());
        status.setText(statusText);
        status.setPadding(0, dp(8), 0, dp(4));
        if (!statusText.isEmpty()) sheet.addView(status);

        // RETAINED outcome line (supersession notice, F2): a SEPARATE persistent
        // surface - kept across popup refresh/re-renders until the user's next
        // intentional action, never clobbered by ordinary status writes.
        String retainedText = SelectorPopupModel.retainedNotice(retainedOutcome);
        if (!retainedText.isEmpty()) {
            TextView retainedView = label(retainedText, 12, TEXT_SECONDARY, false);
            retainedView.setPadding(0, 0, 0, dp(4));
            sheet.addView(retainedView);
        }

        refresh.setOnClickListener(v -> {
            BluetoothGlassesRoutingService s = BluetoothGlassesRoutingService.connected;
            if (s == null) {
                lastResult = "Service disconnected; cannot refresh.";
                status.setText(lastResult);
                return;
            }
            // Production path: reacquires the idle native picker if it closed, then reloads rows.
            status.setText(SelectorPopupModel.LOADING_MESSAGE);
            lastResult = s.refreshOutputs();
            status.setText(lastResult);
        });

        final List<String> shownTitles = SelectorPopupModel.titles(rows);
        List<SelectorPopupModel.Item> items = SelectorPopupModel.items(rows, staleRows);
        boolean firstRow = true;
        for (final SelectorPopupModel.Item item : items) {
            if (item.kind == SelectorPopupModel.Kind.SECTION) {
                if (!firstRow) {
                    View divider = new View(this);
                    divider.setBackgroundColor(TEXT_SECONDARY);
                    LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                    dlp.topMargin = dp(10);
                    dlp.bottomMargin = dp(6);
                    sheet.addView(divider, dlp);
                }
                TextView h = label(item.title, 15, TEXT_PRIMARY, true);
                h.setPadding(0, dp(10), 0, dp(8));
                sheet.addView(h);
                firstRow = false;
                continue;
            }
            if (item.kind == SelectorPopupModel.Kind.CURRENT) {
                // Active device: white pill + check (the native selector emphasizes it the same way).
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                GradientDrawable pill = new GradientDrawable();
                pill.setColor(Color.WHITE);
                pill.setCornerRadius(dp(28));
                row.setBackground(pill);
                row.setMinimumHeight(dp(56));
                row.setPadding(dp(16), dp(6), dp(16), dp(6));
                row.addView(iconView(item.icon, PILL_TEXT));
                row.addView(label(item.title, 16, PILL_TEXT, true),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.addView(roleChips(item.title,
                        item.title.equals(curRoles.preferred), item.title.equals(curRoles.fallback)));
                ImageView check = new ImageView(this);
                check.setImageResource(R.drawable.ic_check);
                check.setLayoutParams(new LinearLayout.LayoutParams(dp(22), dp(22)));
                check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                row.addView(check);
                sheet.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                String curRole = SelectorPopupModel.roleNote(item.title, curRoles.preferred, curRoles.fallback);
                String curSecondary = curRole.isEmpty() ? item.secondary
                        : item.secondary.isEmpty() ? curRole : curRole + " - " + item.secondary;
                TextView note = label(curSecondary, 12, TEXT_SECONDARY, false);
                note.setPadding(dp(16), dp(2), 0, dp(6));
                sheet.addView(note);
                firstRow = false;
                continue;
            }
            boolean selectable = item.kind == SelectorPopupModel.Kind.SELECTABLE;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(selectable
                    ? R.drawable.row_ripple
                    : android.R.color.transparent);
            row.setMinimumHeight(dp(56));
            row.setPadding(dp(16), dp(8), dp(16), dp(8));
            row.addView(iconView(item.icon, selectable ? 0 : TEXT_MUTED));
            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.addView(label(item.title, 16, selectable ? TEXT_PRIMARY : TEXT_MUTED, false));
            String rowRole = SelectorPopupModel.roleNote(item.title, curRoles.preferred, curRoles.fallback);
            String rowSecondary = rowRole.isEmpty() ? item.secondary
                    : item.secondary.isEmpty() ? rowRole : rowRole + " - " + item.secondary;
            if (!rowSecondary.isEmpty())
                textCol.addView(label(rowSecondary, 12, selectable ? TEXT_SECONDARY : TEXT_MUTED, false));
            row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(roleChips(item.title,
                    item.title.equals(curRoles.preferred), item.title.equals(curRoles.fallback)));
            sheet.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (selectable) {
                // Exact title to the production selection path; the shownTitles guard means a tap
                // can only resolve against the rows the user actually saw. Stale snapshot rows are
                // never presented as validated destinations: the tap revalidates/reacquires live
                // before any native row click (the service gates it).
                final String choice = item.title;
                row.setClickable(true);
                row.setFocusable(true);
                row.setOnClickListener(v -> {
                    BluetoothGlassesRoutingService s = BluetoothGlassesRoutingService.connected;
                    if (s == null) {
                        lastResult = "Service disconnected; no row click attempted.";
                        status.setText(lastResult);
                        return;
                    }
                    lastResult = s.spikeSelect(choice, shownTitles);
                    status.setText(lastResult);
                });
            }
            firstRow = false;
        }

        // Actionable empty/error state stays in the main chooser (never fake choices).
        String empty = SelectorPopupModel.emptyMessage(rows);
        if (!empty.isEmpty()) {
            TextView none = label(empty, 13, TEXT_SECONDARY, false);
            none.setPadding(0, dp(8), 0, dp(8));
            sheet.addView(none);
        }

        // Auto-select control: persisted ON/OFF (default OFF until the user enables it)
        // plus the visible five-second countdown.
        LinearLayout auto = new LinearLayout(this);
        auto.setOrientation(LinearLayout.HORIZONTAL);
        auto.setGravity(Gravity.CENTER_VERTICAL);
        auto.setPadding(0, dp(10), 0, 0);
        TextView autoLabel = label("Auto-select (5s)", 14, TEXT_PRIMARY, false);
        auto.addView(autoLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button autoBtn = new Button(this);
        GradientDrawable autoOutline = new GradientDrawable();
        autoOutline.setColor(Color.TRANSPARENT);
        autoOutline.setCornerRadius(dp(24));
        autoOutline.setStroke(dp(2), TEXT_PRIMARY);
        autoBtn.setBackground(autoOutline);
        autoBtn.setForeground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null));
        autoBtn.setAllCaps(false);
        autoBtn.setTextColor(TEXT_PRIMARY);
        autoBtn.setTextSize(14);
        autoBtn.setMinHeight(dp(48));
        autoBtn.setPadding(dp(20), 0, dp(20), 0);
        boolean curAutoOn = BluetoothGlassesRoutingService.connected != null
                && BluetoothGlassesRoutingService.connected.autoSelectEnabled();
        autoBtn.setText(curAutoOn ? "On" : "Off");
        autoBtn.setOnClickListener(v -> {
            BluetoothGlassesRoutingService s = BluetoothGlassesRoutingService.connected;
            String msg = s == null
                    ? "Service disconnected; auto-select unchanged."
                    : s.setAutoSelect(!s.autoSelectEnabled());
            if (s != null) {
                autoBtn.setText(s.autoSelectEnabled() ? "On" : "Off");
                uiHandler.removeCallbacks(countdownTick);
                uiHandler.post(countdownTick);
            }
            lastResult = msg;
            status.setText(msg);
        });
        auto.addView(autoBtn);
        sheet.addView(auto, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Visible countdown line: "Auto-select in 5s: <target> (<role>)" -> 0s, one
        // stable line updated in place (no UI jump while counting).
        countdownView = label("", 12, TEXT_SECONDARY, false);
        countdownView.setPadding(0, dp(4), 0, dp(2));
        sheet.addView(countdownView);
        uiHandler.post(countdownTick);

        // Bottom actions: collapsible Diagnostics (debug/provenance detail) + Done pill.
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout diagBtn = new LinearLayout(this);
        diagBtn.setOrientation(LinearLayout.HORIZONTAL);
        diagBtn.setGravity(Gravity.CENTER_VERTICAL);
        diagBtn.setClickable(true);
        diagBtn.setFocusable(true);
        diagBtn.setMinimumHeight(dp(48));
        diagBtn.setPadding(0, dp(8), dp(8), dp(8));
        TextView diagLabel = label("Diagnostics", 13, TEXT_SECONDARY, false);
        final ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_chevron);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(18), dp(18));
        clp.setMarginStart(dp(4));
        chevron.setLayoutParams(clp);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        diagBtn.addView(diagLabel);
        diagBtn.addView(chevron);
        actions.addView(diagBtn);
        actions.addView(new View(this), new LinearLayout.LayoutParams(0, dp(1), 1));
        Button done = new Button(this);
        GradientDrawable doneBg = new GradientDrawable();
        doneBg.setColor(Color.WHITE);
        doneBg.setCornerRadius(dp(28));
        done.setBackground(doneBg);
        done.setForeground(new RippleDrawable(ColorStateList.valueOf(0x33000000), null, null));
        done.setAllCaps(false);
        done.setTextColor(PILL_TEXT);
        done.setTextSize(15);
        done.setMinHeight(dp(48));
        done.setPadding(dp(28), 0, dp(28), 0);
        done.setText("Done");
        done.setOnClickListener(v -> {
            // Shared session-owned cleanup : a bounded request for the
            // verified session-owned native picker's exact Done action (fail closed to a
            // normal popup close when absent/stale/unverified/repeat), then close OUR popup.
            // Done immediately cancels the auto-select countdown and any pending action.
            BluetoothGlassesRoutingService s = BluetoothGlassesRoutingService.connected;
            if (s != null) s.cancelAutoSelect("Done: auto-select countdown cancelled.");
            lastResult = s == null
                    ? "Service disconnected; native picker untouched; popup closed."
                    : s.closeOwnedPicker(sessionToken);
            status.setText(lastResult);
            finish();
        });
        actions.addView(done);
        sheet.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Debug/provenance detail out of the main chooser: collapsible diagnostic area.
        final TextView diag = label("Provenance: " + provenance
                + "\nRows shown: " + rows.size() + "; stale snapshot: " + staleRows
                + (lastResult == null || lastResult.isEmpty() ? "" : "\nLast result: " + lastResult),
                11, TEXT_MUTED, false);
        diag.setVisibility(View.GONE);
        diag.setPadding(0, dp(8), 0, 0);
        sheet.addView(diag);
        diagBtn.setOnClickListener(v -> {
            boolean show = diag.getVisibility() != View.VISIBLE;
            diag.setVisibility(show ? View.VISIBLE : View.GONE);
            chevron.setRotation(show ? 180f : 0f);
        });
    }

    /** Height-capped scroll area: the dialog card stays bounded; only the device list scrolls. */
    private static final class BoundedScrollView extends ScrollView {
        private final int maxHeight;
        BoundedScrollView(Context c, int maxHeightPx) { super(c); maxHeight = maxHeightPx; }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
        }
    }
}