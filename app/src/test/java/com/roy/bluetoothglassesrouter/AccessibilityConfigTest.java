package com.roy.bluetoothglassesrouter;
import org.junit.Test; import static org.junit.Assert.*;
import javax.xml.parsers.DocumentBuilderFactory; import java.io.File;
public class AccessibilityConfigTest {
 @Test public void clickEventsMustNotBeDelayedPastOriginWindowRemoval() throws Exception {
  String timeout=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File("src/main/res/xml/accessibility_service_config.xml")).getDocumentElement().getAttribute("android:notificationTimeout");
  assertEquals("0",timeout);
 }
}
