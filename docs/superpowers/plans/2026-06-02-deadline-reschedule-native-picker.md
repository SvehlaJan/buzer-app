# Deadline Reschedule Native Picker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace deadline reschedule text date entry with native Material3 date/time picker UX.

**Architecture:** Reuse the existing Android `DateTimeSelection` helper from add-task creation. Keep reschedule UI inside `DeadlineBlockerScreen`, update native fake-backend tests to select default picker values, and leave backend/coordinator behavior unchanged.

**Tech Stack:** Kotlin Android, Compose Material3 `DatePickerDialog`, Compose Material3 `TimePicker` in `AlertDialog`, AndroidX Compose UI tests, fake backend through `10.0.2.2`.

---

## File Map

- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt`
  - Replace `deadlineText` text field with picker-backed `DateTimeSelection`.
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
  - Replace deadline text input with native picker selection.
- Update: `docs/notes/2026-06-01-native-android-emulator-testing.md`
  - Record reschedule native picker coverage.
- Create: `docs/handoffs/2026-06-02-deadline-reschedule-native-picker.md`
  - Record exact verification results.

## Task 1: Update Tests For Picker UX

- [x] Replace invalid-input expected message in `BlockerActivityFakeBackendTest.kt` from `New deadline is required` to `Deadline is required`.
- [x] Add helper:

```kotlin
private fun selectDefaultDeadline() {
    composeRule.onNodeWithContentDescription("New deadline picker").performClick()
    composeRule.onNodeWithText("OK").performClick()
    composeRule.onNodeWithText("OK").performClick()
}
```

- [x] Replace reschedule success/failure test date text input:

```kotlin
composeRule.onNodeWithContentDescription("New deadline input").performTextInput("2026-06-02 18:30")
```

with:

```kotlin
selectDefaultDeadline()
```

- [x] Run:

```bash
cd client
./gradlew :androidApp:assembleDebugAndroidTest
```

Expected: build may fail or managed-device tests will fail until UI is migrated.

## Task 2: Migrate Reschedule Form

- [x] In `BlockerScreens.kt`, remove imports and state for `RescheduleInputParser` / `RescheduleParseResult` / deadline text.
- [x] Add imports:

```kotlin
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.android.ui.datetime.datePickerMillisToLocalDate
import app.buzer.android.ui.datetime.localDateToDatePickerMillis
import java.time.LocalDate
import java.time.LocalDateTime
```

- [x] Add `@OptIn(ExperimentalMaterial3Api::class)` to `DeadlineBlockerScreen`.
- [x] Replace deadline state with:

```kotlin
var deadline by remember { mutableStateOf<DateTimeSelection?>(null) }
var showDatePicker by remember { mutableStateOf(false) }
var showTimePicker by remember { mutableStateOf(false) }
var pendingDate by remember { mutableStateOf<LocalDate?>(null) }
val defaultDateTime = remember { LocalDateTime.now().plusHours(1) }
```

- [x] Make the screen column vertically scrollable:

```kotlin
.verticalScroll(rememberScrollState())
```

- [x] Replace the deadline text field with:

```kotlin
OutlinedButton(
    onClick = {
        showDatePicker = true
        errorText = null
    },
    modifier = Modifier
        .fillMaxWidth()
        .semantics { contentDescription = "New deadline picker" }
) {
    Text(deadline?.formatForDisplay() ?: "No deadline selected")
}
```

- [x] Save validation:

```kotlin
val selectedDeadline = deadline
if (selectedDeadline == null) {
    errorText = "Deadline is required"
    return@Button
}
val duration = durationText.trim().toIntOrNull()
if (duration == null || duration <= 0) {
    errorText = "Duration must be a positive whole number of minutes"
    return@Button
}
onReschedule(
    RescheduleTaskRequest(
        dueDatetime = selectedDeadline.toIsoInstantString(),
        durationMinutes = duration
    )
)
```

- [x] Add date picker and time picker dialogs after the column using the same pattern as `AddTaskScreen`.

## Task 3: Verify And Commit Code

- [x] Run:

```bash
cd client
./gradlew :androidApp:testDebugUnitTest
./gradlew :androidApp:assembleDebugAndroidTest
```

- [x] Start fake backend:

```bash
cd tools/fake-backend
PORT=18433 npm run dev
```

- [x] Run:

```bash
cd client
./gradlew pixel8Api34DebugAndroidTest --rerun-tasks \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PBUZER_ASSIGNEE_ME_ID=user-me \
  -PBUZER_ASSIGNEE_WIFE_ID=user-wife \
  -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
```

- [x] Commit:

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt \
  client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt
git commit -m "feat: use native picker for deadline reschedule"
```

## Task 4: Final Handoff

- [x] Mark all plan checkboxes complete.
- [x] Update `docs/notes/2026-06-01-native-android-emulator-testing.md`.
- [x] Create `docs/handoffs/2026-06-02-deadline-reschedule-native-picker.md`.
- [x] Run final `git status --short`.
- [x] Commit docs:

```bash
git add docs/notes/2026-06-01-native-android-emulator-testing.md \
  docs/handoffs/2026-06-02-deadline-reschedule-native-picker.md \
  docs/superpowers/specs/2026-06-02-deadline-reschedule-native-picker-design.md \
  docs/superpowers/plans/2026-06-02-deadline-reschedule-native-picker.md
git commit -m "docs: record deadline reschedule native picker handoff"
```
