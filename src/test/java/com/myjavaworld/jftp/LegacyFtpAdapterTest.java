package com.myjavaworld.jftp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.myjavaworld.ftp.FTPConstants;

class LegacyFtpAdapterTest {
    @Test
    void snapshotsConnectionSettingsAndTransfersThroughTheOwnedTypes() throws Exception {
        try (TestDirectory fixture = new TestDirectory();
                LegacyFtpTest.LoopbackServer server = LegacyFtpTest.startServer(fixture.root)) {
            RemoteHost site = new RemoteHost("127.0.0.1", server.port, "test-operator",
                    "synthetic-ftp-password", "");
            var endpoint = LegacyFtpAdapter.Endpoint.capture(site);
            site.setPort(0);
            site.setPassword("changed-after-capture");
            List<LegacyFtpAdapter.Event> events = new ArrayList<>();
            try (var transport = LegacyFtpAdapter.create(endpoint, 5_000, 8192, null, events::add)) {
                transport.connect();
                transport.setWorkingDirectory(transport.createRemoteFile("/incoming"));
                RemoteEntry directory = transport.getWorkingDirectory();
                assertEquals("/incoming", directory.getPath());
                byte[] bytes = {0, 13, 10, -1, 26, 42};
                var source = Files.write(fixture.root.resolve("source.bin"), bytes);
                RemoteEntry destination = transport.createRemoteFile(directory, "typed.bin");
                transport.upload(source.toFile(), destination, FTPConstants.TYPE_BINARY);
                RemoteEntry[] listing = transport.list(file -> file.getName().endsWith(".bin"));
                assertEquals(1, listing.length);
                assertEquals("typed.bin", listing[0].getName());
                assertEquals(bytes.length, listing[0].getSize());
                var received = fixture.root.resolve("received.bin");
                transport.download(listing[0], received.toFile(), FTPConstants.TYPE_BINARY);
                assertArrayEquals(bytes, Files.readAllBytes(received));
                transport.disconnect();
                assertFalse(transport.isConnected());
            }
            assertTrue(events.stream().anyMatch(event -> event.kind() == LegacyFtpAdapter.EventKind.CONNECTED));
            assertTrue(events.stream().anyMatch(event -> event.kind() == LegacyFtpAdapter.EventKind.COMMAND
                    && event.message().startsWith("PASS ")));
            assertFalse(events.stream().anyMatch(event -> event.message().contains("synthetic-ftp-password")));
        }
    }

    @Test
    void keepsAProtocolReplyDistinguishableFromALinkFailure() throws Exception {
        try (TestDirectory fixture = new TestDirectory();
                LegacyFtpTest.LoopbackServer server = LegacyFtpTest.startServer(fixture.root)) {
            RemoteHost site = new RemoteHost("127.0.0.1", server.port, "test-operator", "invalid-password", "");
            try (var transport = LegacyFtpAdapter.create(LegacyFtpAdapter.Endpoint.capture(site),
                    5_000, 8192, null, event -> { })) {
                var failure = assertThrows(LegacyFtpAdapter.ReplyFailure.class, transport::connect);
                assertEquals("530", failure.getErrorCode());
            }
        }
    }
}
