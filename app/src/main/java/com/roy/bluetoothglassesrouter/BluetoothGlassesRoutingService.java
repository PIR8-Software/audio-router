package com.roy.bluetoothglassesrouter;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.Display;
import android.view.accessibility.*;
import java.util.*;

/**
 * Accepted product: external display attach -> automatic idle native picker
 * acquisition -> live real output rows -> approved custom external popup ->
 * user selection mapped to the EXACT native picker row (the system performs any
 * route change; the app never routes, never toggles Bluetooth, never changes
 * volume). Retired paths (chosen-device Settings reassert, Remember/Restore
 * pre-attach evidence, native-panel-only auto-opener, Quick Settings recovery)
 * are removed; see the cleanup report. No privileged routing or private UI
 * logging.
 */
public final class BluetoothGlassesRoutingService extends AccessibilityService {
 static BluetoothGlassesRoutingService connected;
 static final String PREFS="router_v2", P="com.android.systemui";
 private final Handler handler=new Handler(Looper.getMainLooper());
 private SharedPreferences prefs; private DisplayManager displays;
 private boolean watching; private DockMemory dock;
 private int displayCallbacks;
 private final SelectorOpener selectorOpener=new SelectorOpener();
 private final Runnable openCustomPopupRun=()->openCustomPopup();
 private final Runnable selectorStep=this::selectorAttempt;
// Auto-acquisition of the idle-capable native picker (source-close lifecycle):
// when the picker is closed, open it via the bounded opener before enumerating.
// The acquisition orchestration (pending NAMED action, deterministic supersession
// with a retained visible outcome, generation-guarded bounded poll loop) lives in
// AcquireSequencer - a production-used test seam; this service supplies the
// Handler scheduler and the side-effecting Host adapter below. A superseded
// continuation is NEVER executed and its outcome stays visible until the user's
// next intentional action (F2: no silently lost pending action).
/** The documented system picker launch (the same SystemUI action the Settings volume-panel
 *  row sends; MediaOutputDialogReceiver). Opens the real media
 *  output dialog with real rows at idle and with display output active; the passed
 *  package_name may be our own (no media session needed). */
private static final String PICKER_OPENER_ACTION="com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG";
private static final String PICKER_OPENER_PKG="com.android.systemui";
private static final String PICKER_OPENER_EXTRA="package_name";
private boolean pickerFallbackSent;
private final AcquireSequencer acquireSequencer=new AcquireSequencer(
 new AcquireSequencer.Scheduler(){
  @Override public void postDelayed(Runnable r,long delayMs){handler.postDelayed(r,delayMs);}
  @Override public void removeCallbacks(Runnable r){handler.removeCallbacks(r);}
 },
 new AcquireSequencer.Host(){
  @Override public long now(){return SystemClock.elapsedRealtime();}
  @Override public boolean pickerOpen(){Integer d=chooserDisplay();if(d!=null){pickerLastDisplay=d;return true;}return false;}
  @Override public boolean verifiedRowPresent(){return panelRowPresent();}
  @Override public boolean launchVolumePanel(){return BluetoothGlassesRoutingService.this.launchVolumePanel();}
  @Override public void openPickerDirect(){BluetoothGlassesRoutingService.this.openPickerDirect();}
  @Override public void setFallbackSent(boolean sent){pickerFallbackSent=sent;}
  @Override public void beginSelector(long now){selectorOpener.begin(now);}
  @Override public void scheduleSelectorStep(){BluetoothGlassesRoutingService.this.scheduleSelectorStep();}
  @Override public void cancelSelectorStep(){handler.removeCallbacks(selectorStep);}
  @Override public void reportStatus(String message){status=message;}
  @Override public void reportRetained(String message){SelectorPopupActivity.retainedOutcome=message;}
 });
// ---- Post-selection bounded route confirmation + AUTO-REFRESH : after an exact row click, poll
// the live native source (bounded) until it reports the chosen row as current, then
// refresh the popup rows + the actual active-route indicator FROM THE LIVE SOURCE.
// Never optimistic: the current marker comes only from the source's own state; a
// failed/unconfirmed selection is reported honestly (confirm sound by ear).
private static final long CONFIRM_POLL_MS=1000;
private static final long CONFIRM_BUDGET_MS=5000;
private final Runnable confirmPoll=this::confirmPollStep;
private String confirmLabel;
private long confirmDeadline=Long.MIN_VALUE;
 // ---- Session-owned native picker cleanup : our popup's Done requests the exact
 // Done action of the native media output picker ONLY when that picker is verified to
 // belong to the current acquisition/session (the live enumeration our popup was launched
 // from). Fail closed everywhere: absent picker, wrong/foreign window, stale session,
 // repeated Done -> our popup closes and native UI is left untouched (never a blind
 // back-key dismiss, never an arbitrary window dismiss, never the picker's stop-casting
 // control). The session token travels via the launch Intent, never static cross-process state.
 /** One-shot Done consumption and the native close result. Written only by
  *  {@link ServiceEntry#closeOwnedPicker}; dismissal bookkeeping must not overwrite
  *  {@code lastOutcome}. */
 private final ServiceEntry.DoneMemory doneMemory=new ServiceEntry.DoneMemory();
 private boolean autoFlowSelect,confirmAutomatic;
 // ---- Preferred/Fallback roles + optional five-second auto-select ----
 // Roles persist by exact native row title (the only address the native chooser
 // gives); a saved role absent from the live list is preserved (it can return)
 // and counts as unavailable at expiry. The countdown is one-shot per POPUP
 // session (popupSessionGen: attach/explicit open - never route auto-refresh),
 // arms only after usable live rows are ready, and expires on the monotonic
 // clock via one bounded handler callback. Expiry selects preferred else
 // fallback ONLY over a fresh live list (fail closed to leave the picker open).
 static final String KEY_ROLE_PREF="role_preferred",KEY_ROLE_FB="role_fallback",KEY_AUTO="auto_select_enabled";
 private final AutoSelectPolicy.Countdown autoCountdown=new AutoSelectPolicy.Countdown();
 private long popupSessionGen=ChooserLifecycle.NO_SESSION;
 private boolean autoRetryDone;
 private final Runnable autoTick=this::autoTickStep;
 private int selectorDisplay=-1;
 // ACTUAL displays observed at runtime (window identity, never assumed): the native
 // volume panel is launched INTERNAL-ONLY (DEFAULT_DISPLAY target) and the picker is
 // OS-placed; both displays are recorded as observed window identity.
 private int panelLastDisplay=-1; private int pickerLastDisplay=-1;
 private String status="Stopped";
 private final SharedPreferences.OnSharedPreferenceChangeListener preferences=(p,key)->{
  // The chooser snapshot cache and the startup-gate state are written by the
  // production flows themselves; they are not configuration changes and must
  // not cancel anything (StartupGate.internalStateKey is the shared contract).
  // Role/auto-select writes come from the popup UI mid-session and are handled
  // explicitly (a role edit cancels only the countdown, documented) - they must
  // never cancel the popup/acquisition flow through this listener.
  if(StartupGate.internalStateKey(key)||key.startsWith("role_")||KEY_AUTO.equals(key))return;
  cancel("Configuration changed; popup/acquisition cancelled");
  handler.removeCallbacks(openCustomPopupRun);
  updateWatchers();
 };
 // ---- Startup gate: one popup offer per dock session, idempotent
 // across service rebind/kill recovery, boot/update signals and unlock. State is
 // persisted in gate_* prefs so process death never duplicates or loses an
 // offer. Decisions are pure (StartupGate); this class only observes facts and
 // schedules the popup path.
 private final StartupGate.State gate=new StartupGate.State();
 private boolean gateLoaded;
 private void loadGate(){
  if(gateLoaded||prefs==null)return;
  gate.hasSession=prefs.getBoolean(StartupGate.KEY_HAS_SESSION,false);
  gate.sessionBoot=prefs.getLong(StartupGate.KEY_BOOT,Long.MIN_VALUE);
  gate.dockedNow=prefs.getBoolean(StartupGate.KEY_DOCKED,false);
  gate.satisfied=prefs.getBoolean(StartupGate.KEY_SATISFIED,false);
  gate.pending=prefs.getBoolean(StartupGate.KEY_PENDING,false);
  gate.detachAt=prefs.getLong(StartupGate.KEY_DETACH_AT,Long.MIN_VALUE);
  gate.popupOpenAtDetach=prefs.getBoolean(StartupGate.KEY_POPUP_OPEN_AT_DETACH,false);
  gateLoaded=true;
 }
 private void persistGate(){
  if(prefs==null)return;
  prefs.edit().putBoolean(StartupGate.KEY_HAS_SESSION,gate.hasSession)
   .putLong(StartupGate.KEY_BOOT,gate.sessionBoot)
   .putBoolean(StartupGate.KEY_DOCKED,gate.dockedNow)
   .putBoolean(StartupGate.KEY_SATISFIED,gate.satisfied)
   .putBoolean(StartupGate.KEY_PENDING,gate.pending)
   .putLong(StartupGate.KEY_DETACH_AT,gate.detachAt)
   .putBoolean(StartupGate.KEY_POPUP_OPEN_AT_DETACH,gate.popupOpenAtDetach)
   .apply();
 }
 /** Current boot count (Settings.Global.BOOT_COUNT); rotates only on real reboot. */
 private long bootCount(){return android.provider.Settings.Global.getInt(getContentResolver(),android.provider.Settings.Global.BOOT_COUNT,0);}
 /** Run one startup-gate decision with live facts and persist the result. */
 private StartupGate.Decision gateDecide(StartupGate.Trigger trigger){
  loadGate();
  // DETACH needs "was the popup up when the display died": true while open, or
  // killed by its display going away (never when the user closed it) - that
  // distinction drives reconnect restore vs never-reopen.
  boolean up=trigger==StartupGate.Trigger.DETACH
   ? SelectorPopupActivity.isOpen()||!SelectorPopupActivity.userClosedLastPopup()
   : SelectorPopupActivity.isOpen();
  StartupGate.Decision d=StartupGate.decide(gate,trigger,bootCount(),!externalDisplays().isEmpty(),
   selectorMode(),unlocked(),up,SystemClock.elapsedRealtime());
  persistGate();
  return d;
 }
 /**
  * Idempotent startup entry: service (re)bind, boot/update broadcast (via
  * BootReceiver) and user unlock (runtime USER_PRESENT receiver) all land here.
  * OFFER schedules the popup path exactly once per dock session; every
  * other decision is a no-op. Never launches the app Activity.
  */
 void onStartupSignal(String source){
  if(prefs==null)return;
  StartupGate.Decision d=gateDecide(StartupGate.Trigger.START);
  if(d==StartupGate.Decision.OFFER){
   handler.removeCallbacks(openCustomPopupRun);
   handler.postDelayed(openCustomPopupRun,DockMemory.SETTLE);
   status="Startup signal ("+source+"): dock present; audio selector popup scheduled after settle (one per dock session).";
  }else{
   status="Startup signal ("+source+"): "+gateSkipReason(d);
  }
 }
 private static String gateSkipReason(StartupGate.Decision d){
  switch(d){
   case SKIP_NOT_DOCKED:return "no external display; nothing to do.";
   case SKIP_NO_SELECTOR:return "selector opt-in is off (no popup, no action).";
   case SKIP_SATISFIED:return "popup already offered for this dock session (no duplicate).";
   case SKIP_LOCKED:return "phone locked; the popup is owed and will open after unlock (never before).";
   case SKIP_RECONNECT:return "dock reconnected within the dedup window; a closed popup is not reopened.";
   case SKIP_POPUP_OPEN:return "selector popup already on screen (no duplicate).";
   default:return "scheduled.";
  }
 }
 /** Mark the gate's offer consumed when the popup actually launches. */
 private void gateMarkShown(){loadGate();StartupGate.markShown(gate);persistGate();}
 // Runtime-only USER_PRESENT receiver: ACTION_USER_PRESENT is NOT deliverable to
 // manifest receivers on API 26+ (implicit-broadcast ban, not exempt), so the
 // lock-deferred attach offer is retried from a context-registered receiver.
 private final android.content.BroadcastReceiver unlockReceiver=new android.content.BroadcastReceiver(){
  @Override public void onReceive(android.content.Context c,android.content.Intent i){
   if(i!=null&&Intent.ACTION_USER_PRESENT.equals(i.getAction()))onStartupSignal("user-unlock");
  }
 };
 private boolean unlockReceiverRegistered;
 private void registerUnlockReceiver(){
  if(unlockReceiverRegistered||prefs==null)return;
  IntentFilter f=new IntentFilter(Intent.ACTION_USER_PRESENT);
  if(Build.VERSION.SDK_INT>=33)registerReceiver(unlockReceiver,f,Context.RECEIVER_NOT_EXPORTED);
  else registerReceiver(unlockReceiver,f);
  unlockReceiverRegistered=true;
 }
 private final DisplayManager.DisplayListener displayListener=new DisplayManager.DisplayListener(){
  public void onDisplayAdded(int id){displayCallbacks++;detect();} public void onDisplayChanged(int id){} public void onDisplayRemoved(int id){displayCallbacks++;detect();}
 };
 @Override protected void onServiceConnected(){
  connected=this;prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
  displays=getSystemService(DisplayManager.class);
  dock=new DockMemory(externalDisplays());
  prefs.registerOnSharedPreferenceChangeListener(preferences);updateWatchers();
  status="Connected. On display attach the audio selector popup opens automatically (switch in the app).";
  registerUnlockReceiver();
  onStartupSignal("service bind"); // idempotent: bind, boot/update nudge and unlock all funnel here
 }
 String status(){
  return status+"\nSelector popup on display attach: "+prefs.getBoolean("selector_auto_open",true)
      +". Watching: "+watching+". App-visible external displays: "+externalDisplays().size()+". Display callbacks since service start: "+displayCallbacks+"."
      +"\n"+(dock==null?"Display memory not initialized":dock.diagnostics(SystemClock.elapsedRealtime()))
      +"\nLast popup target display: "+(selectorDisplay<0?"none":selectorDisplay)
      +". Last panel display (actual): "+(panelLastDisplay<0?"none":panelLastDisplay)+". Last picker display (actual): "+(pickerLastDisplay<0?"none":pickerLastDisplay)+".";
 }
 @Override protected void dump(java.io.FileDescriptor fd,java.io.PrintWriter writer,String[] args){writer.println(prefs==null?"Service not initialized":status());}
 private boolean unlocked(){return !getSystemService(KeyguardManager.class).isKeyguardLocked()&&getSystemService(PowerManager.class).isInteractive();}
 void stopRouting(){
  cancel("Stopped");handler.removeCallbacks(openCustomPopupRun);handler.removeCallbacks(selectorStep);acquireSequencer.cancel();selectorOpener.end();
  if(prefs!=null)prefs.edit().putBoolean("selector_auto_open",false).apply();
 }
 private void cancel(String why){
  handler.removeCallbacks(selectorStep);handler.removeCallbacks(confirmPoll);confirmLabel=null;acquireSequencer.cancel();selectorOpener.end();confirmAutomatic=false;status=why;
  autoCountdown.cancel();handler.removeCallbacks(autoTick);autoRetryDone=false;
 }
 /**
  * No event-driven cancellation remains: expected launch churn never cancels the
  * popup/acquisition flow. The dock attach / HID re-enumeration
  * synthesizes interaction-class events with zero user contact, and our own
  * popup/panel/picker launches generate window churn, so accessibility events
  * are deliberately not used to abort anything; interaction never fights the
  * user. Safety stays on exact identity gates before every click
  * (SpikeMapper/ChooserLifecycle/SelectorOpener).
  */
 @Override public void onAccessibilityEvent(AccessibilityEvent e){/* intentionally empty */}
 private void updateWatchers(){
  boolean wanted=prefs.getBoolean("selector_auto_open",true);if(wanted==watching)return;
  if(wanted){
   dock=new DockMemory(externalDisplays());
   displays.registerDisplayListener(displayListener,handler);
   watching=true;
  }else{
   displays.unregisterDisplayListener(displayListener);
   watching=false;
  }
 }
 private Set<Integer> externalDisplays(){Set<Integer> ids=new HashSet<>();for(Display d:displays.getDisplays())if(d.getDisplayId()!=Display.DEFAULT_DISPLAY)ids.add(d.getDisplayId());return ids;}
 private void detect(){
  if(!watching)return;long now=SystemClock.elapsedRealtime();
  if(dock.displays(externalDisplays(),now)){
   cancel("Display attached.");
   handler.removeCallbacks(openCustomPopupRun);handler.removeCallbacks(selectorStep);acquireSequencer.cancel();
   selectorOpener.end();
   StartupGate.Decision d=gateDecide(StartupGate.Trigger.ATTACH);
   if(d==StartupGate.Decision.OFFER){
    // Selector mode: our custom popup is the attach UX (the native panel-only opener is
    // not accepted). It opens on the external display after the settle.
    handler.postDelayed(openCustomPopupRun,DockMemory.SETTLE);
    status="Display attached; custom audio selector popup scheduled after settle (our popup opens, not the native panel).";
   }else{
    status="Display attached; "+gateSkipReason(d);
   }
  }else if(!dock.docked()){
   handler.removeCallbacks(openCustomPopupRun);handler.removeCallbacks(selectorStep);acquireSequencer.cancel();
   selectorOpener.end();
   gateDecide(StartupGate.Trigger.DETACH); // remember detach for reconnect dedup/restore
   cancel("Display detached.");
  }
 }
 // ---- Custom external selector popup: live rows from the idle native picker ----
 private boolean selectorMode(){return prefs!=null&&prefs.getBoolean("selector_auto_open",true);}
 void scheduleCustomPopupTest(){
  if(prefs==null){status="Service not initialized.";return;}
  handler.removeCallbacks(openCustomPopupRun);handler.removeCallbacks(selectorStep);acquireSequencer.cancel();
  selectorOpener.end();
  handler.postDelayed(openCustomPopupRun,5000);
  status="Custom popup-on-attach test scheduled in 5 s (our popup opens on the external display).";
 }
 /** Custom external popup: enumerate live rows, show our chooser on the external display.
  *  Source-close lifecycle: when the idle native picker is CLOSED, auto-acquire it (bounded internal
  *  automation: volume panel -> one identity-verified row tap) BEFORE enumerating, so the popup is
  *  populated without any manual phone step. If acquisition is impossible, show the last real
  *  snapshot CLEARLY MARKED STALE (never hidden, never presented as validated). */
 void openCustomPopupNow(){openCustomPopup();}
 private void openCustomPopup(){
  long now=SystemClock.elapsedRealtime();
  if(prefs==null){status="Service not initialized.";return;}
  if(!unlocked()){status="Locked; audio selector popup not opened.";return;}
  // Fresh popup session: at most ONE auto-select countdown may start for it (and
  // only after usable live rows are ready) - route auto-refreshes never rearm it.
  popupSessionGen=popupSessionGen==Long.MAX_VALUE?1:popupSessionGen+1;
  ServiceEntry.beginPopupSession(doneMemory);
  autoCountdown.newSession(popupSessionGen);autoRetryDone=false;
  if(autoSelectEnabled())autoCountdown.enable(popupSessionGen,false,now);
  ChooserLifecycle.Plan plan=ChooserLifecycle.plan(chooserOpen(),acquirePossible(now));
  if(plan==ChooserLifecycle.Plan.ACQUIRE_THEN_ENUMERATE){
   // Source closed: open the idle-capable native picker first, then populate from it.
   status="Native picker closed; opening it automatically (volume panel -> verified output row) before enumerating. No manual phone step needed.";
   acquireThen("popup open",this::popupFromLive);
   return;
  }
  popupFromLive();
 }
 /** Populate the external popup from the live native picker, or fall back to the marked stale snapshot. */
 private void popupFromLive(){
  long now=SystemClock.elapsedRealtime();
  gateMarkShown(); // the popup is launching now: this session's offer is consumed exactly once
  List<SpikeMapper.Row> rows=spikeEnumerate();
  if(!rows.isEmpty()){
   saveSnapshot(rows,now);
   // Done ownership is the POPUP session (attach/explicit open), stable across route
   // auto-refresh and picker reacquire - never a per-enumeration generation.
   String prov="Visible outputs enumerated live from the system media output dialog at "+now+"; visible rows="+rows.size()+"; "+spikeDiag()+"; panel display (actual): "+(panelLastDisplay<0?"none":panelLastDisplay)+", picker display (actual): "+(pickerLastDisplay<0?"none":pickerLastDisplay)+" (visible subset - the system chooser may scroll to hide more; never a claim of ALL outputs; the native volume panel opens on the phone display only and the picker at its observed display above; our popup's Done dismisses only the verified session-owned picker's exact Done control; the app never routes)";
   SelectorPopupActivity.launch(this,rows,prov,popupSessionGen);
   selectorDisplay=SelectorOpener.pickDisplay(externalDisplays());
   SelectorPopupActivity.lastResult="Popup loaded: "+rows.size()+" live row(s) on display "+selectorDisplay+".";
   // Usable live rows are ready NOW: start at most one pending auto-select countdown
   // for this popup session (pending explicit enable / session opt-in only - a route
   // auto-refresh after a user choice or selection never rearms anything).
   if(autoSelectEnabled()&&autoCountdown.onRowsReady(popupSessionGen,SystemClock.elapsedRealtime()))scheduleAutoTick();
   status="Custom audio selector popup opening on display "+selectorDisplay+"; tap a Select row to route. The app never routes.";
   return;
  }
  // No live dialog: show the persistent snapshot with a clear stale marker (rows are real history,
  // not validated destinations; selection revalidates/reacquires before any click).
  List<SpikeMapper.Row> snap=loadSnapshot();
  String prov=snap.isEmpty()
   ? "Live enumeration empty (native picker closed or no readable rows; diag: "+spikeDiag()+"). Tap Refresh outputs to reopen the idle native picker and load real outputs. No fake choices shown."
   : "STALE snapshot (live enumeration empty: native picker closed or unreadable; rows below are the last REAL enumeration, NOT validated now): visible rows="+snap.size()+". Tap Refresh to reacquire the idle native picker and reload live rows; a Select tap on a stale row revalidates live before any click.";
  SelectorPopupActivity.launch(this,snap,prov,true,popupSessionGen);
  selectorDisplay=SelectorOpener.pickDisplay(externalDisplays());
  SelectorPopupActivity.lastResult=snap.isEmpty()
   ? "Popup loaded: no rows (see provenance); Refresh reopens the native picker."
   : "Popup loaded: STALE snapshot ("+snap.size()+" row(s), not validated).";
  status="Custom audio selector popup opening on display "+selectorDisplay+"; showing "+(snap.isEmpty()?"no choices":"STALE snapshot rows (not validated)")+" - the app never routes.";
 }
 /** Refresh (production path): {@link ServiceEntry#refreshOutputs} owns the entry
  *  decisions (pending ownership, chooser-open, cooldown). This method only supplies
  *  the service adapter. */
 String refreshOutputs(){
  return ServiceEntry.refreshOutputs(acquireSequencer, new ServiceEntry.RefreshHost(){
   @Override public boolean initialized(){return prefs!=null;}
   @Override public boolean unlocked(){return BluetoothGlassesRoutingService.this.unlocked();}
   @Override public boolean chooserOpen(){return BluetoothGlassesRoutingService.this.chooserOpen();}
   @Override public long now(){return SystemClock.elapsedRealtime();}
   @Override public void popupFromLive(){BluetoothGlassesRoutingService.this.popupFromLive();}
   @Override public void reportStatus(String message){status=message;}
  });
 }
// ---- OUR popup's Done: bounded session-owned native picker cleanup ----
/** The ONE verified picker dialog's exact Done control plus picker presence. */
private static final class PickerDone{
 static final PickerDone NONE=new PickerDone(false,null);
 final boolean pickerPresent;final AccessibilityNodeInfo done;
 PickerDone(boolean pickerPresent,AccessibilityNodeInfo done){this.pickerPresent=pickerPresent;this.done=done;}
}
/** One-shot request from OUR popup's Done : click the verified session-owned
 *  native picker's EXACT Done control when - and only when - the pure plan allows it,
 *  then the popup closes itself. Bounded single find+click (no polling); fail closed to
 *  a normal popup close in every other case (absent picker, wrong/foreign window, stale
 *  session, repeat tap); never a blind back-key dismiss, never an arbitrary window
 *  dismiss, never the picker's stop-casting control. No route/volume change. */
String closeOwnedPicker(long requestSession){
 return ServiceEntry.closeOwnedPicker(acquireSequencer,requestSession,doneMemory,popupSessionGen,prefs!=null,this::lookupVerifiedPicker,message->status=message);
}
/** Adapter only: identity discovery stays in {@link #verifiedPickerDone}. The seam owns the click predicate and the one-shot write. */
private ServiceEntry.VerifiedPicker lookupVerifiedPicker(){
 PickerDone found=verifiedPickerDone();
 if(found.done==null)return new ServiceEntry.VerifiedPicker(found.pickerPresent,null);
 AccessibilityNodeInfo node=found.done;
 return new ServiceEntry.VerifiedPicker(true,new ServiceEntry.DoneControl(){
  @Override public boolean refresh(){return node.refresh();}
  @Override public boolean visibleToUser(){return node.isVisibleToUser();}
  @Override public boolean enabled(){return node.isEnabled();}
  @Override public boolean clickable(){return node.isClickable();}
  @Override public boolean performClick(){return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);}
  @Override public void recycle(){node.recycle();}
 });
}
/** OUR popup ended with a user finish (Done/back): cancel any pending acquisition or async
 *  refresh continuation so the dismissed popup never reopens itself and the native helper
 *  (panel/picker acquisition) is never restarted for it. Display-teardown destroys do NOT
 *  cancel (reconnect restore semantics stay). */
