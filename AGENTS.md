# Repository Guidelines

## Project

JFTP is a Swing desktop client for browsing, managing, and transferring local and remote files over FTP or FTPS.

Write chat responses and repository documentation in English. Put desktop UI strings in the existing resource bundles and preserve locale selection and fallback.

Treat existing preferences, saved sites, and certificate stores as compatibility inputs. Keep localized resources, icons, and JavaHelp available on the packaged classpath. JavaHelp initializes during window startup.

The [desktop modernization ADR](docs/ADR/000-main-architecture.md) links to the PRD and accepted decisions. Baseline and package qualification run on Windows.

## RepoMap

Start with the [RepoMap](docs/repo-maps/repo-map.rust.md) when investigating JFTP architecture, entry points, flows, or Java declarations. It points to files, lines, and signatures. For flow analysis, open only the relevant sections of the [Repomix bundle](docs/repomix/jftp.xml). Before editing, verify the original methods, callers, and configuration. Search the source when the map omits a declaration.

## Agent Workflow

Confirm expected behavior before coding. Use the PRD as the behavior baseline and accepted ADRs for design decisions. Summarize material conflicts among the request, PRD, accepted ADRs, and observed behavior. Ask the user to resolve each conflict before proceeding with the affected work.

### TDD Rules

For every feature and bug fix:
1. Start from the specification, not the existing implementation.
2. Write or extend tests **before** production code.
3. Run the new tests and confirm they fail for the expected reason.
4. Implement the minimum code needed to make them pass.
5. Run the full verification suite for the changed scope.
6. Refactor only while tests stay green.
7. **Manually validate the running application** (see Manual QA below). Automated tests — especially E2E — can produce false passes; a task is not done until the real app has been exercised by hand.

If the area has no suitable test infrastructure yet, add it as part of the task — do not silently skip tests.

### Manual QA

Required for every task affecting the running application:

1. Start the real app with its verified command and authorized local/test configuration, using isolated synthetic state without changing personal preferences or data. Confirm startup separately from compilation/build and tests.
2. Exercise the complete affected flow with synthetic inputs, including success, cancellation and relevant error paths. Use an authorized local test server for FTP or FTPS flows. Verify outputs, transferred file contents, state changes and persisted results as applicable.
3. The agent uses native desktop tools to exercise real windows, dialogs and controls. Check responsiveness, focus and keyboard navigation. Headless tests do not prove GUI behavior. Request human help if desktop or test server access is missing.
4. For packaging changes, also launch the packaged build outside the IDE; verify runtime resources and dependencies.
5. Capture each changed screen and relevant state in `docs/qa/screens` using synthetic data. Inspect application and supporting-service runtime logs for errors.
6. Check UI text against agreed language/localization requirements and visual changes against approved references or existing UI conventions.
7. Report steps, environment and evidence; fix failures before committing. If desktop access, a service or another prerequisite is unavailable, state exactly what remains unverified. The runtime task is incomplete until required Manual QA passes.

### Test Strategy

| Type | Mocks |
|---|---|
| Unit | Dependencies may be isolated |
| Integration | Mocks allowed when useful; endpoint fixtures require a fresh real response |
| E2E and Manual QA | **Zero mocks.** Run the complete application with real backing services, including real LLM calls if the flow uses them. |

#### Endpoint and database mocks

Before creating or refreshing any unit/integration endpoint or database mock, make a fresh real query in the authorized local/test environment. Save the complete response verbatim to a file, then derive the mock from that capture, never documentation or invented objects. Record provenance separately without secrets. Existing fixtures do not replace a fresh capture.

Documentation can be outdated: invented request/response shapes can pass mocked tests while failing against the real endpoint. Use synthetic authorized data; never commit private responses or credentials. Missing access or authorized budget blocks mock creation/refresh and required real checks.

### Verification

Verify the changed scope before committing: startup, tests and Manual QA for runtime changes; content/links for documentation. Tests passing ≠ app working.

### Commit Rules

Create a local commit for each logical change.

- Commit only after verification passes and the changed scope is in a working state.
- Keep commits focused: one logical change per commit.
- Format: `Area: short summary`.
- Do **not** push to remote unless the user explicitly asks.
- Stage explicit paths and preserve unrelated work.

### Completion Criteria

Complete only with agreed behavior, honest verification, required QA evidence and a reviewable result. Investigate failures/new warnings; report blockers.

Update affected repository instructions and documentation in the same logical change whenever enduring project facts or policies change.
