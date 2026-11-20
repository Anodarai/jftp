package com.myjavaworld.jftp;

import java.io.File;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.net.ssl.SSLContext;

import com.myjavaworld.ftp.ConnectionException;
import com.myjavaworld.ftp.ControlConnectionEvent;
import com.myjavaworld.ftp.ControlConnectionListener;
import com.myjavaworld.ftp.DataConnectionEvent;
import com.myjavaworld.ftp.DataConnectionListener;
import com.myjavaworld.ftp.FTPClient;
import com.myjavaworld.ftp.FTPConnectionEvent;
import com.myjavaworld.ftp.FTPConnectionListener;
import com.myjavaworld.ftp.FTPConstants;
import com.myjavaworld.ftp.FTPException;
import com.myjavaworld.ftp.FTPUtil;
import com.myjavaworld.ftp.ListParser;
import com.myjavaworld.ftp.RemoteFile;
import com.myjavaworld.ftp.RemoteFileFilter;
import com.myjavaworld.util.DateFilter;
import com.myjavaworld.util.Filter;
import com.myjavaworld.util.RegexFilter;

/** Owns client, parser, and event translation for one legacy FTP connection. */
public final class LegacyFtpAdapter implements AutoCloseable {
    private final FTPClient client;
    private final ListParser parser;
    private final Endpoint endpoint;

    private LegacyFtpAdapter(FTPClient client, ListParser parser, Endpoint endpoint) {
        this.client = client;
        this.parser = parser;
        this.endpoint = endpoint;
    }

    static LegacyFtpAdapter create(Endpoint endpoint, int timeout, int buffer, SSLContext ssl,
            Consumer<Event> listener) throws ReflectiveOperationException {
        ListParser parser = Class.forName(endpoint.parser()).asSubclass(ListParser.class)
                .getDeclaredConstructor().newInstance();
        FTPClient client = Class.forName(endpoint.client()).asSubclass(FTPClient.class)
                .getDeclaredConstructor().newInstance();
        client.setListParser(parser);
        client.setTimeout(timeout);
        client.setBufferSize(buffer);
        client.setPassive(endpoint.passive());
        client.setSSLUsage(endpoint.sslUsage());
        client.setDataChannelUnencrypted(endpoint.clearData());
        if (ssl != null) {
            client.setSSLContext(ssl);
        }
        LegacyFtpAdapter adapter = new LegacyFtpAdapter(client, parser, endpoint);
        Events events = adapter.new Events(listener);
        client.addControlConnectionListener(events);
        client.addFTPConnectionListener(events);
        client.addDataConnectionListener(events);
        return adapter;
    }

    void connect() throws ReplyFailure, LinkFailure {
        run(() -> client.connect(endpoint.host(), endpoint.port()));
        run(() -> client.login(endpoint.user(), endpoint.password(), endpoint.account()));
    }

    boolean isConnected() {
        return client.isConnected();
    }

    boolean isSecured() {
        return client.isSecured();
    }

    String getRemoteHost() {
        return client.getRemoteHost();
    }

    RemoteEntry getWorkingDirectory() throws ReplyFailure, LinkFailure {
        try {
            return RemoteEntry.wrap(client.getWorkingDirectory());
        } catch (FTPException failure) {
            throw reply(failure);
        } catch (ConnectionException failure) {
            throw link(failure);
        }
    }

    void setWorkingDirectory(RemoteEntry dir) throws ReplyFailure, LinkFailure {
        run(() -> client.setWorkingDirectory(RemoteEntry.unwrap(dir)));
    }

    void setToParentDirectory() throws ReplyFailure, LinkFailure {
        run(client::setToParentDirectory);
    }

    RemoteEntry[] list(Predicate<RemoteEntry> filter) throws ReplyFailure, LinkFailure, ParseException {
        Filter legacyFilter = filter == null ? null : file -> filter.test(RemoteEntry.wrap((RemoteFile) file));
        try {
            RemoteFile[] files = client.list(legacyFilter);
            return files == null ? null : Arrays.stream(files).map(RemoteEntry::wrap).toArray(RemoteEntry[]::new);
        } catch (FTPException failure) {
            throw reply(failure);
        } catch (ConnectionException failure) {
            throw link(failure);
        }
    }

    RemoteEntry createRemoteFile(String path) {
        return RemoteEntry.wrap(parser.createRemoteFile(path));
    }

    RemoteEntry createRemoteFile(String path, boolean directory) {
        return RemoteEntry.wrap(parser.createRemoteFile(path, directory));
    }

    RemoteEntry createRemoteFile(RemoteEntry directory, String name) {
        return RemoteEntry.wrap(parser.createRemoteFile(RemoteEntry.unwrap(directory), name));
    }

    RemoteEntry createRemoteFile(RemoteEntry directory, String name, boolean isDirectory) {
        return RemoteEntry.wrap(parser.createRemoteFile(RemoteEntry.unwrap(directory), name, isDirectory));
    }

    void delete(RemoteEntry file) throws ReplyFailure, LinkFailure {
        run(() -> client.delete(RemoteEntry.unwrap(file)));
    }

    void createDirectory(RemoteEntry dir) throws ReplyFailure, LinkFailure {
        run(() -> client.createDirectory(RemoteEntry.unwrap(dir)));
    }

    void createFile(RemoteEntry file) throws ReplyFailure, LinkFailure {
        run(() -> client.createFile(RemoteEntry.unwrap(file)));
    }

    void rename(RemoteEntry from, RemoteEntry to) throws ReplyFailure, LinkFailure {
        run(() -> client.rename(RemoteEntry.unwrap(from), RemoteEntry.unwrap(to)));
    }

