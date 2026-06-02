# Morning Review Invalid Task Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show invalid assigned-to-user tasks in morning review and let the user repair missing deadline/duration from the blocker.

**Architecture:** Reuse existing snapshot data, `SnapshotReducer`, `DateTimeSelection`, and `/tasks/:id/reschedule`. Add backend-visible morning review dismissal through the existing `/review/dismiss` endpoint. Keep `MorningReviewScreen` UI-only and let `MorningReviewActivity` own coordinator calls, local activity state, and hide-on-backend-failure behavior.

**Tech Stack:** Kotlin Android, Compose Material3, AndroidX Compose UI tests, Ktor shared API client, Node.js fake backend, Gradle Managed Device `pixel8Api34`.

---

## File Map

- Modify: `tools/fake-backend/src/server.ts`
  - Add recorded `POST /review/dismiss` route with forced failure support.
- Modify: `tools/fake-backend/src/server.test.ts`
  - Cover successful and forced-failure review dismiss recording.
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
  - Add `dismissMorningReview()`.
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementApi.kt`
  - Add `dismissMorningReview()` to the interface and backend implementation.
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt`
  - Add `dismissMorningReview()` returning `CoordinatorActionResult`.
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
  - Render invalid tasks and inline repair form.
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt`
  - Load invalid tasks from refresh/snapshot, call repair/snooze actions, hide on success/failure as specified.
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
  - Keep non-blocker in-app morning review compiling by passing no task data.
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
  - Add morning review fake-backend tests.
- Update: `docs/notes/2026-06-01-native-android-emulator-testing.md`
  - Record morning review repair coverage.
- Create: `docs/handoffs/2026-06-02-morning-review-invalid-task-repair.md`
  - Record exact verification.

## Task 1: Fake Backend Review Dismiss

- [x] Add failing tests to `tools/fake-backend/src/server.test.ts`:

```ts
test("review dismiss is recorded", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());

  const response = await app.inject({
    method: "POST",
    url: "/review/dismiss",
    headers: { authorization: "Bearer test-token" }
  });

  assert.equal(response.statusCode, 200);
  assert.equal(typeof response.json().snoozedUntil, "string");

  const calls = await app.inject({ method: "GET", url: "/__calls" });
  assert.equal(calls.json().calls[0].path, "/review/dismiss");
});

test("forced action failures also fail review dismiss and still record call", async (t) => {
  const app = await buildServer({ failActions: true });
  t.after(() => app.close());

  const response = await app.inject({
    method: "POST",
    url: "/review/dismiss",
    headers: { authorization: "Bearer test-token" }
  });

  assert.equal(response.statusCode, 500);

  const calls = await app.inject({ method: "GET", url: "/__calls" });
  assert.equal(calls.json().calls.length, 1);
  assert.equal(calls.json().calls[0].path, "/review/dismiss");
});
```

- [x] Run `cd tools/fake-backend && npm test`.
  - Expected before implementation: FAIL because `/review/dismiss` returns 404.
- [x] Add `POST /review/dismiss` to `tools/fake-backend/src/server.ts`:

```ts
app.post("/review/dismiss", async (request, reply) => {
  await maybeDelay();
  record("POST", request.url, request.body ?? null);
  if (failActions) {
    return reply.code(500).send({ error: "forced_failure" });
  }
  return reply.send({
    snoozedUntil: new Date(Date.now() + 5 * 60_000).toISOString()
  });
});
```

- [x] Run `cd tools/fake-backend && npm test`.
  - Expected after implementation: PASS.
- [x] Commit:

```bash
git add tools/fake-backend/src/server.ts tools/fake-backend/src/server.test.ts
git commit -m "test: add fake backend review dismiss route"
```

## Task 2: Shared API And Coordinator Dismiss Action

- [x] Add `dismissMorningReview()` to `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`:

```kotlin
suspend fun dismissMorningReview() {
    emptyJsonPost("$baseUrl/review/dismiss")
}
```

- [x] Add `suspend fun dismissMorningReview()` to `EnforcementApi` and implement it in `BuzerEnforcementApi` by delegating to `client.dismissMorningReview()`.
- [x] Add this method to `EnforcementCoordinator`:

```kotlin
suspend fun dismissMorningReview(): CoordinatorActionResult =
    runBackendAction(
        action = BlockerAction.MORNING_REVIEW_DISMISS,
        taskId = MORNING_REVIEW_TASK_ID,
        scheduleStartRetry = false
    ) {
        api.dismissMorningReview()
    }
