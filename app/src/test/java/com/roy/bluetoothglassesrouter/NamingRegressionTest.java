package com.roy.bluetoothglassesrouter;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Terminology regression: shipped user-visible copy must not carry product branding
 * (standalone "glasses"). The app is generic audio-device routing.
 * Dynamic user-configured Bluetooth aliases appear at runtime and are allowed; test
 * fixtures may use any alias. This scans only shipped string literals, resources,
 * manifest labels and the root README. Internal identifiers (package/class names)
 * and internal code comments are explicitly out of scope.
 *
 * CLASSIFICATION (F5): NAMING/COPY TRIPWIRE - an appropriate seam for user-visible
 * copy (retained deliberately). It is not behavioral coverage.
 */
public class NamingRegressionTest {
    private static final Pattern JAVA_STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern BANNED = Pattern.compile("\\bglasses\\b", Pattern.CASE_INSENSITIVE);

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static List<String> hits(String text) {
        List<String> found = new ArrayList<>();
        Matcher m = BANNED.matcher(text);
        while (m.find()) found.add(m.group());
        return found;
    }

    private static void assertNoBranding(String surface, String text) {
        assertTrue("Product branding in " + surface + ": " + hits(text), hits(text).isEmpty());
    }

    @Test public void javaUiStringLiteralsCarryNoProductBranding() throws Exception {
        File dir = new File("src/main/java/com/roy/bluetoothglassesrouter");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".java"));
        assertNotNull(files);
        for (File f : files) {
            Matcher m = JAVA_STRING_LITERAL.matcher(read(f.getPath()));
            while (m.find()) assertNoBranding("literal in " + f.getName(), m.group(1));
        }
    }

    @Test public void stringResourcesCarryNoProductBranding() throws Exception {
        assertNoBranding("strings.xml", read("src/main/res/values/strings.xml"));
    }

    @Test public void manifestLabelsCarryNoProductBranding() throws Exception {
        assertNoBranding("AndroidManifest.xml", read("src/main/AndroidManifest.xml"));
    }

    @Test public void rootReadmeCarriesNoProductBranding() throws Exception {
        assertNoBranding("README.md", read("../README.md"));
    }
}
