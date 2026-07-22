# Handoff: Task Editor Redesign — Task 7 Add Task migration

Date: 2026-07-22

## Task / Plan

- Plan: `docs/superpowers/plans/2026-07-21-task-editor-redesign.md`
- Task: Task 7, Migrate Add Task to the new draft and collaborator contract
- Checkbox status: implementation complete in this worktree; the controller owns plan-checkbox updates after review.

## Files Changed

- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/DraftExitPolicy.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/tasks/AddTaskProjectRepository.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskProjectLoadFeedback.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorScaffold.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskProjectLoadFeedbackTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor/TaskEditorScaffoldStateTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/DraftExitPolicyTest.kt`
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

## Task 9 Cross-Check Follow-up

Task 9 found that Add had bypassed the existing dirty-exit policy by wiring the
scaffold's Back and Cancel directly to navigation. This follow-up now:

- builds an Add-specific draft fingerprint from the new editor draft, preserving the
  personal-project `Me` display as a null Todoist assignee;
- captures the first definitive project-load baseline as either an automatically
  resolved persisted project/current-user selection or no project. The sole deferral
  is an unresolved saved project preference, which waits for retry to restore that
  preference without absorbing later user project selections, title, schedule, or
  checklist edits;
- routes both Back and Cancel through `decideDraftExit`, so clean drafts navigate,
  changed drafts open `Discard changes?`, and submitting drafts ignore exit; and
- keeps captured create requests/retry state untouched until the user explicitly
  confirms Discard.

A focused `DraftExitPolicyTest` covers the automatic initial personal-project/Me
selection (clean) and a later title change (discard confirmation). The test was added
before the helper implementation, but its required Gradle invocation could not run:

Verification:

- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.screens.DraftExitPolicyTest`
- Result: SKIPPED
- Evidence: the approved escalated command was rejected by the environment's automatic approval review because the workspace hit its usage limit; no alternative execution path was attempted.

Verification:

- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace diagnostics after the dirty-exit follow-up.

## Follow-up: system Back handling

- Android system Back now uses the same `requestExit()` decision path as the editor's Back and Cancel controls. It navigates away for a clean draft, opens the discard confirmation for a dirty draft, and is ignored while submission is in flight.
- While the discard confirmation is open, system Back closes only that dialog, matching its normal dismissal behavior rather than reopening the exit flow.
- Existing `DraftExitPolicyTest` coverage asserts clean/dirty and in-flight decisions; the Add baseline test covers automatic initial project/assignee initialization that must remain clean.

Verification:

- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors in the uncommitted Task 7 changes after the Back-handler update.
- Command: `rg -n "BackHandler|requestExit|discardConfirmationVisible" client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
- Result: PASS
- Evidence: two mutually exclusive Back handlers are present: one routes normal Back to `requestExit()`, and one dismisses the visible discard dialog.
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.screens.DraftExitPolicyTest`
- Result: SKIPPED
- Evidence: the approved escalated command is still blocked by the environment usage limit; no alternate execution path was attempted.

## Follow-up: Add baseline precision

- Add fingerprints now preserve nullable duration. The empty draft remains `null`, so choosing the UI's 30-minute duration is a real `null -> 30` edit and requires discard confirmation.
- Project baseline capture locks on the first definitive load when there is no saved preference. With a saved preference, either an empty result or a nonempty result missing that project remains uncaptured until retry can restore the persisted-project/`Me` pair. A retry cannot absorb a later manual project selection into the clean baseline.
- Added pure `DraftExitPolicyTest` coverage for all four cases: explicit 30-minute selection from an empty Add draft, manual project selection followed by a retry after an empty initial load, and both empty and nonempty-missing initial responses with a saved project preference that retry restores.

Verification:

- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors in the uncommitted Task 7 changes after the baseline fixes.
- Command: `rg -n "durationMinutes = draft.schedule.durationMinutes|durationMinutes = null|automaticPersistedProject|shouldDeferNoProjectBaseline|deferNoProjectBaseline|captureAddTaskProjectBaseline|selectingThirtyMinutes|manualProjectSelectionBeforeRetry|emptyInitialLoadWithAPersistedProject|nonemptyInitialLoadMissingThePersistedProject" client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/DraftExitPolicy.kt client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/DraftExitPolicyTest.kt`
- Result: PASS
- Evidence: static inspection confirms nullable duration is retained, only auto persisted-project resolution is eligible for the initial baseline, all unresolved saved-preference loads defer correctly, and all pure regression tests are present.
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.screens.DraftExitPolicyTest`
- Result: SKIPPED
- Evidence: the approved escalated command remains blocked by the environment usage limit; no alternate execution path was attempted.
