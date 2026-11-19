package com.myjavaworld.jftp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.listener.Listener;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;

import com.myjavaworld.ftp.DefaultFTPClient;
import com.myjavaworld.ftp.DefaultListParser;
import com.myjavaworld.ftp.FTPClient;
import com.myjavaworld.ftp.FTPConstants;
import com.myjavaworld.ftp.FTPException;
import com.myjavaworld.ftp.ListParser;
import com.myjavaworld.ftp.RemoteFile;
import junit.framework.TestCase;

public class LegacyFtpTest extends TestCase {
    private static final String HOST = "127.0.0.1";
    private static final String USER = "test-operator";
    private static final String PASSWORD = "synthetic-ftp-password";

    public void testPassiveBinaryUploadListAndDownload() throws Exception {
        checkBinaryTransfer(true);
    }

    public void testActiveBinaryUploadListAndDownload() throws Exception {
        checkBinaryTransfer(false);
    }

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
                client.connect(HOST, server.port);
                client.login(USER, PASSWORD);
                client.setWorkingDirectory(parser.createRemoteFile("/incoming"));
                RemoteFile workingDirectory = client.getWorkingDirectory();
                assertEquals("/incoming", workingDirectory.getPath());

                byte[] payload = {0, 13, 10, -1, 26, 42};
                Path upload = Files.write(directory.root.resolve("upload.bin"), payload);
                RemoteFile target = parser.createRemoteFile(workingDirectory, "sample.bin");
                client.upload(upload.toFile(), target, FTPConstants.TYPE_BINARY, false, 0L);
                assertTrue("Bytes on the server", Arrays.equals(payload,
                        Files.readAllBytes(directory.root.resolve("server/incoming/sample.bin"))));

                RemoteFile[] listing = client.list();
                assertEquals(1, listing.length);
                assertEquals("sample.bin", listing[0].getName());
                assertEquals(payload.length, listing[0].getSize());
                Path download = directory.root.resolve("download.bin");
                client.download(listing[0], download.toFile(), FTPConstants.TYPE_BINARY, false);
                assertTrue("Bytes downloaded from the server",
                        Arrays.equals(payload, Files.readAllBytes(download)));
                client.disconnect();
                assertFalse(client.isConnected());
            } finally {
                client.close();
            }
        }
    }

    static LoopbackServer startServer(Path root) throws Exception {
        Path incoming = Files.createDirectories(root.resolve("server/incoming"));
        BaseUser account = new BaseUser();
        account.setName(USER);
        account.setPassword(PASSWORD);
        account.setHomeDirectory(incoming.getParent().toString());
        account.setAuthorities(Collections.singletonList(new WritePermission()));

        PropertiesUserManagerFactory users = new PropertiesUserManagerFactory();
        users.setFile(Files.createFile(root.resolve("users.properties")).toFile());
        FtpServerFactory factory = new FtpServerFactory();
        factory.setUserManager(users.createUserManager());
        factory.getUserManager().save(account);
        ListenerFactory listeners = new ListenerFactory();
        listeners.setServerAddress(HOST);
        listeners.setPort(0);
        DataConnectionConfigurationFactory data = new DataConnectionConfigurationFactory();
        data.setPassiveAddress(HOST);
        data.setActiveLocalAddress(HOST);
        data.setActiveIpCheck(true);
        listeners.setDataConnectionConfiguration(data.createDataConnectionConfiguration());
        Listener listener = listeners.createListener();
        factory.addListener("default", listener);

        FtpServer server = factory.createServer();
        server.start();
        return new LoopbackServer(server, listener.getPort());
    }

    static final class LoopbackServer implements AutoCloseable {
        private final FtpServer server;
        final int port;

        private LoopbackServer(FtpServer server, int port) {
            this.server = server;
            this.port = port;
        }

        @Override
        public void close() {
            server.stop();
        }
    }
}
