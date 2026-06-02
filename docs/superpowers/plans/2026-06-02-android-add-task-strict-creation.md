# Android Add Task Strict Creation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Build the Android add-task flow so companion-created tasks require title, fixed assignee, native-picked deadline, and duration, then create the task through `POST /tasks`.

**Architecture:** Keep add-task as a focused Android UI slice. Add small pure Android helpers for date/time selection and form validation, inject backend create behavior into `AddTaskScreen`, and use the existing fake-backend emulator workflow for end-to-end verification.

**Tech Stack:** Kotlin Android, Compose Material3, Material3 DatePicker/TimePicker, Ktor `BuzerApiClient`, Node/TypeScript fake backend, Gradle Managed Devices.

---

## Context

Product truth:

- `docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md`
- `docs/superpowers/specs/2026-06-02-android-add-task-strict-creation-design.md`

Key decisions:

- Assignee uses fixed `Me` / `Wife` buttons.
- Android build config provides `BUZER_ASSIGNEE_ME_ID` and `BUZER_ASSIGNEE_WIFE_ID`.
- Project selection is deferred; send `projectId = null`.
- Deadline uses native Material3 date/time pickers.
- Backend failure keeps the form visible and preserves entered input.

## File Map

- Modify: `client/androidApp/build.gradle.kts`
  - Add assignee build-config fields.
- Modify: `tools/fake-backend/src/server.ts`
  - Add `POST /tasks` route that records body and can fail through existing `failActions`.
- Modify: `tools/fake-backend/src/server.test.ts`
  - Add fake create-route tests.
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/datetime/DateTimeSelection.kt`
  - Store selected local deadline and convert/format it.
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/datetime/DateTimeSelectionTest.kt`
  - JVM tests for conversion and formatting.
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt`
  - Validate form fields and build `CreateTaskRequest`.
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidatorTest.kt`
  - JVM tests for validation and request building.
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/tasks/AddTaskDependencies.kt`
  - Build assignee options and backend create callback from `BuildConfig`.
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
  - Replace placeholder form with strict form, native picker field, save behavior.
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
  - Keep existing `AddTaskScreen` call buildable with default dependencies.
- Create: `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`
  - Native tests for create success and backend failure.
- Update: `docs/notes/2026-06-01-native-android-emulator-testing.md`
  - Add add-task fake-backend coverage note.
- Create: `docs/handoffs/2026-06-02-android-add-task-strict-creation.md`
  - Record exact commands and results.

---

### Task 1: Add Config And Fake Backend Create Route

**Files:**
- Modify: `client/androidApp/build.gradle.kts`
- Modify: `tools/fake-backend/src/server.test.ts`
- Modify: `tools/fake-backend/src/server.ts`

- [x] **Step 1: Add failing fake-backend create test**

In `tools/fake-backend/src/server.test.ts`, add:

```ts
test("create task is recorded", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());

  const response = await app.inject({
    method: "POST",
    url: "/tasks",
    headers: { authorization: "Bearer test-token" },
    payload: {
      title: "Buy milk",
      projectId: null,
      assigneeId: "user-me",
      dueDatetime: "2026-06-02T16:30:00Z",
      durationMinutes: 45
    }
  });

  assert.equal(response.statusCode, 201);
  assert.deepEqual(response.json(), {
    id: "created-task",
    todoistTaskId: "todoist-created-task"
  });

  const calls = await app.inject({ method: "GET", url: "/__calls" });
  assert.equal(calls.json().calls[0].path, "/tasks");
  assert.equal(calls.json().calls[0].body.title, "Buy milk");
});
```

Add failure coverage:

```ts
test("forced action failures also fail task creation and still record call", async (t) => {
  const app = await buildServer({ failActions: true });
  t.after(() => app.close());

  const response = await app.inject({
    method: "POST",
    url: "/tasks",
    headers: { authorization: "Bearer test-token" },
    payload: {
      title: "Buy milk",
      projectId: null,
      assigneeId: "user-me",
      dueDatetime: "2026-06-02T16:30:00Z",
      durationMinutes: 45
    }
  });

  assert.equal(response.statusCode, 500);

  const calls = await app.inject({ method: "GET", url: "/__calls" });
  assert.equal(calls.json().calls.length, 1);
  assert.equal(calls.json().calls[0].path, "/tasks");
});
```

- [x] **Step 2: Run fake-backend tests to verify RED**

Run:

```bash
cd tools/fake-backend
npm test
```

Expected: `FAILED` because `POST /tasks` is not implemented.

- [x] **Step 3: Implement fake-backend create route**

In `tools/fake-backend/src/server.ts`, add before the action loop:

```ts
  app.post("/tasks", async (request, reply) => {
    await maybeDelay();
    record("POST", request.url, request.body ?? null);
    if (failActions) {
      return reply.code(500).send({ error: "forced_failure" });
    }
    return reply.code(201).send({
      id: "created-task",
      todoistTaskId: "todoist-created-task"
    });
  });
