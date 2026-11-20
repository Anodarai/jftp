# JFTP modernization plan

Deliver a qualified Windows x64 ZIP for JDK 25. Preserve the Swing workflows, FTP and FTPS options, profile formats, certificate prompts, locale selection and fallback, icons, and JavaHelp. Use the PRD for observable behavior and the accepted ADRs for implementation boundaries. [Product requirements](PRD.md) · [Desktop ADR](ADR/000-main-architecture.md)

## Starting point

| Status | Evidence |
| --- | --- |
| Windows regression baseline recorded | The baseline package build passed 10 tests for filters, ZIP, startup resources, legacy profiles, and plain FTP. The build used a JDK 25 host and targeted Java 8 bytecode. [Test baseline](testing.md) · [POM](../pom.xml) |
| Extracted JAR exercised | Native Windows QA launched the root JAR directly with a synthetic home. It checked Help, invalid and cancelled Connect, rejected login, Favorites, binary upload and download, and disconnect. [Windows QA evidence](testing.md#windows-verification) |
| Batch launcher qualified | The package launched from a different working directory and a path with spaces. Both Java selection paths, missing Java diagnostics, and F1 Help were exercised. [Windows launcher recovery](testing.md#windows-launcher-recovery) |
| Toolchain and automated baseline qualified | Oracle JDK 25.0.5.1, Maven 3.10.1, Jupiter, and the extracted-package smoke have Windows evidence. [Toolchain probe](testing.md#windows-jdk-25-verification) · [Desktop smoke](testing.md#automated-windows-desktop-smoke) |
| Transport and profile evidence available | The legacy transport matrix records active and passive data paths, certificate decisions, SOCKS, abort, and the two pre-refactor failure paths. Profile tests cover inspection, migration, atomic write failure, and recovery. Release qualification includes native Manual QA of the modernized package. [Transport evidence](transport-qualification.md) · [Profile recovery tests](testing.md#profile-migration-and-recovery) |
| Candidate built and automated checks passed | The clean Windows build passed the automated package, transport, desktop and rollback checks. Native startup and F1 Help passed. The remaining Manual QA and runtime commits are pending. [Release build evidence](testing.md#release-build-and-rollback-rehearsal) |

## Work sequence

### 1. Finish Windows launcher qualification

Keep the launcher fix and its regression check in one change. Build the ZIP, extract it to a path with spaces, and run `jftp.bat` from another working directory. Verify Java selection through `JAVA_HOME` and through `PATH` with `JAVA_HOME` unset. Check that unavailable Java produces a useful diagnostic and a failure exit code. With an isolated home, open one disconnected tab and launch bundled Help using the keyboard. Re-run the baseline suite and record the launcher evidence in the testing runbook. [Windows build and package layout](build-and-dependencies.md) · [Test baseline](testing.md)

### 2. Pin and qualify the JDK 25 build

Probe the JDK distribution and Maven release specified by the desktop ADR. Record the verified download checksums. Add the pinned Maven Wrapper, set the compiler release to 25, and pin compatible lifecycle and packaging plugins. Preserve the desktop build's exclusion of the unused Apple adapter. Keep the resolved FTP API and JavaHelp artifacts fixed for this comparison. Run `mvnw.cmd clean verify` on Windows once the wrapper is in place. Inspect the ZIP, manifest, runtime libraries and resource paths. Launch the extracted ZIP outside the IDE and recheck startup, Help, German and Traditional Chinese resources, English fallback, a saved-site connection, and binary transfer in both directions. [Toolchain decision](ADR/000-main-architecture.md#3-system-architecture) · [Current build](../pom.xml) · [Runtime resources](jftp-runtime-resources.md)

### 3. Upgrade the test runner and add native UI automation

Move the existing checks from JUnit 3 to the Jupiter version specified by the desktop ADR. Preserve assertions and the frozen favorites fixture bytes. Keep the real loopback FTP server test-scoped. Confirm equivalent test discovery and prove the runner reports a deliberate failed assertion. In a separate change, automate the baseline flows on an interactive Windows desktop with AWT Robot or equivalent. Wait for observable UI state with timeouts and capture synthetic screenshots and redacted logs on failure. Extend the harness for two tabs, Abort and certificate decline as those fixtures become available. Retain manual QA for every runtime change. [Test baseline](testing.md) · [Desktop test strategy](ADR/000-main-architecture.md#10-testing-strategy) · [Repository QA rules](../AGENTS.md#manual-qa)

### 4. Measure transport compatibility before refactoring

On the qualified JDK, run the exact existing FTP artifact against authorized loopback endpoints. Record the negotiation and data-channel results for plain FTP, SSL if available, explicit FTPS, and implicit FTPS, with active and passive data paths. Include bad credentials, server refusal, certificate Continue and Decline with generated chains, SOCKS authentication and refusal, and abort during a multi-item transfer. Record the endpoint configuration, default TLS policy, observed outcomes, and payload hashes for successful binary transfers. Route any required mode that fails under the default JDK policy through the ADR decision before changing compatibility expectations. [Transport decision and test matrix](ADR/001-session-and-ftp-boundary.md)

### 5. Separate FTP integration from session execution

Wrap the legacy FTP client and parser selection behind the adapter from ADR 001 while retaining the protocol behavior measured in phase 4. Move UI-facing inputs, progress and outcomes to JFTP-owned types. Introduce the per-tab coordinator in a separate increment, keeping blocking work off the Swing event dispatch thread and publishing results only to the originating live session. Preserve reconnect settings, current directories, filter and transfer modes, alternate names, ZIP actions and prompts. Prove in the packaged app that a server refusal affects only the requesting tab, an abort stops the next unstarted item, and a completion from a closed tab cannot update another tab. Compare binary hashes and the refreshed listings. [Current connection entry point](../src/main/java/com/myjavaworld/jftp/FTPSession.java#L593) · [Session contracts and acceptance criteria](ADR/001-session-and-ftp-boundary.md)

### 6. Qualify profile compatibility and recovery

This can proceed after phase 2, independently of the session work. Add a read-only inspection path for legacy preferences, plaintext and sealed favorites, and both JKS stores. The current plaintext favorites reader can write the file back during a read. Use frozen synthetic inputs and compare source hashes before and after inspection. Add backup, staging, activation and restore as a separate increment. Preserve existing formats, distinct client and server stores, and the rules for default and external paths in ADR 002. Verify cancellation, malformed input, wrong store password, interrupted activation, retry and restore. Reopen the resulting profile in the packaged app and connect with a migrated favorite. [Current favorites reader](../src/main/java/com/myjavaworld/jftp/FavoritesManager.java#L64) · [Profile migration ADR](ADR/002-profile-storage-and-migration.md)

### 7. Qualify the release candidate

From a clean Windows workspace, build with the pinned toolchain and repeat the automated and manual checks on a clean extraction. Exercise a clean home and an isolated copy of a supported legacy profile. Retain the ZIP checksum, toolchain and runtime dependency versions, protocol outcomes, transfer hashes, profile recovery results, and synthetic screenshots and logs. Rehearse restoring the prior profile and launching it with the previous verified package. Release qualification requires the Windows, FTP and profile acceptance criteria to pass. [Desktop acceptance criteria](ADR/000-main-architecture.md#technical-acceptance-criteria) · [FTP acceptance criteria](ADR/001-session-and-ftp-boundary.md#technical-acceptance-criteria) · [Profile acceptance criteria](ADR/002-profile-storage-and-migration.md#technical-acceptance-criteria)

## Verification and decisions

- Use separate, reviewable commits for build, test infrastructure, behavior and storage changes. Write the failing check first for a repair or new contract. Record baseline outcomes for behavior being preserved. Run the suite for the changed scope and exercise the real app with synthetic inputs and real services. Save changed screens in `docs/qa/screens` and report the startup command, environment, results and log findings. [Repository workflow](../AGENTS.md)
- If a gate fails, repeat it on the last verified package with the same fixture. Fix a regression before advancing the affected phase. Record a material disagreement among the PRD, ADR or observed behavior and ask the user to resolve it before changing the expectation. Keep the previous package and recoverable profile available. [Desktop verification strategy](ADR/000-main-architecture.md#10-testing-strategy)
- Characterize the post-failure state after a failed initial listing and after an interrupted archive download before touching those paths. Record any proposed behavior change for approval. Review dependency substitutions, credential-storage changes and certificate policy changes through separate decisions. [PRD source observations](PRD.md#12-further-notes) · [FTP ADR](ADR/001-session-and-ftp-boundary.md) · [Profile ADR](ADR/002-profile-storage-and-migration.md)
