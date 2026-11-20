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

Set `JAVA_HOME` to Oracle JDK 25.0.5.1 on Windows x64 and build with `.\mvnw.cmd clean verify`. Extract `target/jftp-5.0.2-SNAPSHOT-bin.zip` and run the extracted `jftp.bat`. The launcher resolves the JAR relative to itself and selects Java from `JAVA_HOME` or `PATH`. [Windows launcher](../src/main/scripts/jftp.bat) · [Verification](testing.md#windows-jdk-25-verification)

## Pinned Windows toolchain

The desktop compiler emits Java 25 class files and excludes `OSXAdapterOld.java`. Maven Wrapper 3.3.4 downloads Maven 3.10.1 and validates the ZIP with its pinned SHA-256. The Windows probe checked the Oracle JDK ZIP against Oracle's SHA-256 and the Maven distribution against Apache's SHA-512 before extraction. [Desktop ADR](ADR/000-main-architecture.md#3-system-architecture) · [Wrapper configuration](../.mvn/wrapper/maven-wrapper.properties)

| Windows ZIP | SHA-256 |
| --- | --- |
| Oracle JDK 25.0.5.1 x64 | `163cf56d2920ac14fe1d1f503f14ea1b60a1866dd4113bb785d62a9a5585e733` |
| Apache Maven 3.10.1 | `9c1ef9174ef250a5009ed0dec97cbe8699acf7479add4e62bf7b0ea791c0adb5` |
| Packaged FTP API 3.0.0 JAR | `496782add046e211b5f7a686c483522cca8879048f12286249aa50c8f86b505c` |
| Packaged JavaHelp 2.0.05 JAR | `fcf4922d38ff85184f1d2328317bb60826e14da948abd606ee3d5b8c6a70debd` |

The build pins Clean 3.2.0, Resources 3.4.0, Compiler 3.14.1, Surefire 3.5.4, JAR 3.4.2, Source 3.3.1, Assembly 3.7.1, and Install 3.1.4. The FTP API and JavaHelp checksums match the recovered baseline. Use the [modernization plan](modernization-plan.md) to sequence any subsequent transport and JavaHelp work.
