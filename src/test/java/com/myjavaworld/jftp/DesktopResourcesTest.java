package com.myjavaworld.jftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URL;
import java.util.Locale;

import javax.help.HelpSet;
import javax.help.Map;
import javax.swing.Icon;

import org.junit.jupiter.api.Test;

import com.myjavaworld.util.ResourceLoader;

public class DesktopResourcesTest {
    @Test
    public void testStartupHelpBrokerAndIconResolveFromClasspath() throws Exception {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ENGLISH);
            HelpSet help = JFTPHelp2.getInstance().getHelpBroker().getHelpSet();
            assertEquals("index", help.getHomeID().id);
            URL contents = help.getCombinedMap().getURLFromID(Map.ID.create("index", help));
            assertNotNull(contents);
            try (InputStream input = contents.openStream()) {
                assertTrue(input.read() != -1, "The bundled help topic must contain data");
            }

            Icon icon = JFTPUtil.getIcon("jftp16.gif");
            assertEquals(16, icon.getIconWidth());
            assertEquals(16, icon.getIconHeight());
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    public void testBundlesResolveGermanTraditionalChineseAndEnglishFallback() {
        Locale original = Locale.getDefault();
        String bundle = "com.myjavaworld.jftp.HelpMenu";
        try {
            Locale.setDefault(Locale.ENGLISH);
            assertEquals(Locale.GERMAN, ResourceLoader.getBundle(bundle, Locale.GERMANY).getLocale());
            assertEquals(Locale.TAIWAN, ResourceLoader.getBundle(bundle, Locale.TAIWAN).getLocale());
            assertEquals("Contents and Index",
                    ResourceLoader.getBundle(bundle, Locale.ITALIAN).getString("text.contents"));
        } finally {
            Locale.setDefault(original);
        }
    }
}
