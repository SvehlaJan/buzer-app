# Handoff: Task Editor Redesign — Task 6 compact editor scaffold

Date: 2026-07-22

## Task / Plan

- Plan: `docs/superpowers/plans/2026-07-21-task-editor-redesign.md`
- Task: Task 6, Compose the compact editor scaffold and draft-only selection sheets
- Checkbox status: Task 6 implementation is complete in this worktree. The controller
  owns plan-checkbox updates after its review passes.

## Files Changed

- `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorScaffold.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorSchedule.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorSelectionSheets.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor/TaskEditorScaffoldStateTest.kt`
- This handoff.

## Implementation

- Added pure scaffold reducers for exclusive Schedule/Notes/Checklist disclosure,
  immediate Project/Assignee selection results, project-scoped assignee repair, and
  self-schedule validation. Presentation state remains outside the domain draft.
- Added a cardless Add/Edit-only `TaskEditorScaffold`: borderless one-line title,
  compact Schedule/Project/Assignee/Repeat/Notes/Checklist rows, and a sticky
  Cancel/Save action bar that stays above IME and navigation insets. It accepts only
  values and callbacks; it neither reads repositories nor creates action IDs or clocks.
- Added an injected-time `ScheduleEditor` with horizontal quick choices, exact
  icon-only Custom semantic labels, separate Task 5 wheel dialogs, complete-schedule
  end rendering, and non-self-only No schedule handling. `onNoSchedule` is a separate
  callback so a parent can atomically clear the schedule and set recurrence to never.
- Added immediate draft-only Project search, Assignee, and Recurrence bottom sheets.
  The assignee sheet has identity hints/trailing selection checks and retry treatment
  for unavailable collaborators; personal projects show locked `Me` without a chevron.
- Kept the existing `ChecklistEditor` ownership outside this component and render it
  unchanged through the expanded Checklist content slot. `CommitmentEditor` and its
  protected repair/reschedule callers were not changed.

## TDD Evidence

- Initial RED:
  `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.taskeditor.TaskEditorScaffoldStateTest`
  failed as intended with unresolved scaffold reducers/state/validation symbols.
- The first sandboxed run could not open Gradle's shared wrapper lock. Re-running the
  same focused test with the approved shared Gradle cache exposed the intended Kotlin
  unresolved references before the implementation existed.

## Verification

Verification:

- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.taskeditor.TaskEditorScaffoldStateTest --tests app.buzer.android.ui.taskeditor.TaskEditorDraftTest --tests app.buzer.android.ui.taskeditor.ScheduleQuickChoicesTest --tests app.buzer.android.ui.taskeditor.TaskEditorWheelDialogsTest`
- Result: PASS
- Evidence: focused Task 5 primitives and Task 6 state suite completed successfully.

Verification:

- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`
- Result: PASS
- Evidence: complete shared Kotlin host suite, Android JVM suite, and debug APK assembly all passed.

Verification:

- Command: `git diff --cached --check`
- Result: PASS
- Evidence: no whitespace diagnostics after staging the Task 6 files.

## Manual Verification

- Context7 Material 3 documentation confirmed the `ModalBottomSheet` state/dismissal
  model used for the three selection surfaces. The scaffold's `now` is injected and no
  task-editor renderer reads a system clock.
- No emulator run was performed because this is a not-yet-integrated component
  boundary. Task 9 owns Add/Edit instrumentation and the required fake-backend E2E
  lane after Tasks 7/8 connect the new scaffold.

## Assumptions / Notes

- The owning Add/Edit screen must call the pure `selectProject()` reducer (or apply
  equivalent logic) when project selection changes so stale assignees fall back to a
  valid current user or clear. It must use `onNoSchedule()` to apply the full
  `setNoSchedule()` draft reducer rather than changing only schedule fields.
- When collaborator data are unavailable, the Assignee row is disabled and exposes a
  dedicated Retry collaborators action. A selection surface is never used to invent
  membership.

## Next Recommended Step

Run Task 6 specification and code-quality review, then migrate Add and Edit to this
scaffold in Tasks 7 and 8 while preserving their existing retry/reconciliation flows.

## Review Follow-up

The Task 6 specification review found two Schedule-validation gaps. This follow-up
adds controlled resolution of an expanded section: when the owning screen passes a
Schedule validation focus, Schedule is rendered open even if Notes or Checklist had
been open. The expanded Schedule now also displays the concise error copy
`Set date, start time, and duration to save this task.` above the existing missing
control highlights.

TDD evidence: the added focused test was RED before implementation because
`resolvedExpandedSection` and `scheduleValidationMessage` did not exist. It is GREEN
after the reducer and presentation helpers were added.

Verification:

- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.taskeditor.TaskEditorScaffoldStateTest --tests app.buzer.android.ui.taskeditor.TaskEditorDraftTest --tests app.buzer.android.ui.taskeditor.ScheduleQuickChoicesTest --tests app.buzer.android.ui.taskeditor.TaskEditorWheelDialogsTest`
- Result: PASS
- Evidence: Schedule focus/error-copy coverage and Task 5 primitive regression tests passed.

Verification:

- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`
- Result: PASS
- Evidence: complete shared and Android JVM suites passed and debug APK assembled after the review fix.

## Code-Quality Review Follow-up

The Task 6 code-quality review found three more boundary defects. This follow-up:

- persists Schedule validation focus into the presentation state with
  `persistValidationFocus`, while retaining the immediate controlled rendering needed
  for the first error composition; Schedule therefore remains expanded after the
  parent clears validation following a field edit;
- adds a top, icon-only `Back` affordance with a semantic `Back` content description
  and a dedicated `onBack` callback, separate from the sticky `onCancel` callback so
  the Add/Edit owners can later apply distinct discard policies; and
- changes `TaskEditorSchedule.isComplete()` to require a positive duration. Derived
  end, collapsed summary, and Save-time labels now all reject a zero-minute duration.

TDD evidence: the persistence/zero-duration regression test run was RED first because
`persistValidationFocus` was unresolved. After implementing the reducer and positive
duration invariant, the focused suite passed.

Verification:

- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.taskeditor.TaskEditorScaffoldStateTest --tests app.buzer.android.ui.taskeditor.TaskEditorDraftTest --tests app.buzer.android.ui.taskeditor.ScheduleQuickChoicesTest --tests app.buzer.android.ui.taskeditor.TaskEditorWheelDialogsTest`
- Result: PASS
- Evidence: persisted focus and zero-duration derived-state regressions passed with all Task 5/6 focused tests.

Verification:

- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`
- Result: PASS
- Evidence: complete shared Kotlin host suite, Android JVM suite, and debug APK assembly passed after the code-quality fixes.
