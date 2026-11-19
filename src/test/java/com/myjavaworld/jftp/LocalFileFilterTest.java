package com.myjavaworld.jftp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Date;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.myjavaworld.util.DateFilter;
import com.myjavaworld.util.RegexFilter;
import junit.framework.TestCase;

public class LocalFileFilterTest extends TestCase {
    public void testInclusionAndExclusionKeepDirectoriesNavigable() throws Exception {
        try (TestDirectory directory = new TestDirectory()) {
            long cutoff = 1_700_000_000_000L;
            Path report = Files.createFile(directory.root.resolve("Report.TXT"));
            Files.setLastModifiedTime(report, FileTime.fromMillis(cutoff));
            Path oldReport = Files.createFile(directory.root.resolve("old.txt"));
            Files.setLastModifiedTime(oldReport, FileTime.fromMillis(cutoff - 60_000));
            Path image = Files.createFile(directory.root.resolve("image.bin"));
            Files.setLastModifiedTime(image, FileTime.fromMillis(cutoff));
            Files.createDirectory(directory.root.resolve("subfolder"));

            LocalFile folder = new LocalFile(directory.root.toFile());
            RegexFilter name = new RegexFilter(".*\\.txt", Pattern.CASE_INSENSITIVE);
            DateFilter modified = new DateFilter(new Date(cutoff), null);

            assertEquals(names("Report.TXT", "subfolder"),
                    listNames(folder, new LocalFileFilter(name, modified, true, false)));
            assertEquals(names("old.txt", "image.bin", "subfolder"),
                    listNames(folder, new LocalFileFilter(name, modified, true, true)));
        }
    }

    public void testHiddenAttributeParticipatesInFilter() throws Exception {
        try (TestDirectory directory = new TestDirectory()) {
            Path hidden = Files.createFile(directory.root.resolve(".draft.txt"));
            if (!Files.isHidden(hidden)) {
                Files.getFileAttributeView(hidden, DosFileAttributeView.class).setHidden(true);
            }
            LocalFile file = new LocalFile(hidden.toFile());
            assertTrue(file.isHidden());
            assertTrue(new LocalFileFilter().accept(file));
            assertFalse(new LocalFileFilter(null, null, false, false).accept(file));
            assertTrue(new LocalFileFilter(null, null, false, true).accept(file));
        }
    }

    private Set<String> listNames(LocalFile folder, LocalFileFilter filter) {
        Set<String> names = new TreeSet<>();
        for (LocalFile file : folder.list(filter)) {
            names.add(file.getName());
        }
        return names;
    }

    private Set<String> names(String... names) {
        return new TreeSet<>(Arrays.asList(names));
    }
}
