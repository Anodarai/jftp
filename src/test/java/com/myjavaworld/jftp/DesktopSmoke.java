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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

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
    private static final String SLOW_FILE = "01-slow.bin";
    private static final String NEXT_FILE = "02-next.bin";
    private JFTP window;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path evidence = Path.of(args[1]);
        DesktopSmoke smoke = new DesktopSmoke();
        try {
            smoke.exercise(root);
            Files.createFile(root.resolve("complete"));
            close(smoke.window);
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

    private void exercise(Path root) throws Exception {
        Path local = Files.createDirectory(root.resolve("local files"));
        Path upload = Files.write(local.resolve("upload.bin"), new byte[] {0, 13, 10, -1, 26, 42});
        Path incoming = root.resolve("server/incoming");
        Path tlsRoot = Files.createDirectory(root.resolve("FTPS"));
        Path slowRoot = Files.createDirectory(root.resolve("throttled FTP"));
        LoopbackCertificate certificate = new LoopbackCertificate(tlsRoot);
        try (LegacyFtpTest.LoopbackServer server = LegacyFtpTest.startServer(root);
                LegacyFtpTest.LoopbackServer tls = LegacyFtpTest.startServer(tlsRoot,
                        certificate.serverConfiguration(), false);
                LegacyFtpTest.LoopbackServer slow = LegacyFtpTest.startServer(slowRoot, null, false,
                        new TransferRatePermission(16 * 1024, 0))) {
            Path remoteDownload = Files.write(incoming.resolve("download.bin"), new byte[] {42, -1, 13, 10, 0});
            Files.write(tlsRoot.resolve("server/incoming/tls-fixture.bin"), new byte[] {8, 9});
            byte[] slowData = new byte[512 * 1024];
            Files.write(slowRoot.resolve("server/incoming").resolve(SLOW_FILE), slowData);
            Files.write(slowRoot.resolve("server/incoming").resolve(NEXT_FILE), new byte[] {1, 2, 3});
            JFTPPreferences preferences = JFTP.prefs;
            preferences.setLocale(Locale.ENGLISH);
            preferences.setLocalDirectory(local.toString());
            preferences.setWindowBounds(new Rectangle(80, 70, 1000, 640));
            preferences.setCheckForUpdates(false);
            JFTP.savePreferences(preferences);
            Favorite secure = favorite(TLS_SITE, tls.port, GOOD_PASSWORD);
            secure.setSSLUsage(FTPConstants.USE_EXPLICIT_SSL);
            FavoritesManager.saveFavorites(new ArrayList<>(List.of(
                    favorite(REJECTED_SITE, server.port, BAD_PASSWORD),
                    favorite(WORKING_SITE, server.port, GOOD_PASSWORD), secure,
                    favorite(SLOW_SITE, slow.port, GOOD_PASSWORD))));

            JFTPApplication.main(new String[0]);
            window = await("one packaged JFTP window", () -> showingWindow(JFTP.class, ignored -> true));
            JTabbedPane tabs = awaitComponent(window, JTabbedPane.class, ignored -> true);
            await("one disconnected tab", () -> tabs.getTabCount() == 1 && disconnected(tabs) ? tabs : null);
            require(onEdt(() -> local.equals(window.getCurrentSession().getLocalWorkingDirectory().getFile().toPath())),
                    "The local pane must start inside the synthetic fixture");
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

            checkAbortAndTabIsolation(tabs, remoteTable, local, slowData.length);
            keys(window, KeyEvent.VK_F4);
            await("a cleared remote listing after disconnect",
                    () -> disconnected(tabs) && remoteTable.getRowCount() == 0 ? tabs : null);
        }
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

    private void checkAbortAndTabIsolation(JTabbedPane tabs, JTable firstListing, Path local, int fullSize)
            throws Exception {
        FTPSession first = onEdt(window::getCurrentSession);
        int oldTab = onEdt(tabs::getSelectedIndex);
        String firstLog = onEdt(this::sessionLog);
        AbstractButton newSession = awaitComponent(window, AbstractButton.class,
                button -> button.isEnabled() && "Open a new FTP session".equals(button.getToolTipText()));
        click(window, newSession, null);
        FTPSession second = await("a second disconnected tab", () -> tabs.getTabCount() == 2
                && disconnected(tabs) && window.getCurrentSession() != first ? window.getCurrentSession() : null);
        int newTab = onEdt(tabs::getSelectedIndex);
        checkRejectedLogin(tabs);
        require(onEdt(first::isConnected), "A rejection in the second tab closed the first connection");
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

        if (onEdt(second::isConnected)) {
            keys(window, KeyEvent.VK_F4);
            await("the second tab disconnected", () -> disconnected(tabs) && !second.isBusy() ? second : null);
        }
        click(window, tabs, onEdt(() -> tabs.getBoundsAt(oldTab)));
        await("the original tab is still selected and connected", () -> window.getCurrentSession() == first
                && first.isConnected() && firstLog.equals(sessionLog()) ? first : null);
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
        if (window != null) {
            Files.writeString(evidence.resolve("session.log"), redact(onEdt(this::sessionLog)));
        }
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
