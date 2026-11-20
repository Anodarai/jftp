package com.myjavaworld.jftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.command.CommandFactory;
import org.apache.ftpserver.command.CommandFactoryFactory;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.listener.Listener;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.ssl.SslConfiguration;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.junit.jupiter.api.Test;

import com.myjavaworld.ftp.DefaultFTPClient;
import com.myjavaworld.ftp.DefaultListParser;
import com.myjavaworld.ftp.FTPClient;
import com.myjavaworld.ftp.FTPConstants;
import com.myjavaworld.ftp.FTPException;
import com.myjavaworld.ftp.ListParser;
import com.myjavaworld.ftp.RemoteFile;

public class LegacyFtpTest {
    private static final String HOST = "127.0.0.1";
    private static final String USER = "test-operator";
    private static final String PASSWORD = "synthetic-ftp-password";

    @Test
    public void testPassiveBinaryUploadListAndDownload() throws Exception {
        checkBinaryTransfer(true);
    }

    @Test
    public void testActiveBinaryUploadListAndDownload() throws Exception {
        checkBinaryTransfer(false);
    }

    @Test
    public void testServerRejectsWrongPassword() throws Exception {
        try (TestDirectory directory = new TestDirectory();
                LoopbackServer server = startServer(directory.root)) {
            FTPClient client = new DefaultFTPClient();
            client.setTimeout(5_000);
            try {
                client.connect(HOST, server.port);
                try {
                    client.login(USER, "incorrect");
                    fail("The loopback server should reject the invalid credentials");
                } catch (FTPException expected) {
                    assertEquals("530", expected.getErrorCode());
                    assertFalse(client.isLoggedIn());
                }
            } finally {
                client.close();
            }
        }
    }

    private void checkBinaryTransfer(boolean passive) throws Exception {
        try (TestDirectory directory = new TestDirectory();
                LoopbackServer server = startServer(directory.root)) {
            ListParser parser = new DefaultListParser();
            FTPClient client = new DefaultFTPClient();
            client.setListParser(parser);
            client.setTimeout(5_000);
            client.setPassive(passive);
            try {
                checkBinaryTransfer(directory.root, server.port, client, parser);
            } finally {
                client.close();
            }
        }
    }

    static void checkBinaryTransfer(Path root, int port, FTPClient client, ListParser parser) throws Exception {
        client.connect(HOST, port);
        client.login(USER, PASSWORD);
        client.setWorkingDirectory(parser.createRemoteFile("/incoming"));
        RemoteFile workingDirectory = client.getWorkingDirectory();
        assertEquals("/incoming", workingDirectory.getPath());

        byte[] payload = {0, 13, 10, -1, 26, 42};
        Path upload = Files.write(root.resolve("upload.bin"), payload);
        RemoteFile target = parser.createRemoteFile(workingDirectory, "sample.bin");
        client.upload(upload.toFile(), target, FTPConstants.TYPE_BINARY, false, 0L);
        assertTrue(Arrays.equals(payload, Files.readAllBytes(root.resolve("server/incoming/sample.bin"))),
                "Bytes on the server");

        RemoteFile[] listing = client.list();
        assertEquals(1, listing.length);
        assertEquals("sample.bin", listing[0].getName());
        assertEquals(payload.length, listing[0].getSize());
        Path download = root.resolve("download.bin");
        client.download(listing[0], download.toFile(), FTPConstants.TYPE_BINARY, false);
        assertTrue(Arrays.equals(payload, Files.readAllBytes(download)), "Bytes downloaded from the server");
        client.disconnect();
        assertFalse(client.isConnected());
    }

    static LoopbackServer startServer(Path root) throws Exception {
        return startServer(root, null, false);
    }

    static LoopbackServer startServer(Path root, SslConfiguration tls, boolean implicit, Authority... extraPermissions)
            throws Exception {
        return startServer(root, tls, implicit, null, extraPermissions);
    }

    static LoopbackServer startServerWithoutList(Path root) throws Exception {
        return startServer(root, null, false,
                defaults -> command -> "LIST".equals(command) ? null : defaults.getCommand(command));
    }

    static LoopbackServer startServerWithHeldList(Path root, CountDownLatch requested, CountDownLatch release)
            throws Exception {
        return startServer(root, null, false, defaults -> command -> {
            var delegate = defaults.getCommand(command);
            if (!"LIST".equals(command)) {
                return delegate;
            }
            return (session, context, request) -> {
                requested.countDown();
                try {
                    if (!release.await(25, TimeUnit.SECONDS)) {
                        throw new IOException("The test did not release the held LIST");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
                delegate.execute(session, context, request);
            };
        });
    }

    private static LoopbackServer startServer(Path root, SslConfiguration tls, boolean implicit,
            UnaryOperator<CommandFactory> configureCommands,
            Authority... extraPermissions) throws Exception {
        Path incoming = Files.createDirectories(root.resolve("server/incoming"));
        BaseUser account = new BaseUser();
        account.setName(USER);
        account.setPassword(PASSWORD);
        account.setHomeDirectory(incoming.getParent().toString());
        List<Authority> permissions = new ArrayList<>(List.of(extraPermissions));
        permissions.add(new WritePermission());
        account.setAuthorities(permissions);

        PropertiesUserManagerFactory users = new PropertiesUserManagerFactory();
        users.setFile(Files.createFile(root.resolve("users.properties")).toFile());
        FtpServerFactory factory = new FtpServerFactory();
        if (configureCommands != null) {
            factory.setCommandFactory(configureCommands.apply(new CommandFactoryFactory().createCommandFactory()));
        }
        factory.setUserManager(users.createUserManager());
        factory.getUserManager().save(account);
        ListenerFactory listeners = new ListenerFactory();
        listeners.setServerAddress(HOST);
        listeners.setPort(0);
        if (tls != null) {
            listeners.setSslConfiguration(tls);
            listeners.setImplicitSsl(implicit);
        }
        DataConnectionConfigurationFactory data = new DataConnectionConfigurationFactory();
        data.setPassiveAddress(HOST);
        data.setActiveLocalAddress(HOST);
        data.setActiveIpCheck(true);
        listeners.setDataConnectionConfiguration(data.createDataConnectionConfiguration());
        Listener listener = listeners.createListener();
        factory.addListener("default", listener);

        FtpServer server = factory.createServer();
        server.start();
        return new LoopbackServer(server, listener);
    }

    static final class LoopbackServer implements AutoCloseable {
        private final FtpServer server;
        private final Listener listener;
        final int port;

        private LoopbackServer(FtpServer server, Listener listener) {
            this.server = server;
            this.listener = listener;
            this.port = listener.getPort();
        }

        boolean hasActiveSessions() {
            return !listener.getActiveSessions().isEmpty();
        }

        @Override
        public void close() {
            server.stop();
        }
    }
}
