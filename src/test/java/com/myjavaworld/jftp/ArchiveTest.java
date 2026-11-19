package com.myjavaworld.jftp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import com.myjavaworld.util.RegexFilter;
import com.myjavaworld.zip.Unzip;
import com.myjavaworld.zip.Zip;
import junit.framework.TestCase;

public class ArchiveTest extends TestCase {
    public void testFilteredZipKeepsRelativePathsAndRestoresBytes() throws Exception {
        try (TestDirectory directory = new TestDirectory()) {
            Path input = Files.createDirectory(directory.root.resolve("input"));
            Path nested = Files.createDirectories(input.resolve("selected/nested"));
            Files.createDirectory(nested.resolve("empty"));
            byte[] contents = {0, 10, 13, -1, 42};
            Files.write(nested.resolve("data.bin"), contents);
            Files.write(nested.resolve("excluded.txt"), new byte[] {1});

            Path archive = directory.root.resolve("selected.zip");
            Zip zip = new Zip(archive.toFile());
            zip.setRelativeTo(input.toFile());
            zip.setFilter(new LocalFileFilter(new RegexFilter(".*\\.bin", 0), null));
            zip.open();
            try {
                zip.addEntry(input.resolve("selected").toFile());
            } finally {
                zip.close();
            }
            try (ZipFile entries = new ZipFile(archive.toFile())) {
                assertNotNull(entries.getEntry("selected/nested/data.bin"));
                ZipEntry empty = entries.getEntry("selected/nested/empty/");
                assertNotNull(empty);
                assertTrue(empty.isDirectory());
                assertNull(entries.getEntry("selected/nested/excluded.txt"));
            }

            Path output = Files.createDirectory(directory.root.resolve("output"));
            Unzip unzip = new Unzip(archive.toFile());
            unzip.setTargetDirectory(output.toFile());
            unzip.open();
            try {
                unzip.unzip();
            } finally {
                unzip.close();
            }
            assertTrue(Arrays.equals(contents,
                    Files.readAllBytes(output.resolve("selected/nested/data.bin"))));
            assertTrue(Files.isDirectory(output.resolve("selected/nested/empty")));
            assertFalse(Files.exists(output.resolve("selected/nested/excluded.txt")));
        }
    }

    public void testInvalidArchiveLeavesDestinationEmpty() throws Exception {
        try (TestDirectory directory = new TestDirectory()) {
            Path archive = Files.write(directory.root.resolve("invalid.zip"), new byte[] {1, 2, 3});
            Path output = Files.createDirectory(directory.root.resolve("output"));
            Unzip unzip = new Unzip(archive.toFile());
            unzip.setTargetDirectory(output.toFile());
            try {
                unzip.open();
                unzip.close();
                fail("An invalid archive should fail when opened");
            } catch (ZipException expected) {
                assertEquals(0, output.toFile().list().length);
            }
        }
    }
}
