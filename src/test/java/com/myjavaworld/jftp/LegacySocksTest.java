package com.myjavaworld.jftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.myjavaworld.ftp.ConnectionException;
import com.myjavaworld.ftp.DefaultFTPClient;
import com.myjavaworld.ftp.DefaultListParser;

class LegacySocksTest {
    private static final String LOOPBACK = "127.0.0.1";
    private static final String PROXY_USER = "loopback-proxy";
    private static final String PROXY_PASSWORD = "synthetic-proxy-password";

    enum Scenario {
        ACCEPTED, AUTHENTICATION_REFUSED, CONNECTION_REFUSED
    }

    @ParameterizedTest
    @EnumSource(Scenario.class)
    void qualifiesProcessWideSocksPreferences(Scenario scenario) throws Exception {
        try (TestDirectory test = new TestDirectory()) {
            Path home = Files.createDirectory(test.root.resolve("synthetic home"));
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            if (!Files.isRegularFile(java)) {
                java = java.resolveSibling("java.exe");
            }
            Path log = test.root.resolve("socks.log");
            Process child = new ProcessBuilder(java.toString(), "-Duser.home=" + home, "-Djava.awt.headless=true",
                    "-DsocksNonProxyHosts=", "-cp", System.getProperty("surefire.test.class.path",
                    System.getProperty("java.class.path")), LegacySocksTest.class.getName(),
                    test.root.toString(), scenario.name())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!child.waitFor(25, TimeUnit.SECONDS)) {
                child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                throw new AssertionError("The isolated SOCKS qualification timed out");
            }
            assertEquals(0, child.exitValue(), Files.readString(log));
        }
    }

    public static void main(String[] args) throws Exception {
        Scenario scenario = Scenario.valueOf(args[1]);
        try (LegacyFtpTest.LoopbackServer ftp = LegacyFtpTest.startServer(Path.of(args[0]));
                ServerSocket proxy = new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))) {
            proxy.setSoTimeout(5_000);
            var observed = new CompletableFuture<Scenario>();
            Thread.startVirtualThread(() -> {
                try {
                    observed.complete(proxyConnection(proxy, ftp.port, scenario));
                } catch (Throwable failure) {
                    observed.completeExceptionally(failure);
                }
            });
            JFTP.prefs.setUseProxy(true);
            JFTP.prefs.setProxyHost(LOOPBACK);
            JFTP.prefs.setProxyPort(proxy.getLocalPort());
            JFTP.prefs.setProxyUser(PROXY_USER);
            JFTP.prefs.setProxyPassword((scenario == Scenario.AUTHENTICATION_REFUSED
                    ? "invalid-fixture-password" : PROXY_PASSWORD).toCharArray());
            JFTPUtil.updateProxySettings();

            DefaultFTPClient client = new DefaultFTPClient();
            client.setListParser(new DefaultListParser());
            client.setTimeout(5_000);
            try {
                if (scenario == Scenario.ACCEPTED) {
                    client.connect(LOOPBACK, ftp.port);
                    client.login("test-operator", "synthetic-ftp-password");
                    assertTrue(client.isLoggedIn());
                    assertEquals("/", client.getWorkingDirectory().getPath());
                    client.disconnect();
                } else {
                    assertThrows(ConnectionException.class, () -> client.connect(LOOPBACK, ftp.port));
                    assertFalse(client.isConnected());
                }
            } finally {
                client.close();
            }
            assertEquals(scenario, observed.get(5, TimeUnit.SECONDS));
        }
    }

    private static Scenario proxyConnection(ServerSocket proxy, int ftpPort, Scenario scenario) throws IOException {
        try (Socket client = proxy.accept()) {
            client.setSoTimeout(5_000);
            var input = new DataInputStream(client.getInputStream());
            var reply = client.getOutputStream();
            assertEquals(5, input.readUnsignedByte(), "SOCKS version");
            byte[] methods = input.readNBytes(input.readUnsignedByte());
            assertTrue(IntStream.range(0, methods.length).anyMatch(index -> methods[index] == 2),
                    "Client must offer user/password authentication");
            reply.write(new byte[] {5, 2});

            assertEquals(1, input.readUnsignedByte(), "Username/password subnegotiation version");
            byte[] user = input.readNBytes(input.readUnsignedByte());
            byte[] password = input.readNBytes(input.readUnsignedByte());
            boolean accepted = Arrays.equals(user, PROXY_USER.getBytes(StandardCharsets.ISO_8859_1))
                    && Arrays.equals(password, PROXY_PASSWORD.getBytes(StandardCharsets.ISO_8859_1));
            reply.write(new byte[] {1, (byte) (accepted ? 0 : 1)});
            if (!accepted) {
                return Scenario.AUTHENTICATION_REFUSED;
            }

            assertEquals(5, input.readUnsignedByte(), "CONNECT SOCKS version");
            assertEquals(1, input.readUnsignedByte(), "CONNECT command");
            assertEquals(0, input.readUnsignedByte(), "Reserved CONNECT byte");
            int addressType = input.readUnsignedByte();
            if (addressType == 1) {
                assertTrue(Arrays.equals(new byte[] {127, 0, 0, 1}, input.readNBytes(4)), "FTP destination address");
            } else {
                assertEquals(3, addressType, "CONNECT address type");
                assertEquals(LOOPBACK, new String(input.readNBytes(input.readUnsignedByte()), StandardCharsets.US_ASCII));
            }
            assertEquals(ftpPort, input.readUnsignedShort(), "FTP destination port");
            if (scenario == Scenario.CONNECTION_REFUSED) {
                reply.write(new byte[] {5, 5, 0, 1, 127, 0, 0, 1, 0, 0});
                return Scenario.CONNECTION_REFUSED;
            }

            // The fixture can relay only to the known FTP control listener.
            try (Socket ftp = new Socket(java.net.Proxy.NO_PROXY)) {
                ftp.connect(new java.net.InetSocketAddress(LOOPBACK, ftpPort), 5_000);
                ftp.setSoTimeout(5_000);
                reply.write(new byte[] {5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
                Thread.startVirtualThread(() -> {
                    try {
                        input.transferTo(ftp.getOutputStream());
                        ftp.shutdownOutput();
                    } catch (IOException closed) {
                        // The client and the server close their streams after QUIT.
                    }
                });
                ftp.getInputStream().transferTo(reply);
                return Scenario.ACCEPTED;
            }
        }
    }
}
