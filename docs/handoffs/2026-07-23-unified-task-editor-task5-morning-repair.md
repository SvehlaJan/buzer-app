# Handoff: Unified Task Editor Task 5 — Morning Repair

Date: 2026-07-23
Agent: `unified_editor_task5`
Plan: `docs/superpowers/plans/2026-07-22-unified-task-editor.md`

## Task / Checkbox Status

- [x] Task 5 — Morning Review Repair uses the shared Update host.
- [ ] Tasks 6–8 — not attempted.

## Scope Attempted

- Replaced Morning Review's legacy `CommitmentEditor` repair form, local repair
  validation/state, and date/time helpers with `TaskEditorUpdateScreen` using the
  `Repair` presentation and Activity-log footer.
- Kept Morning Review responsible for loading/retrying choice facts, dispatching the
  existing full coordinator update, and reducing only the repaired invalid task after
  confirmed success. The Activity finishes only when the remaining list is empty.
- Preserved all root/directed-checklist update construction through the canonical
  `updateRequest` builder, including the stored device identity and captured request
  for pending confirmation retries.
- Removed `AddTaskFormValidator.kt`, `AddTaskInvalidField`, and their dedicated tests
  after verifying no remaining source references.
- With parent authorization, migrated only `BlockerActionStateTest` from the deleted
  Morning Review helper to canonical `updateRequest`; no Blocker production code or
  Task 6 behavior was changed.

## Files Changed

- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/MorningReviewScreenStateTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/blockers/BlockerActionStateTest.kt`
- Deleted `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt`
- Deleted `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidatorTest.kt`
- This handoff.

## TDD Evidence

RED:

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewScreenStateTest'`
  - Result: expected FAIL.
  - Evidence: the rewritten state test had unresolved
    `morningReviewRepairPresentation` and `completeMorningReviewRepair` symbols before
    the shared-host migration. The initial sandboxed attempt could not access Gradle's
    shared wrapper-cache lock; the approved cache-access retry reached the intended
    compile failure.

GREEN:

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewScreenStateTest' --tests '*TaskEditorUpdateScreenStateTest' --tests '*BlockerActionStateTest'`
  - Result: PASS.
  - Evidence: `BUILD SUCCESSFUL`; 33 focused Morning Review, shared Update-host, and
    authorized canonical request-builder tests passed after the device-identity and
    entry-validation coverage updates.

## Verification

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewScreenStateTest' --tests '*TaskEditorUpdateScreenStateTest' --tests '*BlockerActionStateTest'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; focused state/request tests completed after migration.

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; broad Android JVM suite completed with no test failures.

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; shared Android-host suite completed.

Verification:
- Command: `rg -n "AddTaskInvalidField|AddTaskFormValidator|AddTaskFormResult" client --glob '!**/build/**'`
- Result: PASS
- Evidence: no remaining source references after deletion.

Verification:
- Command: `rg -n "MorningReviewRepairEditorState|MorningReviewRepairValidation|initialRepairStartDraft|toDateTimeDraftSelection|updateTaskRequestFromEditorStart" client --glob '!**/build/**'`
- Result: PASS
- Evidence: legacy Morning Repair state, validation, draft-selection, and request helper
  names have no remaining source references.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors after the handoff was added.

## Manual Verification

- Not performed. The migration is covered by deterministic Update-host/Morning Review
  state and request tests. No device or emulator was used.

## Known Failures / Skipped Checks

- `scripts/e2e-fake-backend.sh` was intentionally not run: this task explicitly
  prohibits fake-backend tooling.
- `scripts/e2e-real-backend.sh` was intentionally not run: Task 8 owns the real-backend
  E2E lane.
- No instrumentation lane was run. The shared scaffold's deterministic footer/order
  instrumentation coverage belongs to Task 2; Task 8 owns Repair's real-backend
  integration coverage.
- The first focused GREEN attempt exposed a Task 6 test-only dependency on the deleted
  Morning Review request helper. Parent explicitly authorized the narrow
  `BlockerActionStateTest` migration to `updateRequest`; this remains the only
  non-owned test change.

## Assumptions

- `CoordinatorActionResult.Success` is the authoritative confirmation consumed by the
  shared Update host; only that callback invokes Morning Review's invalid-list reduction.
- Unsupported nested checklist hierarchies continue to lock only Checklist while root
  fields remain editable; direct checklist mutations are omitted on save.
- Choice load/retry failure is availability state, not a replacement validation error:
  the shared host keeps the editor rendered and disables its primary action.

## Next Recommended Step

- Integrate/review this branch, then proceed to Task 6's deadline-blocker migration.
