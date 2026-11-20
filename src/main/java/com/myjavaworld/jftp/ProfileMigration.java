package com.myjavaworld.jftp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

final class ProfileMigration {
    private static final String BACKUP_RECORD = "backup.properties";
    private static final String MISSING = "missing";
    private static final List<String> FILES = List.of(LegacyProfile.PREFERENCES, FavoritesManager.FILE_NAME,
            JFTPPreferences.SERVER_CERTIFICATE_STORE_NAME, JFTPPreferences.CLIENT_CERTIFICATE_STORE_NAME);

    private ProfileMigration() {
    }

    static int run(String[] args, InputStream in, PrintStream out, PrintStream error) {
        if (args.length < 2) {
            error.println("Usage: --migrate-profile <user-home> [--copy-server-store] [--copy-client-store]");
            error.println("       --restore-profile <user-home> <legacy|generation>");
            return 1;
        }
        try {
            Path profile = ProfileStorage.root(Path.of(args[1]));
            LegacyProfile.installFilter();
            if ("--restore-profile".equals(args[0])) {
                if (args.length != 3) {
                    throw new IOException("Choose legacy or a validated generation to restore.");
                }
                ProfileStorage.withLock(profile, () -> restore(profile, args[2]));
                out.println("Selected profile " + args[2]);
                return 0;
            }

            List<String> options = List.of(args).subList(2, args.length);
            if (options.stream().anyMatch(option -> !("--copy-server-store".equals(option)
                    || "--copy-client-store".equals(option)))) {
                throw new IOException("Unknown migration option. Use --copy-server-store or --copy-client-store.");
            }
            Plan plan = plan(profile, options.contains("--copy-server-store"), options.contains("--copy-client-store"));
            report(out, plan);
            out.println("Activate the verified copy? Enter yes to continue.");
            out.flush();
            String answer = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).readLine();
            if (answer == null || !"yes".equalsIgnoreCase(answer.strip())) {
                out.println("Migration cancelled.");
                return 0;
            }
            ProfileStorage.withLock(profile, () -> migrate(plan, out));
            return 0;
        } catch (IOException | InvalidPathException failure) {
            error.println(failure.getMessage());
            return 1;
        } catch (IllegalStateException failure) {
            error.println("Profile migration requires a fresh JVM where JFTP can install its legacy serialization filter.");
            return 1;
        }
    }

    private static Plan plan(Path profile, boolean copyServer, boolean copyClient) throws IOException {
        Path data = profile.resolve("data");
        Path prefs = data.resolve(LegacyProfile.PREFERENCES);
        String originalPreferences = fingerprint(prefs);
        LegacyProfile.Snapshot snapshot = LegacyProfile.read(data);
        Input preferences = new Input(prefs, LegacyProfile.PREFERENCES, originalPreferences, true);
        Path favoritePath = data.resolve(FavoritesManager.FILE_NAME);
        Input favorites = new Input(favoritePath, FavoritesManager.FILE_NAME, fingerprint(favoritePath), true);
        Input server = store(snapshot.server(), data, JFTPPreferences.SERVER_CERTIFICATE_STORE_NAME, copyServer);
        Input client = store(snapshot.client(), data, JFTPPreferences.CLIENT_CERTIFICATE_STORE_NAME, copyClient);
        List<Input> inputs = List.of(preferences, favorites, server, client);
        MessageDigest identity = sha256();
        for (Input input : inputs) {
            identity.update((input.name() + "\0" + input.source() + "\0" + input.hash() + "\0" + input.copy() + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        Plan plan = new Plan(profile, HexFormat.of().formatHex(identity.digest()), snapshot, inputs);
        verifySources(plan);
        return plan;
    }

    private static Input store(LegacyProfile.CertificateStore store, Path data, String name, boolean include)
            throws IOException {
        boolean copy = include || store.path().equals(data.resolve(name));
        String hash = fingerprint(store.path());
        if (include && MISSING.equals(hash)) {
            throw new IOException("Missing certificate store selected for copying " + store.path());
        }
        if (store.present() == MISSING.equals(hash)) {
            throw new IOException("Certificate store changed during inspection " + store.path());
        }
        return new Input(store.path(), name, hash, copy);
    }

    private static void report(PrintStream out, Plan plan) {
        out.println("Profile generation " + plan.generation());
        for (Input input : plan.inputs()) {
            if (!input.copy()) {
                out.println("Retain external store " + input.source() + " (" + input.hash() + ")");
            } else {
                out.println(input.source() + " (" + input.hash() + ") -> " + plan.data().resolve(input.name()));
                if (!MISSING.equals(input.hash())) {
                    out.println("  Backup " + plan.backup().resolve(input.name()));
                }
            }
        }
    }

    private static void migrate(Plan plan, PrintStream out) throws IOException {
        verifySources(plan);
        if (Files.notExists(plan.backup())) {
            stage(plan.backup(), staged -> {
                Properties record = new Properties();
                for (Input input : plan.inputs()) {
                    record.setProperty(input.name(), input.copy() ? input.hash() : MISSING);
                    record.setProperty(input.name() + ".source", input.source().toString());
                    if (input.copy() && !MISSING.equals(input.hash())) {
                        Files.copy(input.source(), staged.resolve(input.name()));
                    }
                }
                try (var output = Files.newOutputStream(staged.resolve(BACKUP_RECORD))) {
                    record.store(output, "JFTP verified legacy profile backup");
                }
                verifyBackup(staged);
            });
        }
        verifyBackup(plan.backup());
        compareBackupToPlan(plan);

        boolean existing = Files.exists(plan.data());
        if (!existing) {
            stage(plan.data(), staged -> {
                for (Input input : plan.inputs()) {
                    if (input.copy() && !MISSING.equals(input.hash())) {
                        Files.copy(plan.backup().resolve(input.name()), staged.resolve(input.name()));
                    }
                }
                JFTPPreferences preferences = plan.snapshot().preferences();
                Input server = plan.inputs().get(2);
                Input client = plan.inputs().get(3);
                preferences.setServerCertificateStore(server.copy() ? null : server.source().toString());
                preferences.setClientCertificateStore(client.copy() ? null : client.source().toString());
                try (var output = new ObjectOutputStream(Files.newOutputStream(staged.resolve(LegacyProfile.PREFERENCES)))) {
                    output.writeObject(preferences);
                }
                LegacyProfile.Snapshot reopened = LegacyProfile.read(staged);
                if (!plan.snapshot().server().entries().equals(reopened.server().entries())
                        || !plan.snapshot().client().entries().equals(reopened.client().entries())
                        || plan.snapshot().favorites().size() != reopened.favorites().size()
                        || !preferences.getLocale().equals(reopened.preferences().getLocale())) {
                    throw new IOException("The staged profile did not reopen with its original settings and entries.");
                }
                Files.writeString(staged.resolve(ProfileStorage.READY_MARKER), plan.generation() + "\n");
            });
        }
        verifySources(plan);
        restore(plan.profile(), plan.generation());
        out.println((existing ? "Reused verified generation " : "Activated verified generation ") + plan.generation());
    }

    private static void restore(Path profile, String generation) throws IOException {
        Path data = ProfileStorage.data(profile, generation);
        if (!ProfileStorage.LEGACY.equals(generation)) {
            Properties backup = verifyBackup(profile.resolve("backups").resolve(generation));
            for (String filename : FILES) {
                Path required = data.resolve(filename);
                if (!MISSING.equals(backup.getProperty(filename)) && !Files.isRegularFile(required)) {
                    throw new IOException("Incomplete profile generation, missing " + required);
                }
            }
        }
        LegacyProfile.read(data);
        ProfileStorage.select(profile, generation);
    }

    private static void verifySources(Plan plan) throws IOException {
        for (Input input : plan.inputs()) {
            if (!input.hash().equals(fingerprint(input.source()))) {
                throw new IOException("Source changed after inspection " + input.source());
            }
        }
    }

    private static void compareBackupToPlan(Plan plan) throws IOException {
        for (Input input : plan.inputs()) {
            String expected = input.copy() ? input.hash() : MISSING;
            Path backup = plan.backup().resolve(input.name());
            if (!expected.equals(fingerprint(backup))) {
                throw new IOException("Backup differs from the reviewed source " + backup);
            }
        }
    }

    private static Properties verifyBackup(Path backup) throws IOException {
        Path recordPath = backup.resolve(BACKUP_RECORD);
        if (!Files.isRegularFile(recordPath)) {
            throw new IOException("Missing profile recovery record " + recordPath);
        }
        Properties record = new Properties();
        try (InputStream input = Files.newInputStream(recordPath)) {
            record.load(input);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Malformed profile recovery record " + recordPath, malformed);
        }
        for (String name : FILES) {
            Path file = backup.resolve(name);
            if (!fingerprint(file).equals(record.getProperty(name))
                    || (LegacyProfile.PREFERENCES.equals(name) && !Files.isRegularFile(file))) {
                throw new IOException("Incomplete or modified profile backup " + file);
            }
        }
        return record;
    }

    private static void stage(Path destination, StageAction prepare) throws IOException {
        Path parent = Files.createDirectories(destination.getParent());
        Path staged = Files.createTempDirectory(parent, ".stage-");
        try {
            prepare.run(staged);
            Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (Files.isDirectory(staged)) {
                for (String file : FILES) {
                    Files.deleteIfExists(staged.resolve(file));
                }
                Files.deleteIfExists(staged.resolve(BACKUP_RECORD));
                Files.deleteIfExists(staged.resolve(ProfileStorage.READY_MARKER));
                Files.delete(staged);
            }
        }
    }

    private static String fingerprint(Path file) throws IOException {
        if (Files.notExists(file)) {
            return MISSING;
        }
        try (DigestInputStream input = new DigestInputStream(Files.newInputStream(file), sha256())) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(input.getMessageDigest().digest());
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @FunctionalInterface
    private interface StageAction {
        void run(Path directory) throws IOException;
    }

    private record Input(Path source, String name, String hash, boolean copy) {
    }

    private record Plan(Path profile, String generation, LegacyProfile.Snapshot snapshot, List<Input> inputs) {
        Path data() {
            return profile.resolve("generations").resolve(generation);
        }

        Path backup() {
            return profile.resolve("backups").resolve(generation);
        }
    }
}
