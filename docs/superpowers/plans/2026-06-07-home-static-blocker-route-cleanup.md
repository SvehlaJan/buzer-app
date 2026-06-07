# Home Static Blocker Route Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove obsolete static Start/Deadline blocker navigation from the Android home screen while preserving real blocker activities.

**Architecture:** `HomeScreen` will expose only the user-facing actions that remain valid from home: Add task and Morning review. `BuzerApp` will keep only app-shell sections for Home and Add, with Morning review continuing to launch `MorningReviewActivity`. Real blocker activities and their screen composables remain unchanged.

**Tech Stack:** Kotlin, Compose Multiplatform, Android instrumentation tests, Gradle Managed Devices.

---

## Source Documents

- Design spec: `docs/superpowers/specs/2026-06-07-home-static-blocker-route-cleanup-design.md`
- Product spec: `docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md`
- Latest handoff: `docs/handoffs/2026-06-07-home-refresh-error-banner.md`

## File Map

- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt`
  - Remove Start/Deadline callbacks and buttons.
  - Keep snapshot-count rendering, status message, Add task, and Morning review.
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
  - Remove static `Review`, `Start`, and `Deadline` app sections.
  - Remove imports for static blocker/review screens.
  - Keep Add route and activity-backed Morning review launch.
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
  - Add a home-screen assertion proving Start/Deadline buttons are absent while Add task and Morning review remain visible.
- Modify: `docs/superpowers/plans/2026-06-07-home-static-blocker-route-cleanup.md`
  - Mark task steps complete while executing.
- Create: `docs/handoffs/2026-06-07-home-static-blocker-route-cleanup.md`
  - Document files changed, commands, verification, skipped checks, and next step.

## Task 1: Add Home Cleanup Test Coverage

**Files:**
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`

- [x] **Step 1: Add absent-button assertions to the home counts test**

In `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`, update `homeScreenShowsLatestSnapshotCounts()` to include visible allowed actions and absent obsolete actions:

```kotlin
    @Test
    fun homeScreenShowsLatestSnapshotCounts() {
        resetTestState()

        ActivityScenario.launch(MainActivity::class.java)

        waitForText("Invalid: 1")
        composeRule.onNodeWithText("Active blockers: 0").assertIsDisplayed()
        composeRule.onNodeWithText("Starting today: 1").assertIsDisplayed()
        composeRule.onNodeWithText("Health warnings: 0").assertIsDisplayed()
        composeRule.onNodeWithText("Add task").assertIsDisplayed()
        composeRule.onNodeWithText("Morning review").assertIsDisplayed()
        composeRule.onAllNodesWithText("Start blocker").assertCountEquals(0)
        composeRule.onAllNodesWithText("Deadline blocker").assertCountEquals(0)
    }
```

- [x] **Step 2: Run instrumentation compile to verify the test compiles before implementation**

Run:

```bash
cd client
./gradlew :androidApp:assembleDebugAndroidTest
```

Expected: PASS. This only verifies the new assertions compile. The full managed-device run should still fail until the buttons are removed.

- [x] **Step 3: Run focused managed-device suite to verify RED**

Start the fake backend in a separate terminal/session:

```bash
cd tools/fake-backend
PORT=18433 npm run dev
```

Then run:

```bash
cd client
./gradlew pixel8Api34DebugAndroidTest --rerun-tasks \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PBUZER_ASSIGNEE_ME_ID=user-me \
  -PBUZER_ASSIGNEE_WIFE_ID=user-wife \
  -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
```

Expected: FAIL in `homeScreenShowsLatestSnapshotCounts` because `Start blocker` and `Deadline blocker` still exist on the home screen.

## Task 2: Remove Static Blocker Routes

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
- Modify: `docs/superpowers/plans/2026-06-07-home-static-blocker-route-cleanup.md`

- [x] **Step 1: Remove obsolete callbacks and buttons from `HomeScreen`**

Replace `HomeScreen` in `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt` with this signature and action area:

```kotlin
@Composable
fun HomeScreen(
    onAddTask: () -> Unit,
    onMorningReview: () -> Unit,
    state: HomeScreenState = buildHomeScreenState(null)
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(state.headline, style = MaterialTheme.typography.headlineMedium)
        state.statusMessage?.let { message ->
            Text(message)
        }
        Text("Invalid: ${state.invalidCount}")
        Text("Active blockers: ${state.activeBlockerCount}")
        Text("Starting today: ${state.startingTodayCount}")
        Text("Health warnings: ${state.healthWarningCount}")

        Button(onClick = onAddTask, modifier = Modifier.fillMaxWidth()) {
            Text("Add task")
        }

        OutlinedButton(onClick = onMorningReview, modifier = Modifier.fillMaxWidth()) {
            Text("Morning review")
        }
    }
}
```

Remove the now-unused `Row` import from the same file:

```kotlin
import androidx.compose.foundation.layout.Row
```

- [x] **Step 2: Remove dead static sections from `BuzerApp`**

In `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`, remove these imports:

