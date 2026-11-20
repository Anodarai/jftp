# Build and dependency inventory

## Recovered baseline

The starting point is `3f7f92d`, JFTP `5.0.2-SNAPSHOT`. The repository originally targeted Java 5. The recovered build produced Java 8 bytecode and passed its JUnit suite using Temurin JDK 25.0.4.1 and Maven 3.9.16 on Windows 11 x64. The independent runtime entry point is `com.myjavaworld.jftp.JFTPApplication`.

| Dependency | Baseline version | Role and constraints |
| --- | --- | --- |
| `com.myjavaworld:ftpapi` | 3.0.0 | Runtime FTP and FTPS client, listing parsers, events and several `com.myjavaworld.util` types. Resolved from the jMethods HTTPS repository. Reflection and saved profiles reference class names from this JAR. |
| `javax.help:javahelp` | 2.0.05 | Runtime help API and broker. The main window constructs Help during startup. Resolved from Maven Central. |
| `junit:junit` | 3.8.1 | Baseline test runner and assertions. |
| `org.apache.ftpserver:ftpserver-core` | 1.2.1 | Real loopback test server. The resolved test transitives are `ftplet-api` 1.2.1, `org.apache.mina:mina-core` 2.2.4 and `org.slf4j:slf4j-api` 1.7.36. |
| `org.slf4j:slf4j-simple` | 1.7.36 | Test-only logging binding for the loopback server. |
| JDK `java.base`, `java.desktop` | Probe JDK above | Swing/AWT, native file chooser and desktop actions, Java serialization, ZIP, TLS, crypto and certificate stores. Legacy `JApplet` and Apple EAWT code need separate treatment from the live desktop entry point. |

The baseline pins Compiler 3.14.1, Surefire 3.5.4, JAR 2.4, Source 2.1.2, Assembly 2.3, SCM 1.7, Deploy 2.7, Release 2.3 and the FTP Wagon extension 2.2. SCM, Deploy and Release refer to historic publication tooling. Local verification uses the build lifecycle through `package` or `verify` and never runs the publication goals. [Baseline POM](../pom.xml) · [FTP vendor downloads](https://www.jmethods.com/ftpapi/download.html) · [JavaHelp artifact](https://central.sonatype.com/artifact/javax.help/javahelp/2.0.05) · [Baseline dependency and fixture checksums](testing.md)

## Distribution and compatibility

The assembly puts the application JAR beside a `lib/` folder with FTP API and JavaHelp. The application manifest names the desktop entry point and references the adjacent libraries. The five configured resource roots merge into the JAR with their classpath paths intact. Test tools are outside the runtime distribution. At the recovered baseline, the scripts looked for the application JAR under `lib/`, while the extracted ZIP contained it at the root. [Assembly descriptor](../src/main/assembly/binary.xml) · [Runtime resource inventory](jftp-runtime-resources.md)

Build on Windows with `mvn clean package` and extract `target/jftp-5.0.2-SNAPSHOT-bin.zip`. Run `jftp.bat` in the extracted directory. The Windows launcher resolves the JAR relative to itself and selects Java from `JAVA_HOME` or `PATH`. [Windows launcher](../src/main/scripts/jftp.bat) · [Verification](testing.md#windows-launcher-recovery)

For the JDK 25 upgrade, preserve the exact FTP API and JavaHelp artifacts initially. Their replacement requires real protocol and help-parity evidence. The build and test stack can move separately because it does not provide the product's runtime FTP or help implementation. The accepted [desktop ADR](ADR/000-main-architecture.md) records the toolchain and package gates. Use the [modernization plan](modernization-plan.md) to sequence the changes.
