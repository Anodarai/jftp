# Swing desktop and FTP sessions

- Route menus, toolbar actions and pane operations through the selected session. Preserve per-tab directories, filters, connection options, transfer mode, status and last-host state.
- Before changing workers or session lifecycle, verify which thread owns each event and completion. Keep blocking work off the Swing event dispatch thread and prevent stale completions from touching other tabs.
- Treat the names and serialized fields of preferences and saved hosts, legacy default values, and client/parser class names as compatibility inputs. Verify them using frozen synthetic fixtures.
