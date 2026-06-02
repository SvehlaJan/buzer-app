# Deadline Reschedule Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Implement the deadline blocker `Reschedule` action so it requires a new exact deadline and duration, calls the backend reschedule endpoint, hides on completion or backend failure, and records failures.

**Architecture:** Keep the MVP reschedule UI inside `DeadlineBlockerScreen` so the full-screen blocker remains the active surface. Add a small Android parser that converts user-entered local deadline text (`YYYY-MM-DD HH:MM`) and duration text into the shared `RescheduleTaskRequest`. Wire `DeadlineBlockerActivity` to call `EnforcementCoordinator.rescheduleTask`, relying on existing coordinator/error-reporter behavior for backend failures.

**Tech Stack:** Kotlin Multiplatform shared models, Android Kotlin, Compose Material3, AndroidX unit tests, AndroidX instrumentation tests, host fake backend through `10.0.2.2`.

---

## Context

Product requirement from `docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md`:

- Deadline blocker includes `Done`, `Extend 30 min`, and `Reschedule`.
- `Reschedule` requires a new exact deadline and new duration.
- The backend updates Todoist and recomputes `start_at`.

Current code state:

- Backend route already exists: `POST /tasks/:id/reschedule`.
- Shared request model already exists: `RescheduleTaskRequest(dueDatetime: String, durationMinutes: Int)`.
- Android coordinator already has `rescheduleTask(taskId, request)`.
- `DeadlineBlockerActivity` currently logs `"Reschedule form is not implemented in this slice"` and finishes.
- Native fake-backend instrumentation workflow already exists and can assert `/tasks/:id/reschedule` calls.

## File Map

- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/reschedule/RescheduleInputParser.kt`
  - Parse and validate deadline/duration text into `RescheduleTaskRequest`.
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/reschedule/RescheduleInputParserTest.kt`
  - JVM tests for valid parsing, blank fields, invalid deadline format, and non-positive duration.
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt`
  - Replace placeholder reschedule button callback with an inline reschedule form.
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt`
  - Wire parsed reschedule requests to `coordinator.rescheduleTask`.
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/FakeBackendClient.kt`
  - Add helper for asserting recorded request bodies if needed.
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
  - Add reschedule success, validation, and backend-failure instrumentation coverage.
- Update: `docs/notes/2026-06-01-native-android-emulator-testing.md`
  - Add the reschedule test command/result notes.
- Create/update: `docs/handoffs/2026-06-02-deadline-reschedule-flow.md`
  - Record exact verification results.

## UX Contract

First implementation UI:

- User taps `Reschedule`.
- The deadline blocker stays full-screen and shows:
  - `Reschedule task`
  - `New deadline`
  - helper text `Format: YYYY-MM-DD HH:MM`
  - `Duration minutes`
  - `Save reschedule`
  - `Cancel`
- New deadline is interpreted in the Android device timezone and sent to backend as ISO instant string.
- Duration must be a positive integer.
- Invalid inputs show an inline error and keep the blocker visible.
- Valid submit calls the backend and hides when the action returns, even if the backend fails; backend failure is logged by `BlockerErrorReporter`.

Deferred UX:

- Native date/time pickers.
- Pre-filling current task deadline/duration from the snapshot.
- More human-friendly relative-time shortcuts.

---

### Task 1: Add Reschedule Input Parser

**Files:**
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/reschedule/RescheduleInputParserTest.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/reschedule/RescheduleInputParser.kt`

- [x] **Step 1: Write failing parser tests**

Create `client/androidApp/src/test/kotlin/app/buzer/android/ui/reschedule/RescheduleInputParserTest.kt`:

