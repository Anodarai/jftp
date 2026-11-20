# JFTP baseline tests

Run `mvn test` from the repository root. The suite was verified on Windows 11 x64 with Eclipse Adoptium JDK 25.0.4.1 and Maven 3.9.16. The initial run downloads dependencies from Maven Central and the jMethods HTTPS repository. The tests use the existing JUnit 3 dependency and bind a real test FTP server to IPv4 loopback on an ephemeral port.

The baseline build uses Maven Compiler Plugin 3.14.1, Surefire 3.5.4, and Java 8 bytecode. It excludes `OSXAdapterOld.java`, which directly imports the Apple EAWT API and has no callers in the desktop entry point. JDK 25 reports the expected obsolete Java 8 target warning. [Build configuration](../pom.xml)

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
