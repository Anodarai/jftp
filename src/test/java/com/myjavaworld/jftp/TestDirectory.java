package com.myjavaworld.jftp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Iterator;
import java.util.stream.Stream;

final class TestDirectory implements AutoCloseable {
    final Path root;

    TestDirectory() throws IOException {
        root = Files.createTempDirectory("jftp-test-");
    }

    @Override
    public void close() throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            Iterator<Path> entries = paths.sorted(Comparator.reverseOrder()).iterator();
            while (entries.hasNext()) {
                Files.delete(entries.next());
            }
        }
    }
}
