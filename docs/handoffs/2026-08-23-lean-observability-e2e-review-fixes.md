# Handoff: Lean Observability E2E Review Fixes

Date: 2026-08-23

Plan: `docs/superpowers/plans/2026-08-22-lean-observability.md` (post-review instrumentation isolation fixes)

## Task and checkbox status

- [x] Replace the ambient BugReport activity rule with explicit, isolated startup before `MainActivity` launch.
- [x] Close the AddTask notes IME before the accordion transition and use the stable checklist-add semantics tag.
- [x] Run focused BugReport and AddTask real-backend instrumentation coverage.
- [x] Full unfiltered real-backend E2E is green after project-loading synchronization.

## Files changed

- `client/androidApp/src/androidTest/kotlin/app/buzer/android/observability/BugReportFlowInstrumentationTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`
- `docs/handoffs/2026-08-23-lean-observability-e2e-review-fixes.md`

## Implementation summary

- `BugReportFlowInstrumentationTest` now uses `createEmptyComposeRule`, installs the recording observability adapter and resets fake backend, snapshot cache, morning-review acknowledgement, alarms, notifications, and readiness before explicitly launching `MainActivity`. It waits only for the deterministic Home `Report a bug` action; it no longer branches on whichever foreground surface an earlier test left behind.
- The AddTask selector test closes the Notes IME and waits for Compose idle before opening Checklist. Checklist expansion and the test use the existing `addChecklistItem` semantics tag, waiting for and scrolling to that stable node rather than clicking text during the keyboard transition. Project selection also waits for the existing `Loading projects...` status to disappear, then Compose idle, before opening its sheet: the project row is intentionally clickable while async project loading is still in progress.

## Verification

- Command: `ANDROID_TEST_CLASS=app.buzer.android.observability.BugReportFlowInstrumentationTest scripts/e2e-real-backend.sh`
  Result: PASS
  Evidence: managed device `pixel8Api34`; `OK (1 test)` in `client/androidApp/build/outputs/androidTest-results/managedDevice/debug/pixel8Api34/testlog/test-results.log`.

- Command: `ANDROID_TEST_CLASS=app.buzer.android.addtask.AddTaskFakeBackendTest scripts/e2e-real-backend.sh`
  Result: PASS
  Evidence: managed device `pixel8Api34`; `OK (11 tests)` in the same test-log artifact, including `addTask_selectorSheetsAndAccordionKeepChangesInDraftUntilAdd`.

- Command: `scripts/e2e-real-backend.sh`
  Result: PASS
  Evidence: managed device `pixel8Api34`; `OK (111 tests)` in 332.893 seconds in `client/androidApp/build/outputs/androidTest-results/managedDevice/debug/pixel8Api34/testlog/test-results.log`.

- Command: `git -c core.fsmonitor=false diff --check`
  Result: PASS
  Evidence: no whitespace errors.

## Manual verification

SKIPPED. Automated real-backend instrumentation is the applicable authority for these test-only changes.

## Investigation notes

- The initial full-suite failure was `addTask_backendFailureKeepsFormAndEnteredTitle` waiting for `Test Shared`. Its preserved log proved `/projects` succeeded with `load_success count=2`, but `selectProject` had clicked the always-visible Project row before asynchronous loading settled. Source confirms the row remains enabled while `projectsLoading` is true, while `TaskEditorProjectLoadStatus` exposes `Loading projects...`.
- Two rejected hypotheses were deliberately reverted: `FakeBackendClient.hasCompletedCall("/projects")` and `waitForRecordedCall("/projects")`. The E2E server does not expose/journal project-read completion in `/__calls`; both made focused tests fail despite app-side load success. The final patch relies only on the rendered loading state and Compose idle boundary.
- The first focused attempt was blocked before Gradle by sandbox-denied `tsx` IPC; the same command was rerun with approved host permission.

## Next recommended step

Root agent can integrate the scoped test-only commit. No production code was changed.
