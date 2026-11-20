package com.myjavaworld.jftp;

import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JPasswordField;
import javax.swing.JRootPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;

import org.apache.ftpserver.usermanager.impl.TransferRatePermission;

import com.myjavaworld.ftp.FTPConstants;
import com.myjavaworld.jftp.ssl.SecurityWarningDlg;

final class DesktopSmoke {
    private static final String GOOD_PASSWORD = "synthetic-ftp-password";
    private static final String BAD_PASSWORD = "invalid-desktop-smoke-password";
    private static final String WORKING_SITE = "Loopback FTP";
    private static final String REJECTED_SITE = "Rejected login";
    private static final String TLS_SITE = "Loopback FTPS";
    private static final String SLOW_SITE = "Loopback throttled FTP";
    private static final String DENIED_LIST_SITE = "Loopback without LIST";
    private static final String HELD_LIST_SITE = "Loopback with pending LIST";
    private static final String SLOW_FILE = "01-slow.bin";
    private static final String NEXT_FILE = "02-next.bin";
    private static final String ARCHIVE = "03-slow.zip";
    private static final String ARCHIVE_ENTRY = "archive-fixture.bin";
    private JFTP window;
    private boolean syntheticWindow;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path evidence = Path.of(args[1]);
        DesktopSmoke smoke = new DesktopSmoke();
        try {
            Path candidateJar = Path.of(args[3]);
            if (args[2].equals("restored")) {
                smoke.exerciseRestoredProfile(root, evidence, candidateJar);
            } else if (args[2].equals("previous-window")) {
                smoke.exercisePreviousWindow(root.resolve("local files"));
            } else {
                smoke.exercise(root, args[2].equals("migrated") ? candidateJar : null);
            }
            Files.createFile(root.resolve(args[2].equals("previous-window") ? "previous-complete" : "complete"));
            if (smoke.window != null) {
                close(smoke.window);
            }
        } catch (Throwable failure) {
            try {
                smoke.captureFailure(evidence);
            } catch (Exception captureError) {
                failure.addSuppressed(captureError);
            }
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private void exercise(Path root, Path appJar) throws Exception {
        Path local = Files.createDirectory(root.resolve("local files"));
        Path upload = Files.write(local.resolve("upload.bin"), new byte[] {0, 13, 10, -1, 26, 42});
        Path incoming = root.resolve("server/incoming");
        Path tlsRoot = Files.createDirectory(root.resolve("FTPS"));
        Path slowRoot = Files.createDirectory(root.resolve("throttled FTP"));
        CountDownLatch pending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LoopbackCertificate certificate = new LoopbackCertificate(tlsRoot);
        try (LegacyFtpTest.LoopbackServer server = LegacyFtpTest.startServer(root);
                LegacyFtpTest.LoopbackServer tls = LegacyFtpTest.startServer(tlsRoot,
                        certificate.serverConfiguration(), false);
                LegacyFtpTest.LoopbackServer slow = LegacyFtpTest.startServer(slowRoot, null, false,
                        new TransferRatePermission(16 * 1024, 0));
                LegacyFtpTest.LoopbackServer deniedList = LegacyFtpTest.startServerWithoutList(root.resolve("no LIST"));
                LegacyFtpTest.LoopbackServer heldList = LegacyFtpTest.startServerWithHeldList(
                        root.resolve("pending LIST"), pending, release)) {
            Path remoteDownload = Files.write(incoming.resolve("download.bin"), new byte[] {42, -1, 13, 10, 0});
            Files.write(tlsRoot.resolve("server/incoming/tls-fixture.bin"), new byte[] {8, 9});
            byte[] slowData = new byte[512 * 1024];
            Files.write(slowRoot.resolve("server/incoming").resolve(SLOW_FILE), slowData);
            Files.write(slowRoot.resolve("server/incoming").resolve(NEXT_FILE), new byte[] {1, 2, 3});
            Path archive = slowRoot.resolve("server/incoming").resolve(ARCHIVE);
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                zip.setLevel(Deflater.NO_COMPRESSION);
                zip.putNextEntry(new ZipEntry(ARCHIVE_ENTRY));
                zip.write(slowData);
                zip.closeEntry();
            }
            Favorite secure = favorite(TLS_SITE, tls.port, GOOD_PASSWORD);
            secure.setSSLUsage(FTPConstants.USE_EXPLICIT_SSL);
            List<Favorite> favorites = new ArrayList<>(List.of(
                    favorite(REJECTED_SITE, server.port, BAD_PASSWORD),
                    favorite(WORKING_SITE, server.port, GOOD_PASSWORD), secure,
                    favorite(DENIED_LIST_SITE, deniedList.port, GOOD_PASSWORD),
                    favorite(HELD_LIST_SITE, heldList.port, GOOD_PASSWORD),
                    favorite(SLOW_SITE, slow.port, GOOD_PASSWORD)));
            Path sourceFavorites = Path.of(System.getProperty("user.home"), ".jftp", "data", "favorites.ser");
            byte[] originalFavorites = null;
            if (appJar == null) {
                configurePreferences(JFTP.prefs, local);
                JFTP.savePreferences(JFTP.prefs);
                FavoritesManager.saveFavorites(favorites);
            } else {
                Path generation = prepareMigratedProfile(root, appJar, local, favorites);
                require(JFTP.DATA_HOME.toPath().equals(generation),
                        "The extracted desktop did not select the migrated profile");
                originalFavorites = Files.readAllBytes(sourceFavorites);
            }

            JFTPApplication.main(new String[0]);
            window = await("one packaged JFTP window", () -> showingWindow(JFTP.class, ignored -> true));
            JTabbedPane tabs = awaitComponent(window, JTabbedPane.class, ignored -> true);
            await("one disconnected tab", () -> tabs.getTabCount() == 1 && disconnected(tabs) ? tabs : null);
            require(onEdt(() -> local.equals(window.getCurrentSession().getLocalWorkingDirectory().getFile().toPath())),
                    "The local pane must start inside the synthetic fixture");
            syntheticWindow = true;
            checkHelp();
            checkValidation();
            checkRejectedLogin(tabs);
            checkCertificateDecision(tabs);

            connectFavorite(WORKING_SITE);
            JTable localTable = paneTable(LocalPane.class);
            JTable remoteTable = paneTable(RemotePane.class);
            await("the initial /incoming listing", () -> {
                FTPSession session = window.getCurrentSession();
                return session.isConnected() && session.getRemoteWorkingDirectory() != null
                        && "/incoming".equals(session.getRemoteWorkingDirectory().getPath())
                        && tableRow(remoteTable, "download.bin") >= 0 ? session : null;
            });
            selectFile(localTable, "upload.bin");
            keys(window, KeyEvent.VK_F9);
            await("the uploaded file in the remote listing",
                    () -> tableRow(remoteTable, "upload.bin") >= 0 ? remoteTable : null);
            require(Files.mismatch(upload, incoming.resolve("upload.bin")) == -1, "Upload bytes differ");

            selectFile(remoteTable, "download.bin");
            keys(window, KeyEvent.VK_F7);
            await("the downloaded file in the local listing",
                    () -> tableRow(localTable, "download.bin") >= 0 ? localTable : null);
            require(Files.mismatch(remoteDownload, local.resolve("download.bin")) == -1, "Download bytes differ");

            checkAlternateNamesAndZip(localTable, remoteTable, upload, remoteDownload, incoming);
            checkAbortAndTabIsolation(tabs, remoteTable, local, slowData.length, Files.size(archive));
            checkCloseWhileListing(tabs, heldList, pending, release);
            keys(window, KeyEvent.VK_F4);
            await("a cleared remote listing after disconnect",
                    () -> disconnected(tabs) && remoteTable.getRowCount() == 0 ? tabs : null);
            if (originalFavorites != null) {
                require(Arrays.equals(originalFavorites, Files.readAllBytes(sourceFavorites)),
                        "Using the migrated Favorites dialog changed the legacy source");
            }
        }
    }