```

- [x] **Step 4: Add Android assignee build config**

In `client/androidApp/build.gradle.kts`, add these fields near the existing backend fields:

```kotlin
        buildConfigField("String", "BUZER_ASSIGNEE_ME_ID", "\"${backendConfigValue("BUZER_ASSIGNEE_ME_ID")}\"")
        buildConfigField("String", "BUZER_ASSIGNEE_WIFE_ID", "\"${backendConfigValue("BUZER_ASSIGNEE_WIFE_ID")}\"")
```

- [x] **Step 5: Run verification**

Run:

```bash
cd tools/fake-backend
npm test
cd ../../client
./gradlew :androidApp:assembleDebug
```

Expected: both commands report success.

- [x] **Step 6: Commit**

Run:

```bash
git add client/androidApp/build.gradle.kts tools/fake-backend/src/server.ts tools/fake-backend/src/server.test.ts
git commit -m "test: add fake backend task creation route"
```

Expected: commit succeeds.

---

### Task 2: Add Date-Time Selection Helper

**Files:**
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/datetime/DateTimeSelectionTest.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/datetime/DateTimeSelection.kt`

- [x] **Step 1: Write failing date-time helper tests**

Create `client/androidApp/src/test/kotlin/app/buzer/android/ui/datetime/DateTimeSelectionTest.kt`:

```kotlin
package app.buzer.android.ui.datetime

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class DateTimeSelectionTest {
    @Test
    fun convertsLocalSelectionToIsoInstant() {
        val selection = DateTimeSelection(
            date = LocalDate.of(2026, 6, 2),
            hour = 18,
            minute = 30
        )

        assertEquals(
            "2026-06-02T16:30:00Z",
            selection.toIsoInstantString(ZoneId.of("Europe/Warsaw"))
        )
    }

    @Test
    fun formatsLocalSelectionForDisplay() {
        val selection = DateTimeSelection(
            date = LocalDate.of(2026, 6, 2),
            hour = 8,
            minute = 5
        )

        assertEquals("2026-06-02 08:05", selection.formatForDisplay())
    }

    @Test
    fun convertsDatePickerUtcMillisToLocalDateWithoutDeviceTimezoneShift() {
        val millis = LocalDate.of(2026, 6, 2)
            .atStartOfDay(ZoneId.of("UTC"))
            .toInstant()
            .toEpochMilli()

        assertEquals(
            LocalDate.of(2026, 6, 2),
            datePickerMillisToLocalDate(millis)
        )
    }
}
```

- [x] **Step 2: Run tests to verify RED**

Run:

```bash
cd client
./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.datetime.DateTimeSelectionTest'
```

Expected: `FAILED` because the helper does not exist.

- [x] **Step 3: Implement helper**

Create `client/androidApp/src/main/kotlin/app/buzer/android/ui/datetime/DateTimeSelection.kt`:

```kotlin
package app.buzer.android.ui.datetime

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class DateTimeSelection(
    val date: LocalDate,
    val hour: Int,
    val minute: Int
) {
    fun toIsoInstantString(zoneId: ZoneId = ZoneId.systemDefault()): String =
        date.atTime(hour, minute)
            .atZone(zoneId)
            .toInstant()
            .toString()

    fun formatForDisplay(): String =
        date.atTime(hour, minute).format(displayFormatter)
}

fun datePickerMillisToLocalDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()

fun localDateToDatePickerMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()

private val displayFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
```

- [x] **Step 4: Run tests to verify GREEN**

Run:

```bash
cd client
./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.datetime.DateTimeSelectionTest'
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 5: Commit**

Run:

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/datetime/DateTimeSelection.kt \
  client/androidApp/src/test/kotlin/app/buzer/android/ui/datetime/DateTimeSelectionTest.kt
git commit -m "feat: add Android date time selection helper"
```

Expected: commit succeeds.

---

### Task 3: Add Add-Task Form Validation

**Files:**
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidatorTest.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt`

- [x] **Step 1: Write failing validator tests**

Create `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidatorTest.kt`:

```kotlin
package app.buzer.android.ui.addtask

