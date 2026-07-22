# Handoff: Task Editor Redesign — Task 8 Edit Flow

Date: 2026-07-22
Agent: Codex task8_edit_editor
Plan: `docs/superpowers/plans/2026-07-21-task-editor-redesign.md`

## Scope

- Implement Task 8: move Edit Task to the shared task-editor scaffold while keeping
  update reconciliation, checklist child mutations, and the read-only Activity log.

## Completed

- Replaced the Edit-only `CommitmentEditor` use with `TaskEditorScaffold`.
- Added injected project load/refresh functions with production defaults from
  `AddTaskDependencies.projectRepository()`.
- Kept the task snapshot's project/assignee selection authoritative through cached
  loads; only a refreshed ready membership recalculates a stale shared-project
  assignee. Personal projects resolve to locked Me and serialize an unassigned root
  patch.
- Built one captured `UpdateTaskRequest` containing project, typed nullable assignee,
  typed nullable due/duration, recurrence, and the pre-existing ordered child-mutation
  batch. A retry reuses that captured action ID; domain changes clear it.
- Added the scaffold's optional post-checklist slot and use it only for the unchanged
  Edit Activity log.
- Added schedule-safe validation and a dirty-discard confirmation; project, assignee,
  nullable schedule, recurrence, notes, and checklist changes all contribute to the
  Edit draft fingerprint.
- Added request/state/dirty-fingerprint coverage for project+Wife, no-schedule Wife,
  personal unassignment, cached/fresh membership, and a self task without a schedule.

## Files Changed

- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/EditTaskScreen.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/DraftExitPolicy.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorScaffold.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/EditTaskScreenRequestTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/EditTaskScreenStateTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/DraftExitPolicyTest.kt`
- This handoff.

## Verification

Verification:
- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.screens.EditTaskScreenRequestTest --tests app.buzer.android.ui.screens.EditTaskScreenStateTest --tests app.buzer.android.ui.screens.DraftExitPolicyTest --tests app.buzer.android.ui.taskeditor.TaskEditorScaffoldStateTest`
- Result: PASS
- Evidence: focused Edit/scaffold suite built and completed successfully.

Verification:
- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest`
- Result: PASS
- Evidence: full Android app JVM unit suite completed successfully.

Verification:
- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest`
- Result: PASS
- Evidence: shared Kotlin host-test suite completed successfully.

Verification:
- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:assembleDebug`
- Result: PASS
- Evidence: debug APK assembled successfully.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace diagnostics.

## TDD Evidence

- Initial focused test command: the Edit request/state/DraftExit Gradle command above.
- Result: RED as intended.
- Evidence: the prior `updateRequest` did not accept project, assignee, or nullable
  schedule inputs, and the new Edit membership/state helpers were absent.

## Manual Verification

- Not run in this bounded task. Task 9 owns the deterministic fake-backend
  instrumentation lane required for the final user-visible UI verification.

## Decisions And Assumptions

- Current Todoist `dueDatetime` initializes the editable start; a date-only due value
  initializes a partial date draft rather than being discarded.
- An explicit empty schedule always writes `dueDatetime = null`,
  `durationMinutes = null`, and `RecurrencePreset.never` in the root patch.
- A cached project list may enrich an assignee's display name but does not invalidate
  a captured request. Only a changed serialized project/assignee selection does.
- The existing direct-child ordering remains edit/check, delete, then add.

## Open Issues

- Follow-up after spec review: existing custom recurrence is now presented as
  `Custom: <rule>` (or `Custom recurrence`) and opens a preset sheet with no selected
  row. It remains absent from the root patch until an explicit supported choice is
  tapped; that replacement then becomes the selected row and is serialized.
- Follow-up after static quality review: `onUpdate` now rethrows cancellation, catches
  ordinary update exceptions into a retryable preserved-draft message, and clears its
  submitting state in `finally`. Choosing No schedule for a non-self task also now
  deliberately replaces an untouched custom recurrence with `Does not repeat` rather
  than leaving stale custom copy in the Repeat row.
- The focused Gradle rerun for this follow-up was blocked before execution because the
  execution approval budget was exhausted. The exact intended command was:
  `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.screens.EditTaskScreenRequestTest --tests app.buzer.android.ui.screens.EditTaskScreenStateTest --tests app.buzer.android.ui.screens.DraftExitPolicyTest`.
- Instrumentation/E2E coverage is intentionally deferred to plan Task 9.

## Next Step

- Review Task 8 against the accepted design, then execute Task 9 fake/real harness and
  Android instrumentation updates before the full final verification lane.
