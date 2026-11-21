package com.roy.bluetoothglassesrouter;

import org.junit.Test;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Preferred/Fallback roles + optional five-second auto-select policy
 * . Pure JVM, fake-clock: role
 * assignment is deterministic and distinct (same handle clears the other role
 * with an explanation), the countdown is one-shot per popup session (no rearm
 * after the automatic selection ran, no restart on route auto-refresh), manual
 * selection / Done / OFF / role edits cancel it, and expiry selects preferred
 * else fallback ONLY against the live row list - absent, unusable, ambiguous
 * (duplicate titles) or unreadable lists leave the picker open with a clear
 * reason and never a guessed route.
 */
public class AutoSelectPolicyTest {

    private static SpikeMapper.Row row(String title) {
        return new SpikeMapper.Row(title, true, false, false);
    }

    private static SpikeMapper.Row currentRow(String title) {
        return new SpikeMapper.Row(title, false, false, true);
    }

    private static SpikeMapper.Row blockedRow(String title) {
        return new SpikeMapper.Row(title, false, false, false);
    }

    // ---- roles: distinct single-choice assignment with deterministic same-handle handling ----

    @Test public void assigningPreferredAndFallbackOnDifferentRowsKeepsBoth() {
        AutoSelectPolicy.RoleEdit e1 = AutoSelectPolicy.toggle(new AutoSelectPolicy.Roles(null, null), "A", true);
        assertEquals("A", e1.roles.preferred);
        assertNull(e1.roles.fallback);
        AutoSelectPolicy.RoleEdit e2 = AutoSelectPolicy.toggle(e1.roles, "B", false);
        assertEquals("A", e2.roles.preferred);
        assertEquals("B", e2.roles.fallback);
        assertTrue(e2.changed);
    }

    @Test public void sameHandleClearsTheOtherRoleAndExplains() {
        // Fallback "A" then Preferred "A": the fallback role must be cleared, explained.
        AutoSelectPolicy.Roles fb = new AutoSelectPolicy.Roles(null, "A");
        AutoSelectPolicy.RoleEdit e = AutoSelectPolicy.toggle(fb, "A", true);
        assertEquals("A", e.roles.preferred);
        assertNull("same handle must clear the other role", e.roles.fallback);
        assertTrue(e.message, e.message.contains("A"));
        assertTrue(e.message, e.message.toLowerCase().contains("fallback"));
        // Symmetric: Preferred "B" then Fallback "B".
        AutoSelectPolicy.Roles pf = new AutoSelectPolicy.Roles("B", null);
        AutoSelectPolicy.RoleEdit e2 = AutoSelectPolicy.toggle(pf, "B", false);
        assertNull("same handle must clear the other role", e2.roles.preferred);
        assertEquals("B", e2.roles.fallback);
        assertTrue(e2.message, e2.message.toLowerCase().contains("preferred"));
    }

    @Test public void singleChoiceGroupsMoveTheRoleNotDuplicateIt() {
        AutoSelectPolicy.Roles r = new AutoSelectPolicy.Roles("A", "B");
        // Preferred moves from A to C: only one preferred at a time; fallback untouched.
        AutoSelectPolicy.RoleEdit e = AutoSelectPolicy.toggle(r, "C", true);
        assertEquals("C", e.roles.preferred);
        assertEquals("B", e.roles.fallback);
        // Fallback moves from B to D.
        AutoSelectPolicy.RoleEdit e2 = AutoSelectPolicy.toggle(e.roles, "D", false);
        assertEquals("C", e2.roles.preferred);
        assertEquals("D", e2.roles.fallback);
    }

    @Test public void togglingTheSameRowAgainClearsThatRoleOnly() {
        AutoSelectPolicy.Roles r = new AutoSelectPolicy.Roles("A", "B");
        AutoSelectPolicy.RoleEdit e = AutoSelectPolicy.toggle(r, "A", true);
        assertNull("tap on the assigned row clears that role", e.roles.preferred);
        assertEquals("other role untouched", "B", e.roles.fallback);
        AutoSelectPolicy.RoleEdit e2 = AutoSelectPolicy.toggle(r, "B", false);
        assertEquals("A", e2.roles.preferred);
        assertNull(e2.roles.fallback);
    }

