# Regression and characterization tests

- Run the Jupiter suite with `mvnw.cmd test` on the pinned Windows toolchain.
- Assert observable UI state, file bytes, archive entries, protocol outcomes and persisted data. When changing an implementation or test runner, retain those expectations and verify discovery.
- Keep tests isolated from the real user home and use temporary directories and real loopback services. Preserve frozen synthetic legacy-profile fixture bytes and record their provenance.
- Observe an expected failure before changing production behavior. Reopen the equivalent packaged client for the native QA check.