```

- [x] Add this constant inside `EnforcementCoordinator` companion:

```kotlin
const val MORNING_REVIEW_TASK_ID = "morning-review"
```

- [x] Update `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementCoordinatorTest.kt` fake API to implement `dismissMorningReview()`.
- [x] Add a coordinator unit test:

```kotlin
@Test
fun dismissMorningReviewReportsFailureAndReturnsHiddenFailure() = runTest {
    val api = FakeApi(snapshot()).apply {
        failDismissMorningReview = true
    }
    val reporter = FakeErrorReporter()
    val coordinator = coordinator(api = api, errorReporter = reporter)

    val result = coordinator.dismissMorningReview()

    assertTrue(result is CoordinatorActionResult.FailedAndHidden)
    assertEquals(BlockerAction.MORNING_REVIEW_DISMISS, reporter.entries.single().action)
    assertEquals("morning-review", reporter.entries.single().taskId)
}
```

- [x] Run `cd client && ./gradlew :androidApp:testDebugUnitTest`.
  - Expected after implementation: PASS.
- [x] Commit:

```bash
git add client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt \
  client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementApi.kt \
  client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt \
  client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementCoordinatorTest.kt
git commit -m "feat: add morning review dismiss action"
```

## Task 3: Morning Review Repair UI

- [x] Replace placeholder `MorningReviewScreen` with a UI that accepts invalid tasks:

```kotlin
@Composable
fun MorningReviewScreen(
    invalidTasks: List<TaskSnapshotItem> = emptyList(),
    onBack: () -> Unit,
    onRepair: (String, RescheduleTaskRequest) -> Unit = { _, _ -> },
    onSnooze: () -> Unit = {},
    showBack: Boolean = true
)
```

- [x] Add transient UI state in `MorningReviewScreen`:

```kotlin
var repairingTask by remember { mutableStateOf<TaskSnapshotItem?>(null) }
var deadline by remember(repairingTask?.id) { mutableStateOf<DateTimeSelection?>(null) }
var durationText by remember(repairingTask?.id) { mutableStateOf(repairingTask?.durationMinutes?.toString().orEmpty()) }
var errorText by remember(repairingTask?.id) { mutableStateOf<String?>(null) }
var showDatePicker by remember { mutableStateOf(false) }
var showTimePicker by remember { mutableStateOf(false) }
var pendingDate by remember { mutableStateOf<LocalDate?>(null) }
val defaultDateTime = remember { LocalDateTime.now().plusHours(1) }
```

- [x] In list mode, show:

```kotlin
Text("Morning review")
if (invalidTasks.isEmpty()) {
    Text("No invalid tasks")
} else {
    invalidTasks.forEach { task ->
        Text(task.title)
        if (task.validationErrors.isNotEmpty()) {
            Text(task.validationErrors.joinToString(", "))
        }
        OutlinedButton(
            onClick = { repairingTask = task },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Repair")
        }
    }
}
OutlinedButton(onClick = onSnooze, modifier = Modifier.fillMaxWidth()) {
    Text("Snooze 5 min")
}
```

- [x] In repair mode, show:

```kotlin
Text("Repair task")
Text(task.title)
OutlinedButton(
    onClick = {
        showDatePicker = true
        errorText = null
    },
    modifier = Modifier
        .fillMaxWidth()
        .semantics { contentDescription = "Repair deadline picker" }
) {
    Text(deadline?.formatForDisplay() ?: "No deadline selected")
}
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
        .semantics { contentDescription = "Repair duration minutes input" }
)
errorText?.let { Text(it) }
Button(
    onClick = {
        val selectedDeadline = deadline
        val duration = durationText.trim().toIntOrNull()
        when {
            selectedDeadline == null -> errorText = "Deadline is required"
            duration == null || duration <= 0 -> errorText = "Duration must be a positive whole number of minutes"
            else -> onRepair(
                task.id,
                RescheduleTaskRequest(
                    dueDatetime = selectedDeadline.toIsoInstantString(),
                    durationMinutes = duration
                )
            )
        }
    },
    modifier = Modifier.fillMaxWidth()
) {
    Text("Save repair")
}
OutlinedButton(
    onClick = {
        repairingTask = null
        errorText = null
    },
    modifier = Modifier.fillMaxWidth()
) {
    Text("Cancel")
}
```

- [x] Add date/time dialogs using the same `DatePickerDialog` and `TimePicker` in `AlertDialog` pattern from `AddTaskScreen` and `DeadlineBlockerScreen`.
- [x] Make the top-level column vertically scrollable.
- [x] Run `cd client && ./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebugAndroidTest`.
  - Expected after implementation: PASS.
- [x] Commit:

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt \
  client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt
git commit -m "feat: show morning review repair form"
```