void onPopupUserClosed(){
 // Production dismissal entry: ServiceEntry cancels acquisition ownership;
 // cancel() does the remaining handler/countdown bookkeeping.
 ServiceEntry.onPopupUserClosed(acquireSequencer, () ->
  cancel("Popup closed by user; pending acquisition/refresh cancelled (no reopen, no native helper restart)."));
}
// ---- Preferred/Fallback roles + optional five-second auto-select  ----
/** Persisted roles (exact native row titles; empty = unset). An absent device keeps its
 *  saved role (it can return later) - only an explicit user edit removes it. */
AutoSelectPolicy.Roles roles(){
 if(prefs==null)return new AutoSelectPolicy.Roles(null,null);
 return new AutoSelectPolicy.Roles(prefs.getString(KEY_ROLE_PREF,""),prefs.getString(KEY_ROLE_FB,""));
}
/** Auto-select opt-in is persisted and DEFAULTS TO OFF until the user explicitly enables it. */
boolean autoSelectEnabled(){return prefs!=null&&prefs.getBoolean(KEY_AUTO,false);}
private void persistRoles(AutoSelectPolicy.Roles r){
 prefs.edit().putString(KEY_ROLE_PREF,r.preferred==null?"":r.preferred)
  .putString(KEY_ROLE_FB,r.fallback==null?"":r.fallback).apply();
}
/**
 * A row's role control tap: assign the role when unset, clear it when this row
 * already holds it; assigning a row that holds the OTHER role clears that role
 * and explains (roles must be distinct). A role edit while the countdown is
 * active cancels it (documented safest behavior: restart only via the
 * Auto-select off/on toggle - never an automatic restart).
 */
