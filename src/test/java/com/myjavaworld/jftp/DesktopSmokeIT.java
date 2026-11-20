package com.myjavaworld.jftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

class DesktopSmokeIT {
    @Test
    void exercisesTheExtractedWindowsApplication() throws Exception {
        assertTrue(System.getProperty("os.name").startsWith("Windows"),
                "The desktop-qa profile requires an interactive Windows desktop");
        Path evidence = Path.of(System.getProperty("jftp.desktop.logDirectory")).toAbsolutePath();
        Files.createDirectories(evidence);
        Files.deleteIfExists(evidence.resolve("failure.png"));
        Files.deleteIfExists(evidence.resolve("session.log"));
        try (TestDirectory test = new TestDirectory()) {
            Path archive = Path.of(System.getProperty("jftp.desktop.archive"));
            String appName = System.getProperty("jftp.desktop.appName");
            Path extracted = Files.createDirectory(test.root.resolve("extracted package"));
            extract(archive, extracted);
            Path appJar = extracted.resolve(appName).resolve(appName + ".jar");
            assertTrue(Files.isRegularFile(appJar), "The ZIP must include its root application JAR");
            Set<String> packagedJars = packagedJars(appJar);

            String testClasspath = System.getProperty("surefire.test.class.path",
                    System.getProperty("java.class.path"));
            String childClasspath = appJar + File.pathSeparator
                    + Arrays.stream(testClasspath.split(Pattern.quote(File.pathSeparator)))
                            .filter(entry -> !Path.of(entry).endsWith(Path.of("target", "classes")))
                            .filter(entry -> !packagedJars.contains(Path.of(entry).getFileName().toString()))
                            .collect(Collectors.joining(File.pathSeparator));
            Path home = Files.createDirectory(test.root.resolve("synthetic home"));
            Path rawLog = test.root.resolve("desktop.log");
            Process desktop = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                    "-Duser.home=" + home, "-cp", childClasspath, DesktopSmoke.class.getName(),
                    test.root.toString(), evidence.toString())
                    .directory(test.root.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(rawLog.toFile())
                    .start();
            boolean finished = desktop.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                desktop.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
            Path log = evidence.resolve("desktop.log");
            Files.writeString(log, DesktopSmoke.redact(Files.readString(rawLog)));
            if (!finished) {
                fail("Native desktop smoke timed out. See " + log);
            }
            assertEquals(0, desktop.exitValue(), "See " + log);
            assertTrue(Files.isRegularFile(test.root.resolve("complete")),
                    "The app exited before all desktop checks passed. See " + log);
        }
    }

    private static Set<String> packagedJars(Path appJar) throws Exception {
        Set<String> names = new HashSet<>();
        names.add(appJar.getFileName().toString());
        try (JarFile jar = new JarFile(appJar.toFile())) {
            String manifestClasspath = jar.getManifest().getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            for (String entry : manifestClasspath.split(" +")) {
                Path dependency = appJar.getParent().resolve(entry);
                assertTrue(Files.isRegularFile(dependency), "Missing packaged runtime library " + entry);
                names.add(dependency.getFileName().toString());
            }
        }
        return names;
    }

    private static void extract(Path archive, Path destination) throws Exception {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                Path path = destination.resolve(entry.getName()).normalize();
                if (!path.startsWith(destination)) {
                    throw new IllegalArgumentException("Entry escapes the package directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(path);
                } else {
                    Files.createDirectories(path.getParent());
                    try (var contents = zip.getInputStream(entry)) {
                        Files.copy(contents, path);
                    }
                }
            }
        }
    }
}
