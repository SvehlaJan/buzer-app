# Handoff: Unified Task Editor Task 6 — Deadline Recovery

Date: 2026-07-23
Agent: `unified_editor_task6`
Plan: `docs/superpowers/plans/2026-07-22-unified-task-editor.md`

## Task / Checkbox Status

- [x] Task 6 — migrated deadline-blocker recovery to the shared Update host.
- [ ] Tasks 7–8 — not attempted.

## Scope Attempted

- Retained the complete current `TaskSnapshotItem` in `DeadlineBlockerActivity`, alongside
  the compact display model. Only a successfully refreshed snapshot that matches the alarm
  occurrence and remains active may enter Reschedule; cached, missing, stale, completed, or
  deleted roots render a Refresh/Cancel recovery state instead of a partial editor.
- Replaced the legacy schedule-only `CommitmentEditor` recovery panel with
  `TaskEditorUpdateScreen`, `Reschedule` presentation/range action label, eager validation,
  project/collaborator load/retry, Activity log footer, full root-field editing, and supported
  direct checklist mutations. Unsupported nested checklists leave root fields available and
  show the existing read-only hierarchy warning.
- Routed deadline recovery through complete `UpdateTaskRequest` construction and
  `EnforcementCoordinator.updateTask`. Shared host lifecycle owns pending confirmation,
  duplicate-submit prevention, authoritative success close, generic retained-draft failure,
  and top-Back/bottom-Cancel discard behavior. A late outer Back handler keeps Android system
  Back consumed while the full-screen blocker is active.
- Follow-up independent-spec-review fix: a personal-project task can retain its self assignee
  in the snapshot even though its project cannot assign tasks. The deadline editor now derives
  both its initial and current dirty fingerprints from the same hydrated project-assignee
  projection as `updateRequest`, so choice loading cannot create a false discard prompt.
- Removed the Android enforcement-only schedule path: `RESCHEDULE_TASK`, coordinator
  `rescheduleTask`, and `EnforcementApi.rescheduleTask`. The shared `RescheduleTaskRequest`,
  shared client method, and backend endpoint remain intentionally for Task 7.
- Updated the two deadline-only fake-backend test scenarios at source level to generic shared
  Reschedule UI selectors, `POST /update`, and `UPDATE_TASK`. The fake backend was not started
  or modified, and that instrumentation class was not run.

## Files Changed

- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/BlockerTaskSnapshotGate.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/EditTaskScreen.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementApi.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BlockerAction.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/blockers/BlockerTaskSnapshotGateTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/DeadlineBlockerUpdateStateTest.kt` (new)
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/ui/screens/DeadlineBlockerUpdateHostTest.kt` (new; local Compose host coverage)
- Mechanical test alignment only: `EnforcementCoordinatorTest.kt` removes the now-invalid
  fake API override; `BlockerActionStateTest.kt` replaces deleted schedule-factory coverage
  with canonical full-update request assertions.
- This handoff.

## TDD Evidence

RED:

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*DeadlineBlockerUpdateStateTest' --tests '*BlockerTaskSnapshotGateTest'`
  - Result: expected FAIL.
  - Evidence: `currentTask`, `DeadlineBlockerRecoveryState`, recovery presentation, and
    cancel/system-Back helper symbols were unresolved before production implementation.
    The initial sandboxed invocation could not open Gradle's shared wrapper lock; the approved
    cache-access rerun reached the intended compilation failures.

GREEN:

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*DeadlineBlockerUpdateStateTest' --tests '*BlockerTaskSnapshotGateTest' --tests '*BlockerActionStateTest'`
  - Result: PASS.
  - Evidence: `BUILD SUCCESSFUL`; current snapshot gating, complete Update state, immediate
    validation, root/direct-checklist behavior, hierarchy restriction, pending/confirmation/
    error behavior, cancel parity, and canonical request assertions pass.
- A subsequent strengthened range-label expectation initially failed because the test fixture's
  UTC deadline is correctly converted to the device time zone (09:00Z → 11:00 local). The test
  now derives the expected Reschedule range from the host draft; no production behavior changed.