    @Test public void emptyTitleNeverAssigns() {
        AutoSelectPolicy.RoleEdit e = AutoSelectPolicy.toggle(new AutoSelectPolicy.Roles(null, null), "", true);
        assertFalse(e.changed);
        assertNull(e.roles.preferred);
        AutoSelectPolicy.RoleEdit e2 = AutoSelectPolicy.toggle(new AutoSelectPolicy.Roles(null, null), null, false);
        assertFalse(e2.changed);
        assertNull(e2.roles.fallback);
    }

    @Test public void absentPreferredConfigIsPreservedUntilExplicitlyCleared() {
        // A preferred device that is not on the live list must NOT be auto-cleared:
        // expiry over rows without it changes nothing about the roles.
        AutoSelectPolicy.Roles r = new AutoSelectPolicy.Roles("Gone Device", "B");
        AutoSelectPolicy.expiry(r, Arrays.asList(row("A"), row("B")));
        assertEquals("absent preferred survives", "Gone Device", r.preferred);
        // Explicit clear is the only removal path.
        AutoSelectPolicy.RoleEdit e = AutoSelectPolicy.clear(r, true);
        assertNull(e.roles.preferred);
        assertEquals("B", e.roles.fallback);
    }

    // ---- expiry: preferred first, fallback when preferred unavailable ----

    @Test public void expirySelectsPreferredWhenAvailable() {
        List<SpikeMapper.Row> rows = Arrays.asList(currentRow("This phone"), row("Desk Headset"), row("BT"));
        AutoSelectPolicy.Expiry e = AutoSelectPolicy.expiry(new AutoSelectPolicy.Roles("Desk Headset", "BT"), rows);
        assertEquals(AutoSelectPolicy.ExpiryAction.SELECT, e.action);
        assertEquals("Desk Headset", e.targetTitle);
    }

    @Test public void expiryFallsBackWhenPreferredAbsent() {
        List<SpikeMapper.Row> rows = Arrays.asList(currentRow("This phone"), row("BT"));
        AutoSelectPolicy.Expiry e = AutoSelectPolicy.expiry(new AutoSelectPolicy.Roles("Desk Headset", "BT"), rows);
        assertEquals(AutoSelectPolicy.ExpiryAction.SELECT, e.action);
        assertEquals("fallback must be selected when preferred is absent", "BT", e.targetTitle);
        assertTrue(e.message, e.message.contains("BT"));
    }

    @Test public void expiryFallsBackWhenPreferredBlockedButFallbackUsable() {
        // Preferred title is offered but not a usable route right now (blocked row).
        List<SpikeMapper.Row> rows = Arrays.asList(currentRow("This phone"), blockedRow("Desk Headset"), row("BT"));
        AutoSelectPolicy.Expiry e = AutoSelectPolicy.expiry(new AutoSelectPolicy.Roles("Desk Headset", "BT"), rows);
        assertEquals(AutoSelectPolicy.ExpiryAction.SELECT, e.action);
        assertEquals("BT", e.targetTitle);
    }

    @Test public void expiryNeverClicksWhenTargetIsAlreadyCurrent() {
        List<SpikeMapper.Row> rows = Arrays.asList(currentRow("Desk Headset"), row("BT"));
        AutoSelectPolicy.Expiry e = AutoSelectPolicy.expiry(new AutoSelectPolicy.Roles("Desk Headset", "BT"), rows);
        assertEquals(AutoSelectPolicy.ExpiryAction.ALREADY_CURRENT, e.action);
        assertEquals("Desk Headset", e.targetTitle);
        // Also when only the fallback is current and preferred absent.
        List<SpikeMapper.Row> fbCurrent = Arrays.asList(currentRow("BT"), row("X"));
        AutoSelectPolicy.Expiry e2 = AutoSelectPolicy.expiry(new AutoSelectPolicy.Roles("Gone", "BT"), fbCurrent);
        assertEquals(AutoSelectPolicy.ExpiryAction.ALREADY_CURRENT, e2.action);
        assertEquals("BT", e2.targetTitle);
    }

