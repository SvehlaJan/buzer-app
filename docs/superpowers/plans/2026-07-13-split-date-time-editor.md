# Split Date Time Editor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show available task dates even when Todoist has no time, split the start picker into Date and Time buttons, and show edit validation immediately on Morning Review repair open.

**Architecture:** Preserve Todoist date-only values as snapshot display context while keeping exact `dueDatetime` validation strict. Introduce a partial date/time draft state in the Android UI layer. `CommitmentEditor` renders the draft as two equal buttons that both open the existing unified wheel date-time picker, while submit request builders still require a complete `DateTimeSelection`.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Compose Multiplatform UI components, Android JVM tests.

---

## Task 1: Date/Time Draft State

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/datetime/DateTimeSelection.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/datetime/DateTimeSelectionTest.kt`

- [ ] Add failing tests that a draft with only `LocalDate.of(2026, 7, 13)` has `dateLabel == "2026-07-13"`, `timeLabel == "No time"`, and `toSelection() == null`.
- [ ] Implement `DateTimeDraftSelection` with `fromSelection`, `toSelection`, `dateLabel`, `timeLabel`, and `receiptLabel`.
- [ ] Run `./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.datetime.DateTimeSelectionTest --console=plain` from `client` and confirm pass.

## Task 2: Morning Review Date-Only Initialization And Immediate Validation

**Files:**
- Modify: `backend/src/services/taskSnapshotService.ts`
- Modify: `backend/test/services/taskSnapshotService.test.ts`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/model/TaskModels.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/MorningReviewScreenStateTest.kt`

- [ ] Add failing backend tests that a date-only Todoist raw `due.date` appears in `/tasks/snapshot` as `dueDate = "2026-07-13"` while `dueDatetime` remains null and validation still reports missing exact time.
- [ ] Add `dueDate: String?` to shared `TaskSnapshotItem`.
- [ ] Add failing Android tests that `dueDate = "2026-07-13"` initializes a repair draft with the date preserved and no time.
- [ ] Add failing tests that opening repair for a task missing an exact start immediately returns a start validation error.
- [ ] Implement date-only parsing and initial repair validation helpers.
- [ ] Run `./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.MorningReviewScreenStateTest --console=plain` from `client` and confirm pass.

## Task 3: Shared Split Picker UI

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/components/CommitmentEditor.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/pickers/WheelDateTimeField.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/components/CommitmentEditorStateTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/pickers/WheelDateTimeFieldTest.kt`

- [ ] Add failing state tests for date and time button labels.
- [ ] Update `WheelDateTimeField` to accept `DateTimeDraftSelection`, render two equal buttons, and emit a complete draft after the wheel confirms.
- [ ] Update `CommitmentEditor` to pass and display the draft.
- [ ] Run focused component and picker tests.

## Task 4: Apply To Add, Repair, And Reschedule Flows

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidatorTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/blockers/BlockerActionStateTest.kt`

- [ ] Replace full selection state with draft state where users edit start date/time.
- [ ] Convert draft to full selection only at validation/request construction boundaries.
- [ ] Preserve existing request behavior for complete selections.
- [ ] Run focused add-task, blocker, and morning-review tests.

## Task 5: Verification And Handoff

**Files:**
- Create: `docs/handoffs/2026-07-13-split-date-time-editor.md`

- [ ] Run `./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain` from `client`.
- [ ] Run `git diff --check`.
- [ ] Document skipped e2e if still intentionally skipped per manual testing request.
- [ ] Commit and push the focused UI fix.
