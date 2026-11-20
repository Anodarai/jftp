# Swing desktop and FTP sessions

- Route menus, toolbar actions and pane operations through the selected session. Preserve per-tab directories, filters, connection options, transfer mode, status and last-host state.
- Before changing workers or session lifecycle, verify which thread owns each event and completion. Keep blocking work off the Swing event dispatch thread and prevent stale completions from touching other tabs.
- Submit FTP work through the tab's `SessionCoordinator`. Keep Abort on the separate signal path and gate Swing callbacks on the originating operation and live connection.
- Keep FTP client and parser calls behind `LegacyFtpAdapter`. Use JFTP remote entries, filters, events, and failures in Swing code.
- Treat the names and serialized fields of preferences and saved hosts, legacy default values, and client/parser class names as compatibility inputs. Verify them using frozen synthetic fixtures.
- Use the [read-only inspector](../../../../../../docs/testing.md#read-only-legacy-profile-inspection) for legacy profile inspection. Keep it independent of desktop profile initialization and apply the compatibility filter to plaintext and sealed serialization.
- Use the [profile storage service](../../../../../../docs/testing.md#profile-migration-and-recovery) for generation selection and persisted profile writes. Preserve the selected generation until staging, reopening, and atomic activation succeed.