import app.buzer.android.ui.datetime.DateTimeSelection
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AddTaskFormValidatorTest {
    private val me = AddTaskAssigneeOption(label = "Me", id = "user-me")
    private val deadline = DateTimeSelection(LocalDate.of(2026, 6, 2), 18, 30)
    private val zone = ZoneId.of("Europe/Warsaw")

    @Test
    fun buildsStrictCreateTaskRequest() {
        val result = AddTaskFormValidator.validate(
            title = " Buy milk ",
            selectedAssignee = me,
            deadline = deadline,
            durationText = "45",
            zoneId = zone
        )

        assertTrue(result is AddTaskFormResult.Valid)
        assertEquals("Buy milk", result.request.title)
        assertEquals(null, result.request.projectId)
        assertEquals("user-me", result.request.assigneeId)
        assertEquals("2026-06-02T16:30:00Z", result.request.dueDatetime)
        assertEquals(45, result.request.durationMinutes)
    }

    @Test
    fun rejectsBlankTitle() {
        val result = AddTaskFormValidator.validate("", me, deadline, "45", zone)

        assertEquals(AddTaskFormResult.Invalid("Title is required"), result)
    }

    @Test
    fun rejectsMissingAssignee() {
        val result = AddTaskFormValidator.validate("Buy milk", null, deadline, "45", zone)

        assertEquals(AddTaskFormResult.Invalid("Assignee is required"), result)
    }

    @Test
    fun rejectsBlankAssigneeId() {
        val result = AddTaskFormValidator.validate(
            title = "Buy milk",
            selectedAssignee = AddTaskAssigneeOption("Me", ""),
            deadline = deadline,
            durationText = "45",
            zoneId = zone
        )

        assertEquals(AddTaskFormResult.Invalid("Me assignee ID is not configured"), result)
    }

    @Test
    fun rejectsMissingDeadline() {
        val result = AddTaskFormValidator.validate("Buy milk", me, null, "45", zone)

        assertEquals(AddTaskFormResult.Invalid("Deadline is required"), result)
    }

    @Test
    fun rejectsInvalidDuration() {
        val result = AddTaskFormValidator.validate("Buy milk", me, deadline, "0", zone)

        assertEquals(
            AddTaskFormResult.Invalid("Duration must be a positive whole number of minutes"),
            result
        )
    }
}
```

- [x] **Step 2: Run tests to verify RED**

Run:

```bash
cd client
./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.addtask.AddTaskFormValidatorTest'
```

Expected: `FAILED` because validator types do not exist.

- [x] **Step 3: Implement validator**

Create `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt`:

```kotlin
package app.buzer.android.ui.addtask

import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.shared.model.CreateTaskRequest
import java.time.ZoneId

data class AddTaskAssigneeOption(
    val label: String,
    val id: String
)

sealed interface AddTaskFormResult {
    data class Valid(val request: CreateTaskRequest) : AddTaskFormResult
    data class Invalid(val message: String) : AddTaskFormResult
}

object AddTaskFormValidator {
    fun validate(
        title: String,
        selectedAssignee: AddTaskAssigneeOption?,
        deadline: DateTimeSelection?,
        durationText: String,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): AddTaskFormResult {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) {
            return AddTaskFormResult.Invalid("Title is required")
        }
        if (selectedAssignee == null) {
            return AddTaskFormResult.Invalid("Assignee is required")
        }
        if (selectedAssignee.id.isBlank()) {
            return AddTaskFormResult.Invalid("${selectedAssignee.label} assignee ID is not configured")
        }
        if (deadline == null) {
            return AddTaskFormResult.Invalid("Deadline is required")
        }

        val duration = durationText.trim().toIntOrNull()
        if (duration == null || duration <= 0) {
            return AddTaskFormResult.Invalid("Duration must be a positive whole number of minutes")
        }

        return AddTaskFormResult.Valid(
            CreateTaskRequest(
                title = trimmedTitle,
                projectId = null,
                assigneeId = selectedAssignee.id,
                dueDatetime = deadline.toIsoInstantString(zoneId),
                durationMinutes = duration
            )
        )
    }
}
```

- [x] **Step 4: Run tests to verify GREEN**

Run:

```bash
cd client
./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.addtask.AddTaskFormValidatorTest'
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 5: Commit**

Run:

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt \
  client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidatorTest.kt