String toggleRole(String title,boolean asPreferred){
 acquireSequencer.beginIntentionalAction(); // intentional user action: clear retained outcome
 if(prefs==null)return "Service not initialized.";
 AutoSelectPolicy.RoleEdit e=AutoSelectPolicy.toggle(roles(),title,asPreferred);
 if(!e.changed)return e.message;
 persistRoles(e.roles);
 String msg=e.message;
 if(autoCountdown.cancel()){handler.removeCallbacks(autoTick);autoRetryDone=false;
  autoFlowSelect=false;confirmAutomatic=false;
  msg+=" Auto-select countdown cancelled (roles changed); toggle Auto-select off/on to restart.";}
 return msg;
}
/**
 * Auto-select ON/OFF (persisted opt-in). OFF cancels the countdown and any
 * pending automatic action immediately. ON arms exactly one countdown for the
 * current popup session once usable live rows are ready - never while native
 * acquisition is still running, never again after the automatic selection ran.
 */
String setAutoSelect(boolean enable){
 acquireSequencer.beginIntentionalAction(); // intentional user action: clear retained outcome
 if(prefs==null)return "Service not initialized.";
 prefs.edit().putBoolean(KEY_AUTO,enable).apply();
 if(!enable){
  boolean was=autoCountdown.cancel();handler.removeCallbacks(autoTick);autoRetryDone=false;
  autoFlowSelect=false;confirmAutomatic=false;
  return "Auto-select OFF; "+(was?"countdown cancelled immediately.":"no countdown was running.");
 }
 if(autoCountdown.firedThisSession(popupSessionGen))
  return "Auto-select already ran for this popup session; nothing rearms here (no repeated attempts). The setting stays ON for the next popup session.";
 boolean ready=SelectorPopupActivity.isOpen()&&SelectorPopupActivity.hasLiveRows();
 boolean armedNow=autoCountdown.enable(popupSessionGen,ready,SystemClock.elapsedRealtime());
 if(armedNow){scheduleAutoTick();return "Auto-select ON; 5s countdown started. "+autoCountdownText();}
 if(autoCountdown.hasPending())
  return "Auto-select ON; the 5s countdown starts once usable live outputs are loaded (not while the native picker is still being acquired).";
 return "Auto-select ON; countdown already running.";
}
/** Cancel the countdown and any pending automatic action (manual choice / Done / OFF / close). */
void cancelAutoSelect(String why){
 boolean was=autoCountdown.cancel();handler.removeCallbacks(autoTick);autoRetryDone=false;
 autoFlowSelect=false;confirmAutomatic=false; // pending automatic close is cancelled too
 if(was){status=why;SelectorPopupActivity.lastResult=why;}
}
/** True while the countdown runs (the popup shows the visible 5..0 countdown). */
boolean autoArmed(){return autoCountdown.isArmed();}
/** Visible countdown line ("Auto-select in Ns: target (role)"); "" when not counting. */
String autoCountdownText(){
 if(!autoCountdown.isArmed())return "";
 AutoSelectPolicy.Roles r=roles();
 boolean isPref=r.preferred!=null;
 return SelectorPopupModel.countdownLine(autoCountdown.remaining(SystemClock.elapsedRealtime()),
  isPref?r.preferred:r.fallback,isPref);
}
private void scheduleAutoTick(){
 handler.removeCallbacks(autoTick);
 long rem=autoCountdown.remaining(SystemClock.elapsedRealtime());
 handler.postDelayed(autoTick,Math.max(50,Math.min(200,rem)));
}
private void autoTickStep(){
 long now=SystemClock.elapsedRealtime();
 if(!autoCountdown.isArmed()){handler.removeCallbacks(autoTick);return;}
 if(!SelectorPopupActivity.isOpen()){
  // The popup is gone (user close/display teardown): never auto-route without
  // the user's UI on screen. A later session arms its own fresh countdown.
  autoCountdown.cancel();handler.removeCallbacks(autoTick);return;
 }
 if(!autoCountdown.expireOnce(now)){scheduleAutoTick();return;}
 handler.removeCallbacks(autoTick);
 autoSelectExpiry();
}
/**
 * Expiry: revalidate against a FRESH live list, then select preferred else
 * fallback through the bounded production click path (identity mapping +
 * confirmation + auto refresh - no optimistic success). Fail closed: an
 * unreadable list gets ONE bounded reacquire attempt; anything else leaves the
 * picker open with a clear reason - never a random output.
 */
