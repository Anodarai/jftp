package com.myjavaworld.jftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.myjavaworld.ftp.ConnectionException;
import com.myjavaworld.ftp.ControlConnectionEvent;
import com.myjavaworld.ftp.ControlConnectionListener;
import com.myjavaworld.ftp.DefaultFTPClient;
import com.myjavaworld.ftp.DefaultListParser;
import com.myjavaworld.ftp.FTPConstants;

class LegacyFtpsTest {
    private enum Mode {
        PLAIN(FTPConstants.USE_NO_SSL, false),
        FALLBACK(FTPConstants.USE_SSL_IF_AVAILABLE, false),
        OPPORTUNISTIC(FTPConstants.USE_SSL_IF_AVAILABLE, true),
        EXPLICIT(FTPConstants.USE_EXPLICIT_SSL, true),
        IMPLICIT(FTPConstants.USE_IMPLICIT_SSL, true);

        final int usage;
        final boolean tls;

        Mode(int usage, boolean tls) {
            this.usage = usage;
            this.tls = tls;
        }
    }

    @TempDir
    static Path certificateDirectory;
    private static LoopbackCertificate certificate;

    @BeforeAll
    static void generateSyntheticCertificate() throws Exception {
        certificate = new LoopbackCertificate(certificateDirectory);
    }

    private static Stream<Arguments> transportModes() {
        return Arrays.stream(Mode.values()).flatMap(mode -> Stream.of(
                Arguments.of(mode, "active", false), Arguments.of(mode, "passive", true)));
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("transportModes")
    void transfersWithDefaultSecurityPolicy(Mode mode, String dataPath, boolean passive) throws Exception {
        try (TestDirectory directory = new TestDirectory();
                LegacyFtpTest.LoopbackServer server = LegacyFtpTest.startServer(directory.root,
                        mode.tls ? certificate.serverConfiguration() : null, mode == Mode.IMPLICIT)) {
            DefaultFTPClient client = new DefaultFTPClient();
            DefaultListParser parser = new DefaultListParser();
            client.setListParser(parser);
            client.setTimeout(5_000);
            client.setPassive(passive);
            client.setSSLUsage(mode.usage);
            client.setDataChannelUnencrypted(false);
            if (mode.usage != FTPConstants.USE_NO_SSL) {
                client.setSSLContext(certificate.trustedClientContext());
            }
            List<String> commands = new ArrayList<>();
            client.addControlConnectionListener(new ControlConnectionListener() {
                @Override
                public void commandSent(ControlConnectionEvent event) {
                    String command = event.getMessage().strip();
                    if (command.startsWith("AUTH ") || command.startsWith("PBSZ ") || command.startsWith("PROT ")
                            || command.startsWith("PORT ") || command.equals("PASV")) {
                        commands.add(command);
                    }
                }

                @Override
                public void replyReceived(ControlConnectionEvent event) {
                }

            });
            try {
                LegacyFtpTest.checkBinaryTransfer(directory.root, server.port, client, parser);
            } finally {
                client.close();
            }
            assertEquals(mode == Mode.FALLBACK || mode == Mode.OPPORTUNISTIC || mode == Mode.EXPLICIT,
                    commands.contains("AUTH SSL"), commands::toString);
            assertEquals(mode.tls, commands.contains("PBSZ 0"), commands::toString);
            assertEquals(mode.tls, commands.contains("PROT P"), commands::toString);
            assertTrue(passive ? commands.contains("PASV") : commands.stream().anyMatch(value -> value.startsWith("PORT ")),
                    () -> dataPath + ": " + commands);
        }
    }

    @Test
    void reportsConnectionRefusalOnLoopback() throws Exception {
        try (Socket reserved = new Socket()) {
            reserved.bind(new InetSocketAddress("127.0.0.1", 0));
            DefaultFTPClient client = new DefaultFTPClient();
            client.setTimeout(5_000);
            try {
                assertThrows(ConnectionException.class, () -> client.connect("127.0.0.1", reserved.getLocalPort()));
                assertFalse(client.isConnected());
            } finally {
                client.close();
            }
        }
    }
}
