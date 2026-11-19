package com.myjavaworld.jftp;

import java.io.InputStream;
import java.net.URL;
import java.util.Locale;

import javax.help.HelpSet;
import javax.help.Map;
import javax.swing.Icon;

import com.myjavaworld.util.ResourceLoader;
import junit.framework.TestCase;

public class DesktopResourcesTest extends TestCase {
    public void testStartupHelpBrokerAndIconResolveFromClasspath() throws Exception {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ENGLISH);
            HelpSet help = JFTPHelp2.getInstance().getHelpBroker().getHelpSet();
            assertEquals("index", help.getHomeID().id);
            URL contents = help.getCombinedMap().getURLFromID(Map.ID.create("index", help));
            assertNotNull(contents);
            try (InputStream input = contents.openStream()) {
                assertTrue("The bundled help topic must contain data", input.read() != -1);
            }

            Icon icon = JFTPUtil.getIcon("jftp16.gif");
            assertEquals(16, icon.getIconWidth());
            assertEquals(16, icon.getIconHeight());
        } finally {
            Locale.setDefault(original);
        }
    }

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
