# Daily Morning Review Task 6 Handoff

Plan: `docs/superpowers/plans/2026-08-02-daily-morning-review.md` (controller owns checkbox updates)

Task attempted: Task 6 — render the repair-first Morning Review and preserve editor state.

## Changes

- Replaced legacy raw-invalid-task rendering and its compatibility overload with one state-driven `LazyColumn`.
- Rendered blocking Repair/Sync pending cards before global actions, with one global Snooze and disabled Start during blocking.
- Added summary Overdue/Today rows with time and subline, exact Done/Edit task IDs, retry notices, no-data actions, and state-driven Home Back.
- Kept the single update editor for Repair and overdue Edit; ordinary recomposition preserves it while occurrence/revision invalidation closes it.
- Added accessibility headings and phase-change heading focus; the scrollable list has no fixed-height clipping.
- Rewrote the Compose host test matrix around deterministic explicit screen states.

## Files changed

- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/ui/screens/MorningReviewRepairHostTest.kt`
- this handoff

## Verification

Verification:
- Command: `./gradlew :androidApp:compileDebugAndroidTestKotlin`
- Result: RED then PASS
- Evidence: initial test-first compilation failed on new host-test API assertions before production rendering was changed; after correcting the test framework API usage and implementing the state-only renderer, it compiled successfully.

Verification:
- Command: `./gradlew :androidApp:compileDebugAndroidTestKotlin :androidApp:testDebugUnitTest --tests '*MorningReview*Test'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; Android instrumentation sources and all matching JVM Morning Review tests compiled/passed.

Verification:
- Command: `./gradlew :androidApp:compileDebugKotlin`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

---

## Accessibility follow-up

- Repeated Repair, Sync Retry, overdue Done, and overdue Edit controls now expose task-specific content descriptions while retaining their visible labels.
- Added a multi-item host test that uses those descriptions and verifies Sync Retry plus overdue Done/Edit dispatch the intended callback IDs.

Verification:
- Command: `./gradlew :androidApp:compileDebugAndroidTestKotlin :androidApp:testDebugUnitTest --tests '*MorningReview*Test'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

Verification:
- Command: `./gradlew :androidApp:compileDebugKotlin`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

## Skipped runtime verification

`scripts/e2e-real-backend.sh` and `connectedDebugAndroidTest` were intentionally skipped: `adb devices` has no attached device/emulator, and the task direction defers GMD/real-backend instrumentation to Tasks 9/10. The exact pending scoped check is `./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.buzer.android.ui.screens.MorningReviewRepairHostTest` (or the equivalent real-backend E2E invocation).

## Assumptions and next step

- `MorningReviewScreenState` remains the authority for all rendered content/action availability; `MorningReviewActivity` only supplies callbacks.
- Controller should review the Task 6 commit, update the plan boxes, then proceed with Task 7/Task 9 integration and runtime execution.

---

## Follow-up specification correction

- Today agenda rows are informational only; Done/Edit remain exclusive to overdue rows.
- Added stable tags and bounds-based host assertions for blocking, summary, and global-action order, plus the actual legacy per-task Snooze tag absence.
- The phase transition assertion now requires focused Summary heading.
- Added overdue editor coverage for its presentation, draft survival across an ordinary authoritative refresh, and closure when its occurrence/revision authority changes.

Verification:
- Command: `./gradlew :androidApp:compileDebugAndroidTestKotlin :androidApp:testDebugUnitTest --tests '*MorningReview*Test'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; updated Android host-test sources compile and matching JVM tests pass.

Verification:
- Command: `./gradlew :androidApp:compileDebugKotlin`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.
