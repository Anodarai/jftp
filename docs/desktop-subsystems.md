# Desktop subsystems

Source baseline `3f7f92d`. The source inventory covers all 182 production Java files, the resource and distribution inputs, test suites, and repository build metadata. The workflow descriptions trace the main call paths. The [architecture diagrams](architecture.md) show the major interactions.

| Source area | Responsibility |
| --- | --- |
| `com.myjavaworld.jftp` | Desktop entry point, main frame, session coordinator, file panes, commands, connection and preference forms, saved sites and help |
| `com.myjavaworld.jftp.actions` | Swing entry points for the selected tab, shared by menus, toolbars and popups |
| `com.myjavaworld.jftp.ssl` | JSSE context, key and trust management, and certificate UI |
| `com.myjavaworld.gui` | Shared Swing widgets, models, renderers, text editing, themes, window helpers and background worker |
| `com.myjavaworld.util` | Resource loading, filesystem and platform utilities, file monitoring and progress/status event types |
| `com.myjavaworld.zip` | Streaming archive creation and extraction, entry and progress events |
| `src/main/resources*`, `images`, `help` | Base and localized bundles, icons, and JavaHelp content copied onto the classpath |
| `src/main/scripts`, `assembly`, `pom.xml` | Launchers, distribution layout and build |
| `src/test` | Behavior checks and frozen synthetic favorites fixtures |

The root `.project`, `.classpath` and `.settings/` describe the historical Eclipse setup. The Maven build is the distribution entry point. `XmlFileFormatter.java` sits outside Maven's source tree and is a maintenance utility. [POM](../pom.xml) · [Eclipse classpath](../.classpath) · [XML formatter](../XmlFileFormatter.java)

## Startup and display

`JFTPApplication.main` constructs the desktop application. The first access to `JFTP.prefs` loads preferences, applies proxy settings, and supplies the locale and look and feel. Constructing the main frame builds Help and therefore resolves a JavaHelp descriptor and broker before the first tab is shown. One disconnected tab is selected at startup. Its local pane opens the configured directory if it exists and the platform default otherwise. `JFTPApplet` is a separate historic browser entry point. The desktop entry point calls the reflective `OSXAdapter` only on macOS. [Desktop entry point](../src/main/java/com/myjavaworld/jftp/JFTPApplication.java) · [Main frame](../src/main/java/com/myjavaworld/jftp/JFTP.java) · [JavaHelp bridge](../src/main/java/com/myjavaworld/jftp/JFTPHelp2.java)

## Sessions, file operations and transfers

Each `FTPSession` owns its client, directories, panes, filters, transfer mode and status display. `ConnectionDlg` validates settings and constructs a `RemoteHost`. The selected tab reads the client and directory parser class names from those settings and instantiates them reflectively. It connects on the appropriate port, logs in, runs ordered startup commands, sets the initial remote directory and refreshes the listing. Local and remote actions update the corresponding pane. Reconnect uses the selected tab's last host. [Connection form](../src/main/java/com/myjavaworld/jftp/ConnectionDlg.java) · [Session](../src/main/java/com/myjavaworld/jftp/FTPSession.java)

The application's own `SwingWorker` starts blocking work on a background thread and posts completion to the Swing event dispatch thread when the worker returns. Session listeners also update status and UI. Review the calling thread at those listener sites before moving them. Closing a tab, reconnecting while a previous disconnect is in flight, and aborting a transfer are separate lifecycles. Treat their exact timing and partial outcomes as characterization work before modifying them. [Worker](../src/main/java/com/myjavaworld/gui/SwingWorker.java) · [FTP boundary ADR](ADR/001-session-and-ftp-boundary.md)

Local enumeration uses Swing `FileSystemView`, which supplies Windows display names, icons and folders. The file model snapshots size, time, name and extension, while filters decide what appears and which children a recursive operation sees. Remote entries are supplied by the FTP parser. File selection, sorting and filtering feed local and remote commands, including rename, create, delete, permission changes and raw FTP commands. Binary and ASCII modes can be forced or selected from preferences by extension. [Local model](../src/main/java/com/myjavaworld/jftp/LocalFile.java) · [Local filter](../src/main/java/com/myjavaworld/jftp/LocalFileFilter.java) · [Remote pane](../src/main/java/com/myjavaworld/jftp/RemotePane.java) · [Transfer mode](../src/main/java/com/myjavaworld/jftp/TransferModeMenu.java)

Ordinary upload and download recurse through filtered directory children and refresh the destination. Drag and drop delegates to those session commands. The alternate-name and archive actions obtain destination choices from dialogs. ZIP creation uses relative entries for selected content and progress events for file streams. The remote Open, Edit and Print paths use a local temporary copy and the platform desktop integration. Edit also subscribes to local changes and asks the user before uploading the revised copy. [Drag and drop](../src/main/java/com/myjavaworld/jftp/DnDTransferHandler.java) · [Archive writer](../src/main/java/com/myjavaworld/zip/Zip.java) · [Archive reader](../src/main/java/com/myjavaworld/zip/Unzip.java) · [File monitor](../src/main/java/com/myjavaworld/util/FileChangeMonitor.java)

## Preferences, saved sites and certificates

The normal profile directory is `<user.home>/.jftp/data`. `JFTPPreferences` is Java-serialized as `preferences.ser` with connection, UI, certificate and transfer settings. `FavoritesManager` reads and writes `favorites.ser`. A successful read of a plaintext legacy list can rewrite it as an AES-sealed list. The class descriptors, field types, serial IDs and older null defaults are compatibility inputs. Test any change against frozen synthetic files. [Preferences](../src/main/java/com/myjavaworld/jftp/JFTPPreferences.java) · [Favorites manager](../src/main/java/com/myjavaworld/jftp/FavoritesManager.java) · [Storage ADR](ADR/002-profile-storage-and-migration.md)

`JFTPSSLContext` uses the configured key and trust managers. Client and server JKS stores have distinct roles and can point outside the default profile. The TLS trust callback checks leaf date, compares the configured host to the subject common name, and checks chain certificates against the server store. A per-connection warning may allow continuation or reject the handshake. Continuing is distinct from adding an entry in Manage Certificates. Preserve these decisions while qualifying newer JDKs and libraries. [SSL context](../src/main/java/com/myjavaworld/jftp/ssl/JFTPSSLContext.java) · [Trust manager](../src/main/java/com/myjavaworld/jftp/ssl/JFTPTrustManager.java) · [Key store manager](../src/main/java/com/myjavaworld/jftp/ssl/KeyStoreManager.java)

`JFTPUtil` applies proxy settings through JVM networking. Profile and proxy defaults can therefore affect more than one tab. The optional update worker reads an HTTP properties endpoint and uses localized notifications. Its live availability remains unverified. Use an isolated home with updates disabled during local qualification. [Proxy settings code](../src/main/java/com/myjavaworld/jftp/JFTPUtil.java) · [Update worker](../src/main/java/com/myjavaworld/jftp/AutoUpdater.java)
