# Android Smoke-Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Add/Edit Android Back safe and predictable, repair the debug Start/Deadline launcher identity, and prove the behavior on the physical fake-backend lane.

**Architecture:** Keep `BuzerApp` as the owner of the Home/Add/Edit section state. Add a small pure draft-fingerprint/exit-decision unit used independently by Add and Edit, and make each screen own its discard dialog. The debug launcher remains debug-source-set-only and forwards the existing production alarm identity without changing alarm scheduling or blocker validation.

**Tech Stack:** Kotlin, Compose Material 3, `androidx.activity.compose.BackHandler`, AndroidX instrumentation/Compose UI tests, existing Node fake backend and ADB-reverse connected-device lane.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/DraftExitPolicy.kt` | Pure fingerprints and the clean/confirm/ignore exit decision shared by Add and Edit. |
| `client/androidApp/src/main/kotlin/app/buzer/android/ui/status/DiscardChangesDialog.kt` | Screen-local reusable Material dialog with fixed copy and explicit callbacks. |
| `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt` | Capture the initial Add fingerprint, route Cancel/Back through the exit policy, and avoid treating restored project selection as a user edit. |
| `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/EditTaskScreen.kt` | Capture the authoritative task baseline and route Cancel/Back through the same policy. |
| `client/androidApp/src/debug/kotlin/app/buzer/android/debug/DebugBlockerLauncherActivity.kt` | Forward task ID, occurrence key, and optional alarm kind to debug-launched blocker activities. |
| `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/DraftExitPolicyTest.kt` | Fast dirty-fingerprint and exit-decision regression tests. |
| `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt` | Android Back and discard-dialog behavior for Add Task. |
| `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt` | Edit exit behavior plus debug Start/Deadline launcher coverage; retain production blocker Back tests. |
| `docs/handoffs/2026-07-17-android-smoke-hardening.md` | Exact verification evidence and confirmation that the real-backend build was reinstalled. |

### Task 1: Add pure draft-exit policy with failing JVM tests

**Files:**
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/DraftExitPolicy.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/DraftExitPolicyTest.kt`

- [ ] **Step 1: Write failing tests for exit decisions and fingerprints.**

```kotlin
class DraftExitPolicyTest {
    @Test
    fun cleanDraftNavigatesHome() {
        val draft = AddTaskDraftFingerprint.empty(projectId = "project-personal")
        assertEquals(DraftExitDecision.NavigateHome, decideDraftExit(draft, draft, submitting = false))
    }

    @Test
    fun changedTrackedFieldRequiresConfirmation() {
        val initial = AddTaskDraftFingerprint.empty(projectId = null)
        assertEquals(
            DraftExitDecision.ConfirmDiscard,
            decideDraftExit(initial, initial.copy(title = "Buy milk"), submitting = false)
        )
    }

    @Test
    fun restoredProjectIsNotDirtyButChangingItIs() {
        val initial = AddTaskDraftFingerprint.empty(projectId = "project-personal")
        assertEquals(DraftExitDecision.NavigateHome, decideDraftExit(initial, initial, submitting = false))
        assertEquals(
            DraftExitDecision.ConfirmDiscard,
            decideDraftExit(initial, initial.copy(projectId = "project-shared"), submitting = false)
        )
    }

    @Test
    fun checklistAndEditChildChangesRequireConfirmation() {
        val initial = EditTaskDraftFingerprint(
            title = "Root",
            description = "",
            start = DateTimeDraftSelection.Empty,
            durationMinutes = 30,
            recurrencePreset = RecurrencePreset.never,
            existingChildren = listOf(ChecklistDraftFingerprint("child-1", "Child", null, false)),
            deletedChildIds = emptySet(),
            addedChildren = emptyList()
        )
        assertEquals(
            DraftExitDecision.ConfirmDiscard,
            decideDraftExit(initial, initial.copy(deletedChildIds = setOf("child-1")), submitting = false)
        )
    }

    @Test
    fun inFlightSaveIgnoresExitRequest() {
        val draft = AddTaskDraftFingerprint.empty(projectId = null).copy(title = "Buy milk")
        assertEquals(DraftExitDecision.Ignore, decideDraftExit(draft, draft, submitting = true))
    }
}
```