    @Test public void expiryLeavesOpenWhenNeitherAvailable() {
        List<SpikeMapper.Row> rows = Arrays.asList(currentRow("This phone"), row("BT"));
        AutoSelectPolicy.Expiry e = AutoSelectPolicy.expiry(new AutoSelectPolicy.Roles("Gone 1", "Gone 2"), rows);
        assertEquals(AutoSelectPolicy.ExpiryAction.LEAVE_OPEN, e.action);
        assertNull(e.targetTitle);
        assertTrue(e.message, e.message.length() > 0);
    }

    @Test public void expiryLeavesOpenWhenNoRolesConfigured() {
        AutoSelectPolicy.Expiry e = AutoSelectPolicy.expiry(new AutoSelectPolicy.Roles(null, null),
                Arrays.asList(row("A")));
        assertEquals(AutoSelectPolicy.ExpiryAction.LEAVE_OPEN, e.action);
    }

    @Test public void expiryLeavesOpenOnEmptyOrUnreadableList() {
        assertEquals(AutoSelectPolicy.ExpiryAction.LEAVE_OPEN,
                AutoSelectPolicy.expiry(new AutoSelectPolicy.Roles("A", "B"), Collections.<SpikeMapper.Row>emptyList()).action);
        assertEquals(AutoSelectPolicy.ExpiryAction.LEAVE_OPEN,
                AutoSelectPolicy.expiry(new AutoSelectPolicy.Roles("A", "B"), null).action);
    }

    @Test public void duplicateTitleAliasesAreAmbiguousAndNeverGuessed() {
        // Two live rows share the saved title: fail closed (no random pick, no silent fallback).
        List<SpikeMapper.Row> rows = Arrays.asList(currentRow("This phone"), row("Speaker"), row("Speaker"));
        AutoSelectPolicy.Expiry e = AutoSelectPolicy.expiry(new AutoSelectPolicy.Roles("Speaker", "BT"), rows);
        assertEquals(AutoSelectPolicy.ExpiryAction.LEAVE_OPEN, e.action);
        assertTrue(e.message, e.message.toLowerCase().contains("ambiguous"));
        // Ambiguous fallback is also refused (never a guessed fallback).
        List<SpikeMapper.Row> rows2 = Arrays.asList(currentRow("This phone"), row("BT"), row("BT"));
        AutoSelectPolicy.Expiry e2 = AutoSelectPolicy.expiry(new AutoSelectPolicy.Roles("Gone", "BT"), rows2);
        assertEquals(AutoSelectPolicy.ExpiryAction.LEAVE_OPEN, e2.action);
    }

    @Test public void duplicatePreferredAndFallbackSameTitleIsDeterministic() {
        // Corrupt state (roles equal) still resolves deterministically to the one row.
        List<SpikeMapper.Row> rows = Arrays.asList(currentRow("This phone"), row("BT"));
        AutoSelectPolicy.Expiry e = AutoSelectPolicy.expiry(new AutoSelectPolicy.Roles("BT", "BT"), rows);
        assertEquals(AutoSelectPolicy.ExpiryAction.SELECT, e.action);
        assertEquals("BT", e.targetTitle);
    }

    // ---- countdown timer: fake clock, one shot per popup session ----

    @Test public void countdownIsFiveSecondsAndArmsOnlyExplicitly() {
        AutoSelectPolicy.Countdown c = new AutoSelectPolicy.Countdown();
        assertEquals(5000L, AutoSelectPolicy.COUNTDOWN_MS);
        assertFalse("default is OFF: nothing arms by itself", c.isArmed());
        // Explicit enable with rows ready arms at now+5000 (monotonic clock passed in).
        assertTrue(c.enable(1L, true, 1000L));
        assertTrue(c.isArmed());
        assertEquals(5000L, c.remaining(1000L));
        assertEquals(3200L, c.remaining(2800L));
        assertEquals(0L, c.remaining(6000L));
    }

    @Test public void countdownExpiresExactlyOnceAtFiveSeconds() {
        AutoSelectPolicy.Countdown c = new AutoSelectPolicy.Countdown();
        c.enable(1L, true, 0L);
        assertFalse("not due before 5s", c.expireOnce(4999L));
        assertTrue("due at 5s", c.expireOnce(5000L));
        assertTrue(c.firedThisSession(1L));
        assertFalse("one shot: never a duplicate expiry callback", c.expireOnce(5001L));
        assertFalse("disarmed after firing", c.isArmed());
    }

