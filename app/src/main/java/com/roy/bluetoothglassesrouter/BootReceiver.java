package com.roy.bluetoothglassesrouter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * Real registered manifest path for boot and package-update lifecycle signals.
 *
 * The Android-managed ENABLED AccessibilityService is the lifecycle owner: the
 * system binds it again after boot + first unlock and after process death, so
 * this receiver never starts activities or services - it only nudges the
 * connected service through the idempotent StartupGate (exactly one popup offer
 * per dock session, never before unlock) and records a diagnostic marker. When
 * the service is not bound (e.g. accessibility switched off - the user's
 * consent) it does nothing at all: automation stays off.
 *
 * The registered actions are the ones the platform really delivers to
 * manifest receivers on API 26+: ACTION_BOOT_COMPLETED and
 * ACTION_MY_PACKAGE_REPLACED (both exempt from the implicit-broadcast ban).
 * ACTION_USER_PRESENT is deliberately NOT here (not deliverable to manifest
 * receivers on API 26+); the service runtime-registers it for the unlock retry.
 *
 * Deliberately NOT directBootAware: sensitive prefs stay credential-encrypted
 * and nothing claims a start before user unlock (BOOT_COMPLETED reaches a
 * credential-encrypted app only after unlock).
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        // Diagnostic marker only (internal gate state, never user configuration).
        SharedPreferences prefs = context.getSharedPreferences(
                BluetoothGlassesRoutingService.PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(StartupGate.KEY_PREFIX + "last_signal",
                action + " @" + SystemClock.elapsedRealtime()).apply();
        BluetoothGlassesRoutingService service = BluetoothGlassesRoutingService.connected;
        if (service != null) service.onStartupSignal(action);
    }
}
