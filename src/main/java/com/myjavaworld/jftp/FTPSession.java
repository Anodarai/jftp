/*
 * Copyright 2012 jMethods, Inc. 
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.myjavaworld.jftp;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.text.ParseException;
import java.util.Hashtable;
import java.util.ResourceBundle;

import javax.swing.JOptionPane;
import javax.swing.JSplitPane;
import javax.swing.Timer;
import javax.swing.filechooser.FileSystemView;

import com.myjavaworld.ftp.FTPConstants;
import com.myjavaworld.gui.GUIUtil;
import com.myjavaworld.gui.MScrollPane;
import com.myjavaworld.jftp.LegacyFtpAdapter.Endpoint;
import com.myjavaworld.jftp.LegacyFtpAdapter.Event;
import com.myjavaworld.jftp.LegacyFtpAdapter.LinkFailure;
import com.myjavaworld.jftp.LegacyFtpAdapter.ListingFilter;
import com.myjavaworld.jftp.LegacyFtpAdapter.ReplyFailure;
import com.myjavaworld.jftp.ssl.JFTPSSLContext;
import com.myjavaworld.util.FileChangeEvent;
import com.myjavaworld.util.FileChangeListener;
import com.myjavaworld.util.FileChangeMonitor;
import com.myjavaworld.util.Filter;
import com.myjavaworld.util.ProgressEvent;
import com.myjavaworld.util.ProgressListener;
import com.myjavaworld.util.ResourceLoader;
import com.myjavaworld.zip.ZipEvent;
import com.myjavaworld.zip.ZipListener;

/**
 * FTP session. Contains the main interface and connection and data transfer
 * logic.
 * 
 * @author Sai Pullabhotla, psai [at] jMethods [dot] com
 * @version 2.0
 */