    @Test public void manualDoneOffAndRoleEditsCancelCountdownAndPending() {
        AutoSelectPolicy.Countdown c = new AutoSelectPolicy.Countdown();
        c.enable(1L, true, 0L);
        assertTrue(c.cancel());
        assertFalse(c.isArmed());
        assertFalse("cancelled countdown never fires", c.expireOnce(99999L));
        // OFF while waiting for rows: the pending arm is cancelled too.
        AutoSelectPolicy.Countdown c2 = new AutoSelectPolicy.Countdown();
        c2.enable(1L, false, 0L); // rows not ready yet: pending
        assertFalse(c2.isArmed());
        assertTrue(c2.hasPending());
        c2.cancel();
        assertFalse(c2.hasPending());
        assertFalse(c2.onRowsReady(1L, 100L));
    }

    @Test public void timerStartsOnlyAfterUsableRowsReady() {
        AutoSelectPolicy.Countdown c = new AutoSelectPolicy.Countdown();
        // Enabled before rows are usable (native acquisition still running): pending, not armed.
        c.enable(1L, false, 0L);
        assertFalse(c.isArmed());
        // Usable live rows arrive: the countdown starts now (not at enable time).
        assertTrue(c.onRowsReady(1L, 4200L));
        assertTrue(c.isArmed());
        assertEquals("deadline anchors at rows-ready time", 5000L, c.remaining(4200L));
        // Later route auto-refreshes (rows ready again) never restart the countdown.
        assertFalse(c.onRowsReady(1L, 6000L));
        assertEquals(5000L - 1800L, c.remaining(6000L));
    }

    @Test public void postSelectionNoRearmInSameSession() {
        AutoSelectPolicy.Countdown c = new AutoSelectPolicy.Countdown();
        c.enable(1L, true, 0L);
        assertTrue(c.expireOnce(5000L));
        // The automatic selection ran: nothing rearms in this session (no repeated fights).
        assertFalse(c.enable(1L, true, 6000L));
        assertFalse(c.isArmed());
        // A NEW popup session may arm exactly one fresh timer.
        c.newSession(2L);
        assertTrue(c.enable(2L, true, 7000L));
        assertTrue(c.isArmed());
    }

    @Test public void refreshRelaunchNeverRearmsACancelledTimer() {
        AutoSelectPolicy.Countdown c = new AutoSelectPolicy.Countdown();
        c.newSession(1L);
        c.enable(1L, true, 0L);
        c.cancel(); // manual destination selection
        // The post-selection route auto-refresh re-renders the popup (rows ready):
        assertFalse("a user choice must not be followed by a new countdown", c.onRowsReady(1L, 1000L));
        assertFalse(c.isArmed());
    }

    @Test public void explicitToggleRestartIsTheOnlyDocumentedRestart() {
        // After a cancel (role edit / manual choice / OFF-ON), an explicit enable
        // restarts the countdown deliberately - and only once per enable.
        AutoSelectPolicy.Countdown c = new AutoSelectPolicy.Countdown();
        c.enable(1L, true, 0L);
        assertTrue(c.cancel()); // role edited while counting
        assertTrue(c.enable(1L, true, 100L)); // explicit OFF->ON
        assertTrue(c.isArmed());
        assertEquals(5000L, c.remaining(100L));
    }

    @Test public void enableIsIdempotentWhileArmed() {
        AutoSelectPolicy.Countdown c = new AutoSelectPolicy.Countdown();
        assertTrue(c.enable(1L, true, 0L));
        assertFalse("a second enable never extends or duplicates the timer", c.enable(1L, true, 2500L));
        assertEquals("deadline unchanged", 2500L, c.remaining(2500L));
    }

    // ---- display helpers (countdown text, no UI jump: single-line 5..0) ----