## Task 4: Morning Review Activity Wiring

- [x] In `MorningReviewActivity`, add Compose state:

```kotlin
var invalidTasks by mutableStateOf(emptyList<TaskSnapshotItem>())
```

- [x] After coordinator creation, load cached invalid tasks first if present:

```kotlin
coordinator?.loadLatestSnapshot()
    ?.let(SnapshotReducer::reduce)
    ?.invalidTasks
    ?.let { invalidTasks = it }
```

- [x] In the existing refresh coroutine, set invalid tasks after refresh:

```kotlin
val snapshot = coordinator.refreshAndReconcile()
invalidTasks = SnapshotReducer.reduce(snapshot).invalidTasks
```

- [x] Wire `MorningReviewScreen`:

```kotlin
MorningReviewScreen(
    invalidTasks = invalidTasks,
    onRepair = { taskId, request ->
        val currentCoordinator = coordinator
        if (currentCoordinator == null) {
            finish()
        } else {
            lifecycleScope.launch {
                when (currentCoordinator.rescheduleTask(taskId, request)) {
                    CoordinatorActionResult.Success -> {
                        invalidTasks = invalidTasks.filterNot { it.id == taskId }
                        if (invalidTasks.isEmpty()) finish()
                    }
                    is CoordinatorActionResult.FailedAndHidden -> finish()
                }
            }
        }
    },
    onSnooze = {
        scheduler.schedule(
            ScheduledAlarm(
                taskId = EnforcementCoordinator.MORNING_REVIEW_TASK_ID,
                kind = AlarmKind.MORNING_REVIEW,
                triggerAtEpochMillis = System.currentTimeMillis() + FIVE_MINUTES_MILLIS
            )
        )
        if (coordinator == null) {
            finish()
        } else {
            lifecycleScope.launch {
                coordinator.dismissMorningReview()
                finish()
            }
        }
    },
    onBack = { finish() },
    showBack = false
)
```

- [x] Remove the private duplicate `MORNING_REVIEW_TASK_ID` constant from `MorningReviewActivity`.
- [x] Run `cd client && ./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebugAndroidTest`.
  - Expected after implementation: PASS.
- [x] Commit:

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt
git commit -m "feat: wire morning review repair actions"
```

## Task 5: Native Fake-Backend Instrumentation Tests

- [x] Add tests to `BlockerActivityFakeBackendTest.kt`:

```kotlin
@Test
fun morningReview_showsInvalidTaskFromSnapshot() {
    resetTestState()

    launchMorningReview()

    composeRule.onNodeWithText("Morning review").assertIsDisplayed()
    composeRule.onNodeWithText("Invalid assigned fixture").assertIsDisplayed()
    composeRule.onNodeWithText("missing_due_datetime, missing_duration").assertIsDisplayed()
}