private void autoSelectExpiry(){
 List<SpikeRowNode> found=spikeRows();
 List<SpikeMapper.Row> rows=new java.util.ArrayList<>();
 for(SpikeRowNode n:found)rows.add(n.row);
 if(!rows.isEmpty()){applyAutoExpiry(found,rows);return;}
 for(SpikeRowNode n:found)if(n.clickNode!=null)n.clickNode.recycle();
 long now=SystemClock.elapsedRealtime();
 if(ServiceEntry.tryArmExpiryRevalidation(acquireSequencer, autoRetryDone,
   SelectorPopupActivity.isOpen(), now, this::autoSelectAfterAcquire,
   msg -> {status=msg;SelectorPopupActivity.lastResult=msg;})){
  autoRetryDone=true;
  return;
 }
 String msg="Auto-select: no live outputs readable (acquire failed or list stale); nothing selected (popup stays open).";
 status=msg;SelectorPopupActivity.lastResult=msg;
}
private void autoSelectAfterAcquire(){
 List<SpikeRowNode> found=spikeRows();
 List<SpikeMapper.Row> rows=new java.util.ArrayList<>();
 for(SpikeRowNode n:found)rows.add(n.row);
 if(!ChooserLifecycle.continuationAllowed(SelectorPopupActivity.isOpen(),SelectorPopupActivity.userClosedLastPopup())){
  for(SpikeRowNode n:found)if(n.clickNode!=null)n.clickNode.recycle();
  return; // dismissed while revalidating: no reopen, no automatic action behind the user
 }
 if(rows.isEmpty()){
  for(SpikeRowNode n:found)if(n.clickNode!=null)n.clickNode.recycle();
  String msg="Auto-select: revalidation found no readable outputs; nothing selected (popup stays open).";
  status=msg;SelectorPopupActivity.lastResult=msg;return;
 }
 applyAutoExpiry(found,rows);
}
private void applyAutoExpiry(List<SpikeRowNode> found,List<SpikeMapper.Row> rows){
 AutoSelectPolicy.Expiry ex=AutoSelectPolicy.expiry(roles(),rows);
 if(ex.action==AutoSelectPolicy.ExpiryAction.SELECT){
  autoFlowSelect=true;
  String outcome=spikeSelectOn(found,rows,ex.targetTitle,titles(rows));
  status="Auto-select (5s expired): "+outcome;
  SelectorPopupActivity.lastResult=status;
  return;
 }
 for(SpikeRowNode n:found)if(n.clickNode!=null)n.clickNode.recycle();
 // ALREADY_CURRENT is a CONFIRMED success (the fresh live list at expiry reports the
 // target as the current output): close OUR popup and the session-owned picker after
 // the 5s without any needless route click. Failures/ambiguous/unavailable never close.
 if(ex.action==AutoSelectPolicy.ExpiryAction.ALREADY_CURRENT
   &&ChooserLifecycle.autoSuccessClose(true,true,SelectorPopupActivity.isOpen(),SelectorPopupActivity.userClosedLastPopup())){
  autoSuccessClose(ex.message+" Both popups closed; the app never routes.");
  return;
 }
 status=ex.message;
 SelectorPopupActivity.lastResult=ex.message;
}
/** The exact Done control inside the ONE verified picker dialog, or null. Scope is strictly
 *  the verified dialog subtree (the same SystemUI media_output_dialog window identity
 *  enumeration uses): Done-looking controls in ANY other window - our own popup's Done,
 *  foreign dialogs - are structurally unreachable (wrongwindow refusal). Identity is fail
 *  closed: exact @id/done + Button class + exact "Done" text + visible/enabled/clickable +
 *  exactly one match; anything else -> none. The sibling @id/stop (stop casting) can never
 *  match this identity. */