    @Test public void countdownLineShowsTargetAndRemaining5To0() {
        assertEquals("Auto-select in 5s: Desk Headset (preferred)",
                SelectorPopupModel.countdownLine(5000L, "Desk Headset", true));
        assertEquals("partial seconds round up (visible 5..0 ticks)",
                "Auto-select in 5s: Desk Headset (preferred)",
                SelectorPopupModel.countdownLine(4200L, "Desk Headset", true));
        assertEquals("Auto-select in 4s: Desk Headset (preferred)",
                SelectorPopupModel.countdownLine(3200L, "Desk Headset", true));
        assertEquals("Auto-select in 1s: BT (fallback)",
                SelectorPopupModel.countdownLine(400L, "BT", false));
        assertEquals("Auto-select in 0s: BT (fallback)",
                SelectorPopupModel.countdownLine(0L, "BT", false));
        assertEquals("", SelectorPopupModel.countdownLine(-1L, "X", true));
    }

    @Test public void roleNoteLabelsArePersistentAndUnambiguous() {
        assertEquals("Preferred device", SelectorPopupModel.roleNote("A", "A", null));
        assertEquals("Fallback device", SelectorPopupModel.roleNote("B", "A", "B"));
        assertEquals("", SelectorPopupModel.roleNote("C", "A", "B"));
    }

    // ---- source contracts: persistence + production cancellation wiring ----

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    @Test public void rolesAndAutoOptInPersistAndDefaultToOff() throws Exception {
        String s = read("src/main/java/com/roy/bluetoothglassesrouter/BluetoothGlassesRoutingService.java");
        assertTrue(s.contains("KEY_ROLE_PREF"));
        assertTrue(s.contains("KEY_ROLE_FB"));
        assertTrue("auto-select opt-in is persisted", s.contains("KEY_AUTO"));
        assertTrue("auto-select DEFAULTS TO OFF until the user explicitly enables it",
                s.contains("getBoolean(KEY_AUTO,false)"));
        assertTrue("roles persist across sessions", s.contains("putString(KEY_ROLE_PREF"));
        assertTrue("absent preferred config is preserved: roles are written only in persistRoles",
                s.indexOf("putString(KEY_ROLE_PREF") == s.lastIndexOf("putString(KEY_ROLE_PREF"));
    }

    @Test public void roleAndAutoWritesNeverCancelThePopupFlow() throws Exception {
        String s = read("src/main/java/com/roy/bluetoothglassesrouter/BluetoothGlassesRoutingService.java");
        assertTrue("role/auto pref writes are excluded from the config-changed cancel",
                s.contains("key.startsWith(\"role_\")||KEY_AUTO.equals(key)"));
    }

    @Test public void manualSelectionDoneAndOffCancelCountdownWiring() throws Exception {
        String svc = read("src/main/java/com/roy/bluetoothglassesrouter/BluetoothGlassesRoutingService.java");
        assertTrue("manual destination selection cancels the countdown first",
                svc.contains("cancelAutoSelect(\"Manual selection: auto-select countdown cancelled.\")"));
        String popup = read("src/main/java/com/roy/bluetoothglassesrouter/SelectorPopupActivity.java");
        assertTrue("Done cancels the countdown and pending automatic action",
                popup.contains("cancelAutoSelect(\"Done: auto-select countdown cancelled.\")"));
        assertTrue("OFF immediately cancels (toggle wired to the service)",
                popup.contains("s.setAutoSelect(!s.autoSelectEnabled())"));
    }

    @Test public void autoExpiryUsesTheBoundedSelectionMappingAndConfirmation() throws Exception {
        String s = read("src/main/java/com/roy/bluetoothglassesrouter/BluetoothGlassesRoutingService.java");
        assertTrue("expiry maps through the exact bounded selection path (identity + confirmation)",
                s.contains("spikeSelectOn(found,rows,ex.targetTitle,titles(rows))"));
        assertTrue("expiry decides over roles + live rows via the pure policy",
                s.contains("AutoSelectPolicy.expiry(roles(),rows)"));
    }

    @Test public void timerArmsOnlyAfterRowsReadyOncePerFreshPopupSession() throws Exception {
        String s = read("src/main/java/com/roy/bluetoothglassesrouter/BluetoothGlassesRoutingService.java");
        assertTrue("fresh popup session resets the one-shot countdown",
                s.contains("autoCountdown.newSession(popupSessionGen)"));
        assertTrue("countdown starts only when usable live rows are ready",
                s.contains("autoCountdown.onRowsReady(popupSessionGen"));
        assertTrue("post-selection no rearm for the rest of the session",
                s.contains("autoCountdown.firedThisSession(popupSessionGen)"));
    }
}
