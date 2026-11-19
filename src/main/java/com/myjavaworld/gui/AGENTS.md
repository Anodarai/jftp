# Shared Swing components

- Shared widgets, documents, renderers and Look and Feel helpers affect multiple dialogs and tables. Review callers before changing editing, selection, keyboard or focus behavior.
- Preserve keyboard shortcuts and platform behavior when replacing a deprecated Swing API. The local SwingWorker has a different API from the JDK class. Verify completion and cleanup at its session callers before replacing it.