git commit -m "feat: validate strict add task form"
```

Expected: commit succeeds.

---

### Task 4: Wire Add-Task UI With Native Pickers And Backend Save

**Files:**
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/tasks/AddTaskDependencies.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`

- [x] **Step 1: Implement add-task dependencies**

Create `client/androidApp/src/main/kotlin/app/buzer/android/tasks/AddTaskDependencies.kt`:

```kotlin
package app.buzer.android.tasks

import app.buzer.android.BuildConfig
import app.buzer.android.ui.addtask.AddTaskAssigneeOption
import app.buzer.shared.api.BuzerApiClient
import app.buzer.shared.model.CreateTaskRequest

object AddTaskDependencies {
    fun assigneeOptions(): List<AddTaskAssigneeOption> = listOf(
        AddTaskAssigneeOption("Me", BuildConfig.BUZER_ASSIGNEE_ME_ID),
        AddTaskAssigneeOption("Wife", BuildConfig.BUZER_ASSIGNEE_WIFE_ID)
    )

    fun createTaskAction(): suspend (CreateTaskRequest) -> Unit = { request ->
        val baseUrl = BuildConfig.BUZER_BACKEND_BASE_URL.trimEnd('/')
        require(baseUrl.isNotBlank()) { "BUZER_BACKEND_BASE_URL is missing from client/local.properties" }
        require(BuildConfig.BUZER_BACKEND_TOKEN.isNotBlank()) {
            "BUZER_BACKEND_TOKEN is missing from client/local.properties"
        }
        BuzerApiClient(
            baseUrl = baseUrl,
            token = BuildConfig.BUZER_BACKEND_TOKEN
        ).createTask(request)
    }
}
```

- [x] **Step 2: Replace AddTaskScreen**

Replace `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt` with a form that:

- accepts parameters:

```kotlin
@Composable
fun AddTaskScreen(
    onBack: () -> Unit,
    createTask: suspend (CreateTaskRequest) -> Unit = AddTaskDependencies.createTaskAction(),
    assigneeOptions: List<AddTaskAssigneeOption> = AddTaskDependencies.assigneeOptions()
)
```

- stores:

```kotlin
var title by remember { mutableStateOf("") }
var selectedAssignee by remember { mutableStateOf<AddTaskAssigneeOption?>(null) }
var deadline by remember { mutableStateOf<DateTimeSelection?>(null) }
var durationMinutes by remember { mutableStateOf("") }
var errorText by remember { mutableStateOf<String?>(null) }
var submitting by remember { mutableStateOf(false) }
var showDatePicker by remember { mutableStateOf(false) }
var showTimePicker by remember { mutableStateOf(false) }
var pendingDate by remember { mutableStateOf<LocalDate?>(null) }
```

- renders:
  - `Add committed task`
  - title field with content description `Title input`
  - two full-width or equal-width buttons `Me` and `Wife`
  - deadline button with content description `Deadline picker`
  - duration field with content description `Duration minutes input`
  - inline error text
  - save button
  - back button

Use `KeyboardOptions(keyboardType = KeyboardType.Number)` for duration.

Picker behavior:

```kotlin
val now = remember { LocalDateTime.now().plusHours(1) }
val initialDate = deadline?.date ?: LocalDate.now()
val initialHour = deadline?.hour ?: now.hour
val initialMinute = deadline?.minute ?: 0
```

When `showDatePicker` is true:

- create `rememberDatePickerState(initialSelectedDateMillis = localDateToDatePickerMillis(initialDate))`
- confirm stores `pendingDate = datePickerMillisToLocalDate(selectedDateMillis)` and opens `showTimePicker`
- cancel closes date picker

When `showTimePicker` is true:

- create `rememberTimePickerState(initialHour = initialHour, initialMinute = initialMinute, is24Hour = true)`
- confirm sets `deadline = DateTimeSelection(pendingDate ?: initialDate, timeState.hour, timeState.minute)`
- cancel closes time picker

Save behavior:

```kotlin
when (val result = AddTaskFormValidator.validate(title, selectedAssignee, deadline, durationMinutes)) {
    is AddTaskFormResult.Invalid -> errorText = result.message
    is AddTaskFormResult.Valid -> {
        submitting = true
        scope.launch {
            try {
                createTask(result.request)
                onBack()
            } catch (error: Exception) {
                errorText = error.message ?: "Task creation failed"
            } finally {
                submitting = false
            }
        }
    }
}
```

Guard against double-submit by returning early if `submitting` is true.

- [x] **Step 3: Run Android unit tests**

Run:

