package com.roy.bluetoothglassesrouter;
import org.junit.Test; import static org.junit.Assert.*; import java.util.*;

/**
 * Accepted-behavior coverage for the remaining DockMemory role: the display-attach
 * trigger (first no-display -> display transition) plus honest display diagnostics.
 * The former pre-attach evidence/TTL/cooldown behaviors were retired with the
 * Remember/Restore path and their tests removed (see the cleanup report).
 */
public class DockMemoryTest {
 @Test public void firstNoDisplayToDisplayTransitionTriggersOnce(){
  DockMemory m=new DockMemory(Collections.emptySet());
  assertTrue(m.displays(Collections.singleton(1),10));
  assertFalse(m.displays(Collections.singleton(1),20));
  assertFalse(m.displays(new HashSet<>(Arrays.asList(1,2)),30));
 }
 @Test public void startupAlreadyDockedNeverTriggers(){
  DockMemory m=new DockMemory(Collections.singleton(1));
  assertFalse(m.displays(Collections.singleton(1),10));
  assertFalse(m.displays(new HashSet<>(Arrays.asList(1,2)),20));
 }
 @Test public void detachRearmsForTheNextAttach(){
  DockMemory m=new DockMemory(Collections.emptySet());
  assertTrue(m.displays(Collections.singleton(1),10));
  assertFalse(m.displays(Collections.emptySet(),20)); // detach: never a trigger
  assertFalse(m.docked());
  assertTrue(m.displays(Collections.singleton(2),30));
 }
 @Test public void diagnosticsReportDisplayStateWithoutDeviceNames(){
  DockMemory m=new DockMemory(Collections.emptySet());
  assertEquals("External displays: 0. Docked: false.",m.diagnostics(0));
  m.displays(Collections.singleton(120),10);
  String d=m.diagnostics(20);
  assertTrue(d.contains("External displays: 1"));
  assertTrue(d.contains("Docked: true"));
  assertFalse(d.contains("Remember"));
  assertFalse(d.contains("Evidence"));
 }
}