    private void checkAlternateNamesAndZip(JTable localTable, JTable remoteTable, Path upload, Path remoteDownload,
            Path incoming) throws Exception {
        selectFile(remoteTable, "download.bin");
        DownloadAsDlg download = openFtpDialog("Download As", DownloadAsDlg.class);
        setFileName(download, "cancelled.bin");
        clickButton(download, "Cancel");
        await("Download As cancelled", () -> download.isShowing() ? null : window);
        require(Files.notExists(upload.resolveSibling("cancelled.bin")), "Cancelling Download As wrote a file");
        onEdt(() -> {
            window.toFront();
            window.requestFocus();
            return null;
        });

        DownloadAsDlg approved = openFtpDialog("Download As", DownloadAsDlg.class);
        setFileName(approved, "download-renamed.bin");
        clickButton(approved, "Download");
        await("downloaded alternate name", () -> tableRow(localTable, "download-renamed.bin") >= 0 ? localTable : null);
        require(Files.mismatch(remoteDownload, upload.resolveSibling("download-renamed.bin")) == -1,
                "Download As bytes differ");

        selectFile(localTable, "upload.bin");
        UploadAsDlg uploadAs = openFtpDialog("Upload As", UploadAsDlg.class);
        setFileName(uploadAs, "upload-renamed.bin");
        clickButton(uploadAs, "Upload");
        await("uploaded alternate name", () -> tableRow(remoteTable, "upload-renamed.bin") >= 0 ? remoteTable : null);
        require(Files.mismatch(upload, incoming.resolve("upload-renamed.bin")) == -1, "Upload As bytes differ");

        selectFile(localTable, "upload.bin");
        ZipAndUploadDlg zip = openFtpDialog("Zip and Upload", ZipAndUploadDlg.class);
        setFileName(zip, "uploaded.zip");
        clickButton(zip, "Upload");
        await("uploaded ZIP in the remote listing", () -> tableRow(remoteTable, "uploaded.zip") >= 0 ? remoteTable : null);
        try (ZipFile stored = new ZipFile(incoming.resolve("uploaded.zip").toFile());
                var payload = stored.getInputStream(stored.getEntry("upload.bin"))) {
            require(Arrays.equals(Files.readAllBytes(upload), payload.readAllBytes()), "Uploaded ZIP bytes differ");
        }
    }

