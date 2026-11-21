package com.roy.bluetoothglassesrouter;

import org.junit.Test;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * App-identity icon source contracts. Pins the AUDIO ROUTER
 * launcher identity - bold speaker + split routing arrows, shape-only (no text,
 * no tiny detail) - as an Android adaptive icon with a monochrome themed layer
 * plus legacy density fallbacks, and pins the removal of the legacy eyewear
 * glyph. The in-app device-row icons (ic_device_*) are deliberately out of
 * scope: they describe real output devices, not the app.
 *
 * CLASSIFICATION (F5): ICON/RESOURCE TRIPWIRE - pins drawable/resource shape
 * (retained deliberately). It is not behavioral coverage.
 */
public class IconContractTest {

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static boolean exists(String path) {
        return Files.exists(Paths.get(path));
    }

    @Test public void manifestPointsAtAdaptiveMipmapIcons() throws Exception {
        String m = read("src/main/AndroidManifest.xml");
        assertTrue("launcher icon resolves through the adaptive mipmap",
                m.contains("android:icon=\"@mipmap/ic_launcher\""));
        assertTrue("round icon declared for round-mask launchers",
                m.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""));
        assertFalse("legacy drawable icon reference is gone",
                m.contains("\"@drawable/ic_launcher\""));
    }

    @Test public void adaptiveIconsWireBackgroundForegroundAndMonochrome() throws Exception {
        for (String name : new String[] {"ic_launcher", "ic_launcher_round"}) {
            String xml = read("src/main/res/mipmap-anydpi-v26/" + name + ".xml");
            assertTrue(name + " is an adaptive icon", xml.contains("<adaptive-icon"));
            assertTrue(name + " wires the background layer",
                    xml.contains("android:drawable=\"@drawable/ic_launcher_background\""));
            assertTrue(name + " wires the foreground layer",
                    xml.contains("android:drawable=\"@drawable/ic_launcher_foreground\""));
            assertTrue(name + " wires the monochrome themed-icon layer",
                    xml.contains("android:drawable=\"@drawable/ic_launcher_monochrome\""));
        }
    }

    @Test public void iconLayerDrawablesExistAndMonochromeMirrorsForeground() throws Exception {
        String fg = read("src/main/res/drawable/ic_launcher_foreground.xml");
        String mono = read("src/main/res/drawable/ic_launcher_monochrome.xml");
        String bg = read("src/main/res/drawable/ic_launcher_background.xml");
        assertEquals("monochrome themed layer must mirror the foreground glyph exactly", fg, mono);
        assertTrue("background uses the selector-sheet dark surface (palette coherence)",
                bg.contains("#FF303030"));
    }

    @Test public void legacyDensityFallbacksExist() {
        for (String density : new String[] {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"}) {
            String dir = "src/main/res/mipmap-" + density;
            assertTrue(density + " ic_launcher.png", exists(dir + "/ic_launcher.png"));
            assertTrue(density + " ic_launcher_round.png", exists(dir + "/ic_launcher_round.png"));
        }
    }

    @Test public void legacyEyewearGlyphIsGone() throws Exception {
        assertFalse("legacy eyewear vector must be removed",
                exists("src/main/res/drawable/ic_launcher.xml"));
        String fg = read("src/main/res/drawable/ic_launcher_foreground.xml");
        assertFalse("the legacy silhouette must not survive inside the new foreground",
                fg.contains("M4,16L20,16"));
        // Shape-only glyph: no <text> surface and no drawable-nodpi text art.
        assertFalse("launcher glyph is shape-only, never text", fg.contains("<text"));
    }
}
