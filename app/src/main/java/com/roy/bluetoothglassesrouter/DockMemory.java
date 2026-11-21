package com.roy.bluetoothglassesrouter;
import java.util.*;

/**
 * Display-attach trigger memory: tracks app-visible external displays and
 * reports the first no-display -> display transition (the popup attach
 * trigger). SETTLE is the post-attach settle before the selector popup opens.
 * The former pre-attach evidence/restore roles (Remember/Restore, cooldown
 * buckets) were retired with those paths.
 */
final class DockMemory {
    static final long SETTLE = 1500;
    private Set<Integer> displays;

    DockMemory(Set<Integer> initial) {
        displays = new HashSet<>(initial);
    }

    String diagnostics(long now) {
        return "External displays: " + displays.size() + ". Docked: " + docked() + ".";
    }

    boolean docked() {
        return !displays.isEmpty();
    }

    /** First no-display -> display transition returns true (the attach trigger). */
    boolean displays(Set<Integer> current, long now) {
        boolean first = displays.isEmpty() && !current.isEmpty();
        displays = new HashSet<>(current);
        return first;
    }
}
