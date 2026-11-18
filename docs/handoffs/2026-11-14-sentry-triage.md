# Handoff: Sentry triage and fix planning (2026-11-14)

- Plan produced: `docs/superpowers/plans/2026-11-14-sentry-triage-fixes.md` (all boxes unchecked; no code changed).
- Investigated: Sentry `jans-gi` unresolved issues (30d/90d), user feedback, Android structured logs,
  breadcrumbs for `BUZER-ANDROID-Q` and `BUZER-ANDROID-R`.
- Files changed: the plan above and this handoff only.
- Commands: Sentry MCP queries (issues, issue details, breadcrumbs, logs by trace and task ID), `grep` over
  `client/androidApp` blocker/enforcement code.
- Tests run: none (planning only).
- Skipped: replay `52b26785a32347158efe110bce9c833f` returned 404; backend VPS logs for 2026-11-01 not read.
- Assumptions: the 2026-11-11 21:0x fixture-ID logs came from an `scripts/e2e-real-backend.sh` run on the
  emulator (fixture task IDs + e2e-server error codes).
- Next step: Task 1 in the plan (stale start blocker after completion), test-first in `BlockerHostControllerTest`.

Verification (planning part):
- Command: none
- Result: SKIPPED
- Evidence: documentation-only

## Task 1 implemented — stale start blocker after completion (BUZER-ANDROID-R)

Plan checkbox status: Task 1 steps 1–5 done; the "full unfiltered suite" check is still open (see below).
The notification-cleanup step was dropped: `EnforcementCoordinator` already clears every blocker kind
for the occurrence when an action's snapshot is applied.

Files changed:
- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/BlockerHostController.kt` — new
  `onForeground()` (skips first resume; cached-resolved → finish, otherwise lifecycle refresh; no-op
  during a mutation or in-flight refresh); `Warning`/`Conflict`/`LocalReconciliationFailed` now finish
  when the cached snapshot shows the occurrence resolved instead of stranding the user on a banner.
- `StartBlockerActivity.kt`, `DeadlineBlockerActivity.kt` — `onResume` calls `host?.onForeground()`.
- `client/androidApp/src/test/.../BlockerHostControllerTest.kt` — 5 new tests (foreground cached-resolved,
  foreground refresh, foreground during mutation, conflict resolved, conflict still open).
- `client/androidApp/src/androidTest/.../BlockerActivityFakeBackendTest.kt` — new
  `startBlockerUnderDeadlineBlocker_closesWhenDeadlineBlockerCompletesTheTask`.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.blockers.BlockerHostControllerTest'`
- Result: PASS
- Evidence: 16 tests, 0 failures (new tests failed to compile before the implementation)

- Command: `./gradlew :androidApp:testDebugUnitTest :shared:testAndroidHostTest`
- Result: PASS
- Evidence: BUILD SUCCESSFUL

- Command: `ANDROID_TEST_CLASS=app.buzer.android.blockers.BlockerActivityFakeBackendTest scripts/e2e-real-backend.sh`
- Result: PASS
- Evidence: pixel8Api34 managed device, 44 tests, 0 failures, includes the new scenario

- Command: `scripts/e2e-real-backend.sh` (full unfiltered suite)
- Result: SKIPPED
- Evidence: stopped at the user's request (MacBook low on battery, 2026-11-14); leftover e2e server killed.
  Must be run before this change is considered done per AGENTS.md.

Next step: run the full unfiltered `scripts/e2e-real-backend.sh`, then commit with `Fixes BUZER-ANDROID-R`
and deploy to the Pixel 10 to confirm on-device; then plan Task 3.