private PickerDone verifiedPickerDone(){
 List<AccessibilityNodeInfo> dialogs=new ArrayList<>();
 for(AccessibilityWindowInfo w:getWindows()){
  AccessibilityNodeInfo r=w.getRoot();if(r==null)continue;
  try{
   if(!sys(r))continue;
   AccessibilityNodeInfo d=one(r,SpikeMapper.DIALOG_ID);
   if(d!=null)dialogs.add(d); // exactly ONE verified picker window below; ambiguity refuses
  }finally{r.recycle();}
 }
 if(dialogs.size()!=1){for(AccessibilityNodeInfo d:dialogs)d.recycle();return PickerDone.NONE;}
 AccessibilityNodeInfo dialog=dialogs.get(0);
 try{
  List<AccessibilityNodeInfo> cands=findAll(dialog,SpikeMapper.DONE_ID);
  AccessibilityNodeInfo exact=null;int exactCount=0;
  for(AccessibilityNodeInfo n:cands){
   try{
    String t=n.getText()==null?null:clip(n.getText());
    if(SpikeMapper.DONE_TEXT.equals(t)&&isButtonClass(n)&&n.isVisibleToUser()&&n.isEnabled()&&n.isClickable()){
     exactCount++;if(exact==null)exact=AccessibilityNodeInfo.obtain(n);
    }
   }finally{n.recycle();}
  }
  if(exactCount!=1&&exact!=null){exact.recycle();exact=null;}
  return new PickerDone(true,exact);
 }finally{dialog.recycle();}
}
 // ---- Idle-picker acquisition: open the native picker via the production opener path ----
 /** Actual window display identity; API 30+. Below that the display stays honestly unknown (-1). */
 private static int windowDisplay(AccessibilityWindowInfo w){
  return android.os.Build.VERSION.SDK_INT>=30?w.getDisplayId():-1;
 }
 private boolean chooserOpen(){return chooserDisplay()!=null;}
 /** The native output picker's ACTUAL display (window identity, OS-placed), or null when closed. */
 private Integer chooserDisplay(){
  for(AccessibilityWindowInfo w:getWindows()){
   AccessibilityNodeInfo r=w.getRoot();if(r==null)continue;
   try{
    if(!sys(r))continue;
    AccessibilityNodeInfo d=one(r,SpikeMapper.DIALOG_ID);
    if(d!=null){d.recycle();return windowDisplay(w);}
   }finally{r.recycle();}
  }
  return null;
 }
 private boolean acquirePossible(long now){
  return acquireSequencer.acquirePossible(now);
 }
 /** Run next once the native picker is open (or immediately if already open). Bounded: if the
   *  picker does not appear within the poll budget, run next anyway (it falls back to the marked
   *  snapshot; nothing is invented). A verified panel row already on screen is REUSED (tapped
   *  directly): relaunching the panel stacks identical instances and the fail-closed row guard
   *  then refuses every tap. Overlapping calls have deterministic ownership: the named newer
   *  action supersedes a pending one (its outcome stays visible), and a superseded continuation
   *  is NEVER executed - not even by a delayed old poll callback. */
   private void acquireThen(String action,Runnable next){acquireSequencer.acquireThen(action,next);}
// ---- Persistent route snapshot: only REAL rows ever enter it; shown always marked stale ----
private static final String CACHE="chooser_cache";
private void saveSnapshot(List<SpikeMapper.Row> rows,long now){
 if(prefs==null||rows==null||rows.isEmpty())return;
 prefs.edit().putString(CACHE,ChooserLifecycle.encode(rows)).putLong(CACHE+"_at",now).apply();
}
/** Snapshot rows are never hidden because the picker closed and never validated by age alone
 *  (no TTL freshness): they are history, always rendered marked stale, and any Select tap
 *  revalidates live before a click. */