Include assertions that changing description, assignee, start, duration, recurrence, existing-child checked/title state, and an added child produces `ConfirmDiscard`; returning each value to baseline produces `NavigateHome`. Do not include dialog/picker visibility or validation messages in either fingerprint.

- [ ] **Step 2: Run the focused test and verify it fails because the policy types do not exist.**

Run:

```bash
cd client
./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.screens.DraftExitPolicyTest
```

Expected: FAIL with unresolved references to `DraftExitDecision`, `decideDraftExit`, and the fingerprint types.

- [ ] **Step 3: Implement the minimal pure policy and fingerprints.**

```kotlin
internal enum class DraftExitDecision { NavigateHome, ConfirmDiscard, Ignore }

internal fun <T> decideDraftExit(
    initial: T,
    current: T,
    submitting: Boolean
): DraftExitDecision = when {
    submitting -> DraftExitDecision.Ignore
    initial == current -> DraftExitDecision.NavigateHome
    else -> DraftExitDecision.ConfirmDiscard
}

internal data class ChecklistDraftFingerprint(
    val stableId: String,
    val title: String,
    val description: String?,
    val checked: Boolean?
)
```

Define `AddTaskDraftFingerprint` with title, description, project ID, assignee ID, `DateTimeDraftSelection`, duration, recurrence preset, and `List<ChecklistDraftFingerprint>`. Define `EditTaskDraftFingerprint` with title, description, start, duration, recurrence preset, existing child fingerprints, deleted child IDs, and added child fingerprints. Give both explicit factory functions; do not store composable-only UI state or request objects.

- [ ] **Step 4: Re-run the focused policy tests.**

Run:

```bash
cd client
./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.screens.DraftExitPolicyTest
```

Expected: PASS with all `DraftExitPolicyTest` cases green.

- [ ] **Step 5: Commit the focused unit.**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/DraftExitPolicy.kt client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/DraftExitPolicyTest.kt
git commit -m "feat: add draft exit policy"
```

### Task 2: Add the reusable discard dialog and safe Add Task Back behavior

**Files:**
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/status/DiscardChangesDialog.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`

- [ ] **Step 1: Write failing connected-device tests for Add Task exit behavior.**

Add three tests using `ActivityScenario.launch(MainActivity::class.java)`, `openAddTaskFromHome()`, and Espresso `pressBack()`:

```kotlin
@Test
fun addTask_systemBackOnUntouchedFormReturnsHome() {
    resetTestState()
    ActivityScenario.launch(MainActivity::class.java).use {
        openAddTaskFromHome()
        pressBack()
        waitForContentDescription("Add task")
    }
}

@Test
fun addTask_changedDraftOffersKeepEditingAndDiscard() {
    resetTestState()
    ActivityScenario.launch(MainActivity::class.java).use {
        openAddTaskFromHome()
        enterTitle("Buy milk")
        pressBack()
        composeRule.onNodeWithText("Discard changes?").assertIsDisplayed()
        composeRule.onNodeWithText("Keep editing").performClick()
        composeRule.onNodeWithTag("titleInput").assertTextEquals("Buy milk")
        pressBack()
        composeRule.onNodeWithText("Discard").performClick()
        waitForContentDescription("Add task")
    }
}
```

Add a third test that scrolls to the visible `Cancel` button after entering a title and verifies the same confirmation. Add a fourth test that first persists `Test Personal (personal)` using the existing project-selection helper, closes that first form, reopens Add Task, then presses Android Back and asserts Home without a `Discard changes?` dialog. Import `pressBack` and `assertTextEquals` explicitly.

- [ ] **Step 2: Run only the new Add tests and verify they fail.**

Run:

```bash
BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/e2e-connected-device.sh --class app.buzer.android.addtask.AddTaskFakeBackendTest#addTask_systemBackOnUntouchedFormReturnsHome
```

Expected: FAIL because Android Back finishes `MainActivity` instead of retaining Home/Add section state.

- [ ] **Step 3: Implement the dialog and wire Add Task through `requestExit`.**

Create the dialog with Material 3 `AlertDialog`:

```kotlin
@Composable
internal fun DiscardChangesDialog(onKeepEditing: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text("Discard changes?") },
        text = { Text("Your unsaved changes will be lost.") },
        dismissButton = { TextButton(onClick = onKeepEditing) { Text("Keep editing") } },
        confirmButton = { TextButton(onClick = onDiscard) { Text("Discard") } }
    )
}
```