```bash
cd client
./gradlew :androidApp:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 4: Run androidTest compile**

Run:

```bash
cd client
./gradlew :androidApp:assembleDebugAndroidTest \
  -PBUZER_ASSIGNEE_ME_ID=user-me \
  -PBUZER_ASSIGNEE_WIFE_ID=user-wife
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 5: Commit**

Run:

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/tasks/AddTaskDependencies.kt \
  client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt \
  client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt
git commit -m "feat: wire strict Android add task form"
```

Expected: commit succeeds.

---

### Task 5: Add Native Add-Task Fake Backend Tests

**Files:**
- Create: `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`

- [x] **Step 1: Write native success and failure tests**

Create `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`:

```kotlin
package app.buzer.android.addtask

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.buzer.android.MainActivity
import app.buzer.android.blockers.FakeBackendClient
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddTaskFakeBackendTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val fakeBackend = FakeBackendClient()

    @Test
    fun addTask_createsTaskThroughFakeBackend() {
        resetTestState()

        ActivityScenario.launch(MainActivity::class.java)

        composeRule.onNodeWithText("Add task").performClick()
        composeRule.onNodeWithContentDescription("Title input").performTextInput("Buy milk")
        composeRule.onNodeWithText("Me").performClick()
        selectDefaultDeadline()
        composeRule.onNodeWithContentDescription("Duration minutes input").performTextInput("45")
        composeRule.onNodeWithText("Save").performClick()

        waitForRecordedCall("/tasks")
        fakeBackend.assertRecorded(
            "/tasks",
            "Buy milk",
            "user-me",
            "durationMinutes",
            "45",
            "dueDatetime"
        )
    }

    @Test
    fun addTask_backendFailureKeepsFormAndEnteredTitle() {
        resetTestState()
        fakeBackend.setFailActions(true)
        try {
            ActivityScenario.launch(MainActivity::class.java)

            composeRule.onNodeWithText("Add task").performClick()
            composeRule.onNodeWithContentDescription("Title input").performTextInput("Buy milk")
            composeRule.onNodeWithText("Me").performClick()
            selectDefaultDeadline()
            composeRule.onNodeWithContentDescription("Duration minutes input").performTextInput("45")
            composeRule.onNodeWithText("Save").performClick()

            waitForRecordedCall("/tasks")
            composeRule.onNodeWithText("Add committed task").assertIsDisplayed()
            composeRule.onNodeWithText("Buy milk").assertIsDisplayed()
            composeRule.onNodeWithText("500").assertIsDisplayed()
        } finally {
            fakeBackend.setFailActions(false)
        }
    }

    private fun selectDefaultDeadline() {
        composeRule.onNodeWithContentDescription("Deadline picker").performClick()
        composeRule.onNodeWithText("OK").performClick()
        composeRule.onNodeWithText("OK").performClick()
    }

    private fun resetTestState() {
        fakeBackend.setFailActions(false)
        fakeBackend.resetCalls()
    }

    private fun waitForRecordedCall(path: String) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var lastCalls = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            lastCalls = runCatching { fakeBackend.calls() }.getOrDefault(lastCalls)
            if (lastCalls.contains("\"path\":\"$path\"")) return
            SystemClock.sleep(100)
        }
        fail("Expected fake backend call $path. Recorded calls: $lastCalls")
    }
}
```

If the failure assertion cannot match `"500"` because the Ktor message differs, use the exact visible message from the failure output and document it in the handoff.

- [x] **Step 2: Run instrumentation test to verify RED if UI is not complete**

If Task 4 is already complete, this may be GREEN immediately. Run:

```bash
cd client
./gradlew :androidApp:assembleDebugAndroidTest \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PBUZER_ASSIGNEE_ME_ID=user-me \
  -PBUZER_ASSIGNEE_WIFE_ID=user-wife \
  -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
```

Expected: compile succeeds after Task 4.

- [x] **Step 3: Run managed-device test**

Start fake backend in a separate terminal:

```bash
cd tools/fake-backend
PORT=18433 npm run dev
```

Run:

```bash
cd client
./gradlew pixel8Api34DebugAndroidTest --rerun-tasks \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PBUZER_ASSIGNEE_ME_ID=user-me \
  -PBUZER_ASSIGNEE_WIFE_ID=user-wife \
  -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
