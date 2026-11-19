package com.myjavaworld.jftp;

import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.StreamCorruptedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.crypto.SealedObject;

import com.myjavaworld.ftp.FTPConstants;
import junit.framework.TestCase;

public class ProfilePersistenceTest extends TestCase {
    public void testPreferencesAndLegacyFavoritesInIsolatedJvm() throws Exception {
        // JFTP initializes its static profile path on first use.
        try (TestDirectory directory = new TestDirectory()) {
            Path java = Paths.get(System.getProperty("java.home"), "bin", "java");
            if (!Files.exists(java)) {
                java = java.resolveSibling("java.exe");
            }
            String classpath = System.getProperty("surefire.test.class.path",
                    System.getProperty("java.class.path"));
            Path home = Files.createDirectory(directory.root.resolve("user home"));
            Path log = directory.root.resolve("profile-test.log");
            Process child = new ProcessBuilder(java.toString(), "-Duser.home=" + home,
                    "-Djava.awt.headless=true", "-cp", classpath, ProfilePersistenceTest.class.getName(),
                    home.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            if (!child.waitFor(30, TimeUnit.SECONDS)) {
                child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                fail("The isolated profile test timed out");
            }
            assertEquals(new String(Files.readAllBytes(log), StandardCharsets.UTF_8),
                    0, child.exitValue());
        }
    }

    public static void main(String[] args) throws Exception {
        Path home = Paths.get(args[0]);
        assertEquals(home.toString(), System.getProperty("user.home"));
        Path data = home.resolve(".jftp/data");
        assertEquals(data, JFTP.DATA_HOME.toPath());
        checkPreferences(data);
        checkFavorites(data);
    }

    private static void checkPreferences(Path data) throws Exception {
        JFTPPreferences preferences = JFTP.loadPreferences();
        assertTrue(Files.isRegularFile(data.resolve("preferences.ser")));
        assertTrue(preferences.isPassive());
        assertEquals(FTPConstants.TYPE_BINARY, preferences.getDefaultTransferType());
        assertEquals(Integer.valueOf(FTPConstants.TYPE_ASCII), preferences.getTransferTypes().get("TXT"));

        preferences.setLocale(Locale.TAIWAN);
        preferences.setLocalDirectory("folder with spaces");
        preferences.setPassive(false);
        preferences.setCheckForUpdates(false);
        JFTP.savePreferences(preferences);

        JFTPPreferences reopened = JFTP.loadPreferences();
        assertEquals(Locale.TAIWAN, reopened.getLocale());
        assertEquals("folder with spaces", reopened.getLocalDirectory());
        assertFalse(reopened.isPassive());
        assertFalse(reopened.getCheckForUpdates());
    }

    private static void checkFavorites(Path data) throws Exception {
        Path file = data.resolve("favorites.ser");
        assertTrue(FavoritesManager.getFavorites().isEmpty());
        assertTrue(Files.isRegularFile(file));

        copyFixture("favorites-baseline-plaintext.ser", file);
        assertLegacySite(FavoritesManager.getFavorites());
        try (ObjectInputStream sealed = new ObjectInputStream(Files.newInputStream(file))) {
            assertTrue(sealed.readObject() instanceof SealedObject);
        }
        assertLegacySite(FavoritesManager.getFavorites());

        copyFixture("favorites-baseline-sealed.ser", file);
        byte[] sealedBytes = Files.readAllBytes(file);
        assertLegacySite(FavoritesManager.getFavorites());
        assertTrue("Reopening sealed favorites should preserve the file",
                Arrays.equals(sealedBytes, Files.readAllBytes(file)));

        byte[] corruptBytes = {1, 2, 3, 4};
        Files.write(file, corruptBytes);
        try {
            FavoritesManager.getFavorites();
            fail("A corrupt favorites file should report a read failure");
        } catch (StreamCorruptedException expected) {
            assertTrue(Arrays.equals(corruptBytes, Files.readAllBytes(file)));
        }
    }

    private static void copyFixture(String name, Path file) throws Exception {
        try (InputStream fixture = ProfilePersistenceTest.class.getResourceAsStream(name)) {
            assertNotNull("Missing serialized baseline fixture " + name, fixture);
            Files.copy(fixture, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void assertLegacySite(List<?> favorites) {
        assertEquals(1, favorites.size());
        Favorite site = (Favorite) favorites.get(0);
        assertEquals("B\u00fcro \u53f0\u5317", site.getName());
        assertEquals("ftp.example.invalid", site.getHostName());
        assertEquals(2121, site.getPort());
        assertEquals("fixture-operator", site.getUser());
        assertTrue("The saved password should round-trip",
                "synthetic-test-value".equals(site.getPassword()));
        assertEquals("fixture-account", site.getAccount());
        assertEquals("sample folder", site.getInitialLocalDirectory());
        assertEquals("/incoming", site.getInitialRemoteDirectory());
        assertFalse(site.isPassive());
        assertEquals(FTPConstants.USE_EXPLICIT_SSL, site.getSSLUsage());
        assertEquals(2990, site.getImplicitSSLPort());
        assertTrue(site.isDataChannelUnencrypted());
        assertEquals(Arrays.asList("SYST", "NOOP"), Arrays.asList(site.getCommands()));
    }
}