    void download(RemoteEntry source, File target, int type) throws ReplyFailure, LinkFailure {
        run(() -> client.download(RemoteEntry.unwrap(source), target, type, false));
    }

    void upload(File source, RemoteEntry target, int type) throws ReplyFailure, LinkFailure {
        run(() -> client.upload(source, RemoteEntry.unwrap(target), type, false, 0L));
    }

    void executeCommand(String command) throws ReplyFailure, LinkFailure {
        run(() -> client.executeCommand(command));
    }

    static String parseAttributes(String attributes) {
        return FTPUtil.parseAttributes(attributes);
    }

    public void abort() throws ReplyFailure, LinkFailure {
        run(client::abort);
    }

    void disconnect() throws ReplyFailure, LinkFailure {
        run(client::disconnect);
    }

    @Override
    public void close() {
        client.close();
    }

    private void run(Command command) throws ReplyFailure, LinkFailure {
        try {
            command.run();
        } catch (FTPException failure) {
            throw reply(failure);
        } catch (ConnectionException failure) {
            throw link(failure);
        }
    }

    private ReplyFailure reply(FTPException failure) {
        return new ReplyFailure(redact(failure.getMessage()), failure.getErrorCode());
    }

    private LinkFailure link(ConnectionException failure) {
        return new LinkFailure(redact(failure.getMessage()));
    }

    private String redact(String message) {
        if (message == null) {
            return "";
        }
        String password = endpoint.password();
        if (password != null && !password.isEmpty()) {
            message = message.replace(password, "*****");
        }
        String account = endpoint.account();
        if (account != null && !account.isEmpty()) {
            message = message.replace(account, "*****");
        }
        return message;
    }

    private interface Command {
        void run() throws FTPException, ConnectionException;
    }

    static final class ListingFilter implements Predicate<RemoteEntry> {
        private final RemoteFileFilter filter;

        ListingFilter(RegexFilter name, DateFilter date, boolean exclusion) {
            filter = new RemoteFileFilter(name, date, exclusion);
        }

        RegexFilter name() {
            return filter.getRegexFilter();
        }

        DateFilter date() {
            return filter.getDateFilter();
        }

        boolean exclusion() {
            return filter.isExclusionFilter();
        }

        @Override
        public boolean test(RemoteEntry entry) {
            return filter.accept(RemoteEntry.unwrap(entry));
        }
    }

    record Endpoint(String client, String parser, String host, int port, String user, String password,
            String account, int sslUsage, boolean clearData, boolean passive) {
        static Endpoint capture(RemoteHost site) {
            int port = site.getSSLUsage() == FTPConstants.USE_IMPLICIT_SSL ? site.getImplicitSSLPort() : site.getPort();
            return new Endpoint(site.getFTPClientClassName(), site.getListParserClassName(), site.getHostName(),
                    port, site.getUser(), site.getPassword(), site.getAccount(), site.getSSLUsage(),
                    site.isDataChannelUnencrypted(), site.isPassive());
        }

        boolean requiresSecurity() {
            return sslUsage != FTPConstants.USE_NO_SSL;
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    enum EventKind {
        CONNECTED, DISCONNECTED, COMMAND, REPLY, TRANSFER_FINISHED, TRANSFER_PROGRESS, TRANSFER_ABORTED
    }

    record Event(EventKind kind, String message, long bytes) {
    }

    public static final class ReplyFailure extends Exception {
        private final String errorCode;

        private ReplyFailure(String message, String errorCode) {
            super(message);
            this.errorCode = errorCode;
        }

        String getErrorCode() {
            return errorCode;
        }
    }

    public static final class LinkFailure extends Exception {
        private LinkFailure(String message) {
            super(message);
        }
    }

    private final class Events implements FTPConnectionListener, ControlConnectionListener, DataConnectionListener {
        private final Consumer<Event> listener;

        private Events(Consumer<Event> listener) {
            this.listener = listener;
        }

        private void emit(EventKind kind, String message) {
            listener.accept(new Event(kind, redact(message), 0));
        }

        @Override
        public void connectionOpened(FTPConnectionEvent event) {
            emit(EventKind.CONNECTED, event.getMessage());
        }

        @Override
        public void connectionClosed(FTPConnectionEvent event) {
            emit(EventKind.DISCONNECTED, event.getMessage());
        }

        @Override
        public void commandSent(ControlConnectionEvent event) {
            String command = event.getMessage();
            String upper = command.toUpperCase(Locale.ROOT);
            if (upper.startsWith("PASS ")) {
                command = "PASS *****";
            } else if (upper.startsWith("ACCT ")) {
                command = "ACCT *****";
            }
            emit(EventKind.COMMAND, command);
        }

        @Override
        public void replyReceived(ControlConnectionEvent event) {
            emit(EventKind.REPLY, event.getMessage());
        }

        @Override
        public void dataTransferStarted(DataConnectionEvent event) {
        }

        @Override
        public void dataTransferFinished(DataConnectionEvent event) {
            emit(EventKind.TRANSFER_FINISHED, "");
        }

        @Override
        public void dataTransferProgress(DataConnectionEvent event) {
            listener.accept(new Event(EventKind.TRANSFER_PROGRESS, "", event.getBytesTransferred()));
        }

        @Override
        public void dataTransferAborted(DataConnectionEvent event) {
            emit(EventKind.TRANSFER_ABORTED, "");
        }

        @Override
        public void dataTransferError(DataConnectionEvent event) {
        }
    }
}
