package com.roy.bluetoothglassesrouter;

import org.junit.Test;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Source contracts for the restyled popup render wiring: the
 * chooser renders through the pure model, taps feed the production selection
 * path with the shownTitles guard, refresh/dismiss stay wired, debug/provenance
 * detail lives only in the collapsible diagnostics area, and the Android
 * native-selector styling tokens (opaque sheet, rounded corners, 48dp targets)
 * are present. Not physical dock proof.
 *
 * CLASSIFICATION (F5): SOURCE-CONTRACT TRIPWIRE - pins code shape, not behavior.
 * Behavioral popup coverage lives in SelectorPopupModelTest and
 * SupersessionOutcomeRenderTest (real execution).
 */
public class PopupRenderWiringTest {

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static String popup() throws Exception {
        return read("src/main/java/com/roy/bluetoothglassesrouter/SelectorPopupActivity.java");
    }

    @Test public void renderRendersThroughThePureModel() throws Exception {
        String p = popup();
        assertTrue(p.contains("SelectorPopupModel.items(rows, staleRows)"));
        assertTrue(p.contains("SelectorPopupModel.emptyMessage(rows)"));
        assertTrue(p.contains("SelectorPopupModel.titles(rows)"));
    }

    @Test public void rowTapIsWiredToProductionSelectWithShownTitles() throws Exception {
        String p = popup();
        assertTrue(p.contains("spikeSelect(choice, shownTitles)"));
        assertTrue(p.contains("shownTitles"));
        assertFalse("no Select-prefixed debug buttons", p.contains("Select: "));
    }

    @Test public void refreshShowsLoadingStateAndDismissStaysWired() throws Exception {
        String p = popup();
        assertTrue(p.contains("Refresh outputs"));
        assertTrue(p.contains("refreshOutputs()"));
        assertTrue("loading state is shown during refresh", p.contains("SelectorPopupModel.LOADING_MESSAGE"));
        assertTrue(p.contains("Done"));
        assertTrue(p.contains("finish()"));
    }

    @Test public void provenanceDetailIsCollapsibleNotInTheMainChooser() throws Exception {
        String p = popup();
        assertTrue("collapsible diagnostics area exists", p.contains("Diagnostics"));
        assertTrue(p.contains("Provenance: "));
        assertTrue("diagnostics start collapsed", p.contains("setVisibility(View.GONE)"));
        assertFalse("no feasibility debug dump in the main header", p.contains("FEASIBILITY"));
    }

    @Test public void nativeSelectorStyleSurfacesAndTouchTargets() throws Exception {
        String p = popup();
        assertTrue("opaque sheet surface matches ground truth #303030", p.contains("0xFF303030"));
        assertTrue("rounded sheet corners", p.contains("setCornerRadius(dp(24))"));
        assertTrue("rows keep 48dp+ touch targets", p.contains("setMinimumHeight(dp(56))"));
        assertTrue("buttons keep 48dp touch targets", p.contains("setMinHeight(dp(48))"));
    }

    @Test public void staleRowsStillRevalidateBeforeAnyClick() throws Exception {
        String p = popup();
        assertTrue(p.contains("Revalidate & select"));
        assertTrue(p.contains("staleRows"));
        assertTrue(p.contains("staleRows && !rows.isEmpty()"));
    }
}