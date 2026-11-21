package com.roy.bluetoothglassesrouter;

import org.junit.Test;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Boot / background lifecycle source contracts. The
 * Android-managed ENABLED AccessibilityService is the lifecycle owner (it rebinds
 * after boot + first unlock and after process death); these tests pin the MINIMAL
 * additions around it - one boot receiver for lifecycle signals + the idempotent
 * startup gate - and pin down what must NOT be bolted on (no foreground service,
 * no persistent notification, no watchdog/alarm, no direct-boot prefs migration).
 *
 * Platform facts these contracts encode (verified against the Android 8+
 * implicit-broadcast rules):
 *  - ACTION_BOOT_COMPLETED and ACTION_MY_PACKAGE_REPLACED ARE deliverable to
 *    manifest-registered receivers (both are explicit/exempt), so they are the
 *    "real registered manifest paths".
 *  - ACTION_USER_PRESENT is NOT manifest-deliverable on API 26+ (implicit
 *    broadcast ban, not on the exempt list); it is runtime-registered by the
 *    service instead.
 *  - Receiving BOOT_COMPLETED requires the RECEIVE_BOOT_COMPLETED permission
 *    (normal level = install-time only, never a runtime prompt).
 *
 * CLASSIFICATION (F5): MANIFEST TRIPWIRE - pins manifest/lifecycle wiring shape
 * (retained deliberately: these are cheap refactor tripwires). Behavioral boot
 * lifecycle truth lives in StartupGateTest (real execution).
 */
public class BootLifecycleManifestTest {

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static int count(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    @Test public void manifestRegistersTheBootReceiverWithRealDeliverableActions() throws Exception {
        String m = read("src/main/AndroidManifest.xml");
        int recv = m.indexOf(".BootReceiver");
        assertTrue("boot receiver must be registered in the manifest", recv > 0);
        String block = m.substring(recv, Math.min(m.length(), recv + 600));
        assertTrue("real BOOT_COMPLETED manifest path", block.contains("android.intent.action.BOOT_COMPLETED"));
        assertTrue("package-update manifest path (adb install -r keeps the lifecycle alive)",
                block.contains("android.intent.action.MY_PACKAGE_REPLACED"));
        assertFalse("USER_PRESENT is not manifest-deliverable on API 26+ (implicit broadcast ban); the service runtime-registers it",
                block.contains("USER_PRESENT"));
        assertTrue("receiver is not exported (only the system sends these)",
                block.contains("android:exported=\"false\""));
    }

    @Test public void manifestDeclaresOnlyTheRequiredNormalBootPermission() throws Exception {
        String m = read("src/main/AndroidManifest.xml");
        // RECEIVE_BOOT_COMPLETED is required to receive the boot broadcast; it is
        // NORMAL level = install-time, never a runtime prompt, never a user grant.
        assertTrue("RECEIVE_BOOT_COMPLETED required to receive BOOT_COMPLETED",
                m.contains("android.permission.RECEIVE_BOOT_COMPLETED"));
        assertEquals("exactly one declared permission", 1, count(m, "<uses-permission"));
        // No bolt-ons: no foreground service (no persistent notification needed -
        // the system keeps an enabled accessibility service alive), no watchdog.
        assertFalse("no foreground service", m.contains("android.permission.FOREGROUND_SERVICE"));
        assertFalse("no persistent notification permission", m.contains("android.permission.POST_NOTIFICATIONS"));
        assertFalse("no exact-alarm/watchdog surface", m.contains("SCHEDULE_EXACT_ALARM"));
        assertFalse("no runtime permissions", m.contains("android.permission.CAMERA")
                || m.contains("android.permission.RECORD_AUDIO") || m.contains("android.permission.ACCESS_FINE_LOCATION"));
        assertFalse("no direct boot receiver (sensitive prefs stay credential-encrypted)",
                m.contains("LOCKED_BOOT_COMPLETED"));
        assertFalse("no direct boot aware components", m.contains("directBootAware"));
    }

    @Test public void theServiceStaysTheSoleLifecycleOwner() throws Exception {
        String m = read("src/main/AndroidManifest.xml");
        // Exactly one service: the accessibility service. No companion/watchdog service.
        int first = m.indexOf("<service");
        assertTrue(first > 0);
        assertEquals("exactly one <service> element", -1, m.indexOf("<service", first + 1));
        assertTrue(m.contains("android.permission.BIND_ACCESSIBILITY_SERVICE"));
        String s = read("src/main/java/com/roy/bluetoothglassesrouter/BluetoothGlassesRoutingService.java");
        assertFalse("no WorkManager/watchdog scheduling", s.contains("WorkManager"));
        assertFalse("no exact alarms", s.contains("setExact"));
        assertFalse("no foreground service start", s.contains("startForeground"));
    }

    @Test public void bootReceiverWiringIsIdempotentAndPopupSafe() throws Exception {
        String r = read("src/main/java/com/roy/bluetoothglassesrouter/BootReceiver.java");
        assertTrue("receiver routes through the idempotent startup gate", r.contains("onStartupSignal"));
        assertTrue("receiver only nudges the connected service", r.contains("connected"));
        assertFalse("receiver never starts activities", r.contains("startActivity"));
        assertFalse("receiver never starts services", r.contains("startService"));
        assertFalse("receiver never touches settings", r.contains("Settings.Secure"));
        String s = read("src/main/java/com/roy/bluetoothglassesrouter/BluetoothGlassesRoutingService.java");
        assertTrue("service exposes one idempotent startup entry", s.contains("onStartupSignal"));
        assertTrue("service persists gate state across process death", s.contains("StartupGate.KEY_SATISFIED"));
        assertTrue("internal keys never cancel flows", s.contains("StartupGate.internalStateKey"));
        assertTrue("lock-deferred offers retry on the runtime unlock signal", s.contains("USER_PRESENT"));
    }

    @Test public void startupOffersNeverLaunchTheAppActivity() throws Exception {
        String s = read("src/main/java/com/roy/bluetoothglassesrouter/BluetoothGlassesRoutingService.java");
        assertFalse("boot/startup paths must never launch or raise MainActivity",
                s.contains("MainActivity"));
        String r = read("src/main/java/com/roy/bluetoothglassesrouter/BootReceiver.java");
        assertFalse(r.contains("MainActivity"));
    }
}
