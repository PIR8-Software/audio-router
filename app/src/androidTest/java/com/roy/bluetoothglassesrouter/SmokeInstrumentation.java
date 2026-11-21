package com.roy.bluetoothglassesrouter;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.hardware.display.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Emulator-only smoke runner; compiled into the separate test APK, NEVER the delivered app. */
public final class SmokeInstrumentation extends Instrumentation {
    private final StringBuilder evidence=new StringBuilder();
    /** TEST-ONLY screenshot fixture mode (am instrument -e fixture fresh|stale); not a production path. */
    private String fixture=null;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if(arguments!=null) fixture=arguments.getString("fixture");
        start();
    }
    private void require(boolean condition,String message) {
        if(!condition) throw new AssertionError(message);
        evidence.append("PASS: ").append(message).append('\n');
    }
    private BluetoothGlassesRoutingService service() {
        AtomicReference<BluetoothGlassesRoutingService> ref=new AtomicReference<>();
        runOnMainSync(()->ref.set(BluetoothGlassesRoutingService.connected)); return ref.get();
    }
    // Do not call service() from the main thread: it uses runOnMainSync.
    private String mainStatus() {
        AtomicReference<String> ref=new AtomicReference<>();
        runOnMainSync(()->ref.set(BluetoothGlassesRoutingService.connected.status())); return ref.get();
    }
    @Override public void onStart() {
        Bundle result=new Bundle(); VirtualDisplay virtual=null;
        if(fixture!=null){ renderFixture(result); return; }
        try {
            Context c=getTargetContext();
            if (!Build.HARDWARE.equals("ranchu") && !Build.HARDWARE.equals("goldfish"))
                throw new AssertionError("Smoke runner refuses non-emulator hardware");
            // Instrumentation restarts the app process; force a fresh service bind AFTER that restart.
            // FLAG_DONT_SUPPRESS avoids UiAutomation suppressing the service under test.
            UiAutomation automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            try (ParcelFileDescriptor ignored=automation.executeShellCommand("settings put secure enabled_accessibility_services ''")) {
                SystemClock.sleep(300);
            }
            try (ParcelFileDescriptor ignored=automation.executeShellCommand("settings put secure enabled_accessibility_services com.roy.bluetoothglassesrouter/.BluetoothGlassesRoutingService")) {
                SystemClock.sleep(300);
            }
            try (ParcelFileDescriptor ignored=automation.executeShellCommand("settings put secure accessibility_enabled 1")) {
                SystemClock.sleep(300);
            }
            Activity activity=startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            require(activity!=null,"MainActivity launches on Android emulator");
            long deadline=SystemClock.elapsedRealtime()+15000;
            while(service()==null && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(100);
            require(service()!=null,"Accessibility service binds (emulator shell test setup only)");
            SharedPreferences prefs=c.getSharedPreferences(BluetoothGlassesRoutingService.PREFS,Context.MODE_PRIVATE);
            // Accepted product smoke: the selector opt-in registers the display watcher.
            runOnMainSync(()->prefs.edit().putBoolean("selector_auto_open",true).commit());
            waitForIdleSync();
            deadline=SystemClock.elapsedRealtime()+5000;
            while(!mainStatus().contains("Watching: true") && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(100);
            require(mainStatus().contains("Watching: true"),"Selector opt-in registers the display watcher");
            DisplayManager dm=c.getSystemService(DisplayManager.class);
            virtual=dm.createVirtualDisplay("router-smoke-display",800,600,160,null,0);
            require(virtual!=null,"Virtual display created for callback smoke");
            deadline=SystemClock.elapsedRealtime()+5000;
            while(!mainStatus().contains("Display attached") && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(100);
            require(mainStatus().contains("Display attached"),"Display addition callback observed; attach schedules the selector popup");
            // STOP before the 1.5 s settle so no popup/acquisition runs on the emulator.
            runOnMainSync(()->BluetoothGlassesRoutingService.connected.stopRouting());
            waitForIdleSync();
            require(!prefs.getBoolean("selector_auto_open",true),"STOP disables the selector opt-in and cancels the pending attach popup");
            require(mainStatus().contains("Watching: false"),"STOP unregisters the display watcher");
            PackageInfo info=c.getPackageManager().getPackageInfo(c.getPackageName(),PackageManager.GET_PERMISSIONS);
            List<String> permissions=info.requestedPermissions==null?Collections.emptyList():Arrays.asList(info.requestedPermissions);
            require(!permissions.contains("android.permission.INTERNET") && !permissions.contains("android.permission.RECORD_AUDIO")
                && !permissions.contains("android.permission.MODIFY_AUDIO_ROUTING"),"No network, microphone or privileged routing permission");
            require(!permissions.contains("android.permission.BLUETOOTH") && !permissions.contains("android.permission.BLUETOOTH_CONNECT"),
                "No Bluetooth permission (retired reassert/device-selection paths left none behind)");
            runOnMainSync(activity::finish);
            result.putString("stream",evidence+"\nSmoke checks passed. No routing/audibility test.\n");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable failure) {
            result.putString("stream",evidence+"\nFAIL: "+failure+"\n");
            finish(Activity.RESULT_CANCELED,result);
        } finally { if(virtual!=null) virtual.release(); }
    }

    /**
     * TEST-ONLY synthetic fixture render for UI screenshots: sets synthetic rows on
     * the popup statics and holds the popup up for an external screencap. Explicitly
     * not live enumeration - the provenance line says so on screen. Never reachable
     * from the production app path.
     */
    private void renderFixture(Bundle result) {
        Context c=getTargetContext();
        boolean stale="stale".equals(fixture);
        List<SpikeMapper.Row> rows=new ArrayList<>();
        rows.add(new SpikeMapper.Row("External Device",true,false,false));
        rows.add(new SpikeMapper.Row("Speakers & Displays",false,true,false));
        rows.add(new SpikeMapper.Row("This phone",true,false,false));
        rows.add(new SpikeMapper.Row("Current Output",false,false,true));
        if(stale) rows.add(new SpikeMapper.Row("Busy output",false,false,false));
        String prov="TEST-ONLY synthetic fixture rows ("+fixture+"); NOT live enumeration - not real outputs.";
        runOnMainSync(()->SelectorPopupActivity.launch(c,rows,prov,stale,ChooserLifecycle.NO_SESSION));
        SystemClock.sleep(25000); // hold the popup for an external screencap
        result.putString("stream","FIXTURE ("+fixture+") rendered (synthetic rows, test only).\n");
        finish(Activity.RESULT_OK,result);
    }
}