```

Expected: all instrumentation tests pass.

- [x] **Step 4: Commit**

Run:

```bash
git add client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt
git commit -m "test: cover Android add task creation"
```

Expected: commit succeeds.

---

### Task 6: Final Verification And Handoff

**Files:**
- Update: `docs/notes/2026-06-01-native-android-emulator-testing.md`
- Create: `docs/handoffs/2026-06-02-android-add-task-strict-creation.md`
- Update: this plan file

- [x] **Step 1: Run full verification**

Run:

```bash
cd tools/fake-backend
npm test
cd ../../client
./gradlew :shared:testDebugUnitTest
./gradlew :androidApp:testDebugUnitTest
./gradlew :androidApp:assembleDebugAndroidTest \
  -PBUZER_ASSIGNEE_ME_ID=user-me \
  -PBUZER_ASSIGNEE_WIFE_ID=user-wife
```

Start fake backend and run:

```bash
cd client
./gradlew pixel8Api34DebugAndroidTest --rerun-tasks \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PBUZER_ASSIGNEE_ME_ID=user-me \
  -PBUZER_ASSIGNEE_WIFE_ID=user-wife \
  -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
```

Expected: every command reports `BUILD SUCCESSFUL` or test success.

- [x] **Step 2: Stop fake backend**

Stop the terminal session or kill the listener on `18433`.

- [x] **Step 3: Update emulator testing note**

Append:

```markdown
## Add Task Creation Coverage

The native fake-backend workflow now also covers `POST /tasks`.

The Android add-task flow uses fixed `Me` / `Wife` assignee buttons, native Material3 date/time pickers, local validation, and backend submission through `BuzerApiClient.createTask`.

Managed-device tests pass backend config through Gradle properties:

- `BUZER_BACKEND_BASE_URL=http://10.0.2.2:18433`
- `BUZER_BACKEND_TOKEN=test-token`
- `BUZER_ASSIGNEE_ME_ID=user-me`
- `BUZER_ASSIGNEE_WIFE_ID=user-wife`
- `FAKE_BACKEND_BASE_URL=http://10.0.2.2:18433`
```

- [x] **Step 4: Write handoff**

Create `docs/handoffs/2026-06-02-android-add-task-strict-creation.md` with:

```markdown
# Handoff: Android Add Task Strict Creation

Date: 2026-06-02

## Task / Plan

Plan: `docs/superpowers/plans/2026-06-02-android-add-task-strict-creation.md`

Status: Complete when all checkboxes are `[x]`.

## Files Changed

- list exact files changed

## Verification

- Command: `cd tools/fake-backend && npm test`
- Result: PASS/FAIL/SKIPPED
- Evidence: exact output summary

- Command: `cd client && ./gradlew :shared:testDebugUnitTest`
- Result: PASS/FAIL/SKIPPED
- Evidence: exact output summary

- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest`
- Result: PASS/FAIL/SKIPPED
- Evidence: exact output summary

- Command: `cd client && ./gradlew :androidApp:assembleDebugAndroidTest -PBUZER_ASSIGNEE_ME_ID=user-me -PBUZER_ASSIGNEE_WIFE_ID=user-wife`
- Result: PASS/FAIL/SKIPPED
- Evidence: exact output summary

- Command: `cd client && ./gradlew pixel8Api34DebugAndroidTest --rerun-tasks -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token -PBUZER_ASSIGNEE_ME_ID=user-me -PBUZER_ASSIGNEE_WIFE_ID=user-wife -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433`
- Result: PASS/FAIL/SKIPPED
- Evidence: exact output summary

## Known Failures Or Skipped Checks

- Record `None` if all checks pass.

## Next Recommended Step

Migrate deadline reschedule to the native date/time picker helper.
```

- [x] **Step 5: Commit docs**

Run:

```bash
git add docs/notes/2026-06-01-native-android-emulator-testing.md \
  docs/handoffs/2026-06-02-android-add-task-strict-creation.md \
  docs/superpowers/plans/2026-06-02-android-add-task-strict-creation.md
git commit -m "docs: record Android add task strict creation handoff"
```

Expected: commit succeeds.

## Plan Self-Review

- Spec coverage: covers fixed assignees, default project, native deadline picker, local validation, backend create, failure preservation, fake-backend tests, and handoff.
- Deferred scope: project picker, metadata endpoints, offline queue, widget-specific changes, and reschedule picker migration are explicitly deferred.
- Placeholder scan: no unresolved implementation placeholders are intentional. The handoff template uses PASS/FAIL/SKIPPED placeholders only inside the handoff-writing step and must be filled during Task 6.
- Risk: Material3 picker semantics may require a minor test selector adjustment. If so, record the exact visible selector in the handoff and keep the UI behavior unchanged.
