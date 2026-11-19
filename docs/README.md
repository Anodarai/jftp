# JFTP engineering documentation

The [product requirements](PRD.md) describe the existing Swing desktop client. The modernization keeps the workflows and profile formats that users rely on.

| Document | What it covers |
| --- | --- |
| [Modernization plan](modernization-plan.md) | Sequenced work, verification gates and decisions |
| [Desktop architecture](architecture.md) | Components and diagrams for startup, connections, transfers and storage |
| [Desktop subsystems](desktop-subsystems.md) | Source layout and ownership, detailed workflows and compatibility constraints |
| [Build and dependencies](build-and-dependencies.md) | Baseline versions, development and packaged startup, and dependency decisions |
| [Runtime resources](jftp-runtime-resources.md) | Bundles, icons and JavaHelp needed by the app |
| [Testing](testing.md) | Executable regression suite, fixture provenance and Windows QA |
| [Accepted architecture decisions](ADR/000-main-architecture.md) | Windows delivery, session and FTP boundary, and profile migration |
| [RepoMap](repo-maps/repo-map.rust.md) and [Repomix](repomix/README.md) | Source navigation and historical context |

Use original source and the effective Maven build to verify current details when a historical source map or snapshot differs.
