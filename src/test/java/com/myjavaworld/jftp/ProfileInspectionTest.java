package com.myjavaworld.jftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.SealedObject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProfileInspectionTest {
    private static final String PREFERENCES = "preferences.ser";
    private static final String FAVORITES = "favorites.ser";
    private static final String SERVER = "serverCertificates.jks";
    private static final String CLIENT = "clientCertificates.jks";

    @ParameterizedTest
    @ValueSource(strings = {"favorites-baseline-plaintext.ser", "favorites-baseline-sealed.ser"})
    void readsFrozenProfileAndBothStoresWithoutWriting(String favoriteFixture) throws Exception {
        try (TestDirectory fixture = new TestDirectory()) {
            Path source = Files.createDirectory(fixture.root.resolve("legacy data"));
            Path operator = Files.createDirectory(fixture.root.resolve("operator home"));
            copyFixture("preferences-baseline.ser", source.resolve(PREFERENCES));
            copyFixture(favoriteFixture, source.resolve(FAVORITES));
            createStores(fixture.root, source);
            List<Path> inputs = List.of(source.resolve(PREFERENCES), source.resolve(FAVORITES),
                    source.resolve(SERVER), source.resolve(CLIENT));
            List<String> before = hashes(inputs);

            Result result = inspect(operator, source);

            assertEquals(0, result.exitCode(), result.output());
            assertTrue(result.output().contains("Preferences locale zh-TW"), result.output());
            assertTrue(result.output().contains("Saved sites 1"), result.output());
            assertTrue(result.output().contains("trusted-server (trusted certificate)"), result.output());
            assertTrue(result.output().contains("loopback (key entry)"), result.output());
            assertFalse(result.output().contains("fixture-operator"));
            assertFalse(result.output().contains("synthetic-test-value"));
            assertEquals(before, hashes(inputs));
            assertFalse(Files.exists(operator.resolve(".jftp")));
        }
    }

    @Test
    void reportsMissingMalformedAndUnsupportedInputs() throws Exception {
        try (TestDirectory fixture = new TestDirectory()) {
            Path source = Files.createDirectory(fixture.root.resolve("legacy"));
            Path operator = Files.createDirectory(fixture.root.resolve("operator"));
            assertFailure(inspect(operator, source), "Missing", PREFERENCES);
            copyFixture("preferences-baseline.ser", source.resolve(PREFERENCES));
            Result empty = inspect(operator, source);
            assertEquals(0, empty.exitCode(), empty.output());
            assertTrue(empty.output().contains("Saved sites 0"), empty.output());
            assertFalse(Files.exists(source.resolve(FAVORITES)));
            Path favorites = Files.write(source.resolve(FAVORITES), new byte[] {1, 2, 3, 4});
            assertFailure(inspect(operator, source), "Malformed", FAVORITES);
            try (var output = new ObjectOutputStream(Files.newOutputStream(favorites))) {
                output.writeObject(new java.util.Date(0));
            }
            assertFailure(inspect(operator, source), "Unsupported", FAVORITES);
            try (var output = new ObjectOutputStream(Files.newOutputStream(favorites))) {
                output.writeObject(new SealedObject(new java.util.Date(0),
                        FavoritesManager.getCipher(Cipher.ENCRYPT_MODE)));
            }
            assertFailure(inspect(operator, source), "Unsupported serialization", FAVORITES);
            assertFalse(Files.exists(operator.resolve(".jftp")));
        }
    }

    @Test
    void reportsWrongStorePasswordAndLeavesStoreUntouched() throws Exception {
        try (TestDirectory fixture = new TestDirectory()) {
            Path source = Files.createDirectory(fixture.root.resolve("legacy"));
            Path operator = Files.createDirectory(fixture.root.resolve("operator"));
            copyFixture("preferences-baseline.ser", source.resolve(PREFERENCES));
            Path store = source.resolve(SERVER);
            KeyStore empty = KeyStore.getInstance("JKS");
            empty.load(null, null);
            try (var output = Files.newOutputStream(store)) {
                empty.store(output, "different-test-password".toCharArray());
            }
            List<String> before = hashes(List.of(store));
            assertFailure(inspect(operator, source), "saved password", SERVER);
            assertEquals(before, hashes(List.of(store)));
        }
    }

    static void createStores(Path root, Path source) throws Exception {
        new LoopbackCertificate(root);
        KeyStore original = KeyStore.getInstance("JKS");
        try (var input = Files.newInputStream(root.resolve("loopback.jks"))) {
            original.load(input, "synthetic-loopback-store".toCharArray());
        }
        KeyStore server = KeyStore.getInstance("JKS");
        server.load(null, null);
        server.setCertificateEntry("trusted-server", original.getCertificate("loopback"));
        try (var output = Files.newOutputStream(source.resolve(SERVER))) {
            server.store(output, "changeit".toCharArray());
        }
        original.setKeyEntry("loopback", original.getKey("loopback", "synthetic-loopback-store".toCharArray()),
                "changeit".toCharArray(), original.getCertificateChain("loopback"));
        try (var output = Files.newOutputStream(source.resolve(CLIENT))) {
            original.store(output, "changeit".toCharArray());
        }
    }

    private static Result inspect(Path operator, Path source) throws Exception {
        Path exe = Path.of(System.getProperty("java.home"), "bin", "java");
        if (!Files.exists(exe)) {
            exe = exe.resolveSibling("java.exe");
        }
        Path log = operator.resolve("inspection.log");
        Process child = new ProcessBuilder(exe.toString(), "-Duser.home=" + operator,
                "-Djava.awt.headless=true", "-cp", System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path")), JFTPApplication.class.getName(),
                "--inspect-profile", source.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!child.waitFor(25, TimeUnit.SECONDS)) {
            child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError("Profile inspection timed out");
        }
        return new Result(child.exitValue(), Files.readString(log));
    }

    private static void assertFailure(Result result, String status, String name) {
        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.output().contains(status), result.output());
        assertTrue(result.output().contains(name), result.output());
    }

    static void copyFixture(String name, Path path) throws Exception {
        try (var input = ProfileInspectionTest.class.getResourceAsStream(name)) {
            Files.copy(input, path);
        }
    }

    static List<String> hashes(List<Path> files) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        var hashes = new java.util.ArrayList<String>();
        for (Path path : files) {
            hashes.add(HexFormat.of().formatHex(sha.digest(Files.readAllBytes(path))));
        }
        return hashes;
    }

    private record Result(int exitCode, String output) {
    }
}