@Test
fun morningReview_repairShowsValidationErrorForMissingDeadline() {
    resetTestState()

    launchMorningReview()

    composeRule.onNodeWithText("Repair").performClick()
    composeRule.onNodeWithText("Save repair").performClick()

    composeRule.onNodeWithText("Deadline is required").assertIsDisplayed()
}

@Test
fun morningReview_repairCallsFakeBackend() {
    resetTestState()
    val scenario = launchMorningReview()

    composeRule.onNodeWithText("Repair").performClick()
    selectDefaultRepairDeadline()
    composeRule.onNodeWithContentDescription("Repair duration minutes input").performTextInput("45")
    composeRule.onNodeWithText("Save repair").performClick()

    waitForActivityDestroyed(scenario)
    waitForRecordedCall("/tasks/task-invalid/reschedule")
    fakeBackend.assertRecorded(
        "/tasks/task-invalid/reschedule",
        "durationMinutes",
        "45",
        "dueDatetime"
    )
}

@Test
fun morningReview_repairBackendFailureHidesAndWritesErrorLog() {
    resetTestState()
    fakeBackend.setFailActions(true)
    try {
        val scenario = launchMorningReview()

        composeRule.onNodeWithText("Repair").performClick()
        selectDefaultRepairDeadline()
        composeRule.onNodeWithContentDescription("Repair duration minutes input").performTextInput("45")
        composeRule.onNodeWithText("Save repair").performClick()

        waitForActivityDestroyed(scenario)
        waitForRecordedCall("/tasks/task-invalid/reschedule")
        waitForErrorLog("action=RESCHEDULE_TASK taskId=task-invalid", "500")
    } finally {
        fakeBackend.setFailActions(false)
    }
}

@Test
fun morningReview_snoozeCallsFakeBackendAndHides() {
    resetTestState()
    val scenario = launchMorningReview()

    composeRule.onNodeWithText("Snooze 5 min").performClick()

    waitForActivityDestroyed(scenario)
    waitForRecordedCall("/review/dismiss")
    fakeBackend.assertRecorded("/review/dismiss")
}
```

- [x] Add helpers:

```kotlin
private fun selectDefaultRepairDeadline() {
    composeRule.onNodeWithContentDescription("Repair deadline picker").performClick()
    composeRule.onNodeWithText("OK").performClick()
    composeRule.onNodeWithText("OK").performClick()
}

private fun launchMorningReview(): ActivityScenario<MorningReviewActivity> =
    ActivityScenario.launch(
        Intent(ApplicationProvider.getApplicationContext(), MorningReviewActivity::class.java)
    )
```

- [x] Run `cd client && ./gradlew :androidApp:assembleDebugAndroidTest`.
  - Expected: PASS after implementation.
- [x] Start fake backend: `cd tools/fake-backend && PORT=18433 npm run dev`.
- [x] Run managed-device suite:

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
git add client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt
git commit -m "test: cover morning review repair flow"
```

## Task 6: Final Documentation And Handoff

- [x] Update `docs/notes/2026-06-01-native-android-emulator-testing.md` with morning review coverage.
- [x] Create `docs/handoffs/2026-06-02-morning-review-invalid-task-repair.md`.
- [x] Mark all checkboxes complete in this plan.
- [x] Run final `git status --short`.
- [x] Commit docs:

```bash
git add docs/notes/2026-06-01-native-android-emulator-testing.md \
  docs/handoffs/2026-06-02-morning-review-invalid-task-repair.md \
  docs/superpowers/specs/2026-06-02-morning-review-invalid-task-repair-design.md \
  docs/superpowers/plans/2026-06-02-morning-review-invalid-task-repair.md
git commit -m "docs: record morning review repair handoff"
```