- A follow-up RED unit compile for
  `personalProjectSelfAssigneeStaysCleanAfterChoicesLoadAndExitsWithoutDiscardPrompt` proved the
  dedicated deadline fingerprint seam did not yet exist. The GREEN regression asserts that
  hydration normalizes the personal self assignee from `me` to `null` in both baseline/current
  fingerprints, both exits return directly without a discard prompt, and a real title edit still
  requests discard confirmation.
- Read-only spec review caught that a cached matching root remained editable after an
  authoritative refresh timeout/error. A second RED test added the unconfirmed-snapshot case;
  it passed after the activity started tracking refresh confirmation and the recovery state
  gated it to Refresh/Retry.
- Read-only code-quality review caught that the original Activity-footer assertion only tested
  presentation metadata. A local Compose host test now renders `DeadlineBlockerScreen`, enters
  Reschedule, asserts the actual Checklist-before-Activity layout, and verifies enabled direct
  checklist controls. It is source/compilation checked only, without a backend or emulator.

## Verification

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*DeadlineBlockerUpdateStateTest' --tests '*BlockerTaskSnapshotGateTest' --tests '*BlockerActionStateTest'`
- Result: PASS
- Evidence: focused deadline/snapshot/update request state suites passed after the final lazy
  project-repository initialization, snapshot-confirmation refinements, and personal-project
  self-assignee clean-exit regression.

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest :shared:testAndroidHostTest :androidApp:compileDebugAndroidTestKotlin`
- Result: PASS
- Evidence: broad Android JVM suite, shared Android-host suite, and changed instrumentation
  source compilation all completed successfully; no emulator or backend process was launched.

Verification:
- Command: `rg -n "rescheduleTask|RESCHEDULE_TASK|onReschedule|descriptionAwareReschedule|rescheduleRequestFromEditorStart" client/androidApp/src/main/kotlin client/androidApp/src/test/kotlin client/androidApp/src/androidTest/kotlin --glob '*.kt'`
- Result: PASS
- Evidence: no Android production/test source match remains for the legacy deadline recovery
  coordinator/action/factory path.

Verification:
- Command: `rg -n "Save task|Start picker|Duration picker|/reschedule" client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- Result: PASS
- Evidence: no legacy deadline-reschedule selectors or endpoint assertion remains in the
  migrated instrumentation source.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors after the final handoff update.

Verification:
- Command: `npx ctx7@latest library "Jetpack Compose" "state hoisting a stateful screen host that delegates a task editor form to a reusable composable and coordinates suspend updates, pending state, and back/cancel handling"` followed by `npx ctx7@latest docs "/websites/developer_android_develop_ui_compose" "State hoisting: a stateful screen host owns business logic, suspend update side effects, and navigation, while a reusable form receives UI state plus callbacks; include guidance for handling Back and user events."`
- Result: PASS
- Evidence: official Compose guidance supports host-owned update/navigation state with a reusable
  callback-driven editor form, as used by the deadline wrapper.

## Manual Verification

- Not performed. The task explicitly prohibits starting or changing `tools/fake-backend`; the
  Task 8 real-backend E2E lane owns runtime verification. The changed Android test source was
  compilation-checked only.

## Known Failures / Skipped Checks

- `scripts/e2e-fake-backend.sh` and `tools/fake-backend` were intentionally not run or changed.
- `scripts/e2e-real-backend.sh` was intentionally not run: Task 8 owns the authoritative
  full E2E lane.
- No emulator/device instrumentation test class was executed. The source compile is the allowed
  alignment check for `BlockerActivityFakeBackendTest`.

## Assumptions

- `CoordinatorActionResult.Success` from `updateTask` is the authoritative confirmation used by
  the shared host; it closes the deadline blocker immediately. `Pending` retains the captured
  full request/action ID for the host's confirmation retry and keeps the blocker open.
- Cached snapshots remain useful to display the blocker but are not sufficient to open recovery.
  Only the most recent successful authoritative refresh can permit editor entry; an
  omitted/stale/resolved root is never used for editing.
- Task 7 remains responsible for deleting the shared `RescheduleTaskRequest` and backend route.

## Next Recommended Step

- Complete the integration review and merge this Task 6 branch. Then execute Task 7 to retire
  the remaining shared/client/backend reschedule contract and legacy editor symbols atomically.
