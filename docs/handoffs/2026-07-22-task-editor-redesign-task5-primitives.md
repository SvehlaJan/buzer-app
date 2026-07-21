# Handoff: Task Editor Redesign — Task 5 draft and picker primitives

Date: 2026-07-22

## Task / Plan

- Plan: `docs/superpowers/plans/2026-07-21-task-editor-redesign.md`
- Task: Task 5, Build pure Add/Edit draft, schedule, and picker primitives
- Checkbox status: Task 5 implementation is complete in this worktree. The parent
  controller owns plan-checkbox updates.

## Files Changed

- `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorDraft.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/ScheduleQuickChoices.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorWheelDialogs.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor/TaskEditorDraftTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor/ScheduleQuickChoicesTest.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/DraftExitPolicy.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/DraftExitPolicyTest.kt`
- This handoff.

## Implementation

- Added the nullable `TaskEditorSchedule` and domain `TaskEditorDraft`, including
  complete/empty recognition, non-self-only `No schedule`, and draft-only custom
  date/time/duration reducers. Cancel returns the exact input draft; Apply updates
  only its matching field.
- Added deterministic quick-choice and rendering helpers. They take an injected
  `LocalDateTime`, round an empty selection to the next five-minute slot, deduplicate
  date choices, retain a selected time, keep selected duration values in the compact
  choice set, and format cross-midnight summaries/save labels from the same draft.
- Added independent Material 3 `AlertDialog` wheel primitives for Date, Time, and
  Duration. Each owns pending Compose state and exposes typed Apply callbacks; Cancel
  and outside dismissal invoke only `onDismiss`. The legacy combined
  `WheelDateTimeField`, `WheelDurationField`, and `CommitmentEditor` were untouched.
- Extended the Edit fingerprint/factory with project and assignee IDs, so either
  domain selection participates in dirty-exit comparison. Accordion and dialog
  visibility remain outside that fingerprint.

## TDD / Debugging Evidence

- Initial RED:
  `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.taskeditor.TaskEditorDraftTest --tests app.buzer.android.ui.taskeditor.ScheduleQuickChoicesTest --tests app.buzer.android.ui.screens.DraftExitPolicyTest`
  failed as intended because the task-editor package and Edit `projectId`/
  `assigneeId` fingerprint fields did not exist.
- First green run found the selected-Today date case yielded two choices after
  deduplication. Moving the fallback workday candidate beyond tomorrow restored the
  required three compact choices; the focused suite then passed.

## Verification

Verification:

- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.taskeditor.TaskEditorDraftTest --tests app.buzer.android.ui.taskeditor.ScheduleQuickChoicesTest --tests app.buzer.android.ui.screens.DraftExitPolicyTest`
- Result: PASS
- Evidence: focused primitives and dirty-exit suite completed successfully.

Verification:

- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest`
- Result: PASS
- Evidence: complete shared Kotlin host suite passed.

Verification:

- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest`
- Result: PASS
- Evidence: complete Android JVM unit suite passed.

Verification:

- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:assembleDebug`
- Result: PASS
- Evidence: debug APK assembled successfully.

Verification:

- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace diagnostics before staging.

## Manual Verification

- Context7 lookup of WheelPickerCompose confirmed the current `WheelDatePicker` and
  `WheelTextPicker` callback/initial-index APIs used by the three independent
  dialogs. There is no direct system-clock read in the new task-editor package.
- No emulator run was needed for these not-yet-integrated primitives; Task 9 owns the
  redesigned Add/Edit instrumentation and complete E2E lane.

## Assumptions / Notes

- Existing `testImplementation` dependencies already provide Kotlin/JUnit coverage;
  no new test-only dependency is required for these pure unit tests.
- The custom dialog caller supplies the draft-or-injected-current initial value. The
  dialogs themselves deliberately do not consult the system clock.
- `TaskEditorScaffold`/`ScheduleEditor` remain Task 6 ownership and will consume
  these pure helpers without changing repair or blocker reschedule surfaces.

## Next Recommended Step

Run Task 5 specification and code-quality review, then proceed to Task 6's compact
editor scaffold and draft-only selection sheets.

## Review Follow-up

The Task 5 code-quality review found and this follow-up fixed three boundary cases:

- A non-Today selected date now occupies the selected slot while the other two quick
  choices retain Tomorrow and the next useful workday; it can no longer be truncated
  before the workday candidate.
- `ScheduleTimeChoice` now carries its calendar date, and
  `selectQuickStartTime()` applies that date with the time. A 23:56–23:59 injected
  clock rounds to the next day and displays `Tomorrow 00:00`, rather than silently
  reducing the value to a misleading Today `00:00` time.
- The duration wheel rounds the total clamped minutes before decomposing hours and
  minutes, so 59 and 119 minutes seed `1h 0m` and `2h 0m` instead of shortening to
  `0h 55m` and `1h 55m`.

Verification:

- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.taskeditor.ScheduleQuickChoicesTest --tests app.buzer.android.ui.taskeditor.TaskEditorWheelDialogsTest`
- Result: PASS
- Evidence: focused date-retention, midnight-rollover, and duration-hour-carry
  regression tests passed.

Verification:

- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest`
- Result: PASS
- Evidence: complete Android JVM unit suite passed after the review fixes.
