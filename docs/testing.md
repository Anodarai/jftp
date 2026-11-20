# JFTP tests

Run `.\mvnw.cmd test` from the repository root with the [pinned Windows toolchain](build-and-dependencies.md#pinned-windows-toolchain). The initial run downloads dependencies from Maven Central and the jMethods HTTPS repository. Tests bind a real FTP server to IPv4 loopback on an ephemeral port.

The recovered baseline used JUnit 3.8.1, Eclipse Adoptium JDK 25.0.4.1, and Maven 3.9.16 to emit Java 8 bytecode. The desktop build excludes `OSXAdapterOld.java`, which directly imports the Apple EAWT API and has no callers in the desktop entry point. [Build configuration](../pom.xml)

| Area | Coverage |
| --- | --- |
| Local browsing | Filename and modification-date filtering, the inclusive date boundary, exclusion mode, hidden files, and directory navigation |
| Archives | Filtered recursive ZIP entries, relative paths, binary contents, empty directories, and an invalid archive |
| Desktop resources | Bundled JavaHelp home topic and startup icon, German and Traditional Chinese bundles, and English fallback |
| Profile storage | Default and saved preferences, reading and sealing a pre-existing plaintext favorites file, reopening sealed favorites without a rewrite, and a corrupt file |
| FTP transport | Active and passive plain FTP against Apache MINA FtpServer 1.2.1, changing directories, listing, binary upload and download, disconnect, and rejected credentials |

The profile test starts a separate JVM with a temporary `user.home`. Both favorites fixtures were generated from revision `59f241db5ebb4b5c738e4957fa7de238dda93090`. The plaintext fixture is a serialized `ArrayList<Favorite>` with one synthetic site. The baseline `FavoritesManager` produced the sealed fixture by reading the plaintext file in an isolated profile.

| Fixture | SHA-256 |
| --- | --- |
| [Plaintext favorites](../src/test/resources/com/myjavaworld/jftp/favorites-baseline-plaintext.ser) | `b40f9818769300a99e15cb17266233120f8864b5a3cf8c8f2132459aca9f5516` |
| [Sealed favorites](../src/test/resources/com/myjavaworld/jftp/favorites-baseline-sealed.ser) | `dda3551369b7a0044373cccf38b2bcb334ed04ec98bd576cddb02d1aa6f5f575` |

## Windows verification

`mvn clean package` passed all 10 tests and produced the distribution ZIP. The resolved `com.myjavaworld:ftpapi:3.0.0` artifact SHA-256 is `496782add046e211b5f7a686c483522cca8879048f12286249aa50c8f86b505c`. The FTP server logged separate `PORT` and `PASV` paths. Its passive-port cleanup warning appeared after successful transfers on ephemeral ports. The Assembly plugin warned that it could not attach the loose directory as a deployment artifact and produced the ZIP and TAR.GZ.

The extracted ZIP places the application JAR beside `lib/`. For a direct smoke check from another directory, launch the root JAR with its adjacent libraries and an existing temporary home.

```powershell
java "-Duser.home=C:\temp\jftp-test-home" -jar "C:\temp\extracted package\jftp-5.0.2-SNAPSHOT\jftp-5.0.2-SNAPSHOT.jar"
```

The native Windows check started the extracted JAR directly from a different working directory with an isolated home, English resources, and updates disabled. Steps and observations are recorded below.

| Step | Observed result |
| --- | --- |
| Start JFTP and press F1 | One disconnected tab, both synthetic files visible locally, and the bundled Contents topic opens. [Desktop](qa/screens/baseline-packaged-startup.jpg) · [JavaHelp](qa/screens/baseline-help.jpg) |
| Open Connect and submit a blank host | A required-host error appears. Dismissing it returns focus to Host Name. Cancel returns to the disconnected tab. [Connect form](qa/screens/baseline-connect-form.jpg) · [Validation](qa/screens/baseline-connect-validation.jpg) |
| Connect using the two synthetic favorites | The server returns FTP reply 530 for the invalid password, and the session stays disconnected. The valid site opens `/incoming` and lists the remote fixture. [Favorites](qa/screens/baseline-favorites.jpg) · [Login failure](qa/screens/baseline-ftp-rejected-login.jpg) |
| Select files and press F9 to upload and F7 to download | Each destination pane refreshes. Upload and download each match their source by SHA-256. [Transfer results](qa/screens/baseline-ftp-transfers.jpg) |
| Press F4 and close JFTP | The remote listing clears, the tab shows Not Connected, and the app closes. [Disconnected session](qa/screens/baseline-ftp-disconnected.jpg) |

The application process logs were empty. The session and server logs recorded the expected authentication failure and successful transfers.

## Windows launcher recovery

On Windows 11 x64, rebuilding with Temurin 25.0.4.1 and Maven 3.9.16 passed all 10 baseline tests. The same package check against an archive containing the previous launcher failed with “Unable to access jarfile” because the launcher looked under `lib/`. The rebuilt ZIP passed the following check with both Temurin 25.0.4.1 and Oracle JDK 25.0.5.1.

```powershell
.\src\test\scripts\Test-WindowsPackage.ps1 -Archive .\target\jftp-5.0.2-SNAPSHOT-bin.zip
```

The script extracts to a path with spaces and calls the launcher from a different working directory. It checks startup with `JAVA_HOME` and with only the JDK on `PATH`. An invalid `JAVA_HOME` and missing Java on `PATH` each produce an actionable diagnostic and exit code 1. It uses headless mode for these command-line checks.

For native QA, the extracted `jftp.bat` ran from its parent directory with the Oracle JDK and an isolated English profile containing two synthetic local files. One disconnected tab opened, F1 displayed the bundled Contents topic, and Alt+F4 closed Help and then JFTP. Standard output was empty and standard error contained only the JVM's notice that it had picked up the synthetic-home option. [Disconnected tab](qa/screens/launcher-packaged-startup.jpg) · [JavaHelp Contents](qa/screens/launcher-packaged-help.jpg)

## Windows JDK 25 verification

On Windows 11 x64 with Oracle JDK 25.0.5.1 and Maven Wrapper 3.3.4 running Maven 3.10.1, `.\mvnw.cmd clean verify` passed all 10 baseline tests. The archive check rejected the Java 8 baseline with class version 52 and passed for the rebuilt ZIP with class version 69.

```powershell
.\src\test\scripts\Test-WindowsPackage.ps1 -Archive .\target\jftp-5.0.2-SNAPSHOT-bin.zip -ExpectedJavaRelease 25
```

The probed ZIP SHA-256 was `175702233650ba22dcb9e1c62b1784faf78299d6df2e5b323784f6b28825e8b0`. The manifest names `JFTPApplication` and the two baseline runtime JARs under `lib/`. The ZIP contained no test libraries. Comparison with the recovered baseline found 377 byte-identical resource entries and three fewer compiler-generated classes. The missing synthetic classes are the Java 8 access helpers for `MDialog`, `MInternalFrame`, and `OSXAdapter`. Compilation reported the legacy browser applet's JDK removal warning. Assembly reported the existing warning about attaching the loose directory. [Toolchain and runtime checksums](build-and-dependencies.md#pinned-windows-toolchain)

Native QA used the extracted `jftp.bat` from another working directory with separate synthetic homes and update checks disabled.

| Check | Result |
| --- | --- |
| Startup and Help | The English profile showed one disconnected tab and the synthetic local files. F1 opened Contents. [Startup](qa/screens/toolchain-startup.jpg) · [Help](qa/screens/toolchain-help.jpg) |
| Localization | The German and Traditional Chinese profiles showed translated menus and session labels. An Italian profile used English UI text and Italian date formatting. [German](qa/screens/toolchain-german.jpg) · [Traditional Chinese](qa/screens/toolchain-traditional-chinese.jpg) · [English fallback](qa/screens/toolchain-english-fallback.jpg) |
| Validation and cancellation | Connect rejected an empty host, returned focus to Host Name, and could be cancelled back to the disconnected tab. [Validation](qa/screens/toolchain-validation.jpg) |
| Favorite and transfers | The saved site connected to Apache MINA FtpServer 1.2.1 on IPv4 loopback and listed `/incoming`. F9 uploaded `upload.bin` and F7 downloaded `download.bin`. The destination listings refreshed and source and destination SHA-256 matched for both files. F4 cleared the remote listing. [Favorite](qa/screens/toolchain-favorite.jpg) · [Transfers](qa/screens/toolchain-transfers.jpg) · [Disconnected](qa/screens/toolchain-disconnected.jpg) |

Upload SHA-256 was `fc745af96e9674102571aeb7ddf3fd82c7ce457352569c801bf8e620fb2a797b`. Download SHA-256 was `bba3057854925e69b0be9275ce165edbab3fd09b62dc5a1fcc4b84e25f7f3e9d`. The server logged binary mode, successful `STOR` and `RETR` replies, and a normal disconnect. The four app launches produced no output beyond the JVM notices for the synthetic-home setting.

## Jupiter runner verification

Surefire 3.5.4 selected its JUnit Platform provider for Jupiter 6.1.3. A temporary failing assertion in the locale fallback test produced exit code 1 and reported exactly 10 tests with one assertion failure and zero errors. After removing the probe, `.\mvnw.cmd clean verify` reported 10 passing tests with no skips. Both frozen favorites fixtures retained the hashes above.

The resolved tree contains Jupiter API, Params, and Engine 6.1.3 with Platform Commons and Engine 6.1.3. FTPServer 1.2.1, Ftplet 1.2.1, MINA 2.2.4, and SLF4J 1.7.36 remain test-scoped. One MINA session-close callback was rejected during server teardown after successful transfers and `QUIT`. The ZIP still contained only the application, FTP API, and JavaHelp JARs. [Dependency inventory](build-and-dependencies.md)

## Automated Windows desktop smoke

Run the opt-in Failsafe suite on an unlocked Windows desktop with the pinned toolchain. Keep the test window in the foreground during the run.

```powershell
.\mvnw.cmd -Pdesktop-qa clean verify
```

The suite extracts the ZIP to a path with spaces, loads its application JAR and manifest libraries, isolates `user.home`, and uses Apache MINA FtpServer on IPv4 loopback. It drives the displayed controls with AWT mouse, key, and window events. The managed Windows session returned `0,0` when Robot tried to move the OS pointer to the test menu. The event driver exercises Swing's control dispatch in the realized windows, and the native-input checks above cover physical input routing.

The test waits for startup, Contents and Index, required-host validation and focus return, cancellation, rejected login with reply 530, a successful favorite connection, the refreshed upload and download listings, matching file bytes, and disconnect. It bounds each UI wait and the whole child process. A deliberate assertion produced a nonzero Failsafe result, an application-only screenshot, and redacted logs. The passing run completed the desktop test in 9.5 seconds. [Failure capture probe](qa/screens/native-desktop-diagnostics.png)

Reports are under `target/failsafe-reports`. The application and server log is `target/desktop-qa/desktop.log`. Failed tests also retain the redacted session log and, when a window rendered, its PNG in the same directory. The server reported the expected rejection and successful transfers. JVM shutdown printed one “Exception while removing reference.” line after the successful `QUIT`, with no stack trace and exit code 0.

## Loopback FTP and FTPS compatibility

`LegacyFtpsTest` runs in the default Maven test suite. On Windows with Oracle JDK 25.0.5.1 and its default TLS policy, FTP API 3.0.0 passed active and passive transfers against MINA FtpServer 1.2.1 in five configurations. It exercised plain FTP, FTP fallback after the server declined `AUTH SSL`, TLS when available, explicit FTPS, and implicit FTPS. The server used a temporary self-signed loopback certificate, and the client trusted it for the test. The TLS cases checked `PBSZ 0` and `PROT P`. The test compared upload and download bytes with the original payload, whose SHA-256 is `fc745af96e9674102571aeb7ddf3fd82c7ce457352569c801bf8e620fb2a797b`.

All 11 cases passed, including the separate connection-refusal check. MINA logged passive-port cleanup warnings and reported an unsent TLS reply after a successful `QUIT`. Certificate prompts, SOCKS, and transfer abort remain separate release checks. [Transport acceptance criteria](ADR/001-session-and-ftp-boundary.md#technical-acceptance-criteria)
