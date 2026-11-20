package com.myjavaworld.jftp;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public final class ProfileStorage {
    static final String LEGACY = "legacy";
    static final String ACTIVE_MARKER = "active-profile";
    static final String READY_MARKER = "profile-ready";

    private ProfileStorage() {
    }

    static Path root(Path userHome) {
        return userHome.toAbsolutePath().normalize().resolve(".jftp");
    }

    static Path desktopData(Path userHome) {
        try {
            return activeData(root(userHome));
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot select the active JFTP profile", failure);
        }
    }

    static Path activeData(Path profile) throws IOException {
        Path marker = profile.resolve(ACTIVE_MARKER);
        if (Files.notExists(marker)) {
            return data(profile, LEGACY);
        }
        if (!Files.isRegularFile(marker) || Files.size(marker) > 128) {
            throw new IOException("Invalid active profile marker " + marker);
        }
        return data(profile, Files.readString(marker).strip());
    }

    static Path data(Path profile, String generation) throws IOException {
        if (LEGACY.equals(generation)) {
            return profile.resolve("data");
        }
        if (!generation.matches("[0-9a-f]{64}")) {
            throw new IOException("Invalid profile generation selected in " + profile);
        }
        Path data = profile.resolve("generations").resolve(generation);
        Path ready = data.resolve(READY_MARKER);
        if (!Files.isRegularFile(ready) || Files.size(ready) > 128
                || !generation.equals(Files.readString(ready).strip())) {
            throw new IOException("Profile generation is missing or incomplete " + data);
        }
        return data;
    }

    static void select(Path profile, String generation) throws IOException {
        Path marker = profile.resolve(ACTIVE_MARKER);
        try {
            replace(marker, output -> output.write((generation + "\n").getBytes(StandardCharsets.US_ASCII)));
        } catch (IOException failed) {
            throw new IOException("Could not atomically replace " + marker + ". The previous profile remains selected.",
                    failed);
        }
    }

    static void withLock(Path profile, LockedAction action) throws IOException {
        try (LockedProfile ignored = lock(profile)) {
            action.run();
        }
    }

    private static LockedProfile lock(Path profile) throws IOException {
        FileChannel channel = FileChannel.open(profile.resolve("profile.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new IOException("Another operation is writing the JFTP profile " + profile);
            }
            return new LockedProfile(channel, lock);
        } catch (OverlappingFileLockException busy) {
            channel.close();
            throw new IOException("Another operation is writing the JFTP profile " + profile, busy);
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    static LockedProfile lockActive() throws IOException {
        Path root = root(JFTP.USER_HOME.toPath());
        LockedProfile lock = lock(root);
        try {
            if (!activeData(root).equals(JFTP.DATA_HOME.toPath())) {
                throw new IOException("The active JFTP profile changed. Reopen JFTP before saving.");
            }
            return lock;
        } catch (IOException | RuntimeException failure) {
            lock.close();
            throw failure;
        }
    }

    public static void writeActive(Path file, OutputWriter write) throws IOException {
        try (LockedProfile ignored = lockActive()) {
            Path target = file.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(target)) {
                target = target.toRealPath();
            }
            replace(target, write);
        }
    }

    static void replace(Path file, OutputWriter write) throws IOException {
        Path staged = Files.createTempFile(file.getParent(), ".jftp-write-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(staged)) {
                write.write(output);
            }
            try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    @FunctionalInterface
    interface LockedAction {
        void run() throws IOException;
    }

    @FunctionalInterface
    public interface OutputWriter {
        void write(OutputStream output) throws IOException;
    }

    record LockedProfile(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
