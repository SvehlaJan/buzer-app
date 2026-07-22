# Handoff: Unified Task Editor Task 1 — Validation

Date: 2026-07-22
Agent: `unified_editor_task1`
Plan: `docs/superpowers/plans/2026-07-22-unified-task-editor.md`

## Task / Checkbox Status

- [x] Task 1 — centralize validation and presentation data
- [ ] Tasks 2–8 — not attempted; owned by follow-on tasks.

## Scope Attempted

- Added immutable, UI-neutral task-editor field validation, exposure projection, and
  host-presentation values.
- Extended the pure draft with original-schedule/custom-recurrence state so a changed
  custom recurrence needs an explicit supported Repeat replacement.
- Replaced the hard-coded Save formatter with a generic verb-plus-range formatter while
  retaining a temporary Save wrapper for the unmigrated scaffold.
- Converted `AddTaskFormValidator` into a compatibility request builder that delegates
  field acceptance to the common validator.
- Did not modify `TaskEditorScaffold`, Add/Edit/Morning/Blocker screens, shared/backend
  APIs, `CommitmentEditor`, or `tools/fake-backend`.

## Files Changed

- `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorValidation.kt` (new)
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorDraft.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/ScheduleQuickChoices.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor/TaskEditorValidationTest.kt` (new)
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor/TaskEditorDraftTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor/ScheduleQuickChoicesTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidatorTest.kt`
- This handoff.

## TDD Evidence

RED (all run from `client/` with `ANDROID_HOME` and `ANDROID_SDK_ROOT` set):

- `./gradlew :androidApp:testDebugUnitTest --tests '*TaskEditorValidationTest'`
  - Expected RED: unresolved `validateTaskEditor` and `ValidationExposure` before the
    shared layer existed.
- Same focused validator test after adding the non-self schedule test.
  - Expected RED: `nonSelfCanUseAnEmptyScheduleOnlyWithNeverRepeat` failed because the
    prior rule required a schedule for every assignee.
- Same focused validator test after adding the custom-recurrence test.
  - Expected RED: `recurrenceKind` and `repeatReplacementSelected` did not yet exist.
- Same focused validator test after adding collaborator-membership coverage.
  - Expected RED: the resolved collaborator mismatch was accepted.
- Same focused validator test after adding presentation coverage.
  - Expected RED: unresolved `TaskEditorPresentation`.
- `./gradlew :androidApp:testDebugUnitTest --tests '*ScheduleQuickChoicesTest'`
  - Expected RED: unresolved `primaryActionLabel`.
- `./gradlew :androidApp:testDebugUnitTest --tests '*AddTaskFormValidatorTest'`
  - Expected RED: the legacy validator silently normalized a non-self weekly recurrence
    without a schedule instead of requiring Repeat = Never.

GREEN:

- Each corresponding focused test command passed immediately after the minimal
  implementation for that behavior.

## Verification

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*TaskEditorValidationTest' --tests '*TaskEditorDraftTest' --tests '*ScheduleQuickChoicesTest' --tests '*AddTaskFormValidatorTest'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; all shared validation, draft, label, and compatibility tests passed.

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; 38 Gradle tasks completed with no unit-test failures.

## Code-Quality Correction (2026-07-23)

- Closed the legacy Add compatibility bridge bypass: it now converts central unavailable
  project/collaborator fields into the existing `AddTaskFormResult.Invalid` path before
  creating a request. A shared project with unresolved collaborators therefore cannot
  be serialized as a valid create request.

TDD evidence:

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*AddTaskFormValidatorTest'`
  - Result: RED
  - Evidence: `rejectsCreateWhenSharedProjectCollaboratorsAreUnavailable` expected the
    assignee-unavailable `Invalid` result, but the pre-fix bridge returned `Valid`.
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*TaskEditorValidationTest' --tests '*AddTaskFormValidatorTest'`
  - Result: PASS
  - Evidence: `BUILD SUCCESSFUL`; central validation and compatibility bridge coverage
    both passed.

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; 38 Gradle tasks completed with no unit-test failures.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

## Manual Verification

- Not applicable: this task adds pure Kotlin validation/presentation values and does not
  migrate or render a screen.

## Known Failures / Skipped Checks

- The first test command from the worktree root used `./gradlew` and failed because the
  wrapper is correctly located at `client/gradlew`; all recorded Gradle results above
  use that directory.
- The initial sandboxed Gradle run could not create the shared wrapper-cache lock; the
  approved elevated retry ran normally.
- `scripts/e2e-fake-backend.sh` was intentionally skipped. The Task 1 guardrail and
  plan forbid starting or using the deprecated fake backend, and no screen/scaffold or
  instrumentation-owned behavior was changed. Task 8 owns real e2e coverage.

## Assumptions

- `TaskEditorDraft.originalSchedule` captures the draft's initial schedule; future
  Update hosts must set `recurrenceKind` from the snapshot and mark
  `repeatReplacementSelected` only after the user chooses a supported Repeat option.
- Project/collaborator availability remains a separate input to `validateTaskEditor`:
  it disables submission without fabricating a field error while facts are loading.
- The temporary `saveActionLabel` delegate is retained only until Task 2 supplies each
  host's `TaskEditorPresentation.primaryVerb` to the scaffold.

## Next Recommended Step

- Task 2 should consume `TaskEditorPresentation`, `TaskEditorValidation.visible(...)`,
  and `primaryActionLabel(...)` in the universal scaffold, including field errors and
  disabled primary action behavior. It should then remove the temporary Save-specific
  call path as part of its owned scaffold migration.

## Spec-Review Correction (2026-07-23)

- Corrected the resolved-membership rule for personal projects. The original predicate
  checked the collaborator list only when `project.canAssignTasks` was true, allowing a
  non-member assignee to pass a resolved personal project before compatibility request
  serialization silently dropped that assignee. The common validator now checks every
  resolved project's collaborator list; a personal project containing Me still accepts
  Me and rejects every other assignee.

TDD evidence:

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*TaskEditorValidationTest'`
  - Result: RED
  - Evidence: `resolvedPersonalProjectRejectsANonMemberAssignee` failed before the
    predicate change because Wife had no assignee error and `canSubmit` remained true.
- Same focused command after the one-condition change.
  - Result: PASS
  - Evidence: the personal-project regression and existing validator tests passed.

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; 38 Gradle tasks completed with no unit-test failures.