In `AddTaskScreen`, create `currentDraft()` from the tracked fields and store one initial fingerprint. Seed its project ID from `persistedProjectId`; after the first successful project load, normalize only that baseline project ID to `selectedProject?.id` so automatic restoration never creates a dirty draft. Do not reset title, description, schedule, recurrence, or checklist baseline after load.

Add one `BackHandler(enabled = !showDiscardDialog)` that calls:

```kotlin
fun requestExit() {
    when (decideDraftExit(initialDraft, currentDraft(), submitting)) {
        DraftExitDecision.NavigateHome -> onBack()
        DraftExitDecision.ConfirmDiscard -> showDiscardDialog = true
        DraftExitDecision.Ignore -> Unit
    }
}
```

Pass `requestExit` to `CommitmentEditor.onCancel`. Render `DiscardChangesDialog` outside the scroll container; `onDiscard` clears the dialog then calls `onBack`, while `onKeepEditing` only clears the dialog. Retain existing project-load and submit-error dialogs unchanged.

- [ ] **Step 4: Run all Add Task fake-backend instrumentation tests.**

Run:

```bash
BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/e2e-connected-device.sh --class app.buzer.android.addtask.AddTaskFakeBackendTest
```

Expected: PASS with existing add/create/retry cases, the dirty/clean exit cases, and the restored-project no-prompt case green.

- [ ] **Step 5: Commit Add Task exit behavior.**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/status/DiscardChangesDialog.kt client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt
git commit -m "feat: confirm add task draft discard"
```

### Task 3: Apply the same exit contract to Edit Task

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/EditTaskScreen.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- Test: `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/DraftExitPolicyTest.kt`

- [ ] **Step 1: Write failing instrumentation tests from a fixture Home row.**

Add this helper that launches `MainActivity`, waits for the fixture, and clicks a clickable agenda row rather than the Done button:

```kotlin
@Test
fun editTask_systemBackOnUntouchedFormReturnsHome() {
    resetTestState()
    ActivityScenario.launch(MainActivity::class.java).use {
        openEditTaskFromHome()
        pressBack()
        waitForAnyText("Deadline blocker fixture")
    }
}

@Test
fun editTask_changedDraftOffersKeepEditingAndDiscard() {
    resetTestState()
    ActivityScenario.launch(MainActivity::class.java).use {
        openEditTaskFromHome()
        composeRule.onNodeWithTag("editTitleInput").performTextInput(" revised")
        pressBack()
        composeRule.onNodeWithText("Discard changes?").assertIsDisplayed()
        composeRule.onNodeWithText("Keep editing").performClick()
        composeRule.onNodeWithTag("editTitleInput")
            .assertTextEquals("Deadline blocker fixture revised")
        pressBack()
        composeRule.onNodeWithText("Discard").performClick()
        waitForAnyText("Deadline blocker fixture")
    }
}

private fun openEditTaskFromHome() {
    waitForAnyText("Deadline blocker fixture")
    composeRule.onAllNodesWithText("Deadline blocker fixture")
        .filter(hasClickAction())
        .onFirst()
        .performClick()
    composeRule.onNodeWithTag("editTitleInput").assertIsDisplayed()
}
```

Pass `titleModifier = Modifier.testTag("editTitleInput")` to `CommitmentEditor` in `EditTaskScreen`. Use the existing `startBlocker_systemBackDoesNotCloseProductionBlocker` and `deadlineBlocker_systemBackDoesNotCloseProductionBlocker` tests as the regression proof; do not duplicate or weaken their assertions.

- [ ] **Step 2: Run the new Edit exit tests and verify they fail.**

Run:

```bash
BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/e2e-connected-device.sh --class app.buzer.android.blockers.BlockerActivityFakeBackendTest#editTask_changedDraftOffersKeepEditingAndDiscard
```

Expected: FAIL because `EditTaskScreen` has no `BackHandler` and Cancel immediately invokes `onBack`.

- [ ] **Step 3: Implement Edit baseline, BackHandler, and dialog.**

Build `EditTaskDraftFingerprint` from the authoritative `task` on `task.id` change and derive the current fingerprint from title, description, start, duration, recurrence, existing drafts, deleted IDs, and added drafts. Use the Task 1 policy and the Task 2 dialog:

```kotlin
var showDiscardDialog by remember(task.id) { mutableStateOf(false) }
BackHandler(enabled = !showDiscardDialog) { requestExit() }