```kotlin
package app.buzer.android.ui.reschedule

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RescheduleInputParserTest {
    private val zone = ZoneId.of("Europe/Warsaw")

    @Test
    fun parsesLocalDeadlineAndPositiveDuration() {
        val result = RescheduleInputParser.parse(
            deadlineText = "2026-06-02 18:30",
            durationText = "45",
            zoneId = zone
        )

        assertTrue(result is RescheduleParseResult.Valid)
        assertEquals("2026-06-02T16:30:00Z", result.request.dueDatetime)
        assertEquals(45, result.request.durationMinutes)
    }

    @Test
    fun rejectsBlankDeadline() {
        val result = RescheduleInputParser.parse(
            deadlineText = " ",
            durationText = "45",
            zoneId = zone
        )

        assertEquals(
            RescheduleParseResult.Invalid("New deadline is required"),
            result
        )
    }

    @Test
    fun rejectsInvalidDeadlineFormat() {
        val result = RescheduleInputParser.parse(
            deadlineText = "tomorrow evening",
            durationText = "45",
            zoneId = zone
        )

        assertEquals(
            RescheduleParseResult.Invalid("New deadline must use YYYY-MM-DD HH:MM"),
            result
        )
    }

    @Test
    fun rejectsNonPositiveDuration() {
        val result = RescheduleInputParser.parse(
            deadlineText = "2026-06-02 18:30",
            durationText = "0",
            zoneId = zone
        )

        assertEquals(
            RescheduleParseResult.Invalid("Duration must be a positive whole number of minutes"),
            result
        )
    }
}
```

- [x] **Step 2: Run parser test to verify RED**

Run:

```bash
cd client
./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.reschedule.RescheduleInputParserTest'
```

Expected: `FAILED` because `RescheduleInputParser` and `RescheduleParseResult` do not exist.

- [x] **Step 3: Implement parser**

Create `client/androidApp/src/main/kotlin/app/buzer/android/ui/reschedule/RescheduleInputParser.kt`:

```kotlin
package app.buzer.android.ui.reschedule

import app.buzer.shared.model.RescheduleTaskRequest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

sealed interface RescheduleParseResult {
    data class Valid(val request: RescheduleTaskRequest) : RescheduleParseResult
    data class Invalid(val message: String) : RescheduleParseResult
}

object RescheduleInputParser {
    private val formatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
            .withResolverStyle(ResolverStyle.STRICT)

    fun parse(
        deadlineText: String,
        durationText: String,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): RescheduleParseResult {
        val trimmedDeadline = deadlineText.trim()
        if (trimmedDeadline.isEmpty()) {
            return RescheduleParseResult.Invalid("New deadline is required")
        }

        val deadline = try {
            LocalDateTime.parse(trimmedDeadline, formatter)
        } catch (_: DateTimeParseException) {
            return RescheduleParseResult.Invalid("New deadline must use YYYY-MM-DD HH:MM")
        }

        val duration = durationText.trim().toIntOrNull()
        if (duration == null || duration <= 0) {
            return RescheduleParseResult.Invalid("Duration must be a positive whole number of minutes")
        }

        return RescheduleParseResult.Valid(
            RescheduleTaskRequest(
                dueDatetime = deadline.atZone(zoneId).toInstant().toString(),
                durationMinutes = duration
            )
        )
    }
}
```

- [x] **Step 4: Run parser test to verify GREEN**

Run:

```bash
cd client
./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.reschedule.RescheduleInputParserTest'
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 5: Commit parser slice**

Run:

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/reschedule/RescheduleInputParser.kt \
  client/androidApp/src/test/kotlin/app/buzer/android/ui/reschedule/RescheduleInputParserTest.kt
git commit -m "feat: parse deadline reschedule input"
```

Expected: commit succeeds.

---