private List<SpikeMapper.Row> loadSnapshot(){
 if(prefs==null)return new java.util.ArrayList<>();
 return ChooserLifecycle.decode(prefs.getString(CACHE,""));
}
  /** The production panel launch shared by idle acquisition.
   *  The native
   *  volume slider must NEVER appear on the external display, so the panel is launched with
   *  an explicit DEFAULT_DISPLAY target - the stable internal display id (Display.DEFAULT_DISPLAY),
   *  never the rotating external ids, and never OS-placed. Exactly one identity-verified
   *  output row tap follows via selectorAttempt; the app still never dismisses native UI. */
  private boolean launchVolumePanel(){
  try{
   Intent i=new Intent(SelectorOpener.PANEL_ACTION).setPackage(SelectorOpener.SETTINGS_PKG)
     .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
   android.content.pm.ResolveInfo ri=getPackageManager().resolveActivity(i,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
   if(ri==null||ri.activityInfo==null||!ri.activityInfo.exported){status="Selector unavailable: volume panel activity not resolvable/exported.";return false;}
   startActivity(i,android.app.ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle());
  }catch(RuntimeException denied){status="Selector unavailable: launch denied ("+denied.getClass().getSimpleName()+").";return false;}
  return true;
 }
 /** Phase-2 picker opener: the documented system action the Settings volume-panel row
  *  itself sends (SystemUI MediaOutputDialogReceiver: LAUNCH_MEDIA_OUTPUT_DIALOG with a
  *  package_name extra). Opens the real media output dialog wherever the OS places it;
  *  the app never clicks anything inside it and never routes. */
 private void openPickerDirect(){
  try{
   sendBroadcast(new Intent(PICKER_OPENER_ACTION).setPackage(PICKER_OPENER_PKG)
     .putExtra(PICKER_OPENER_EXTRA,getPackageName()));
  }catch(RuntimeException denied){
   status="Selector unavailable: picker opener denied ("+denied.getClass().getSimpleName()+").";
  }
 }
 private void scheduleSelectorStep(){handler.removeCallbacks(selectorStep);handler.postDelayed(selectorStep,250);}
 private void selectorAttempt(){
  long now=SystemClock.elapsedRealtime();
  if(!selectorOpener.stepping(now)){
   if(!pickerFallbackSent&&selectorOpener.expired(now)){
    // Phase 2: the verified row is not tappable in this state (observed on
    // display-output rows: the row tap opens nothing and the gate refuses it),
    // so open the same system picker the row would open via the documented
    // SystemUI action, then keep polling for it. Nothing is clicked or routed.
    pickerFallbackSent=true;
    openPickerDirect();
    // The bounded acquire poll chain (AcquireSequencer) keeps running while an
    // acquisition is pending - it completes the named action when the picker
    // appears or the budget expires. Nothing is clicked or routed.
    status="Auto-acquire: verified output row not tappable in this state; opening the system output picker directly (documented SystemUI action). The app never selects a route.";
    return;
   }
   handler.removeCallbacks(selectorStep);
   status="Auto-acquire: verified output row not tapped in time; panel left open and marked snapshot shown.";return;}
  List<PanelRow> rows=verifiedPanelRows();
  List<String> ids=new ArrayList<>();
  for(PanelRow r:rows)ids.add(r.identity);
  int pick=ChooserLifecycle.pickRow(ids);
  if(pick<0){
   for(PanelRow r:rows)r.node.recycle();
   if(rows.isEmpty()){scheduleSelectorStep();return;} // panel not up yet: keep looking, bounded
   selectorOpener.end();handler.removeCallbacks(selectorStep);
   status="Ambiguous output rows (distinct verified identities); no click.";
   return;
  }
  PanelRow target=rows.get(pick);
  boolean clicked=false;
  try{clicked=target.node.isVisibleToUser()&&target.node.isEnabled()&&target.node.isClickable()&&target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);}
  finally{
   for(int i=0;i<rows.size();i++)if(i!=pick)rows.get(i).node.recycle();
   target.node.recycle();
  }
  selectorOpener.end();handler.removeCallbacks(selectorStep);
  panelLastDisplay=target.displayId;
  status=clicked
   ?"Verified output row tapped on its ACTUAL panel display "+target.displayId+"; the system output picker opens where the OS places it. The app never selects a route."
   :"Verified output row rejected click; panel left open.";
 }
 /** One identity-verified output row: its click node, its ACTUAL display (window identity) and
  *  its "title|summary" identity. No coordinate guessing anywhere. */
 private static final class PanelRow{final AccessibilityNodeInfo node;final int displayId;final String identity;
  PanelRow(AccessibilityNodeInfo node,int displayId,String identity){this.node=node;this.displayId=displayId;this.identity=identity;}}
 /** All identity-verified output rows across ALL windows/displays (topmost first). The row gate is
  *  SelectorOpener.rowValid (exact title/summary identity); stale duplicates are grouped by
  *  identity downstream via ChooserLifecycle.pickRow. */
 private List<PanelRow> verifiedPanelRows(){
  List<PanelRow> out=new ArrayList<>();
  for(AccessibilityWindowInfo w:getWindows()){
   AccessibilityNodeInfo r=w.getRoot();if(r==null)continue;
   try{
    if(!SelectorOpener.SETTINGS_PKG.contentEquals(String.valueOf(r.getPackageName())))continue;
    List<AccessibilityNodeInfo> rows=r.findAccessibilityNodeInfosByViewId(SelectorOpener.SETTINGS_PKG+":id/row_view");
    if(rows==null)continue;
    for(AccessibilityNodeInfo row:rows){
     if(row==null)continue;
     AccessibilityNodeInfo title=null,summary=null;
     try{
      title=firstText(row,"android:id/title");summary=firstText(row,"android:id/summary");
      String t=title==null?null:clip(title.getText()),s=summary==null?null:clip(summary.getText());
      // Panel window identity is OBSERVED here even when the row is inert (the click
      // gate below refuses disabled/non-clickable rows): the status line then reports
      // the volume panel's ACTUAL display tapped or not.
      if(SelectorOpener.rowLabel(t))panelLastDisplay=windowDisplay(w);
      if(SelectorOpener.rowValid(String.valueOf(row.getPackageName()),t,s,row.isVisibleToUser(),row.isEnabled(),row.isClickable()))
       out.add(new PanelRow(AccessibilityNodeInfo.obtain(row),windowDisplay(w),t+"|"+s));
     }finally{if(title!=null)title.recycle();if(summary!=null)summary.recycle();row.recycle();}
    }
   }finally{r.recycle();}
  }
  return out;
 }
 /** True only when a TAPPABLE verified row exists (identity-unique after grouping): acquisition
  *  must reuse that panel instead of stacking another instance. */
 private boolean panelRowPresent(){
  List<PanelRow> rows=verifiedPanelRows();
  List<String> ids=new ArrayList<>();
  for(PanelRow r:rows)ids.add(r.identity);
  boolean usable=ChooserLifecycle.pickRow(ids)>=0;
  for(PanelRow r:rows)r.node.recycle();
  return usable;
 }
 private AccessibilityNodeInfo firstText(AccessibilityNodeInfo root,String viewId){
  List<AccessibilityNodeInfo> a=root.findAccessibilityNodeInfosByViewId(viewId);
  AccessibilityNodeInfo found=null;int count=0;
  if(a!=null)for(AccessibilityNodeInfo n:a){if(n==null)continue;if(n.isVisibleToUser()&&n.getText()!=null){count++;if(found==null)found=AccessibilityNodeInfo.obtain(n);}n.recycle();}
  if(count!=1&&found!=null){found.recycle();found=null;}
  return found;
 }
  // ---- Production enumeration + exact selection mapping (live SystemUI media output dialog) ----
  private static final class SpikeRowNode{final SpikeMapper.Row row;final AccessibilityNodeInfo clickNode;SpikeRowNode(SpikeMapper.Row row,AccessibilityNodeInfo clickNode){this.row=row;this.clickNode=clickNode;}}
  private static final class SpikeFlags{String title;int titleCount,mainCount;boolean hasSeparator,blocked,slider;char blocker='-';}
  private volatile String spikeDiag="no enumeration yet";
  String spikeDiag(){return spikeDiag;}
  List<SpikeMapper.Row> spikeEnumerate(){
   List<SpikeRowNode> found=spikeRows();
   List<SpikeMapper.Row> out=new java.util.ArrayList<>();
   for(SpikeRowNode n:found){out.add(n.row);if(n.clickNode!=null)n.clickNode.recycle();}
   return out;
  }
  /** Real popup taps pass shownTitles (the rows the user actually saw). A closed native picker
   *  is never clicked through: the tap reacquires the idle picker and revalidates the live
   *  list/identity/exact row before any click. */
  String spikeSelect(String label,List<String> shownTitles){
   // Manual destination selection: immediately cancels the countdown and any
   // pending automatic action (no fight between the user and the timer). It is
   // an intentional user action: the retained supersession outcome clears now.
   acquireSequencer.beginIntentionalAction();
   cancelAutoSelect("Manual selection: auto-select countdown cancelled.");
   autoFlowSelect=false; // a manual tap can never turn its confirm into an auto-close
   List<SpikeRowNode> found=spikeRows();
   List<SpikeMapper.Row> rows=new java.util.ArrayList<>();
   for(SpikeRowNode n:found)rows.add(n.row);
   if(!rows.isEmpty())return spikeSelectOn(found,rows,label,shownTitles);
   for(SpikeRowNode n:found)if(n.clickNode!=null)n.clickNode.recycle();
   if(!acquirePossible(SystemClock.elapsedRealtime())){
    status="Selection: native picker closed and reacquire unavailable; no click. Refresh to retry.";
    SelectorPopupActivity.lastResult=status;return status;
   }
   // Stale-list safety: reacquire + revalidate instead of any wrong-route click.
   status="Selection: native picker closed; reopening it to revalidate live rows before any click (nothing clicked yet).";
   SelectorPopupActivity.lastResult=status;
   acquireThen("selection revalidation",()->spikeSelectAfterAcquire(label,shownTitles));
   return status;
  }
  private void spikeSelectAfterAcquire(String label,List<String> shownTitles){
   List<SpikeRowNode> found=spikeRows();
   List<SpikeMapper.Row> rows=new java.util.ArrayList<>();
   for(SpikeRowNode n:found)rows.add(n.row);
   String outcome=rows.isEmpty()
    ? "Selection: native picker still closed after reacquire; no click (fail closed)."
    : spikeSelectOn(found,rows,label,shownTitles);
   // Dismissed while the async reacquire was pending: the popup is never reopened
   // behind the user and the native helper is never restarted for it.
   if(ChooserLifecycle.continuationAllowed(SelectorPopupActivity.isOpen(),SelectorPopupActivity.userClosedLastPopup()))popupFromLive();
   SelectorPopupActivity.lastResult=outcome; // set after relaunch so the selection outcome wins
   status=outcome;
  }
  private String spikeSelectOn(List<SpikeRowNode> found,List<SpikeMapper.Row> rows,String label,List<String> shownTitles){
   // Revalidation gate: the live list must still match exactly what the user was shown;
   // anything else is stale -> refuse the click (stale-list refresh, never wrong-route click).
   if(ChooserLifecycle.gate(true,titles(rows),shownTitles)==ChooserLifecycle.Gate.REFUSE_STALE){
    for(SpikeRowNode n:found)if(n.clickNode!=null)n.clickNode.recycle();
    status="Selection: output list changed since shown (stale); no click. Refresh the list.";return status;
   }
   SpikeMapper.Row want=SpikeMapper.resolve(label,rows);
   if(want==null){for(SpikeRowNode n:found)if(n.clickNode!=null)n.clickNode.recycle();status="Selection: choice refused (absent, ambiguous or not a selectable native row). No click.";return status;}
   SpikeRowNode target=null;
   for(SpikeRowNode n:found)if(n.row==want&&target==null)target=n;
   boolean clicked=false;
   try{
    AccessibilityNodeInfo t=target.clickNode;
    clicked=t!=null&&t.refresh()&&t.isVisibleToUser()&&t.isEnabled()&&t.isClickable()&&t.performAction(AccessibilityNodeInfo.ACTION_CLICK);
   }finally{for(SpikeRowNode n:found)if(n.clickNode!=null)n.clickNode.recycle();}
  if(clicked){
   status="Selection: exact native row clicked for \""+label+"\"; the system performs any route change. Confirming against the live system dialog (list auto-refreshes); the app never routes.";
   scheduleSelectionConfirm(label);
  }else status="Selection: native row rejected click; no route change intended.";
  return status;
 }
 // ---- Post-selection bounded route confirmation + auto refresh (no manual Refresh) ----
 private void scheduleSelectionConfirm(String label){
  handler.removeCallbacks(confirmPoll);
  confirmLabel=label;
  confirmAutomatic=autoFlowSelect;autoFlowSelect=false; // auto flow only: one-shot flag
  confirmDeadline=SystemClock.elapsedRealtime()+CONFIRM_BUDGET_MS;
  handler.postDelayed(confirmPoll,CONFIRM_POLL_MS);
 }
 private void confirmPollStep(){
  String label=confirmLabel;
  if(label==null){handler.removeCallbacks(confirmPoll);return;}
  List<SpikeRowNode> found=spikeRows();
  List<SpikeMapper.Row> rows=new java.util.ArrayList<>();
  for(SpikeRowNode n:found)rows.add(n.row);
  boolean confirmed=ChooserLifecycle.selectionConfirmed(rows,label);
  for(SpikeRowNode n:found)if(n.clickNode!=null)n.clickNode.recycle();
  if(!confirmed&&SystemClock.elapsedRealtime()<confirmDeadline){
   handler.postDelayed(confirmPoll,CONFIRM_POLL_MS);return;
  }
  handler.removeCallbacks(confirmPoll);confirmLabel=null;
  boolean automatic=confirmAutomatic;confirmAutomatic=false;
  // CONFIRMED AUTOMATIC success only : close OUR popup and the exact session-owned native picker.
  // Manual selections keep the established auto-refresh; unconfirmed/failed stays
  // open with the honest reason; nothing acts behind a dismissed popup.
  if(automatic&&confirmed
    &&ChooserLifecycle.autoSuccessClose(true,true,SelectorPopupActivity.isOpen(),SelectorPopupActivity.userClosedLastPopup())){
   autoSuccessClose("Auto-select: \""+label+"\" confirmed as the current output by the live system dialog;");
   return;
  }
  refreshAfterSelection(label);
 }
 /** Confirmed automatic success: close OUR popup AND the session-owned native picker
 *  via the same bounded, identity-verified Done click (never a generic back/dismiss).
 *  The exact close result is preserved in doneMemory.lastOutcome; pending polls are dropped
 *  first so no later confirmation refresh can resurrect either popup. */
 private void autoSuccessClose(String note){
  handler.removeCallbacks(confirmPoll);confirmLabel=null;confirmAutomatic=false;
  handler.removeCallbacks(autoTick);
  String out=closeOwnedPicker(popupSessionGen);
  status=note+" "+out;
  SelectorPopupActivity.lastResult=status;
  SelectorPopupActivity.closeAll();
 }
 /** Auto refresh of the live choices + the actual active-route indicator after a selection.
  *  Truth is re-read from the live native source at refresh time (never optimistic); if the
  *  source closed meanwhile it is reacquired via the production bounded path. The popup is
  *  never reopened behind a user who closed it, and never duplicates (SINGLE_TOP relaunch). */
 private void refreshAfterSelection(String label){
  if(!SelectorPopupActivity.isOpen())return; // nothing on screen to refresh: no surprise UI
  Runnable apply=()->{
   if(!SelectorPopupActivity.isOpen())return; // closed during reacquire: no reopen
   List<SpikeMapper.Row> rows=spikeEnumerate();
   boolean confirmed=ChooserLifecycle.selectionConfirmed(rows,label);
   String outcome=ChooserLifecycle.confirmStatus(confirmed,label,ChooserLifecycle.currentTitle(rows),!rows.isEmpty());
   popupFromLive();
   SelectorPopupActivity.lastResult=outcome; // set after relaunch so the outcome wins
   status=outcome;
  };
  long now=SystemClock.elapsedRealtime();
  if(chooserOpen()){apply.run();return;}
  if(acquirePossible(now)){
   status="Auto-refresh after selection: live dialog closed; reacquiring it to reload the real output state.";
   acquireThen("post-selection refresh",apply);return;
  }
  apply.run();
 }
  private static List<String> titles(List<SpikeMapper.Row> rows){List<String> t=new java.util.ArrayList<>();for(SpikeMapper.Row r:rows)t.add(r.title);return t;}
  private List<SpikeRowNode> spikeRows(){
   List<SpikeRowNode> out=new java.util.ArrayList<>();int dialogs=0,items=0;String listName="none";
   java.util.List<Integer> dialogDisplays=new java.util.ArrayList<>();
   for(AccessibilityWindowInfo w:getWindows()){
    AccessibilityNodeInfo r=w.getRoot();if(r==null)continue;
    try{
     if(!sys(r))continue;
     AccessibilityNodeInfo dialog=one(r,SpikeMapper.DIALOG_ID);if(dialog==null)continue;
     dialogs++;dialogDisplays.add(windowDisplay(w));
     try{
      List<AccessibilityNodeInfo> dl=findAll(dialog,SpikeMapper.LIST_ID);
      List<AccessibilityNodeInfo> lr=findAll(dialog,SpikeMapper.LIST_ID_ALT);
      String scope=SpikeMapper.scope(dl.size(),lr.size());
      AccessibilityNodeInfo use=null;
      if(SpikeMapper.LIST_ID.equals(scope))use=dl.get(0);
      else if(SpikeMapper.LIST_ID_ALT.equals(scope))use=lr.get(0);
      else if("dialogwalk".equals(scope))use=AccessibilityNodeInfo.obtain(dialog);
      if(use!=null){
       listName="none".equals(listName)?scope:"ambiguous";
       try{items+=spikeWalk(use,out,null,0,new int[1]);}finally{use.recycle();}
      }else listName="none".equals(listName)?("refused(dl="+dl.size()+",lr="+lr.size()+")"):"ambiguous";
      for(AccessibilityNodeInfo n:dl)if(n!=null&&n!=use)n.recycle();
      for(AccessibilityNodeInfo n:lr)if(n!=null&&n!=use)n.recycle();
     }finally{dialog.recycle();}
    }finally{r.recycle();}
   }
   // Actionable diagnostics only (counts + observed display identity); no internal
   // resource-id dumps or debug flag strings in user-visible text.
   spikeDiag="dialog="+dialogs+" scope="+listName+" items="+items+" rows="+out.size()+" dialogDisplays="+dialogDisplays;
   if(dialogs==1)pickerLastDisplay=dialogDisplays.get(0); // actual picker display (window identity)
   return dialogs==1?out:new java.util.ArrayList<SpikeRowNode>(); // ambiguous dialog: refuse all
  }
  /** Visible nodes whose view id short name matches, regardless of resource-package namespace. */
  private List<AccessibilityNodeInfo> findAll(AccessibilityNodeInfo r,String id){
   List<AccessibilityNodeInfo> out=new java.util.ArrayList<>();
   findAllWalk(r,id,out,0,new int[1]);
   return out;
  }
  private void findAllWalk(AccessibilityNodeInfo n,String id,List<AccessibilityNodeInfo> out,int depth,int[] count){
   if(n==null||depth>12||++count[0]>SpikeMapper.MAX_NODES)return;
   String full=n.getViewIdResourceName();
   if(full!=null&&full.endsWith(":id/"+id)&&n.isVisibleToUser())out.add(AccessibilityNodeInfo.obtain(n));
   for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c==null)continue;try{findAllWalk(c,id,out,depth+1,count);}finally{c.recycle();}}
  }
  private int spikeWalk(AccessibilityNodeInfo n,List<SpikeRowNode> out,StringBuilder diag,int depth,int[] count){
   if(n==null||depth>12||++count[0]>SpikeMapper.MAX_NODES)return 0;
   String full=n.getViewIdResourceName();
   if(full!=null&&full.endsWith(":id/"+SpikeMapper.ITEM_ID)){
    SpikeFlags f=new SpikeFlags();int[] fc=new int[1];
    spikeFlags(n,f,0,fc);
    spikeSiblingBlockers(n,f,fc);
    List<AccessibilityNodeInfo> clicks=new java.util.ArrayList<>();
    spikeClickables(n,clicks,0,new int[1]);
    boolean uniqueClick=clicks.size()==1;
    boolean hasMain=f.mainCount==1||uniqueClick;
    AccessibilityNodeInfo handoff=null;
    if(f.mainCount<=1){
     SpikeMapper.Row row=SpikeMapper.row(f.title,f.titleCount,hasMain,uniqueClick,f.hasSeparator,f.blocked,f.slider);
     if(row!=null){
      if(row.selectable&&uniqueClick)handoff=clicks.get(0);
      out.add(new SpikeRowNode(row,handoff));
     }
    }
    for(AccessibilityNodeInfo c:clicks)if(c!=handoff)c.recycle();
    return 1;
   }
   int items=0;
   for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c==null)continue;try{items+=spikeWalk(c,out,diag,depth+1,count);}finally{c.recycle();}}
   return items;
  }
  private void spikeFlags(AccessibilityNodeInfo n,SpikeFlags f,int depth,int[] count){
   if(n==null||depth>8||++count[0]>SpikeMapper.MAX_NODES)return;
   String full=n.getViewIdResourceName();
   String id=full==null?"":full.substring(full.lastIndexOf('/')+1);
   if((SpikeMapper.TITLE_ID.equals(id)||SpikeMapper.TITLE_ID_ALT.equals(id))&&n.isVisibleToUser()&&n.getText()!=null){f.titleCount++;if(f.titleCount==1)f.title=String.valueOf(n.getText());}
   if(SpikeMapper.MAIN_ID.equals(id))f.mainCount++;
   if(SpikeMapper.SEP_ID.equals(id))f.hasSeparator=true;
   if(SpikeMapper.SLIDER_ID.equals(id))f.slider=true;
   if(SpikeMapper.BLOCKER_IDS.contains(id)){f.blocked=true;if(f.blocker=='-')f.blocker='i';}
   else if(n.isSelected()){f.blocked=true;if(f.blocker=='-')f.blocker='s';}
   else if(isButtonClass(n)){f.blocked=true;if(f.blocker=='-')f.blocker='n';}
   for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c==null)continue;try{spikeFlags(c,f,depth+1,count);}finally{c.recycle();}}
  }
  /** The only admissible row click target: exactly one clickable, non-control node in the item. */
  private void spikeClickables(AccessibilityNodeInfo n,List<AccessibilityNodeInfo> out,int depth,int[] count){
   if(n==null||depth>8||++count[0]>SpikeMapper.MAX_NODES)return;
   String full=n.getViewIdResourceName();
   String id=full==null?"":full.substring(full.lastIndexOf('/')+1);
   if(SpikeMapper.BLOCKER_IDS.contains(id)||isButtonClass(n))return; // control subtrees are never the row click target
   if(n.isVisibleToUser()&&n.isEnabled()&&n.isClickable())out.add(AccessibilityNodeInfo.obtain(n));
   for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c==null)continue;try{spikeClickables(c,out,depth+1,count);}finally{c.recycle();}}
  }
  /** Blocker controls may sit beside the item in its device_container; list-level siblings must not leak. */
  private void spikeSiblingBlockers(AccessibilityNodeInfo item,SpikeFlags f,int[] count){
   AccessibilityNodeInfo p=item.getParent();if(p==null)return;
   try{
    String full=p.getViewIdResourceName();
    String pid=full==null?"":full.substring(full.lastIndexOf('/')+1);
    if(!SpikeMapper.CONTAINER_ID.equals(pid))return;
    for(int i=0;i<p.getChildCount();i++){AccessibilityNodeInfo c=p.getChild(i);if(c==null)continue;try{spikeBlockersOnly(c,f,0,count);}finally{c.recycle();}}
   }finally{p.recycle();}
  }
  private void spikeBlockersOnly(AccessibilityNodeInfo n,SpikeFlags f,int depth,int[] count){
   if(n==null||depth>8||++count[0]>SpikeMapper.MAX_NODES)return;
   String full=n.getViewIdResourceName();
   String id=full==null?"":full.substring(full.lastIndexOf('/')+1);
   if(SpikeMapper.BLOCKER_IDS.contains(id)){f.blocked=true;if(f.blocker=='-')f.blocker='i';}
   else if(n.isSelected()){f.blocked=true;if(f.blocker=='-')f.blocker='s';}
   else if(isButtonClass(n)){f.blocked=true;if(f.blocker=='-')f.blocker='n';}
   for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c==null)continue;try{spikeBlockersOnly(c,f,depth+1,count);}finally{c.recycle();}}
  }
  /** Action controls (e.g. a "connect device" button) are never output-route rows. */
  private boolean isButtonClass(AccessibilityNodeInfo n){
   CharSequence cls=n.getClassName();
   return cls!=null&&"android.widget.Button".contentEquals(cls);
  }
 private boolean sys(AccessibilityNodeInfo n){return n!=null&&P.contentEquals(n.getPackageName()==null?"":n.getPackageName());}
 private AccessibilityNodeInfo one(AccessibilityNodeInfo r,String id){
  List<AccessibilityNodeInfo> a=r.findAccessibilityNodeInfosByViewId(P+":id/"+id);AccessibilityNodeInfo found=null;int count=0;
  for(AccessibilityNodeInfo n:a){if(n.isVisibleToUser()){count++;if(found==null)found=AccessibilityNodeInfo.obtain(n);}n.recycle();}
  if(count!=1&&found!=null){found.recycle();found=null;}return found;
 }
 private static String clip(CharSequence raw){return raw!=null&&raw.length()<=128?raw.toString():"";}
 @Override public void onInterrupt(){cancel("Accessibility interrupted");}
 @Override public boolean onUnbind(Intent i){cleanup();return super.onUnbind(i);}
 @Override public void onDestroy(){cleanup();super.onDestroy();}
 private void cleanup(){
  cancel("Disconnected");
  handler.removeCallbacks(openCustomPopupRun);handler.removeCallbacks(selectorStep);acquireSequencer.cancel();
  if(watching){displays.unregisterDisplayListener(displayListener);watching=false;}
  if(prefs!=null)prefs.unregisterOnSharedPreferenceChangeListener(preferences);
  if(connected==this)connected=null;
 }
}