public class FTPSession extends SessionPanel implements ActionListener,
		FileChangeListener, ProgressListener, ZipListener {

	private static final ResourceBundle resources = ResourceLoader
			.getBundle("com.myjavaworld.jftp.FTPSession");
	private LocalPane localPane = null;
	private JFTP jftp = null;
	private RemotePane remotePane = null;
	private final SessionCoordinator operations = new SessionCoordinator();
	private volatile LegacyFtpAdapter client = null;
	private volatile Object transportOrigin;
	private LocalFile lwd = null;
	private RemoteEntry rwd = null;
	private StatusWindow statusWindow = null;
	private StatusBar statusBar = null;
	private Timer timer = null;
	private int timeElapsed = 0;
	private long bytesTransferred = 0L;
	private MessageFormat connectStatusFormat = null;
	private MessageFormat downloadStatusFormat = null;
	private MessageFormat uploadStatusFormat = null;
	private MessageFormat listStatusFormat = null;
	private String listParentStatus = null;
	private JSplitPane splitPane1 = null;
	private JSplitPane splitPane2 = null;
	private static int sessionId = 0;
	private int id = 0;
	private RemoteHost remoteHost = null;
	private int transferType = FTPConstants.TYPE_BINARY;
	private boolean autoDetect = true;
	private volatile boolean abort = false;
	private Filter localFileFilter = null;
	private ListingFilter remoteFileFilter = null;
	private FileChangeMonitor fileChangeMonitor = null;
	private Hashtable monitoredTransfers = null;
	private MessageFormat zipStatusFormat = null;
	private MessageFormat unzipStatusFormat = null;
	private Timer progressTimer = null;
	private int progress = 0;

	public FTPSession(JFTP jftp) {
		super();
		this.jftp = jftp;
		this.id = ++sessionId;
		setName("cmd.session" + id);
		updateTitle();
		localFileFilter = new LocalFileFilter(null, null, false);
		connectStatusFormat = new MessageFormat(
				resources.getString("status.connect"));
		downloadStatusFormat = new MessageFormat(
				resources.getString("status.download"));
		uploadStatusFormat = new MessageFormat(
				resources.getString("status.upload"));
		listStatusFormat = new MessageFormat(resources.getString("status.list"));
		listParentStatus = resources.getString("status.listParent");
		timer = new Timer(1000, this);
		getContentPane().setLayout(new BorderLayout(0, 0));
		getContentPane().add(getCenterPanel(), BorderLayout.CENTER);
		getContentPane().add(getSouthPanel(), BorderLayout.SOUTH);
		File lwd = new File(JFTP.prefs.getLocalDirectory());
		// if (!lwd.exists() || !lwd.isDirectory())
		// lwd = JFTP.USER_HOME;
		if (!lwd.exists() || !lwd.isDirectory()) {
			lwd = FileSystemView.getFileSystemView().getDefaultDirectory();
		}
		setLocalWorkingDirectory(new LocalFile(lwd));
		addComponentListener(new ComponentAdapter() {

			@Override
			public void componentShown(ComponentEvent arg0) {
				splitPane1.setDividerLocation(0.5);
				splitPane2.setDividerLocation(0.2);
			}
		});
		fileChangeMonitor = new FileChangeMonitor();
		fileChangeMonitor.addFileChangeListener(this);
		monitoredTransfers = new Hashtable();
		zipStatusFormat = new MessageFormat(resources.getString("status.zip"));
		unzipStatusFormat = new MessageFormat(
				resources.getString("status.unzip"));
		progressTimer = new Timer(1000, this);
	}

	public void fileChanged(FileChangeEvent evt) {
		operations.publish(() -> handleFileChange(evt));
	}

	private void handleFileChange(FileChangeEvent evt) {
		File file = evt.getFile();
		TransferObject transferObject = (TransferObject) monitoredTransfers
				.get(file);
		if (transferObject == null) {
			return;
		}
		final LocalFile localFile = transferObject.getLocalFile();
		final RemoteEntry remoteFile = transferObject.getRemoteFile();
		MessageFormat formatter = new MessageFormat(
				resources.getString("confirm.uploadChangedFile"));
		String message = formatter.format(new Object[] { remoteFile,
				getRemoteHost() });
		int confirm = GUIUtil.showConfirmation(this, message);
		if (confirm != JOptionPane.YES_OPTION) {
			return;
		}
		setBusy(true);
		new Worker() {

			private RemoteEntry[] children = null;

			@Override
			public Object construct() {
				upload(localFile, remoteFile);
				try {
					children = client.list(remoteFileFilter);
				} catch (ReplyFailure exp) {
					ftpException(exp);
				} catch (LinkFailure exp) {
					connectionException(exp);
				} catch (Exception exp) {
					operations.publish(() -> statusWindow.addError("<ERROR> " + exp));
					exp.printStackTrace();
				}
				return null;
			}

			@Override
			public void finished() {
				remotePane.setData(rwd, children);
				setBusy(false);
			}
		}.start();
	}

	private void updateOwnToolbar() {
		operations.publish(() -> {
			if (jftp.getCurrentSession() == this) {
				jftp.updateToolBar();
			}
		});
	}

	public int getId() {
		return id;
	}

	@Override
	public void setBusy(boolean busy) {
		operations.publish(() -> super.setBusy(busy));
	}

	public void setTransferType(int transferType) {
		this.transferType = transferType;
	}

	public int getTransferType() {
		return transferType;
	}

	public void setAutoDetect(boolean autoDetect) {
		this.autoDetect = autoDetect;
	}

	public boolean isAutoDetect() {
		return autoDetect;
	}

	public int getTransferType(String ext) {
		if (autoDetect) {
			Object obj = JFTP.prefs.getTransferTypes().get(ext);
			if (obj == null) {
				return JFTP.prefs.getDefaultTransferType();
			} else {
				return ((Integer) obj).intValue();
			}
		}
		return transferType;
	}

	public void actionPerformed(ActionEvent evt) {
		Object source = evt.getSource();
		if (source == timer) {
			timeElapsed++;
			operations.publish(() -> statusBar.setProgress((int) bytesTransferred));
			operations.publish(() -> statusBar.setSpeed(bytesTransferred / timeElapsed));
			operations.publish(() -> statusBar.setTimeElapsed(JFTPUtil.getTimeString(timeElapsed)));
		} else if (source == progressTimer) {
			timeElapsed++;
			operations.publish(() -> statusBar.setProgress(progress));
			operations.publish(() -> statusBar.setTimeElapsed(JFTPUtil.getTimeString(timeElapsed)));
		}
	}

	@Override
	public String toString() {
		return getTitle();
	}

	/**
	 * @param filter
	 */
	public void setLocalFileFilter(Filter filter) {
		this.localFileFilter = filter;
		localPane.refresh();
	}

	/**
	 * @return current filter set on local files.
	 */
	public Filter getLocalFileFilter() {
		return localFileFilter;
	}

	/**
	 * @param filter
	 */
	public void setRemoteFileFilter(ListingFilter filter) {
		this.remoteFileFilter = filter;
		remotePane.refresh();
	}

	/**
	 * @return current filter set on local files.
	 */
	public ListingFilter getRemoteFileFilter() {
		return remoteFileFilter;
	}

	private void updateTitle() {
		jftp.updateSessionTitle(this);
	}

	private Component getCenterPanel() {
		localPane = new LocalPane(this);
		remotePane = new RemotePane(this);
		statusWindow = new StatusWindow();
		MScrollPane scroller = new MScrollPane(statusWindow);

		splitPane1 = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, localPane,
				remotePane);
		splitPane1.setOneTouchExpandable(true);
		splitPane1.setResizeWeight(0.5d);
		splitPane1.setContinuousLayout(true);

		splitPane2 = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scroller,
				splitPane1);
		splitPane2.setOneTouchExpandable(true);
		splitPane2.setContinuousLayout(true);
		splitPane2.setResizeWeight(0.2d);

		return splitPane2;
	}

	private Component getSouthPanel() {
		statusBar = new StatusBar();
		return statusBar;
	}

	public void closeSession() {
		abort = true;
		timer.stop();
		progressTimer.stop();
		operations.close(() -> {
			LegacyFtpAdapter retired = client;
			if (retired != null) {
				retired.close();
			}
			fileChangeMonitor.stopMonitor();
		});
	}

	public void abortTransfer() {
		LegacyFtpAdapter target = client;
		if (target == null || !target.isConnected()) {
			return;
		}
		abort = true;
		operations.signal(() -> {
			try {
				target.abort();
			} catch (ReplyFailure failure) {
				ftpException(failure);
			} catch (LinkFailure failure) {
				connectionException(failure);
			}
		});
	}

	public abstract class Worker {
		public abstract Object construct();

		public void finished() {
		}

		public final void start() {
			operations.submit(() -> setBusy(true), this::construct, failure -> {
				try {
					if (failure == null) {
						finished();
					} else {
						exception(failure);
						operations.publish(() -> statusBar.reset());
					}
				} finally {
					setBusy(false);
				}
			});
		}
	}

	void publishUi(Runnable result) {
		operations.publish(result);
	}

	public RemoteHost getRemoteHost() {
		return remoteHost;
	}

	LegacyFtpAdapter getTransport() {
		return client;
	}

	public void setLocalWorkingDirectory(String dir) {
		File file = new File(dir);
		if (!file.isAbsolute()) {
			file = new File(lwd.getFile(), dir);
		}
		setLocalWorkingDirectory(new LocalFile(file));
	}

	public void setLocalWorkingDirectory(final LocalFile dir) {
		if (dir == null || !dir.isDirectory() || !dir.canRead()) {
			GUIUtil.showError(this,
					resources.getString("error.changeLocalDirectory"));
			return;
		}
		setBusy(true);
		new Worker() {

			LocalFile[] data = null;

			@Override
			public Object construct() {
				data = dir.list(localFileFilter);
				lwd = dir;
				return null;
			}

			@Override
			public void finished() {
				localPane.setData(dir, data);
				setBusy(false);
			}
		}.start();
	}

	public LocalFile getLocalWorkingDirectory() {
		return lwd;
	}

	public void upLocalWorkingDirectory() {
		if (lwd == null) {
			return;
		}
		LocalFile parent = lwd.getParent();
		if (parent == null) {
			return;
		}
		setLocalWorkingDirectory(parent);
	}

	public void refreshLocalPane() {
		localPane.refresh();
	}

	public void setRemoteWorkingDirectory(String name) {
		RemoteEntry dir = client.createRemoteFile(name, true);
		setRemoteWorkingDirectory(dir);
	}

	public RemoteEntry getRemoteWorkingDirectory() {
		return rwd;
	}

	public void setRemoteWorkingDirectory(final RemoteEntry dir) {
		if (dir == null || !(dir.isDirectory() || dir.isLink())) {
			return;
		}
		Object args = new Object[] { dir.getName() };
		String status = listStatusFormat.format(args);
		operations.publish(() -> statusBar.setStatus(status));
		operations.publish(() -> statusBar.setIndeterminate(true));
		setBusy(true);
		new Worker() {

			RemoteEntry[] data = null;

			@Override
			public Object construct() {
				try {
					client.setWorkingDirectory(dir);
					RemoteEntry wd = client.getWorkingDirectory();
					rwd = wd;
					data = client.list(remoteFileFilter);
				} catch (ReplyFailure exp) {
					ftpException(exp);
				} catch (ParseException exp) {
					statusWindow
							.addError("<ERROR> Could not parse the LIST command output. "
									+ "The original data returned by the server is: \n"
									+ exp.getMessage());
				} catch (LinkFailure exp) {
					connectionException(exp);
				} catch (Exception exp) {
					operations.publish(() -> statusWindow.addError("<ERROR> " + exp));
					exp.printStackTrace();
				} finally {
					return null;
				}
			}

			@Override
			public void finished() {
				remotePane.setData(rwd, data);
				operations.publish(() -> statusBar.reset());
				setBusy(false);
			}
		}.start();
	}

	public void upRemoteWorkingDirectory() {
		if (!isConnected()) {
			return;
		}
		operations.publish(() -> statusBar.setStatus(listParentStatus));
		operations.publish(() -> statusBar.setIndeterminate(true));
		setBusy(true);
		new Worker() {

			RemoteEntry[] data = null;

			@Override
			public Object construct() {
				try {
					client.setToParentDirectory();
					RemoteEntry wd = client.getWorkingDirectory();
					rwd = wd;
					data = client.list(remoteFileFilter);
				} catch (ReplyFailure exp) {
					ftpException(exp);
				} catch (ParseException exp) {
					statusWindow
							.addError("<ERROR> Could not parse the LIST command output. "
									+ "The original data returned by the server is: \n"
									+ exp.getMessage());
				} catch (LinkFailure exp) {
					connectionException(exp);
				} catch (Exception exp) {
					operations.publish(() -> statusWindow.addError("<ERROR> " + exp));
					exp.printStackTrace();
				} finally {
					return null;
				}
			}

			@Override
			public void finished() {
				remotePane.setData(rwd, data);
				operations.publish(() -> statusBar.reset());
				setBusy(false);
			}
		}.start();
	}

	public void refreshRemotePane() {
		remotePane.refresh();
	}

	public int getLocalFileSelectionCount() {
		return localPane.getSelectionCount();
	}

	public LocalFile getSelectedLocalFile() {
		return localPane.getSelectedFile();
	}

	public LocalFile[] getSelectedLocalFiles() {
		return localPane.getSelectedFiles();
	}

	public int getRemoteFileSelectionCount() {
		return remotePane.getSelectionCount();
	}

	public RemoteEntry getSelectedRemoteFile() {
		return remotePane.getSelectedFile();
	}

	public RemoteEntry[] getSelectedRemoteFiles() {
		return remotePane.getSelectedFiles();
	}

	private void onTransportEvent(Object connection, Event event) {
		operations.publish(() -> {
			if (transportOrigin == connection) {
				applyTransportEvent(event);
			}
		});
	}

	private void applyTransportEvent(Event event) {
		switch (event.kind()) {
		case CONNECTED -> {
			statusWindow.addStatus("<STATUS> " + event.message());
			statusBar.setSecured(client != null && client.isSecured());
			updateOwnToolbar();
		}
		case DISCONNECTED -> {
			setBusy(false);
			statusWindow.addStatus("<STATUS> " + event.message());
			updateOwnToolbar();
			timer.stop();
			timeElapsed = 0;
			bytesTransferred = 0L;
			statusBar.reset();
			statusBar.setSecured(false);
			remotePane.clearAll();
			updateTitle();
		}
		case COMMAND -> statusWindow.addCommand("<COMMAND> " + event.message());
		case REPLY -> statusWindow.addReply("<REPLY> " + event.message());
		case TRANSFER_FINISHED -> statusBar.setProgress(0);
		case TRANSFER_PROGRESS -> bytesTransferred = event.bytes();
		case TRANSFER_ABORTED -> {
			statusWindow.addStatus("<STATUS> Data transfer aborted by the user. ");
			timer.stop();
			timeElapsed = 0;
			bytesTransferred = 0L;
			statusBar.reset();
		}
		}
	}

	public void ftpException(Exception exp) {
		operations.publish(() -> statusWindow.addError(exp.getMessage()));
	}

	public void exception(Throwable t) {
		operations.publish(() -> statusWindow.addError(t.toString()));
		t.printStackTrace();
	}

	public void connectionException(Exception exp) {
		operations.publish(() -> applyConnectionFailure(exp));
	}

	private void applyConnectionFailure(Exception exp) {
		setBusy(false);
		statusWindow.addError(exp.getMessage());
		updateOwnToolbar();
		timer.stop();
		this.timeElapsed = 0;
		this.bytesTransferred = 0L;
		statusBar.reset();
		remotePane.clearAll();
		updateTitle();
		exp.printStackTrace();
	}

	public void connect(final RemoteHost remoteHost) {
		if (isConnected()) {
			MessageFormat format = new MessageFormat(
					resources.getString("confirm.reconnect"));
			int option = GUIUtil
					.showConfirmation(
							this,
							format.format(new Object[] { this.remoteHost,
									remoteHost }));
			if (option != JOptionPane.YES_OPTION) {
				return;
			}
			disconnect();
		}
		this.remoteHost = remoteHost;
		Endpoint endpoint = Endpoint.capture(remoteHost);
		String[] startupCommands = remoteHost.getCommands();
		String[] commands = startupCommands == null ? new String[0] : startupCommands.clone();
		String initialRemoteDirectory = remoteHost.getInitialRemoteDirectory();
		String initialLocalDirectory = remoteHost.getInitialLocalDirectory();
		int timeout = JFTP.prefs.getTimeout();
		int bufferSize = JFTP.prefs.getBufferSize();
		Object args = new Object[] { endpoint.host(), endpoint.port() };
		String status = connectStatusFormat.format(args);
		operations.publish(() -> statusBar.setStatus(status));
		operations.publish(() -> statusBar.setIndeterminate(true));
		setBusy(true);
		new Worker() {

			RemoteEntry[] data = null;

			@Override
			public Object construct() {
				try {
					var ssl = endpoint.requiresSecurity()
							? JFTPSSLContext.getSSLContext(jftp, endpoint.host(), () -> !operations.isClosed())
							: null;
					Object connection = new Object();
					LegacyFtpAdapter transport = LegacyFtpAdapter.create(endpoint, timeout, bufferSize, ssl,
							event -> onTransportEvent(connection, event));
					transportOrigin = connection;
					client = transport;
					if (operations.isClosed()) {
						transport.close();
						return null;
					}
					try {
						client.connect();
					} catch (ReplyFailure exp) {
						ftpException(exp);
						client.disconnect();
						client = null;
						return null;
					}
					for (int i = 0; i < commands.length; i++) {
						client.executeCommand(commands[i]);
					}
					if (initialRemoteDirectory != null
							&& initialRemoteDirectory.trim().length() > 0) {
						RemoteEntry remoteDir = client
								.createRemoteFile(initialRemoteDirectory);
						client.setWorkingDirectory(remoteDir);
					}
					RemoteEntry dir = client.getWorkingDirectory();
					data = client.list(remoteFileFilter);
					rwd = dir;
				} catch (ReplyFailure exp) {
					ftpException(exp);
				} catch (LinkFailure exp) {
					connectionException(exp);
				} catch (Exception exp) {
					operations.publish(() -> statusWindow.addError("<ERROR> " + exp.getMessage()));
					exp.printStackTrace();
				} finally {
					return null;
				}
			}

			@Override
			public void finished() {
				if (client != null && client.isConnected()) {
					remotePane.setData(rwd, data);
					updateTitle();
				}
				operations.publish(() -> statusBar.reset());
				setBusy(false);
				setLocalWorkingDirectory(initialLocalDirectory);
			}
		}.start();
	}

	public void reconnect() {
		if (remoteHost != null) {
			connect(this.remoteHost);
		}
	}

	public void download() {
		if (!isConnected()) {
			return;
		}
		final RemoteEntry[] selectedFiles = remotePane.getSelectedFiles();
		if (selectedFiles == null || selectedFiles.length == 0) {
			return;
		}
		abort = false;
		setBusy(true);
		new Worker() {

			LocalFile[] children = null;

			@Override
			public Object construct() {
				for (int i = 0; i < selectedFiles.length; i++) {
					if (!client.isConnected() || abort) {
						break;
					}
					download(rwd, lwd.getFile(), selectedFiles[i]);
				}
				children = lwd.list(localFileFilter);
				return null;
			}

			@Override
			public void finished() {
				localPane.setData(lwd, children);
				setBusy(false);
			}
		}.start();
	}

	public void download(RemoteEntry sourceDir, File targetDir, RemoteEntry source) {
		download(sourceDir, targetDir, source, null);
	}

	public void download(RemoteEntry sourceDir, File targetDir,
			RemoteEntry source, String targetName) {

		if (client == null || !client.isConnected() || abort) {
			return;
		}
		if (source.isDirectory()
				&& (source.getName().equals(".") || source.getName().equals(
						".."))) {
			return;
		}
		if (targetName == null) {
			targetName = source.getNormalizedName();
		}
		if (source.isFile()) {
			File destination = new File(targetDir, targetName);
			// int type = getTransferType(source.getExtension());
			// Object[] args = {source.getName()};
			// statusBar.setStatus(downloadStatusFormat.format(args));
			// statusBar.setMaximum((int) source.getSize());
			// timeElapsed = 0;
			// timer.start();
			// try {
			// client.download(source, destination, type);
			// }
			// catch(ReplyFailure exp) {
			// ftpException(exp);
			// }
			// catch(LinkFailure exp) {
			// connectionException(exp);
			// }
			// catch(Exception exp) {
			// statusWindow.addError("<ERROR> " + exp);
			// exp.printStackTrace();
			// }
			// finally {
			// timer.stop();
			// timeElapsed = 0;
			// statusBar.reset();
			// }
			downloadDataFile(source, destination);
		} else if (source.isDirectory()) {
			// File target = new File(targetDir, source.getName());
			File target = new File(targetDir, targetName);
			if (!target.exists()) {
				target.mkdirs();
			}
			RemoteEntry[] children = null;
			try {
				client.setWorkingDirectory(source);
				children = client.list(remoteFileFilter);
			} catch (ReplyFailure exp) {
				ftpException(exp);
			} catch (LinkFailure exp) {
				connectionException(exp);
			} catch (Exception exp) {
				operations.publish(() -> statusWindow.addError("<ERROR> " + exp));
				exp.printStackTrace();
			}
			if (children != null) {
				for (int i = 0; i < children.length; i++) {
					download(source, target, children[i], null);
				}
			}
		}
	}

	public void downloadDataFile(RemoteEntry source, File target) {
		int type = getTransferType(source.getExtension());
		Object[] args = { source.getName() };
		operations.publish(() -> statusBar.setStatus(downloadStatusFormat.format(args)));
		operations.publish(() -> statusBar.setMaximum((int) source.getSize()));
		timeElapsed = 0;
		operations.publish(() -> timer.start());
		try {
			client.download(source, target, type);
		} catch (ReplyFailure exp) {
			ftpException(exp);
		} catch (LinkFailure exp) {
			connectionException(exp);
		} catch (Exception exp) {
			operations.publish(() -> statusWindow.addError("<ERROR> " + exp));
			exp.printStackTrace();
		} finally {
			operations.publish(() -> timer.stop());
			timeElapsed = 0;
			operations.publish(() -> statusBar.reset());
		}
	}

	public void upload() {
		if (!isConnected()) {
			return;
		}
		final LocalFile[] selectedFiles = localPane.getSelectedFiles();
		if (selectedFiles == null || selectedFiles.length == 0) {
			return;
		}
		abort = false;
		setBusy(true);
		new Worker() {

			RemoteEntry[] children = null;

			@Override
			public Object construct() {
				for (int i = 0; i < selectedFiles.length; i++) {
					if (!client.isConnected() || abort) {
						break;
					}
					upload(lwd, rwd, selectedFiles[i]);
				}
				try {
					children = client.list(remoteFileFilter);
				} catch (ReplyFailure exp) {
					ftpException(exp);
				} catch (LinkFailure exp) {
					connectionException(exp);
				} catch (Exception exp) {
					operations.publish(() -> statusWindow.addError("<ERROR> " + exp));
					exp.printStackTrace();
				} finally {
					return null;
				}
			}

			@Override
			public void finished() {
				remotePane.setData(rwd, children);
				setBusy(false);
			}
		}.start();
	}

	public void upload(LocalFile sourceDir, RemoteEntry targetDir,
			LocalFile source) {
		upload(sourceDir, targetDir, source, null);
	}

	public void upload(LocalFile sourceDir, RemoteEntry targetDir,
			LocalFile source, String targetName) {
		if (client == null || !client.isConnected() || abort) {
			return;
		}
		if (source.isDirectory()
				&& (source.getName().equals(".") || source.getName().equals(
						".."))) {
			return;
		}
		if (targetName == null) {
			targetName = source.getName();
		}
		if (source.isFile()) {
			// RemoteEntry destination = client.createRemoteFile(targetDir,
			// source.getName(), false);
			RemoteEntry destination = client.createRemoteFile(targetDir,
					targetName, false);
			int type = getTransferType(source.getExtension());
			Object[] args = { destination.getName() };
			operations.publish(() -> statusBar.setStatus(uploadStatusFormat.format(args)));
			operations.publish(() -> statusBar.setMaximum((int) source.getSize()));
			timeElapsed = 0;
			operations.publish(() -> timer.start());
			try {
				client.upload(source.getFile(), destination, type);
			} catch (ReplyFailure exp) {
				ftpException(exp);
			} catch (LinkFailure exp) {
				connectionException(exp);
			} catch (Exception exp) {
				operations.publish(() -> statusWindow.addError("<ERROR> " + exp));
				exp.printStackTrace();
			} finally {
				operations.publish(() -> timer.stop());
				timeElapsed = 0;
				operations.publish(() -> statusBar.reset());
			}
		} else if (source.isDirectory()) {
			// RemoteEntry destination = client.createRemoteFile(targetDir,
			// source.getName(), true);
			RemoteEntry destination = client.createRemoteFile(targetDir,
					targetName, true);
			try {
				client.createDirectory(destination);
			} catch (ReplyFailure exp) {
				ftpException(exp);
			} catch (LinkFailure exp) {
				connectionException(exp);
			} catch (Exception exp) {
				operations.publish(() -> statusWindow.addError("<ERROR> " + exp));
				exp.printStackTrace();
			}
			LocalFile[] children = source.list(localFileFilter);
			for (int i = 0; i < children.length; i++) {
				upload(source, destination, children[i], null);
			}
		}
	}

	public void upload(LocalFile localFile, String remoteFileName) {
		RemoteEntry remoteFile = client
				.createRemoteFile(rwd, remoteFileName);
		upload(localFile, remoteFile);
	}

	public void upload(LocalFile localFile, RemoteEntry destination) {
		int type = getTransferType(localFile.getExtension());
		Object[] args = { destination.getName() };
		operations.publish(() -> statusBar.setStatus(uploadStatusFormat.format(args)));
		operations.publish(() -> statusBar.setMaximum((int) localFile.getSize()));
		timeElapsed = 0;
		operations.publish(() -> timer.start());
		try {
			client.upload(localFile.getFile(), destination, type);
		} catch (ReplyFailure exp) {
			ftpException(exp);
		} catch (LinkFailure exp) {
			connectionException(exp);
		} catch (Exception exp) {
			operations.publish(() -> statusWindow.addError("<ERROR> " + exp));
			exp.printStackTrace();
		} finally {
			operations.publish(() -> timer.stop());
			timeElapsed = 0;
			operations.publish(() -> statusBar.reset());
		}
	}

	public void createRemoteDirectory(final String directory) {
		setBusy(true);
		new Worker() {

			RemoteEntry[] data = null;

			@Override
			public Object construct() {
				RemoteEntry dir = client.createRemoteFile(directory, true);
				try {
					client.createDirectory(dir);
					data = client.list(remoteFileFilter);
				} catch (ReplyFailure exp) {
					ftpException(exp);
				} catch (LinkFailure exp) {
					connectionException(exp);
				} catch (Exception exp) {
					operations.publish(() -> statusWindow.addError("<ERROR> " + exp.getMessage()));
					exp.printStackTrace();
				} finally {
					return null;
				}
			}

			@Override
			public void finished() {
				if (data != null) {
					remotePane.setData(rwd, data);
				}
				setBusy(false);
			}
		}.start();
	}

	public void createRemoteFile(final String file) {
		setBusy(true);
		new Worker() {

			RemoteEntry[] data = null;

			@Override
			public Object construct() {
				RemoteEntry newFile = client.createRemoteFile(file, false);
				try {
					client.createFile(newFile);
					data = client.list(remoteFileFilter);
				} catch (ReplyFailure exp) {
					ftpException(exp);
				} catch (LinkFailure exp) {
					connectionException(exp);
				} catch (Exception exp) {
					operations.publish(() -> statusWindow.addError("<ERROR> " + exp.getMessage()));
					exp.printStackTrace();
				} finally {
					return null;
				}
			}

			@Override
			public void finished() {
				if (data != null) {
					remotePane.setData(rwd, data);
				}
				setBusy(false);
			}
		}.start();
	}

	public void renameRemoteFile(final String fromName, final String toName) {
		setBusy(true);
		new Worker() {

			RemoteEntry[] data = null;

			@Override
			public Object construct() {
				RemoteEntry from = client.createRemoteFile(fromName);
				RemoteEntry to = client.createRemoteFile(toName);
				try {
					client.rename(from, to);
					data = client.list(remoteFileFilter);
				} catch (ReplyFailure exp) {
					ftpException(exp);
				} catch (Exception exp) {
					System.err.println(exp);
					exp.printStackTrace();
				} finally {
					return null;
				}
			}

			@Override
			public void finished() {
				if (data != null) {
					remotePane.setData(rwd, data);
				}
				setBusy(false);
			}
		}.start();
	}

	public void changeRemoteFilePermissions(final RemoteEntry oldFile,
			final RemoteEntry newFile, final boolean recursive) {
		setBusy(true);
		new Worker() {

			RemoteEntry[] data = null;

			@Override
			public Object construct() {
				try {
					String newAttributes = newFile.getAttributes();
					newAttributes = newAttributes.substring(1);
					if (recursive) {
						changeRemoteFilePermissions(oldFile,
								LegacyFtpAdapter.parseAttributes(newAttributes));
					} else {
						String fileName = oldFile.getNormalizedPath();
						// if(fileName.indexOf(' ') > 0) {
						// fileName = "\"" + fileName + "\"";
						// }
						client.executeCommand("SITE CHMOD "
								+ LegacyFtpAdapter.parseAttributes(newAttributes) + " "
								+ fileName);
					}
					client.setWorkingDirectory(rwd);
					data = client.list(remoteFileFilter);
				} catch (ReplyFailure exp) {
					ftpException(exp);
				} catch (Exception exp) {
					System.err.println(exp);
					exp.printStackTrace();
				} finally {
					return null;
				}
			}

			@Override
			public void finished() {
				if (data != null) {
					remotePane.setData(rwd, data);
				}
				setBusy(false);
			}
		}.start();
	}

	private void changeRemoteFilePermissions(RemoteEntry file, String attributes) {
		if (client == null || !client.isConnected() || abort) {
			return;
		}
		if (file.isDirectory()
				&& (file.getName().equals(".") || file.getName().equals(".."))) {
			return;
		}
		String fileName = file.getNormalizedPath();
		// if(fileName.indexOf(' ') > 0) {
		// fileName = "\"" + fileName + "\"";
		// }
		try {
			client.executeCommand("SITE CHMOD " + attributes + " " + fileName);
		} catch (ReplyFailure exp) {
			ftpException(exp);
		} catch (LinkFailure exp) {
			connectionException(exp);
		}
		if (file.isDirectory()) {
			RemoteEntry[] children = null;
			try {
				client.setWorkingDirectory(file);
				children = client.list(remoteFileFilter);
			} catch (ReplyFailure exp) {
				ftpException(exp);
			} catch (LinkFailure exp) {
				connectionException(exp);
			} catch (Exception exp) {
				operations.publish(() -> statusWindow.addError("<ERROR> " + exp));
				exp.printStackTrace();
			}
			if (children != null) {
				for (int i = 0; i < children.length; i++) {
					changeRemoteFilePermissions(children[i], attributes);
				}
			}
		}
	}

	public void updateRemoteFile(final RemoteEntry oldFile,
			final RemoteEntry newFile) {
		setBusy(true);
		new Worker() {

			RemoteEntry[] data = null;

			@Override
			public Object construct() {
				try {
					boolean reload = false;
					String oldName = oldFile.getName();
					String newName = newFile.getName();
					if (!oldName.equals(newName)) {
						client.rename(oldFile, newFile);
						reload = true;
					}
					String oldAttributes = oldFile.getAttributes();
					String newAttributes = newFile.getAttributes();
					if (!oldAttributes.equals(newAttributes)) {
						newAttributes = newAttributes.substring(1);
						client.executeCommand("SITE CHMOD " + newName + " "
								+ LegacyFtpAdapter.parseAttributes(newAttributes));
						reload = true;
					}
					if (reload) {
						data = client.list(remoteFileFilter);
					}
				} catch (Exception exp) {
					System.err.println(exp);
					exp.printStackTrace();
				} finally {
					return null;
				}
			}

			@Override
			public void finished() {
				if (data != null) {
					remotePane.setData(rwd, data);
				}
				setBusy(false);
			}
		}.start();
	}

	public void deleteRemoteFiles() {
		final RemoteEntry[] selectedFiles = remotePane.getSelectedFiles();
		if (selectedFiles == null || selectedFiles.length == 0) {
			return;
		}
		int option = GUIUtil.showConfirmation(jftp,
				resources.getString("confirm.deleteRemoteFiles"));
		if (option != JOptionPane.YES_OPTION) {
			return;
		}
		setBusy(true);
		new Worker() {

			RemoteEntry[] data = null;

			@Override
			public Object construct() {
				for (int i = 0; i < selectedFiles.length; i++) {
					delete(selectedFiles[i]);
				}
				try {
					client.setWorkingDirectory(rwd);
					data = client.list(remoteFileFilter);
				} catch (ReplyFailure exp) {
					ftpException(exp);
				} catch (LinkFailure exp) {
					connectionException(exp);
				} catch (Exception exp) {
					operations.publish(() -> statusWindow.addError("<ERROR> " + exp));
					exp.printStackTrace();
				} finally {
					return null;
				}
			}

			@Override
			public void finished() {
				remotePane.setData(rwd, data);
				setBusy(false);
			}
		}.start();
	}

	private void delete(RemoteEntry file) {
		if (client == null || !client.isConnected()) {
			return;
		}
		if (file.isDirectory()
				&& (file.getName().equals(".") || file.getName().equals(".."))) {
			return;
		}
		try {
			if (file.isFile()) {
				client.delete(file);
			} else if (file.isDirectory()) {
				RemoteEntry[] children = null;
				client.setWorkingDirectory(file);
				children = client.list(remoteFileFilter);
				for (int i = 0; i < children.length; i++) {
					delete(children[i]);
				}
				client.setToParentDirectory();
				client.delete(file);
			}
		} catch (ReplyFailure exp) {
			ftpException(exp);
		} catch (LinkFailure exp) {
			connectionException(exp);
		} catch (Exception exp) {
			operations.publish(() -> statusWindow.addError("<ERROR> " + exp));
			exp.printStackTrace();
		}
	}

	public void createLocalDirectory(final String dir) {
		setBusy(true);
		new Worker() {

			LocalFile[] data = null;
			boolean created = true;

			@Override
			public Object construct() {
				File file = new File(dir);
				if (!file.isAbsolute()) {
					file = new File(lwd.getFile(), dir);
				}
				created = file.mkdirs();
				if (created) {
					data = lwd.list(localFileFilter);
				}
				return null;
			}

			@Override
			public void finished() {
				if (!created) {
					GUIUtil.showError(jftp,
							resources.getString("error.createLocalDirectory"));
				} else {
					localPane.setData(lwd, data);
				}
				setBusy(false);
			}
		}.start();
	}

	public void createLocalFile(final String fileName) {
		setBusy(true);
		new Worker() {

			LocalFile[] data = null;
			boolean created = true;
			Exception error;

			@Override
			public Object construct() {
				File file = new File(fileName);
				if (!file.isAbsolute()) {
					file = new File(lwd.getFile(), fileName);
				}
				try {
					created = file.createNewFile();
					if (created) {
						data = lwd.list(localFileFilter);
					}
				} catch (Exception exp) {
					error = exp;
				}
				return null;
			}

			@Override
			public void finished() {
				if (error != null) {
					GUIUtil.showError(jftp, error);
				} else if (!created) {
					GUIUtil.showError(jftp,
							resources.getString("error.createLocalFile"));
				} else {
					localPane.setData(lwd, data);
				}
				setBusy(false);
			}
		}.start();
	}

	public void renameLocalFile(String fromName, String toName) {
		File fromFile = new File(fromName);
		File toFile = new File(toName);
		if (!fromFile.isAbsolute()) {
			fromFile = new File(lwd.getFile(), fromName);
		}
		if (!toFile.isAbsolute()) {
			toFile = new File(lwd.getFile(), toName);
		}
		boolean renamed = fromFile.renameTo(toFile);
		if (renamed) {
			LocalFile[] children = lwd.list(localFileFilter);
			localPane.setData(lwd, children);
		} else {
			GUIUtil.showError(this,
					resources.getString("error.renameLocalFile"));
		}
	}

	public void deleteLocalFiles() {
		final LocalFile[] selectedFiles = localPane.getSelectedFiles();
		if (selectedFiles == null || selectedFiles.length == 0) {
			return;
		}
		int option = GUIUtil.showConfirmation(this,
				resources.getString("conform.deleteLocalFiles"));
		if (option != JOptionPane.YES_OPTION) {
			return;
		}

		setBusy(true);

		new Worker() {

			LocalFile[] data = null;

			@Override
			public Object construct() {
				for (int i = 0; i < selectedFiles.length; i++) {
					// delete(selectedFiles[i].getFile());
					delete(selectedFiles[i]);
				}
				data = lwd.list(localFileFilter);
				return null;
			}

			@Override
			public void finished() {
				setBusy(false);
				localPane.setData(lwd, data);
			}
		}.start();
	}

	private void delete(LocalFile file) {
		if (file.isDirectory()
				&& (file.getName().equals(".") || file.getName().equals(".."))) {
			return;
		}
		if (file.isFile()) {
			file.delete();
		} else if (file.isDirectory()) {
			LocalFile[] children = file.list(localFileFilter);
			for (int i = 0; i < children.length; i++) {
				delete(children[i]);
			}
			file.delete();
		}
	}

	public void executeCommand(final String command) {
		if (client == null || !client.isConnected()) {
			return;
		}
		setBusy(true);
		new Worker() {

			@Override
			public Object construct() {
				try {
					client.executeCommand(command);
				} catch (ReplyFailure exp) {
					ftpException(exp);
				} catch (LinkFailure exp) {
					connectionException(exp);
				} catch (Exception exp) {
					operations.publish(() -> statusWindow.addError(exp.toString()));
					exp.printStackTrace();
				} finally {
					return null;
				}
			}

			@Override
			public void finished() {
				setBusy(false);
			}
		}.start();
	}

	public void executeCommands(final String[] commands) {
		if (commands == null || client == null || !client.isConnected()) {
			return;
		}
		setBusy(true);
		new Worker() {

			@Override
			public Object construct() {
				try {
					for (int i = 0; i < commands.length; i++) {
						String command = commands[i].toUpperCase();
						if (!(command.startsWith("LIST")
								|| command.startsWith("NLST")
								|| command.startsWith("RETR")
								|| command.startsWith("STOR")
								|| command.startsWith("STOU")
								|| command.startsWith("APPE")
								|| command.startsWith("PASV")
								|| command.startsWith("PORT")
								|| command.startsWith("DATA")
								|| command.startsWith("CWD") || command
									.startsWith("CDUP"))) {
							client.executeCommand(commands[i]);
						}
					}
				} catch (ReplyFailure exp) {
					ftpException(exp);
				} catch (LinkFailure exp) {
					connectionException(exp);
				} catch (Exception exp) {
					operations.publish(() -> statusWindow.addError(exp.toString()));
					exp.printStackTrace();
				} finally {
					return null;
				}
			}

			@Override
			public void finished() {
				setBusy(false);
			}
		}.start();
	}

	public void disconnect() {
		if (client == null || !client.isConnected()) {
			return;
		}
		setBusy(true);
		new Worker() {

			@Override
			public Object construct() {
				try {
					client.disconnect();
				} catch (ReplyFailure exp) {
					ftpException(exp);
				} catch (LinkFailure exp) {
					connectionException(exp);
				} catch (Exception exp) {
					operations.publish(() -> statusWindow.addError("<ERROR> " + exp));
					exp.printStackTrace();
				} finally {
					return null;
				}
			}

			@Override
			public void finished() {
				if (timer.isRunning()) {
					operations.publish(() -> timer.stop());
				}
				operations.publish(() -> statusBar.reset());
				bytesTransferred = 0L;
				timeElapsed = 0;
				remotePane.clearAll();
				updateTitle();
				setBusy(false);
			}
		}.start();
	}

	public boolean isConnected() {
		if (client == null) {
			return false;
		}
		return client.isConnected();
	}

	public File downloadToTempFile(RemoteEntry source, boolean monitor) {
		if (!source.isFile()) {
			return null;
		}
		File destination = null;
		try {
			destination = File.createTempFile("jftp",
					"." + source.getExtension());
			destination.deleteOnExit();
		} catch (IOException exp) {
			operations.publish(() -> statusWindow.addError("<ERROR> " + exp));
			exp.printStackTrace();
			return null;
		}
		int type = getTransferType(source.getExtension());
		Object[] args = { source.getName() };
		operations.publish(() -> statusBar.setStatus(downloadStatusFormat.format(args)));
		operations.publish(() -> statusBar.setMaximum((int) source.getSize()));
		timeElapsed = 0;
		operations.publish(() -> timer.start());
		try {
			client.download(source, destination, type);
			if (monitor) {
				fileChangeMonitor.add(destination);
				monitoredTransfers.put(destination, new TransferObject(
						TransferObject.UPLOAD, new LocalFile(destination),
						source));
			}
			return destination;
		} catch (ReplyFailure exp) {
			ftpException(exp);
			return null;
		} catch (LinkFailure exp) {
			connectionException(exp);
			return null;
		} catch (Exception exp) {
			operations.publish(() -> statusWindow.addError("<ERROR> " + exp));
			exp.printStackTrace();
			return null;
		} finally {
			operations.publish(() -> timer.stop());
			timeElapsed = 0;
			operations.publish(() -> statusBar.reset());
		}
	}

	public void updateToolBar() {
		updateOwnToolbar();
	}

	public void setAbortFlag(boolean abort) {
		this.abort = abort;
	}

	public void clearAbortFlag() {
		abort = false;
	}

	public JFTP getJFTP() {
		return jftp;
	}

	public void setStatus(String status) {
		operations.publish(() -> statusBar.setStatus(status));
	}

	public void resetStatusBar() {
		operations.publish(() -> statusBar.reset());
	}

	public void setProgress(int progress) {
		operations.publish(() -> statusBar.setProgress(progress));
	}

	public void progressChanged(ProgressEvent evt) {
		progress = evt.getProgress();
	}

	public void beginFile(ZipEvent evt) {
		Object[] args = { evt.getFile() };
		if (evt.getType() == ZipEvent.ZIP) {
			operations.publish(() -> statusBar.setStatus(zipStatusFormat.format(args)));
		} else if (evt.getType() == ZipEvent.UNZIP) {
			operations.publish(() -> statusBar.setStatus(unzipStatusFormat.format(args)));
		}
		operations.publish(() -> progressTimer.start());
	}

	public void endFile(ZipEvent evt) {
		operations.publish(() -> progressTimer.stop());
		timeElapsed = 0;
		operations.publish(() -> statusBar.reset());
	}

	public void selectAllLocalFiles() {
		localPane.selectAll();
	}

	public void invertLocalFileSelection() {
		localPane.invertSelection();
	}

	public void selectAllRemoteFiles() {
		remotePane.selectAll();
	}

	public void invertRemoteFileSelection() {
		remotePane.invertSelection();
	}
}
