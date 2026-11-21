package com.roy.bluetoothglassesrouter;

import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import android.widget.*;

/**
 * Compact settings for the selector: on external display attach
 * the app automatically opens the custom audio selector popup (live
 * native rows, user chooses, exact native mapping). All consent and device
 * selection is performed by the user, never accessibility automation.
 */
public final class MainActivity extends Activity {
    private SharedPreferences prefs;
    private LinearLayout column;
    private TextView status;
    private final Handler statusHandler=new Handler(Looper.getMainLooper());
    private final Runnable refreshStatus=new Runnable(){public void run(){updateStatus();statusHandler.postDelayed(this,500);}};
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs=getSharedPreferences(BluetoothGlassesRoutingService.PREFS,MODE_PRIVATE);
    }
    @Override public void onResume() { super.onResume(); render(); statusHandler.removeCallbacks(refreshStatus);statusHandler.post(refreshStatus); }
    @Override public void onPause(){statusHandler.removeCallbacks(refreshStatus);super.onPause();}
    private void render() {
        ScrollView scroll=new ScrollView(this);
        column=new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL); column.setPadding(32,32,32,32);
        scroll.addView(column); setContentView(scroll);
        text("Audio Router · "+versionName(),22);
        text("When an external display attaches, the audio selector popup opens on it automatically, listing only the real outputs enumerated live from the system media output dialog. Tap a row to route: the app maps it to the exact native picker row and the system performs the route change. After a tap the list auto-refreshes from the live system dialog and shows the confirmed current output - no manual Refresh needed. The app never routes itself, never toggles Bluetooth and never changes volume. The native volume panel and output picker briefly show on the phone screen only - never on the external monitor. The app window itself never opens from the flow - the background service drives everything. Confirm sound by ear.",14);

        Switch selector=new Switch(this); selector.setText(R.string.selector_switch);
        selector.setChecked(prefs.getBoolean("selector_auto_open",true));
        selector.setOnCheckedChangeListener((v,on)->prefs.edit().putBoolean("selector_auto_open",on).apply()); column.addView(selector);

        button("Open audio selector",()-> {
            BluetoothGlassesRoutingService service=BluetoothGlassesRoutingService.connected;
            if(service==null)toast("Enable accessibility first");else new Handler(Looper.getMainLooper()).postDelayed(()->{service.openCustomPopupNow();updateStatus();},300);
        });
        button("Test popup-on-attach in 5 seconds",()-> {
            BluetoothGlassesRoutingService service=BluetoothGlassesRoutingService.connected;
            if(service==null)toast("Enable accessibility first");else {service.scheduleCustomPopupTest();updateStatus();}
        });
        button("Accessibility settings (you enable service)",()->openSettings(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        button("STOP selector and automation",()-> {
            prefs.edit().putBoolean("selector_auto_open",false).apply();
            if(BluetoothGlassesRoutingService.connected!=null) BluetoothGlassesRoutingService.connected.stopRouting();
            render();
        });

        // Diagnostics: collapsed by default, actionable status only.
        final TextView diagToggle=text("▸ Diagnostics",16);
        status=text("",13); status.setVisibility(View.GONE);
        final Button refreshButton=button("Refresh service status",this::updateStatus);
        refreshButton.setVisibility(View.GONE);
        updateStatus();
        diagToggle.setOnClickListener(v->{
            boolean show=status.getVisibility()!=View.VISIBLE;
            status.setVisibility(show?View.VISIBLE:View.GONE);
            refreshButton.setVisibility(show?View.VISIBLE:View.GONE);
            diagToggle.setText(show?"▾ Diagnostics":"▸ Diagnostics");
            if(show)updateStatus();
        });
    }
    private String versionName(){
        try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}
        catch(Exception unavailable){return "";}
    }
    private TextView text(String value,int size) {
        TextView view=new TextView(this); view.setText(value); view.setTextSize(size); view.setPadding(0,12,0,12); column.addView(view); return view;
    }
    private Button button(String label,Runnable action) {
        Button b=new Button(this); b.setText(label); b.setAllCaps(false); b.setOnClickListener(v->action.run()); column.addView(b); return b;
    }
    private void updateStatus() {
        BluetoothGlassesRoutingService s=BluetoothGlassesRoutingService.connected;
        if(status!=null) status.setText(s==null ? "Accessibility service disconnected. No automation." : s.status());
    }
    private void toast(String value) { Toast.makeText(this,value,Toast.LENGTH_LONG).show(); }
    private void openSettings(Intent intent) {
        try { startActivity(intent); }
        catch(ActivityNotFoundException | SecurityException unavailable) { toast("Settings unavailable. Open Accessibility in system Settings manually."); }
    }
}
