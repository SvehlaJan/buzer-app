# Handoff: Task Editor Redesign — Task 7 Add Task migration

Date: 2026-07-22

## Task / Plan

- Plan: `docs/superpowers/plans/2026-07-21-task-editor-redesign.md`
- Task: Task 7, Migrate Add Task to the new draft and collaborator contract
- Checkbox status: implementation complete in this worktree; the controller owns plan-checkbox updates after review.

## Files Changed

- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/tasks/AddTaskProjectRepository.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskProjectLoadFeedback.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorScaffold.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskProjectLoadFeedbackTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor/TaskEditorScaffoldStateTest.kt`
- This handoff.

## Implementation

- Replaced Add Task's `CommitmentEditor` and static assignee state with the Add/Edit-only `TaskEditorScaffold` and one `TaskEditorDraft`.
- Project selection now uses the shared reducer: it retains a still-valid assignee, otherwise restores project-valid `Me`. Personal projects render locked `Me`; the existing validator still encodes their Todoist request with `assigneeId = null`.
- The project repository caches whole `ProjectItem` projections (including collaborator list/readiness). A refresh failure preserves that cache and leaves it usable after the error dialog is dismissed.
- A shared project whose membership mirror is not ready disables the Assignee surface, exposes Retry collaborators, and disables Save. The validation guard also blocks a request should that state be reached programmatically.
- Domain changes clear the captured create request/action ID. Sheet/accordion state stays inside the scaffold and does not invalidate retry. The prior create action UUID capture, authoritative snapshot handoff, and failure retry remain unchanged.
- Schedule changes use the new `TaskEditorSchedule`; `No schedule` calls the complete `setNoSchedule` reducer, clearing schedule and setting recurrence to `never`. A valid non-self unscheduled draft continues through `AddTaskFormValidator` as `dueDatetime = null`, `durationMinutes = null`, with the scaffold-derived `Save task` label.
- Added optional inline Project/Assignee error inputs to the scaffold and used them from Add, so validation shows one field-specific error. Schedule validation still focuses and highlights Schedule rather than issuing a create request.

## TDD Evidence

- Initial RED:
  `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.addtask.AddTaskProjectLoadFeedbackTest`
  failed as expected on unresolved project-cache/membership/project-selection helpers.
- Second RED:
  `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.taskeditor.TaskEditorScaffoldStateTest`
  failed as expected because the inline field-error presentation helper did not exist.

## Verification

Verification:

- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.addtask.AddTaskCreateFlowTest --tests app.buzer.android.ui.addtask.AddTaskSubmitStateTest --tests app.buzer.android.ui.addtask.AddTaskProjectLoadFeedbackTest --tests app.buzer.android.ui.addtask.AddTaskFormValidatorTest --tests app.buzer.android.ui.taskeditor.TaskEditorScaffoldStateTest`
- Result: PASS
- Evidence: focused Add create/retry/validator/project-member state and inline-error regression suites completed.

Verification:

- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`
- Result: PASS
- Evidence: full shared Kotlin host suite, full Android JVM suite, and debug APK assembly completed.

Verification:

- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace diagnostics.

## Manual / Deferred Verification

- SKIPPED in this task: `scripts/e2e-fake-backend.sh` and the complementary real-backend lane. Task 9 owns the required fake/real fixture and instrumentation migration; the current harness still asserts legacy Add controls. The controller must run both lanes after Task 9 before considering the UI redesign complete.
- No device manual pass was performed for the same reason. The next interaction verification should cover collapsed Schedule, quick choices, each custom picker, Project/Assignee sheets, unscheduled Wife save, retry preservation, and dirty exit.

## Assumptions / Notes

- Todoist's `/projects` contract always includes the current-user collaborator in each usable project. The screen's blank-ID `Me` fallback is presentation-only and cannot become a valid submission; it prevents an invented assignee if the contract is unavailable.
- `nowProvider` is injectable for deterministic screen tests; the scaffold only receives its value and does not read the clock itself.
- The legacy `ProjectSelector` and `AssigneeSelector` helpers remain in the source because existing repair-screen presentation tests call their pure personal-project helper. Add Task no longer calls them or accepts static assignee options.

## Next Recommended Step

Run the required Task 7 specification and code-quality reviews, then migrate Edit Task in Task 8. After Task 9 updates fixture parity and instrumentation semantics, run both deterministic E2E lanes.

## Specification Review Follow-up

The Task 7 specification review found that blank-title and invalid-checklist Save
failures were retained in Add state but had no visible presentation in the new
scaffold. This follow-up resolves that P1:

- `TaskEditorScaffold` now accepts optional title and checklist errors. The title
  field displays concise error text directly below its divider with a title-specific
  accessibility description; the Checklist row shows concise error text and error
  color without opening or mutating the draft.
- Add wires its existing `AddTaskInvalidField.Title` error to the title input and its
  fieldless checklist/generic validation message to Checklist. Project, Assignee,
  and Schedule handling is unchanged; all validation branches return before a create
  request is assembled.

TDD evidence: the added `conciseInlineError` state test was RED first with unresolved
references, then GREEN after the scaffold presentation inputs were added.

Verification:

- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.taskeditor.TaskEditorScaffoldStateTest --tests app.buzer.android.ui.addtask.AddTaskProjectLoadFeedbackTest --tests app.buzer.android.ui.addtask.AddTaskFormValidatorTest`
- Result: PASS
- Evidence: concise title/checklist inline-error presentation and Add validation regressions passed.

Verification:

- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`
- Result: PASS
- Evidence: complete shared host suite, Android JVM suite, and debug APK assembly passed after the review fix.