fun requestExit() = when (decideDraftExit(initialDraft, currentDraft(), submitting)) {
    DraftExitDecision.NavigateHome -> onBack()
    DraftExitDecision.ConfirmDiscard -> showDiscardDialog = true
    DraftExitDecision.Ignore -> Unit
}
```

Pass `requestExit` to `CommitmentEditor.onCancel`. Keep `CoordinatorActionResult.Success -> onBack()` exactly as it is so a confirmed save never shows discard confirmation. Keep stale-conflict draft state untouched; because it differs from the original fingerprint, exiting afterward asks for confirmation.

- [ ] **Step 4: Run the focused Edit and existing blocker Back tests.**

Run:

```bash
BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/e2e-connected-device.sh --class app.buzer.android.blockers.BlockerActivityFakeBackendTest
```

Expected: PASS; Edit exit tests pass and the existing Start/Deadline production blocker Back tests still pass.

- [ ] **Step 5: Commit Edit exit behavior.**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/EditTaskScreen.kt client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/DraftExitPolicyTest.kt
git commit -m "feat: protect edit task drafts on exit"
```

### Task 4: Repair debug blocker identity forwarding

**Files:**
- Modify: `client/androidApp/src/debug/kotlin/app/buzer/android/debug/DebugBlockerLauncherActivity.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`

- [ ] **Step 1: Add failing debug-launcher tests for Start and Deadline.**

Add this helper, then tests that wait for real target content:

```kotlin
private fun launchDebugBlocker(kind: String, taskId: String) {
    ActivityScenario.launch<DebugBlockerLauncherActivity>(
        Intent(ApplicationProvider.getApplicationContext(), DebugBlockerLauncherActivity::class.java).apply {
            putExtra(DebugBlockerLauncherActivity.EXTRA_BLOCKER_KIND, kind)
            putExtra(AlarmIntentFactory.EXTRA_TASK_ID, taskId)
            putExtra(AlarmIntentFactory.EXTRA_OCCURRENCE_KEY, "occurrence-$taskId-1")
        }
    )
}

@Test
fun debugLauncherOpensStartBlockerWithCompleteIdentity() {
    resetTestState()
    launchDebugBlocker(DebugBlockerLauncherActivity.KIND_START, "task-start")
    waitForText("I'm starting")
    composeRule.onNodeWithText("Start blocker fixture").assertIsDisplayed()
}

@Test
fun debugLauncherOpensDeadlineBlockerWithCompleteIdentity() {
    resetTestState()
    launchDebugBlocker(DebugBlockerLauncherActivity.KIND_DEADLINE, "task-deadline")
    waitForText("Done")
    composeRule.onNodeWithText("Deadline blocker fixture").assertIsDisplayed()
}
```

- [ ] **Step 2: Run the debug Start test and verify it fails with missing identity.**

Run:

```bash
BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/e2e-connected-device.sh --class app.buzer.android.blockers.BlockerActivityFakeBackendTest#debugLauncherOpensStartBlockerWithCompleteIdentity
```

Expected: FAIL or timeout; current logs contain `start_blocker missing_alarm_identity` because the launcher drops the occurrence key.

- [ ] **Step 3: Forward the complete identity and retain fail-closed validation.**

Make the launcher constants `internal` so instrumentation can refer to them. Build the target intent only when the identity satisfies the selected kind:

```kotlin
val taskId = intent.getStringExtra(AlarmIntentFactory.EXTRA_TASK_ID)
val occurrenceKey = intent.getStringExtra(AlarmIntentFactory.EXTRA_OCCURRENCE_KEY)
val requiresTaskIdentity = target == StartBlockerActivity::class.java || target == DeadlineBlockerActivity::class.java
if (target != null && (!requiresTaskIdentity || (!taskId.isNullOrBlank() && !occurrenceKey.isNullOrBlank()))) {
    startActivity(Intent(this, target).apply {
        taskId?.let { putExtra(AlarmIntentFactory.EXTRA_TASK_ID, it) }
        occurrenceKey?.let { putExtra(AlarmIntentFactory.EXTRA_OCCURRENCE_KEY, it) }
        intent.getStringExtra(AlarmIntentFactory.EXTRA_ALARM_KIND)?.let {
            putExtra(AlarmIntentFactory.EXTRA_ALARM_KIND, it)
        }
    })
}
finish()
```

