package com.myjavaworld.jftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import com.sun.nio.file.ExtendedOpenOption;

class ProfileMigrationTest {
    private static final String PREFERENCES = "preferences.ser";
    private static final String FAVORITES = "favorites.ser";
    private static final String SERVER = "serverCertificates.jks";
    private static final String CLIENT = "clientCertificates.jks";

    @Test
    void confirmedMigrationBacksUpReopensAndRestoresTheProfile() throws Exception {
        try (TestDirectory directory = new TestDirectory()) {
            Path home = directory.root.resolve("synthetic home");
            Path data = prepareProfile(home);
            ProfileInspectionTest.createStores(directory.root, data);
            Path root = data.getParent();
            Path active = root.resolve("active-profile");
            List<String> originals = List.of(PREFERENCES, FAVORITES, SERVER, CLIENT);
            List<String> before = ProfileInspectionTest.hashes(originals.stream().map(data::resolve).toList());

            Result migrated = run(directory.root, "yes\n", "--migrate-profile", home.toString());

            assertEquals(0, migrated.exitCode(), migrated.output());
            String generation = Files.readString(active).strip();
            Path backup = root.resolve("backups").resolve(generation);
            Path migratedData = root.resolve("generations").resolve(generation);
            assertEquals(before, ProfileInspectionTest.hashes(originals.stream().map(backup::resolve).toList()));
            assertEquals(before, ProfileInspectionTest.hashes(originals.stream().map(data::resolve).toList()));
            assertEquals(-1, Files.mismatch(data.resolve(FAVORITES), migratedData.resolve(FAVORITES)));
            assertEquals(-1, Files.mismatch(data.resolve(SERVER), migratedData.resolve(SERVER)));
            assertEquals(-1, Files.mismatch(data.resolve(CLIENT), migratedData.resolve(CLIENT)));
            Result inspected = run(directory.root, "", "--inspect-profile", migratedData.toString());
            assertEquals(0, inspected.exitCode(), inspected.output());
            assertTrue(inspected.output().contains("Saved sites 1"), inspected.output());
            assertTrue(inspected.output().contains("trusted-server (trusted certificate)"), inspected.output());
            assertTrue(inspected.output().contains("loopback (key entry)"), inspected.output());
            verifyDesktopProfileSelection(home, migratedData);

            Result retried = run(directory.root, "yes\n", "--migrate-profile", home.toString());
            assertEquals(0, retried.exitCode(), retried.output());
            assertEquals(generation, Files.readString(active).strip());
            try (var generations = Files.list(root.resolve("generations"))) {
                assertEquals(1, generations.count());
            }
            Result restore = run(directory.root, "", "--restore-profile", home.toString(), "legacy");
            assertEquals(0, restore.exitCode(), restore.output());
            assertEquals("legacy", Files.readString(active).strip());
            verifyDesktopProfileSelection(home, data);
            Path displaced = Files.move(migratedData.resolve(FAVORITES), directory.root.resolve("displaced favorites"));
            Result incomplete = run(directory.root, "", "--restore-profile", home.toString(), generation);
            assertEquals(1, incomplete.exitCode(), incomplete.output());
            assertTrue(incomplete.output().contains(FAVORITES), incomplete.output());
            assertEquals("legacy", Files.readString(active).strip());
            Files.move(displaced, migratedData.resolve(FAVORITES));
            Result reselect = run(directory.root, "", "--restore-profile", home.toString(), generation);
            assertEquals(0, reselect.exitCode(), reselect.output());
            assertEquals(generation, Files.readString(active).strip());
            Result unavailable = run(directory.root, "", "--restore-profile", home.toString(), "0".repeat(64));
            assertEquals(1, unavailable.exitCode(), unavailable.output());
            assertEquals(generation, Files.readString(active).strip());
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aFailedDesktopSaveLeavesTheCurrentPreferencesComplete() throws Exception {
        try (TestDirectory directory = new TestDirectory()) {
            Path home = directory.root.resolve("synthetic home");
            Path data = prepareProfile(home);
            Result result = runJava(home, "", ProfileMigrationTest.class.getName(),
                    new String[] {data.toString(), "verify-save-failure"});
            assertEquals(0, result.exitCode(), result.output());
        }
    }

    @Test
    void cancellationAndMalformedSourceLeaveTheOriginalActive() throws Exception {
        try (TestDirectory directory = new TestDirectory()) {
            Path home = directory.root.resolve("synthetic home");
            Path data = prepareProfile(home);
            Path active = data.getParent().resolve("active-profile");
            Result cancel = run(directory.root, "no\n", "--migrate-profile", home.toString());
            assertEquals(0, cancel.exitCode(), cancel.output());
            assertTrue(cancel.output().contains("Migration cancelled"), cancel.output());
            assertFalse(Files.exists(active));
            assertFalse(Files.exists(data.getParent().resolve("backups")));
            Files.write(data.resolve(FAVORITES), new byte[] {1, 2, 3, 4});
            Result corrupt = run(directory.root, "yes\n", "--migrate-profile", home.toString());
            assertEquals(1, corrupt.exitCode(), corrupt.output());
            assertTrue(corrupt.output().contains(FAVORITES), corrupt.output());
            assertFalse(Files.exists(active));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void retainedAndIncludedExternalStoresKeepTheirIntendedReferences() throws Exception {
        try (TestDirectory directory = new TestDirectory()) {
            Path home = directory.root.resolve("synthetic home");
            Path data = prepareProfile(home);
            ProfileInspectionTest.createStores(directory.root, data);
            Path external = Files.move(data.resolve(SERVER), directory.root.resolve("external trusted.jks"));
            JFTPPreferences preferences;
            try (var input = new ObjectInputStream(Files.newInputStream(data.resolve(PREFERENCES)))) {
                preferences = (JFTPPreferences) input.readObject();
            }
            preferences.setServerCertificateStore(external.toString());
            try (var output = new ObjectOutputStream(Files.newOutputStream(data.resolve(PREFERENCES)))) {
                output.writeObject(preferences);
            }
            Path root = data.getParent();
            Result retained = run(directory.root, "yes\n", "--migrate-profile", home.toString());
            assertEquals(0, retained.exitCode(), retained.output());
            assertTrue(retained.output().contains("Retain external store " + external), retained.output());
            String first = Files.readString(root.resolve("active-profile")).strip();
            Path firstData = root.resolve("generations").resolve(first);
            assertFalse(Files.exists(firstData.resolve(SERVER)));
            Result inspected = run(directory.root, "", "--inspect-profile", firstData.toString());
            assertEquals(0, inspected.exitCode(), inspected.output());
            assertTrue(inspected.output().contains(external.toString()), inspected.output());

            try (FileChannel protectedMarker = FileChannel.open(root.resolve("active-profile"),
                    StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE)) {
                Result interrupted = run(directory.root, "yes\n", "--migrate-profile", home.toString(),
                        "--copy-server-store");
                assertEquals(1, interrupted.exitCode(), interrupted.output());
                assertTrue(interrupted.output().contains("active-profile"), interrupted.output());
                assertEquals(first, Files.readString(root.resolve("active-profile")).strip());
            }
            Result included = run(directory.root, "yes\n", "--migrate-profile", home.toString(), "--copy-server-store");
            assertEquals(0, included.exitCode(), included.output());
            String second = Files.readString(root.resolve("active-profile")).strip();
            Path secondData = root.resolve("generations").resolve(second);
            assertEquals(-1, Files.mismatch(external, secondData.resolve(SERVER)));
            assertEquals(-1, Files.mismatch(external, root.resolve("backups").resolve(second).resolve(SERVER)));
        }
    }

    private static Path prepareProfile(Path home) throws Exception {
        Path data = Files.createDirectories(home.resolve(".jftp/data"));
        ProfileInspectionTest.copyFixture("preferences-baseline.ser", data.resolve(PREFERENCES));
        ProfileInspectionTest.copyFixture("favorites-baseline-plaintext.ser", data.resolve(FAVORITES));
        return data;
    }

    private static Result run(Path directory, String input, String... arguments) throws Exception {
        Path home = Files.createDirectories(directory.resolve("operator"));
        Result result = runJava(home, input, JFTPApplication.class.getName(), arguments);
        assertFalse(Files.exists(home.resolve(".jftp")), "Profile commands must keep the operator home separate");
        return result;
    }

    private static void verifyDesktopProfileSelection(Path home, Path selected) throws Exception {
        Result result = runJava(home, "", ProfileMigrationTest.class.getName(), new String[] {selected.toString()});
        assertEquals(0, result.exitCode(), result.output());
    }

    public static void main(String[] args) throws Exception {
        Path expected = Path.of(args[0]);
        assertEquals(expected, JFTP.DATA_HOME.toPath());
        assertEquals("zh-TW", JFTP.prefs.getLocale().toLanguageTag());
        assertEquals(expected.resolve(SERVER), Path.of(JFTP.prefs.getServerCertificateStore()));
        assertEquals(expected.resolve(CLIENT), Path.of(JFTP.prefs.getClientCertificateStore()));
        if (args.length == 2 && "verify-save-failure".equals(args[1])) {
            Path file = expected.resolve(PREFERENCES);
            byte[] original = Files.readAllBytes(file);
            JFTP.prefs.setLocale(java.util.Locale.FRENCH);
            try (FileChannel protectedPrefs = FileChannel.open(file, StandardOpenOption.READ,
                    ExtendedOpenOption.NOSHARE_DELETE)) {
                assertThrows(IOException.class, () -> JFTP.savePreferences(JFTP.prefs));
                assertArrayEquals(original, Files.readAllBytes(file));
            }
        }
    }

    private static Result runJava(Path home, String input, String mainClass, String[] arguments) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        if (!Files.exists(java)) {
            java = java.resolveSibling("java.exe");
        }
        Path log = home.resolve("cli.log");
        List<String> command = new ArrayList<>(List.of(java.toString(), "-Duser.home=" + home,
                "-Djava.awt.headless=true", "-cp", System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path")), mainClass));
        command.addAll(List.of(arguments));
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try (var stdin = child.getOutputStream()) {
            stdin.write(input.getBytes(StandardCharsets.UTF_8));
        }
        if (!child.waitFor(25, TimeUnit.SECONDS)) {
            child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError("Profile command timed out");
        }
        return new Result(child.exitValue(), Files.readString(log));
    }

    private record Result(int exitCode, String output) {
    }
}