### Task 2: Add Inline Reschedule Form To Deadline Blocker UI

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt`

- [x] **Step 1: Write failing native validation test**

In `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`, add:

```kotlin
@Test
fun deadlineBlocker_rescheduleShowsValidationErrorForInvalidInput() {
    resetTestState()

    launchDeadlineBlocker("task-reschedule-invalid")

    composeRule.onNodeWithText("Reschedule").performClick()
    composeRule.onNodeWithText("Reschedule task").assertIsDisplayed()
    composeRule.onNodeWithText("Save reschedule").performClick()

    composeRule.onNodeWithText("New deadline is required").assertIsDisplayed()
    composeRule.onNodeWithText("Done").assertDoesNotExist()
}
```

Also add import:

```kotlin
import androidx.compose.ui.test.assertDoesNotExist
```

- [x] **Step 2: Run instrumentation compile/test to verify RED**

Start fake backend in a separate terminal:

```bash
cd tools/fake-backend
PORT=18433 npm run dev
```

Run:

```bash
cd client
./gradlew pixel8Api34DebugAndroidTest \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
```

Expected: test fails because tapping `Reschedule` still logs placeholder behavior and finishes instead of showing a form.

- [x] **Step 3: Replace `DeadlineBlockerScreen` implementation**

In `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt`, update imports:

```kotlin
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import app.buzer.android.ui.reschedule.RescheduleInputParser
import app.buzer.android.ui.reschedule.RescheduleParseResult
import app.buzer.shared.model.RescheduleTaskRequest
```

Replace `DeadlineBlockerScreen` with:

```kotlin
@Composable
fun DeadlineBlockerScreen(
    onBack: () -> Unit,
    onDone: () -> Unit = {},
    onExtend: () -> Unit = {},
    onReschedule: (RescheduleTaskRequest) -> Unit = {},
    showBack: Boolean = true
) {
    var rescheduleVisible by remember { mutableStateOf(false) }
    var deadlineText by remember { mutableStateOf("") }
    var durationText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (rescheduleVisible) {
            Text("Reschedule task")
            OutlinedTextField(
                value = deadlineText,
                onValueChange = {
                    deadlineText = it
                    errorText = null
                },
                label = { Text("New deadline") },
                supportingText = { Text("Format: YYYY-MM-DD HH:MM") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "New deadline input" }
            )
            OutlinedTextField(
                value = durationText,
                onValueChange = {
                    durationText = it
                    errorText = null
                },
                label = { Text("Duration minutes") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Duration minutes input" }
            )
            errorText?.let { Text(it) }
            Button(
                onClick = {
                    when (val result = RescheduleInputParser.parse(deadlineText, durationText)) {
                        is RescheduleParseResult.Valid -> onReschedule(result.request)
                        is RescheduleParseResult.Invalid -> errorText = result.message
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save reschedule")
            }
            OutlinedButton(
                onClick = {
                    rescheduleVisible = false
                    errorText = null
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel")
            }
            return@Column
        }

        Text("Deadline blocker")
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("Done")
        }
        OutlinedButton(onClick = onExtend, modifier = Modifier.fillMaxWidth()) {
            Text("Extend 30 min")
        }
        OutlinedButton(
            onClick = {
                rescheduleVisible = true
                errorText = null
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reschedule")
        }
        if (showBack) {
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
        }
    }
}
```

- [x] **Step 4: Update activity placeholder callback signature**

In `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt`, replace:

```kotlin
onReschedule = { _ ->
    if (actionInFlight) return@DeadlineBlockerScreen
    actionInFlight = true
    errorReporter.report(
        action = BlockerAction.RESCHEDULE_TASK,
        taskId = taskId,
        message = "Reschedule backend action is not wired in this slice"
    )
    finish()
},
```

with:

```kotlin
onReschedule = { _ ->
    if (actionInFlight) return@DeadlineBlockerScreen
    actionInFlight = true
    errorReporter.report(
        action = BlockerAction.RESCHEDULE_TASK,
        taskId = taskId,
        message = "Reschedule backend action is not wired in this slice"
    )
    finish()
},
```

This keeps Task 2 buildable after `DeadlineBlockerScreen` changes `onReschedule` from `() -> Unit` to `(RescheduleTaskRequest) -> Unit`. The `_` parameter is intentionally unused in this temporary placeholder.

- [x] **Step 5: Run Android test compile**

Run:

```bash
cd client
./gradlew :androidApp:assembleDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 6: Run validation instrumentation test**

Run the managed-device command from Step 2.

Expected: validation test passes; existing happy/failure tests still pass.

---

### Task 3: Wire DeadlineBlockerActivity To Coordinator Reschedule

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`

- [x] **Step 1: Update `DeadlineBlockerActivity` reschedule callback**

In `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt`, replace the current placeholder `onReschedule` block:

```kotlin
onReschedule = { _ ->
    if (actionInFlight) return@DeadlineBlockerScreen
    actionInFlight = true
    errorReporter.report(
        action = BlockerAction.RESCHEDULE_TASK,
        taskId = taskId,
        message = "Reschedule form is not implemented in this slice"
    )
    finish()
},
```

with:

```kotlin
onReschedule = { request ->
    runActionOnce { coordinator.rescheduleTask(taskId, request) }
},
```

- [x] **Step 2: Update preview/navigation caller if needed**

Check `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`.

Expected: `DeadlineBlockerScreen(onBack = { section = AppSection.Home })` still compiles because `onReschedule` has a default lambda.

If compilation fails due to named argument ambiguity, update the call to:

```kotlin
AppSection.Deadline -> DeadlineBlockerScreen(
    onBack = { section = AppSection.Home }
)
```

- [x] **Step 3: Run unit tests**

Run:

```bash
cd client
./gradlew :androidApp:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 4: Commit UI/activity slice**

Run:

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt \
  client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt \
  client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt \
  client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt
git commit -m "feat: show deadline reschedule form"
```

Expected: commit succeeds.

---

### Task 4: Add Native Reschedule Success And Failure Coverage

**Files:**
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/FakeBackendClient.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`

- [x] **Step 1: Add fake backend body assertion helper**

In `FakeBackendClient.kt`, add:

```kotlin
fun assertRecorded(path: String, vararg bodyFragments: String) {
    val body = calls()
    check(body.contains("\"path\":\"$path\"")) {
        "Expected fake backend call $path, got $body"
    }
    bodyFragments.forEach { fragment ->
        check(body.contains(fragment)) {
            "Expected fake backend call $path body to contain $fragment, got $body"
        }
    }
}
```

Keep the existing `assertRecorded(path: String)` overload.

- [x] **Step 2: Add success instrumentation test**

In `BlockerActivityFakeBackendTest.kt`, add import:

```kotlin
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithContentDescription
```

Add test:

```kotlin
@Test
fun deadlineBlocker_rescheduleCallsFakeBackend() {
    resetTestState()

    val scenario = launchDeadlineBlocker("task-reschedule")

    composeRule.onNodeWithText("Reschedule").performClick()
    composeRule.onNodeWithContentDescription("New deadline input").performTextInput("2026-06-02 18:30")
    composeRule.onNodeWithContentDescription("Duration minutes input").performTextInput("45")
    composeRule.onNodeWithText("Save reschedule").performClick()

    waitForActivityDestroyed(scenario)
    waitForRecordedCall("/tasks/task-reschedule/reschedule")
    fakeBackend.assertRecorded(
        "/tasks/task-reschedule/reschedule",
        "durationMinutes",
        "45",
        "dueDatetime"
    )
}
```

- [x] **Step 3: Add backend failure instrumentation test**

In `BlockerActivityFakeBackendTest.kt`, add:

```kotlin
@Test
fun deadlineBlocker_rescheduleBackendFailureHidesAndWritesErrorLog() {
    resetTestState()
    fakeBackend.setFailActions(true)
    try {
        val scenario = launchDeadlineBlocker("task-reschedule-fail")

        composeRule.onNodeWithText("Reschedule").performClick()
        composeRule.onNodeWithContentDescription("New deadline input").performTextInput("2026-06-02 18:30")
        composeRule.onNodeWithContentDescription("Duration minutes input").performTextInput("45")
        composeRule.onNodeWithText("Save reschedule").performClick()

        waitForActivityDestroyed(scenario)
        waitForRecordedCall("/tasks/task-reschedule-fail/reschedule")
        waitForErrorLog("action=RESCHEDULE_TASK taskId=task-reschedule-fail", "500")
    } finally {
        fakeBackend.setFailActions(false)
    }
}
```

- [x] **Step 4: Run instrumentation suite**

Start fake backend:

```bash
cd tools/fake-backend
PORT=18433 npm run dev
```

Run:

```bash
cd client
./gradlew pixel8Api34DebugAndroidTest \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
```

Expected: all blocker instrumentation tests pass.

- [x] **Step 5: Stop fake backend**

Find and stop any listener:

```bash
pid=$(lsof -tiTCP:18433 -sTCP:LISTEN)
if [ -n "$pid" ]; then kill $pid; fi
```

Expected: no process remains listening on `18433`.

- [x] **Step 6: Commit native test slice**

Run:

```bash
git add client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/FakeBackendClient.kt \
  client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt
git commit -m "test: cover deadline reschedule blocker flow"
```

Expected: commit succeeds.

---

### Task 5: Final Verification And Handoff

**Files:**
- Update: `docs/notes/2026-06-01-native-android-emulator-testing.md`
- Create: `docs/handoffs/2026-06-02-deadline-reschedule-flow.md`
- Update: this plan file

- [x] **Step 1: Run full relevant verification**

Run:

```bash
cd client
./gradlew :shared:testDebugUnitTest
./gradlew :androidApp:testDebugUnitTest
./gradlew :androidApp:assembleDebugAndroidTest
```

Expected: all commands report `BUILD SUCCESSFUL`.

Start fake backend and run:

```bash
cd client
./gradlew pixel8Api34DebugAndroidTest \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
```

Expected: all managed-device instrumentation tests pass.

- [x] **Step 2: Update workflow note**

Append this to `docs/notes/2026-06-01-native-android-emulator-testing.md`:

```markdown
## Deadline Reschedule Coverage

The deadline blocker instrumentation suite covers:

- `Done` success and backend failure
- `Extend 30 min` visibility and existing action behavior
- `Reschedule` invalid input staying on the blocker
- `Reschedule` success posting `/tasks/:id/reschedule`
- `Reschedule` backend failure hiding the blocker and writing `RESCHEDULE_TASK` to `blocker_action_errors.log`

The first reschedule UI accepts local deadline text in `YYYY-MM-DD HH:MM` format and converts it to an ISO instant using the Android device timezone.
```

- [x] **Step 3: Write handoff**

Create `docs/handoffs/2026-06-02-deadline-reschedule-flow.md`:

```markdown
# Handoff: Deadline Reschedule Flow

Date: 2026-06-02

## Task / Plan

Plan: `docs/superpowers/plans/2026-06-02-deadline-reschedule-flow.md`

Status: Complete when all checkboxes in `docs/superpowers/plans/2026-06-02-deadline-reschedule-flow.md` are marked `[x]`; otherwise list the unchecked tasks here.

## Files Changed

- `client/androidApp/src/main/kotlin/app/buzer/android/ui/reschedule/RescheduleInputParser.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/reschedule/RescheduleInputParserTest.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/FakeBackendClient.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- `docs/notes/2026-06-01-native-android-emulator-testing.md`
- `docs/handoffs/2026-06-02-deadline-reschedule-flow.md`

## Verification

- Command: `cd client && ./gradlew :shared:testDebugUnitTest`
- Result: PASS/FAIL/SKIPPED
- Evidence: `BUILD SUCCESSFUL` or the failure summary.

- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest`
- Result: PASS/FAIL/SKIPPED
- Evidence: `BUILD SUCCESSFUL` or the failure summary.

- Command: `cd client && ./gradlew :androidApp:assembleDebugAndroidTest`
- Result: PASS/FAIL/SKIPPED
- Evidence: `BUILD SUCCESSFUL` or the failure summary.

- Command: `cd client && ./gradlew pixel8Api34DebugAndroidTest -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433`
- Result: PASS/FAIL/SKIPPED
- Evidence: managed-device test count and Gradle status.

## Known Failures Or Skipped Checks

- Record `None` if every command above passed.
- If the emulator was unavailable, record the exact SDK/device error and whether a connected device fallback was attempted.

## Next Recommended Step

Implement native date/time picker UX or move to add-task/morning-review workflow, depending on product priority.
```

- [x] **Step 4: Commit docs**

Run:

```bash
git add docs/notes/2026-06-01-native-android-emulator-testing.md \
  docs/handoffs/2026-06-02-deadline-reschedule-flow.md \
  docs/superpowers/plans/2026-06-02-deadline-reschedule-flow.md
git commit -m "docs: record deadline reschedule flow handoff"
```

Expected: commit succeeds.

## Plan Self-Review

- Spec coverage: covers the product requirement that reschedule requires a new exact deadline and duration, posts to backend, and hides/logs failures through existing coordinator behavior.
- Deferred scope: native date/time pickers and current task prefill are explicitly deferred.
- Test coverage: parser JVM tests, invalid-input instrumentation test, success instrumentation test, backend-failure instrumentation test, plus existing unit/instrumentation suite.
- Risk: `YYYY-MM-DD HH:MM` is less ergonomic than a picker but is deterministic, dependency-free, and matches the current `AddTaskScreen` text-field style.
