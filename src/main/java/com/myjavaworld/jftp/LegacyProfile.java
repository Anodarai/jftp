package com.myjavaworld.jftp;

import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.UnrecoverableKeyException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.SealedObject;

final class LegacyProfile {
    static final String PREFERENCES = "preferences.ser";
    private static final long MAX_SERIALIZED_BYTES = 16 * 1024 * 1024;

    private LegacyProfile() {
    }

    static int inspect(String[] args, PrintStream out, PrintStream error) {
        if (args.length != 2) {
            error.println("Usage: java -jar jftp.jar --inspect-profile <data-directory>");
            return 1;
        }
        try {
            installFilter();
            Path data = Path.of(args[1]).toAbsolutePath().normalize();
            Snapshot profile = read(data);
            out.println("Preferences locale " + profile.preferences().getLocale().toLanguageTag());
            out.println("Saved sites " + profile.favorites().size()
                    + (Files.notExists(data.resolve(FavoritesManager.FILE_NAME)) ? " (favorites file missing)" : ""));
            reportStore(out, "Server certificate store", profile.server());
            reportStore(out, "Client certificate store", profile.client());
            return 0;
        } catch (IOException | InvalidPathException failure) {
            error.println(failure.getMessage());
            return 1;
        } catch (IllegalStateException failure) {
            error.println("Profile inspection requires a fresh JVM where JFTP can install its legacy serialization filter.");
            return 1;
        }
    }

    // The filter factory also covers the stream opened within SealedObject.
    static void installFilter() {
        ObjectInputFilter legacy = ObjectInputFilter.Config.createFilter(
                "maxbytes=" + MAX_SERIALIZED_BYTES + ";maxdepth=24;maxrefs=100000;maxarray=4194304;"
                + String.join(";",
                        JFTPPreferences.class.getName(), RemoteHost.class.getName(), Favorite.class.getName(),
                        SealedObject.class.getName(), "java.awt.Rectangle", "java.lang.Boolean", "java.lang.Integer",
                        "java.lang.Number", "java.lang.Object", "java.lang.String", "java.util.Locale",
                        "java.util.TreeMap", "java.util.HashMap", "java.util.Map$Entry", "java.util.ArrayList",
                        "java.util.Arrays$ArrayList", "java.util.LinkedList", "java.util.Vector", "!*"));
        ObjectInputFilter.Config.setSerialFilterFactory((current, requested) -> {
            ObjectInputFilter filter = current == null ? legacy : current;
            return requested == null ? filter : ObjectInputFilter.merge(filter, requested);
        });
    }

    static Snapshot read(Path data) throws IOException {
        Path prefsFile = data.resolve(PREFERENCES);
        if (!(readSerialized(prefsFile, false) instanceof JFTPPreferences preferences)
                || preferences.getLocale() == null) {
            throw new IOException("Unsupported preferences in " + prefsFile);
        }
        List<RemoteHost> favorites = readFavorites(data.resolve(FavoritesManager.FILE_NAME));
        CertificateStore server = readStore(preferences.getServerCertificateStore(data.toFile()),
                preferences.getServerCertificateStorePassword());
        CertificateStore client = readStore(preferences.getClientCertificateStore(data.toFile()),
                preferences.getClientCertificateStorePassword());
        return new Snapshot(preferences, favorites, server, client);
    }

    private static List<RemoteHost> readFavorites(Path file) throws IOException {
        long bytes;
        try {
            bytes = Files.size(file);
        } catch (NoSuchFileException missing) {
            return List.of();
        } catch (IOException failure) {
            throw new IOException("Unreadable profile file " + file);
        }
        if (bytes == 0 && Files.isRegularFile(file)) {
            return List.of();
        }
        if (!(readSerialized(file, true) instanceof List<?> sites)) {
            throw new IOException("Unsupported favorites in " + file);
        }
        List<RemoteHost> result = new ArrayList<>(sites.size());
        for (Object site : sites) {
            if (!(site instanceof RemoteHost host)) {
                throw new IOException("Unsupported saved site in " + file);
            }
            result.add(host);
        }
        return List.copyOf(result);
    }

    private static Object readSerialized(Path file, boolean allowSealed) throws IOException {
        try (InputStream stream = open(file, MAX_SERIALIZED_BYTES)) {
            try (ObjectInputStream input = new ObjectInputStream(stream)) {
                Object value = input.readObject();
                if (allowSealed && value instanceof SealedObject sealed) {
                    Cipher cipher = FavoritesManager.getCipher(Cipher.DECRYPT_MODE);
                    if (cipher == null) {
                        throw new InvalidClassException("Legacy AES cipher unavailable");
                    }
                    return sealed.getObject(cipher);
                }
                return value;
            } catch (InvalidClassException | ClassNotFoundException unsupported) {
                throw new IOException("Unsupported serialization in " + file);
            } catch (IOException | GeneralSecurityException | RuntimeException malformed) {
                throw new IOException("Malformed serialized profile file " + file);
            }
        }
    }

    private static InputStream open(Path file, long maximumBytes) throws IOException {
        long bytes;
        try {
            bytes = Files.size(file);
        } catch (NoSuchFileException missing) {
            throw new IOException("Missing profile file " + file);
        } catch (IOException failure) {
            throw new IOException("Unreadable profile file " + file);
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException("Unreadable profile file " + file);
        }
        if (bytes > maximumBytes) {
            throw new IOException("Unsupported profile file size for " + file);
        }
        try {
            return Files.newInputStream(file);
        } catch (IOException failure) {
            throw new IOException("Unreadable profile file " + file);
        }
    }

    private static CertificateStore readStore(String location, char[] password) throws IOException {
        Path file = Path.of(location).toAbsolutePath().normalize();
        if (Files.notExists(file)) {
            return new CertificateStore(file, false, List.of());
        }
        try (InputStream input = open(file, Long.MAX_VALUE)) {
            KeyStore store = KeyStore.getInstance("JKS");
            try {
                store.load(input, password);
            } catch (IOException invalid) {
                if (invalid.getCause() instanceof UnrecoverableKeyException) {
                    throw new IOException("Cannot read certificate store with its saved password " + file);
                }
                throw new IOException("Malformed certificate store " + file);
            }
            List<StoreEntry> entries = new ArrayList<>();
            List<String> aliases = Collections.list(store.aliases());
            Collections.sort(aliases);
            for (String alias : aliases) {
                entries.add(new StoreEntry(alias, store.isKeyEntry(alias)));
            }
            return new CertificateStore(file, true, List.copyOf(entries));
        } catch (GeneralSecurityException invalid) {
            throw new IOException("Unsupported certificate store " + file);
        }
    }

    private static void reportStore(PrintStream out, String label, CertificateStore store) {
        out.println(label + " " + store.path() + (store.present() ? "" : " (missing)"));
        for (StoreEntry entry : store.entries()) {
            out.println("  " + entry.alias() + (entry.key() ? " (key entry)" : " (trusted certificate)"));
        }
    }

    record Snapshot(JFTPPreferences preferences, List<RemoteHost> favorites,
            CertificateStore server, CertificateStore client) {
    }

    record CertificateStore(Path path, boolean present, List<StoreEntry> entries) {
    }

    record StoreEntry(String alias, boolean key) {
    }
}