Do not alter `StartBlockerActivity`, `DeadlineBlockerActivity`, `AlarmIntentFactory`, manifests, or production source sets.

- [ ] **Step 4: Run both debug-launcher tests and the whole blocker class.**

Run:

```bash
BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/e2e-connected-device.sh --class app.buzer.android.blockers.BlockerActivityFakeBackendTest
```

Expected: PASS; Start/Deadline debug-launcher tests render their target screens and production blocker Back tests remain green.

- [ ] **Step 5: Commit debug launcher repair.**

```bash
git add client/androidApp/src/debug/kotlin/app/buzer/android/debug/DebugBlockerLauncherActivity.kt client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt
git commit -m "fix: forward debug blocker occurrence identity"
```

### Task 5: Run full verification, restore real device config, and hand off

**Files:**
- Create: `docs/handoffs/2026-07-17-android-smoke-hardening.md`
- Modify: `docs/superpowers/plans/2026-07-17-android-smoke-hardening.md` (check completed boxes only)

- [ ] **Step 1: Run JVM checks for all affected Android code.**

Run:

```bash
cd client
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain
```

Expected: PASS with zero test failures and a debug APK assembled.

- [ ] **Step 2: Run the complete physical-device fake-backend suite.**

Run:

```bash
BUZER_DEVICE_SERIAL=56061FDCR005J0 ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk scripts/e2e-connected-device.sh
```

Expected: PASS with zero failed instrumentation tests on Pixel 10. This uses ADB reverse and fake data only; it must not call Todoist, WhatsApp, or the VPS.

- [ ] **Step 3: Reinstall the real-backend debug build.**

Run from `client/`, loading the ignored machine-local configuration without printing its token:

```bash
source /Users/svehla/playground/buzer-app/client/local.properties
ANDROID_SERIAL=56061FDCR005J0 ./gradlew :androidApp:installDebug --console=plain \
  -PBUZER_BACKEND_BASE_URL="$BUZER_BACKEND_BASE_URL" \
  -PBUZER_BACKEND_TOKEN="$BUZER_BACKEND_TOKEN" \
  -PBUZER_ASSIGNEE_ME_ID="$BUZER_ASSIGNEE_ME_ID" \
  -PBUZER_ASSIGNEE_WIFE_ID="$BUZER_ASSIGNEE_WIFE_ID"
```

Expected: `Installed on 1 device.` Do not write `local.properties` into the worktree and do not include a token in handoff output.

- [ ] **Step 4: Record the handoff with evidence and remaining VPS state.**

Document every commit, exact verification command/result, manual Add/Edit Back observations, debug-launcher result, and real-build reinstall result. State explicitly whether the public VPS snapshot now contains `snapshotGeneration`; that investigation remains separate from this plan.

- [ ] **Step 5: Commit plan checkboxes and handoff only after all verification passes.**

```bash
git add docs/superpowers/plans/2026-07-17-android-smoke-hardening.md docs/handoffs/2026-07-17-android-smoke-hardening.md
git commit -m "docs: record android smoke hardening verification"
```

## Plan Self-Review

- Spec coverage: Tasks 1-3 cover clean/dirty/in-flight Add and Edit exits; Task 4 covers all missing debug identity fields without changing production blockers; Task 5 covers focused JVM, physical-device E2E, real-build restoration, and handoff evidence.
- Type consistency: `DraftExitDecision`, `decideDraftExit`, `AddTaskDraftFingerprint`, `EditTaskDraftFingerprint`, and `ChecklistDraftFingerprint` are introduced in Task 1 and used with those exact names later.
- Scope: no task changes VPS deployment, Funnel, Todoist, WhatsApp, production alarm identity, or navigation framework.
- Placeholder scan: no unfinished implementation markers remain; all code and verification steps name concrete files, symbols, tests, and commands.