    private <T extends JDialog> T openFtpDialog(String label, Class<T> type) throws Exception {
        JMenu ftp = awaitComponent(window, JMenu.class, menu -> "FTP".equals(menu.getText()));
        click(window, ftp, null);
        AbstractButton item = awaitComponent(ftp.getPopupMenu(), AbstractButton.class,
                button -> button.isEnabled() && button.getText().startsWith(label));
        click(window, item, null);
        return await(label, () -> showingWindow(type, ignored -> true));
    }

    private static void setFileName(JDialog dialog, String name) throws Exception {
        JTextField field = awaitComponent(dialog, JTextField.class, JTextField::isEnabled);
        onEdt(() -> {
            field.setText(name);
            return null;
        });
    }

    private void exerciseRestoredProfile(Path root, Path evidence, Path candidateJar) throws Exception {
        Path local = Files.createDirectory(root.resolve("local files"));
        Path home = Path.of(System.getProperty("user.home"));
        try (LegacyFtpTest.LoopbackServer server = LegacyFtpTest.startServer(root)) {
            Files.write(root.resolve("server/incoming/rollback.bin"), new byte[] {3, 5, 8});
            Path generation = prepareMigratedProfile(root, candidateJar, local,
                    new ArrayList<>(List.of(favorite(WORKING_SITE, server.port, GOOD_PASSWORD))));
            Path profile = generation.getParent().getParent();
            Path legacy = profile.resolve("data");
            Path backup = profile.resolve("backups").resolve(generation.getFileName());
            byte[] preferences = Files.readAllBytes(legacy.resolve("preferences.ser"));
            byte[] favorites = Files.readAllBytes(legacy.resolve("favorites.ser"));
            runProfileCommand(candidateJar, root.resolve("migration operator"), "--restore-profile", home, "legacy");
            require(Files.readString(profile.resolve("active-profile")).strip().equals("legacy"),
                    "The release package did not restore the legacy profile");
            require(Arrays.equals(preferences, Files.readAllBytes(legacy.resolve("preferences.ser")))
                    && Arrays.equals(favorites, Files.readAllBytes(legacy.resolve("favorites.ser")))
                    && Arrays.equals(preferences, Files.readAllBytes(backup.resolve("preferences.ser")))
                    && Arrays.equals(favorites, Files.readAllBytes(backup.resolve("favorites.ser"))),
                    "The restored profile and its backup must match the legacy inputs");

            Process oldWindow = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                    "-Duser.home=" + home, "-cp", System.getProperty("java.class.path"), DesktopSmoke.class.getName(),
                    root.toString(), evidence.toString(), "previous-window", candidateJar.toString())
                    .inheritIO().start();
            if (!oldWindow.waitFor(35, TimeUnit.SECONDS)) {
                oldWindow.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                throw new AssertionError("Previous package timed out after profile restore");
            }
            require(oldWindow.exitValue() == 0 && Files.isRegularFile(root.resolve("previous-complete")),
                    "Previous package could not reopen the restored profile");
        }
    }

    private void exercisePreviousWindow(Path local) throws Exception {
        JFTPApplication.main(new String[0]);
        window = await("the previous package window", () -> showingWindow(JFTP.class, ignored -> true));
        JTabbedPane tabs = awaitComponent(window, JTabbedPane.class, ignored -> true);
        await("the previous package opened the restored preferences", () -> tabs.getTabCount() == 1
                && disconnected(tabs)
                && local.equals(window.getCurrentSession().getLocalWorkingDirectory().getFile().toPath()) ? tabs : null);
        syntheticWindow = true;
        checkHelp();
        connectFavorite(WORKING_SITE);
        JTable remoteTable = paneTable(RemotePane.class);
        await("the restored favorite lists the real loopback server", () -> window.getCurrentSession().isConnected()
                && tableRow(remoteTable, "rollback.bin") >= 0 ? remoteTable : null);
        keys(window, KeyEvent.VK_F4);
        await("the previous package disconnected", () -> disconnected(tabs) ? tabs : null);
    }

    private static void configurePreferences(JFTPPreferences preferences, Path local) {
        preferences.setLocale(Locale.ENGLISH);
        preferences.setLocalDirectory(local.toString());
        preferences.setWindowBounds(new Rectangle(80, 70, 1000, 640));
        preferences.setCheckForUpdates(false);
    }

    private static Path prepareMigratedProfile(Path root, Path appJar, Path local, List<Favorite> favorites)
            throws Exception {
        Path home = Path.of(System.getProperty("user.home"));
        Path profile = Files.createDirectories(home.resolve(".jftp/data")).getParent();
        try (var input = new ObjectInputStream(DesktopSmoke.class.getResourceAsStream("preferences-baseline.ser"))) {
            JFTPPreferences preferences = (JFTPPreferences) input.readObject();
            configurePreferences(preferences, local);
            try (var output = new ObjectOutputStream(Files.newOutputStream(profile.resolve("data/preferences.ser")))) {
                output.writeObject(preferences);
            }
        }
        try (var output = new ObjectOutputStream(Files.newOutputStream(profile.resolve("data/favorites.ser")))) {
            output.writeObject(favorites);
        }
        Path operator = Files.createDirectory(root.resolve("migration operator"));
        runProfileCommand(appJar, operator, "--migrate-profile", home);
        String generation = Files.readString(profile.resolve("active-profile")).strip();
        return profile.resolve("generations").resolve(generation);
    }

    private static void runProfileCommand(Path appJar, Path operator, String command, Path home, String... options)
            throws Exception {
        Path log = operator.resolve(command.substring(2) + ".log");
        List<String> args = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Duser.home=" + operator, "-jar", appJar.toString(), command, home.toString()));
        args.addAll(List.of(options));
        Process migration = new ProcessBuilder(args)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try (var answer = migration.getOutputStream()) {
            if (command.equals("--migrate-profile")) {
                answer.write("yes\n".getBytes(StandardCharsets.UTF_8));
            }
        }
        if (!migration.waitFor(25, TimeUnit.SECONDS)) {
            migration.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError(command + " timed out");
        }
        require(migration.exitValue() == 0, command + " failed: " + Files.readString(log));
    }

    private void checkHelp() throws Exception {
        JMenu menu = awaitComponent(window, JMenu.class, candidate -> "Help".equals(candidate.getText()));
        click(window, menu, null);
        AbstractButton contents = awaitComponent(menu.getPopupMenu(), AbstractButton.class,
                candidate -> "Contents and Index".equals(candidate.getText()));
        click(window, contents, null);
        Window help = await("the bundled Help window",
                () -> showingWindow(Window.class, candidate -> {
                    var topic = JFTPHelp2.getInstance().getHelpBroker().getCurrentID();
                    return candidate != window && topic != null && "index".equals(topic.id);
                }));
        close(help);
        await("Help closed", () -> help.isShowing() ? null : window);
    }

    private void checkValidation() throws Exception {
        String initialLog = onEdt(this::sessionLog);
        keys(window, KeyEvent.VK_F3);
        ConnectionDlg dialog = await("Connect", () -> showingWindow(ConnectionDlg.class, ignored -> true));
        Component hostField = await("Host Name field focus", () -> {
            Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            return focus instanceof JTextField && !(focus instanceof JPasswordField)
                    && SwingUtilities.isDescendingFrom(focus, dialog) ? focus : null;
        });
        clickButton(dialog, "Connect");
        JDialog error = await("the required host error",
                () -> showingWindow(JDialog.class, candidate -> "Error".equals(candidate.getTitle())
                        && components(candidate).anyMatch(component -> component instanceof JLabel label
                                && "Please enter the host name".equals(label.getText()))));
        keys(error, KeyEvent.VK_ENTER);
        await("focus returned to Host Name", () -> hostField.isFocusOwner() ? hostField : null);
        clickButton(dialog, "Cancel");
        await("Connect cancelled", () -> dialog.isShowing() ? null : window);
        require(initialLog.equals(onEdt(this::sessionLog)), "Cancelling Connect changed the session log");
    }

    private void checkRejectedLogin(JTabbedPane tabs) throws Exception {
        connectFavorite(REJECTED_SITE);
        await("reply 530 and a disconnected tab",
                () -> sessionLog().contains("530 ") && disconnected(tabs) ? tabs : null);
    }

    private void checkCertificateDecision(JTabbedPane tabs) throws Exception {
        Path trustStore = Path.of(JFTP.prefs.getServerCertificateStore());
        require(Files.notExists(trustStore), "The FTPS fixture must start with an empty server trust store");
        connectFavorite(TLS_SITE);
        SecurityWarningDlg warning = awaitCertificateWarning();
        clickButton(warning, "No");
        await("the declined certificate leaves the tab disconnected", () -> {
            FTPSession session = window.getCurrentSession();
            return !session.isBusy() && disconnected(tabs) && sessionLog().contains("No trusted certificate found")
                    ? session : null;
        });
        byte[] emptyTrustStore = Files.readAllBytes(trustStore);
        require(KeyStore.getInstance(trustStore.toFile(), JFTP.prefs.getServerCertificateStorePassword()).size() == 0,
                "Declining the connection must leave the initialized server trust store empty");

        connectFavorite(TLS_SITE);
        clickButton(awaitCertificateWarning(), "Yes");
        JTable table = paneTable(RemotePane.class);
        await("the FTPS listing after continuing", () -> tableRow(table, "tls-fixture.bin") >= 0 ? table : null);
        keys(window, KeyEvent.VK_F4);
        await("the FTPS tab disconnected", () -> disconnected(tabs) && table.getRowCount() == 0 ? tabs : null);
        require(Arrays.equals(emptyTrustStore, Files.readAllBytes(trustStore)),
                "Continuing this connection persisted a trust exception");
    }

    private static SecurityWarningDlg awaitCertificateWarning() throws Exception {
        return await("the untrusted loopback certificate warning",
                () -> showingWindow(SecurityWarningDlg.class, warning -> components(warning)
                        .anyMatch(component -> component instanceof JLabel label && label.getText() != null
                                && label.getText().contains("certificate authority that you do not"))));
    }

    private void checkAbortAndTabIsolation(JTabbedPane tabs, JTable firstListing, Path local, int fullSize, long zipSize)
            throws Exception {
        FTPSession first = onEdt(window::getCurrentSession);
        int oldTab = onEdt(tabs::getSelectedIndex);
        String firstLog = onEdt(this::sessionLog);
        FTPSession second = newSession(tabs);
        int newTab = onEdt(tabs::getSelectedIndex);
        checkRejectedLogin(tabs);
        require(onEdt(first::isConnected), "A rejection in the second tab closed the first connection");
        checkFailedInitialListing(tabs, second);
        connectFavorite(SLOW_SITE);
        JTable slowListing = paneTable(RemotePane.class);
        await("both files in the throttled listing",
                () -> tableRow(slowListing, SLOW_FILE) >= 0 && tableRow(slowListing, NEXT_FILE) >= 0 ? slowListing : null);
        selectFile(slowListing, SLOW_FILE);
        selectFile(slowListing, NEXT_FILE, InputEvent.CTRL_DOWN_MASK);
        await("both files selected for download", () -> slowListing.getSelectedRowCount() == 2 ? slowListing : null);
        keys(window, KeyEvent.VK_F7);
        Path partial = local.resolve(SLOW_FILE);
        await("a throttled transfer in progress", () -> {
            long received = partial.toFile().length();
            return second.isBusy() && received > 0 && received < fullSize ? second : null;
        });

        click(window, tabs, onEdt(() -> tabs.getBoundsAt(oldTab)));
        await("the first tab remains responsive while the second transfers", () -> first.isConnected()
                && window.getCurrentSession() == first && tableRow(firstListing, "upload.bin") >= 0 ? first : null);
        click(window, tabs, onEdt(() -> tabs.getBoundsAt(newTab)));
        await("the transfer tab is selected again", () -> window.getCurrentSession() == second ? second : null);
        keys(window, KeyEvent.VK_F12);
        await("the transfer reached its aborted terminal state",
                () -> !second.isBusy() && sessionLog().contains("Data transfer aborted by the user") ? second : null);
        require(Files.notExists(local.resolve(NEXT_FILE)), "The next selected file started after Abort");
        require(partial.toFile().length() < fullSize, "The transfer completed before Abort");
        checkInterruptedArchive(local, second, zipSize);

        if (onEdt(second::isConnected)) {
            keys(window, KeyEvent.VK_F4);
            await("the second tab disconnected", () -> disconnected(tabs) && !second.isBusy() ? second : null);
        }
        click(window, tabs, onEdt(() -> tabs.getBoundsAt(oldTab)));
        await("the original tab is still selected and connected", () -> window.getCurrentSession() == first
                && first.isConnected() && firstLog.equals(sessionLog()) ? first : null);
    }

    private FTPSession newSession(JTabbedPane tabs) throws Exception {
        int count = onEdt(tabs::getTabCount);
        AbstractButton newSession = awaitComponent(window, AbstractButton.class,
                button -> button.isEnabled() && "Open a new FTP session".equals(button.getToolTipText()));
        click(window, newSession, null);
        return await("a new disconnected tab", () -> tabs.getTabCount() == count + 1
                && disconnected(tabs) ? window.getCurrentSession() : null);
    }

    private void checkCloseWhileListing(JTabbedPane tabs, LegacyFtpTest.LoopbackServer heldList,
            CountDownLatch requested, CountDownLatch release) throws Exception {
        FTPSession first = onEdt(window::getCurrentSession);
        int firstIndex = onEdt(tabs::getSelectedIndex);
        String firstLog = onEdt(this::sessionLog);
        FTPSession pending = newSession(tabs);
        try {
            connectFavorite(HELD_LIST_SITE);
            require(requested.await(10, TimeUnit.SECONDS), "The local server never received the held LIST");
            JMenu ftp = awaitComponent(window, JMenu.class, menu -> "FTP".equals(menu.getText()));
            click(window, ftp, null);
            AbstractButton closeTab = awaitComponent(ftp.getPopupMenu(), AbstractButton.class,
                    button -> button.isEnabled() && "Close Session".equals(button.getText()));
            click(window, closeTab, null);
            await("the pending tab closed without waiting for the server",
                    () -> tabs.indexOfComponent(pending) == -1 && first.isConnected() ? first : null);
            FTPSession replacement = newSession(tabs);
            String newLog = onEdt(this::sessionLog);
            release.countDown();
            await("the closed tab released its FTP session", () -> heldList.hasActiveSessions() ? null : tabs);
            require(onEdt(() -> window.getCurrentSession() == replacement && disconnected(tabs)
                    && newLog.equals(sessionLog())), "The retired worker changed the replacement tab");
            click(window, tabs, onEdt(() -> tabs.getBoundsAt(firstIndex)));
            await("the original connection and log survived the closed worker",
                    () -> window.getCurrentSession() == first && first.isConnected()
                            && firstLog.equals(sessionLog()) ? first : null);
        } finally {
            release.countDown();
        }
    }

    private void checkFailedInitialListing(JTabbedPane tabs, FTPSession session) throws Exception {
        connectFavorite(DENIED_LIST_SITE);
        JTable listing = paneTable(RemotePane.class);
        await("a rejected initial LIST with a connected tab and empty remote pane", () -> !session.isBusy()
                && session.isConnected() && session.getRemoteWorkingDirectory() == null && listing.getRowCount() == 0
                && DENIED_LIST_SITE.equals(tabs.getTitleAt(tabs.getSelectedIndex()))
                && sessionLog().contains("502 Command LIST not implemented")
                ? session : null);
        System.out.println(onEdt(() -> "Rejected LIST state, connected=" + session.isConnected()
                + ", directory=" + session.getRemoteWorkingDirectory()
                + ", tab=" + tabs.getTitleAt(tabs.getSelectedIndex())));
        if (onEdt(session::isConnected)) {
            keys(window, KeyEvent.VK_F4);
            await("disconnect after the rejected LIST", () -> disconnected(tabs) && !session.isBusy() ? session : null);
        }
    }

    private void checkInterruptedArchive(Path local, FTPSession session, long fullSize) throws Exception {
        if (!onEdt(session::isConnected)) {
            connectFavorite(SLOW_SITE);
        }
        JTable listing = paneTable(RemotePane.class);
        selectFile(listing, ARCHIVE);
        DownloadAndUnzipDlg dialog = openFtpDialog("Download and Unzip", DownloadAndUnzipDlg.class);
        clickButton(dialog, "Download the file to the current local working directory");
        clickButton(dialog, "Unzip the file to the current local working directory");
        clickButton(dialog, "Download");
        Path partial = local.resolve(ARCHIVE);
        await("a partial ZIP download", () -> session.isBusy() && partial.toFile().length() > 0
                && partial.toFile().length() < fullSize ? session : null);
        keys(window, KeyEvent.VK_F12);
        JDialog error = await("the legacy interrupted ZIP extraction error",
                () -> showingWindow(JDialog.class, candidate -> "Error".equals(candidate.getTitle())));
        require(Files.notExists(local.resolve(ARCHIVE_ENTRY)), "The interrupted ZIP extracted a payload");
        require(partial.toFile().length() > 0 && partial.toFile().length() < fullSize,
                "The interrupted ZIP did not remain partial");
        System.out.println("Interrupted ZIP left a partial download and displayed an extraction error.");
        keys(error, KeyEvent.VK_ENTER);
        await("the extraction error dismissed", () -> !error.isShowing() && !session.isBusy() ? session : null);
    }

    private void connectFavorite(String name) throws Exception {
        AbstractButton favorites = awaitComponent(window, AbstractButton.class,
                button -> button.isEnabled() && "Manage favorite FTP sites".equals(button.getToolTipText()));
        click(window, favorites, null);
        FavoritesDlg dialog = await("Manage Favorites", () -> showingWindow(FavoritesDlg.class, ignored -> true));
        JList<?> list = awaitComponent(dialog, JList.class, ignored -> true);
        Rectangle cell = await("saved site " + name, () -> {
            for (int index = 0; index < list.getModel().getSize(); index++) {
                if (list.getModel().getElementAt(index) instanceof Favorite favorite
                        && name.equals(favorite.getName())) {
                    return list.getCellBounds(index, index);
                }
            }
            return null;
        });
        click(dialog, list, cell);
        clickButton(dialog, "Connect");
        await("Favorites closed", () -> dialog.isShowing() ? null : window);
    }

    private static Favorite favorite(String name, int port, String password) {
        Favorite favorite = new Favorite();
        favorite.setName(name);
        favorite.setHostName("127.0.0.1");
        favorite.setPort(port);
        favorite.setUser("test-operator");
        favorite.setPassword(password);
        favorite.setInitialRemoteDirectory("/incoming");
        return favorite;
    }

    private static boolean disconnected(JTabbedPane tabs) {
        return "Not Connected".equals(tabs.getTitleAt(tabs.getSelectedIndex()));
    }

    private JTable paneTable(Class<? extends Container> type) throws Exception {
        Container pane = awaitComponent(onEdt(window::getCurrentSession), type, ignored -> true);
        return awaitComponent(pane, JTable.class, ignored -> true);
    }

    private static int tableRow(JTable table, String file) {
        for (int row = 0; row < table.getRowCount(); row++) {
            if (table.prepareRenderer(table.getCellRenderer(row, 0), row, 0) instanceof JLabel cell
                    && file.equals(cell.getText())) {
                return row;
            }
        }
        return -1;
    }

    private void selectFile(JTable table, String file) throws Exception {
        selectFile(table, file, 0);
    }

    private void selectFile(JTable table, String file, int modifiers) throws Exception {
        Rectangle cell = await("visible file " + file, () -> {
            int row = tableRow(table, file);
            return row < 0 ? null : table.getCellRect(row, 0, true);
        });
        click(window, table, cell, modifiers);
    }

    private void clickButton(Window owner, String text) throws Exception {
        AbstractButton button = awaitComponent(owner, AbstractButton.class,
                candidate -> text.equals(candidate.getText()) && candidate.isEnabled());
        click(owner, button, null);
    }

    private static void click(Window owner, Component component, Rectangle cell) throws Exception {
        click(owner, component, cell, 0);
    }

    private static void click(Window owner, Component component, Rectangle cell, int modifiers) throws Exception {
        awaitActive(owner);
        EventQueue.invokeLater(() -> {
            Rectangle bounds = cell == null ? new Rectangle(component.getSize()) : cell;
            int x = bounds.x + bounds.width / 2;
            int y = bounds.y + bounds.height / 2;
            long now = System.currentTimeMillis();
            component.dispatchEvent(new MouseEvent(component, MouseEvent.MOUSE_PRESSED, now,
                    modifiers | InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
            component.dispatchEvent(new MouseEvent(component, MouseEvent.MOUSE_RELEASED, now,
                    modifiers, x, y, 1, false, MouseEvent.BUTTON1));
            component.dispatchEvent(new MouseEvent(component, MouseEvent.MOUSE_CLICKED, now,
                    modifiers, x, y, 1, false, MouseEvent.BUTTON1));
        });
    }

    private static void keys(Window owner, int key) throws Exception {
        awaitActive(owner);
        Component focus = await("keyboard focus in " + owner.getClass().getSimpleName(), () -> {
            Component candidate = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            return candidate != null && SwingUtilities.getWindowAncestor(candidate) == owner ? candidate : null;
        });
        long now = System.currentTimeMillis();
        EventQueue events = Toolkit.getDefaultToolkit().getSystemEventQueue();
        events.postEvent(new KeyEvent(focus, KeyEvent.KEY_PRESSED, now, 0, key, KeyEvent.CHAR_UNDEFINED));
        events.postEvent(new KeyEvent(focus, KeyEvent.KEY_RELEASED, now, 0, key, KeyEvent.CHAR_UNDEFINED));
    }

    private static void close(Window owner) {
        Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(new WindowEvent(owner, WindowEvent.WINDOW_CLOSING));
    }

    private static void awaitActive(Window owner) throws Exception {
        await("the active window is " + owner.getClass().getSimpleName(),
                () -> owner.isActive() ? owner : null);
    }

    private String sessionLog() {
        return components(window.getCurrentSession()).filter(StatusWindow.class::isInstance)
                .map(StatusWindow.class::cast).findFirst().orElseThrow().getText();
    }

    private void captureFailure(Path evidence) throws Exception {
        if (!syntheticWindow) {
            return;
        }
        Files.writeString(evidence.resolve("session.log"), redact(onEdt(this::sessionLog)));
        BufferedImage screenshot = onEdt(() -> {
            Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
            RootPaneContainer target = active instanceof RootPaneContainer top ? top : window;
            if (target == null) {
                return null;
            }
            JRootPane pane = target.getRootPane();
            BufferedImage contents = new BufferedImage(pane.getWidth(), pane.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = contents.createGraphics();
            try {
                pane.printAll(graphics);
            } finally {
                graphics.dispose();
            }
            return contents;
        });
        if (screenshot != null) {
            ImageIO.write(screenshot, "png", evidence.resolve("failure.png").toFile());
        }
    }

    static String redact(String log) {
        return log.replace(BAD_PASSWORD, "[redacted]").replace(GOOD_PASSWORD, "[redacted]")
                .replaceAll("(?im)^(.*\\bPASS)\\h+.*$", "$1 [redacted]");
    }

    private static Stream<Component> components(Component root) {
        return Stream.concat(Stream.of(root), root instanceof Container container
                ? Arrays.stream(container.getComponents()).flatMap(DesktopSmoke::components) : Stream.empty());
    }

    private static <T extends Component> T awaitComponent(Component root, Class<T> type, Predicate<T> accepts)
            throws Exception {
        return await(type.getSimpleName(), () -> components(root).filter(type::isInstance)
                .map(type::cast).filter(Component::isShowing).filter(accepts).findFirst().orElse(null));
    }

    private static <W extends Window> W showingWindow(Class<W> type, Predicate<W> accepts) {
        return Arrays.stream(Window.getWindows()).filter(type::isInstance).map(type::cast)
                .filter(Window::isShowing).filter(accepts).findFirst().orElse(null);
    }

    private static <T> T await(String description, Supplier<T> observe) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            T result = onEdt(observe);
            if (result != null) {
                return result;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out waiting for " + description);
    }

    private static <T> T onEdt(Supplier<T> observe) throws Exception {
        FutureTask<T> task = new FutureTask<>(observe::get);
        SwingUtilities.invokeLater(task);
        return task.get(5, TimeUnit.SECONDS);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