```kotlin
import app.buzer.android.ui.screens.DeadlineBlockerScreen
import app.buzer.android.ui.screens.MorningReviewScreen
import app.buzer.android.ui.screens.StartBlockerScreen
```

Replace the enum with:

```kotlin
private enum class AppSection {
    Home,
    Add
}
```

Update both `HomeScreen` call sites so they only pass `onAddTask`, `onMorningReview`, and `state`:

```kotlin
HomeScreen(
    onAddTask = {
        AppLog.debug("ui navigate section=Add source=home bootRescheduleRequired=true")
        section = AppSection.Add
    },
    onMorningReview = {
        openMorningReview()
    },
    state = app.buzer.android.ui.screens.buildHomeScreenState(snapshot)
        .copy(statusMessage = homeStatusMessage)
)
```

```kotlin
AppSection.Home -> HomeScreen(
    onAddTask = {
        AppLog.debug("ui navigate section=Add source=home")
        section = AppSection.Add
    },
    onMorningReview = {
        openMorningReview()
    },
    state = app.buzer.android.ui.screens.buildHomeScreenState(snapshot)
        .copy(statusMessage = homeStatusMessage)
)
```

Remove these `when` branches:

```kotlin
AppSection.Review -> MorningReviewScreen(onBack = { section = AppSection.Home })
AppSection.Start -> StartBlockerScreen(onBack = { section = AppSection.Home })
AppSection.Deadline -> DeadlineBlockerScreen(onBack = { section = AppSection.Home })
```

Keep:

```kotlin
AppSection.Add -> AddTaskScreen(onBack = { section = AppSection.Home })
```

- [x] **Step 3: Run code search to verify static routes are gone**

Run:

```bash
rg -n "AppSection\\.(Start|Deadline|Review)|mode=static_compose_route|onStartBlocker|onDeadlineBlocker" client/androidApp/src
```

Expected: no output.

Run:

```bash
rg -n "Start blocker|Deadline blocker" client/androidApp/src/main/kotlin/app/buzer/android/ui client/androidApp/src/androidTest
```

Expected: matches only real blocker screen/activity assertions, not `HomeScreen.kt` or `App.kt`.

- [x] **Step 4: Run local client verification**

Run:

```bash
cd client
./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug
```

Expected: PASS.

- [x] **Step 5: Run managed-device fake-backend verification**

With the fake backend still running on `18433`, run:

```bash
cd client
./gradlew pixel8Api34DebugAndroidTest --rerun-tasks \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PBUZER_ASSIGNEE_ME_ID=user-me \
  -PBUZER_ASSIGNEE_WIFE_ID=user-wife \
  -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
```

Expected: PASS. The suite should include `homeScreenShowsLatestSnapshotCounts`, proving the obsolete buttons are absent and the remaining home actions still render.

- [x] **Step 6: Stop fake backend**

Stop the `PORT=18433 npm run dev` process that was started for this plan. If the process is attached to a tool session, send `Ctrl-C` to that session.

Expected: no listener remains on port `18433`.

- [x] **Step 7: Write handoff**

Create `docs/handoffs/2026-06-07-home-static-blocker-route-cleanup.md`.

Use this structure, replacing each verification entry with the exact result observed during this implementation session:

```markdown
# Handoff: Home Static Blocker Route Cleanup

Date: 2026-06-07

## Task / Plan

Plan: `docs/superpowers/plans/2026-06-07-home-static-blocker-route-cleanup.md`

Status: Complete. All checklist items in the implementation plan are marked `[x]`.

## Files Changed

- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- `docs/superpowers/plans/2026-06-07-home-static-blocker-route-cleanup.md`
- `docs/handoffs/2026-06-07-home-static-blocker-route-cleanup.md`

## Verification

- Record the RED managed-device run after adding absent-button assertions.
- Record `cd client && ./gradlew :androidApp:assembleDebugAndroidTest`.
- Record `cd client && ./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`.
- Record `cd client && ./gradlew pixel8Api34DebugAndroidTest --rerun-tasks -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token -PBUZER_ASSIGNEE_ME_ID=user-me -PBUZER_ASSIGNEE_WIFE_ID=user-wife -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433`.
- For each command, include `Result:` with the observed outcome and `Evidence:` with the key output line.

## Manual Verification

- No live Todoist, WhatsApp, or VPS destructive checks were performed.

## Known Failures Or Skipped Checks

- State whether any checks were skipped. If none were skipped, write `None`.

## Assumptions

- Real blocker activities remain the only supported Start/Deadline entry points.

## Next Recommended Step

Run a real device/VPS smoke test across refresh, add strict task, morning review, start, postpone, deadline done, extend, and reschedule.
```

- [x] **Step 8: Commit cleanup**

Run:

```bash
git status --short
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt \
  client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt \
  client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt \
  docs/superpowers/plans/2026-06-07-home-static-blocker-route-cleanup.md \
  docs/handoffs/2026-06-07-home-static-blocker-route-cleanup.md
git commit -m "fix: remove static home blocker routes"
```

Expected: commit succeeds. Do not stage unrelated untracked files such as `docs/handoffs/2026-06-07-project-state-assessment.md` unless the user explicitly asks for them.
