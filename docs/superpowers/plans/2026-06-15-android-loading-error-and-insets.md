# Android Loading, Error, and Insets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add immediate loading feedback, visible retryable errors, duplicate-action prevention, and Android system-bar safe-area handling across the Android client.

**Architecture:** Add a lightweight Compose status layer with reusable state models and UI components, then wire it into Home, Add Task, Morning Review, and blocker screens. Keep the existing navigation and backend APIs; all behavior changes stay in Android UI/activity state and focused reducers.

**Tech Stack:** Kotlin, Compose Multiplatform/Material3, Android activity/window insets, existing Android JVM unit tests, focused real-device verification through `scripts/adb-verify.sh`.

---

## File Structure

- Create `client/androidApp/src/main/kotlin/app/buzer/android/ui/status/OperationStatus.kt`
  - Pure state models for screen loading, operation progress, and user-visible errors.
- Create `client/androidApp/src/main/kotlin/app/buzer/android/ui/status/StatusComponents.kt`
  - Reusable Compose components: inset-safe root, loading row, warning banner, blocking loading/error states, loading action button, and error dialog.
- Create `client/androidApp/src/test/kotlin/app/buzer/android/ui/status/OperationStatusTest.kt`
  - Pure tests for status state helpers.
- Modify `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
  - Apply inset-safe root container around normal app content.
- Modify `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
  - Track Home refresh in-flight/error state and pass it to the app.
- Modify `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt`
  - Replace the `snapshot == null` zero-count behavior with explicit loading/error/ready state.
- Modify `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt`
  - Cover loading, stale-data warning, and no-data error states.
- Modify `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
  - Add project-load alert, submit error alert, loading action button, and duplicate-submit/form-disable behavior.
- Modify `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskProjectLoadFeedbackTest.kt`
  - Adjust/extend expected state helper behavior if extracted.
- Modify `client/androidApp/src/main/kotlin/app/buzer/android/blockers/StartBlockerActivity.kt`
  - Track pre-show refresh state and action failure state.
- Modify `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt`
  - Track pre-show refresh state and action failure state.
- Modify `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt`
  - Add checking, warning, action-in-flight, and alert presentation for blocker screens.
- Modify `client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt`
  - Track refresh/action in-flight and error state.
- Modify `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
  - Add screen-level loading, refresh banner, action loading, and alert presentation.
- Add or modify targeted tests under `client/androidApp/src/test/kotlin/app/buzer/android/ui/` for pure screen state builders.
- Update `docs/handoffs/2026-06-15-vps-deploy-and-home-in-progress.md`
  - Record commands, results, manual verification, and any skipped checks.

---

## Task 1: Shared Status Models and UI Components

**Files:**
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/status/OperationStatus.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/status/StatusComponents.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/status/OperationStatusTest.kt`

- [x] **Step 1: Write failing status model tests**

Create `client/androidApp/src/test/kotlin/app/buzer/android/ui/status/OperationStatusTest.kt`:

```kotlin
package app.buzer.android.ui.status

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationStatusTest {
    @Test
    fun loadingOperationIsInFlightAndHasNoMessage() {
        val status = OperationStatus.Loading("Saving...")

        assertTrue(status.isInFlight)
        assertEquals("Saving...", status.loadingLabel)
        assertEquals(null, status.errorMessage)
    }

    @Test
    fun failedOperationExposesErrorMessage() {
        val status = OperationStatus.Failed("Backend unavailable")

        assertFalse(status.isInFlight)
        assertEquals(null, status.loadingLabel)
        assertEquals("Backend unavailable", status.errorMessage)
    }

    @Test
    fun screenStateReportsBlockingWhenLoadingOrFailedWithoutData() {
        assertTrue(ScreenLoadState.Loading.isBlocking)
        assertTrue(ScreenLoadState.Failed("No backend").isBlocking)
        assertFalse(ScreenLoadState.Ready(data = "snapshot", warning = "Stale").isBlocking)
    }
}
```

- [x] **Step 2: Run focused test and verify RED**

Run:

```bash
cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.status.OperationStatusTest' --console=plain
```

Expected: FAIL at Kotlin compilation because `OperationStatus` and `ScreenLoadState` do not exist.

- [x] **Step 3: Add pure status models**

Create `client/androidApp/src/main/kotlin/app/buzer/android/ui/status/OperationStatus.kt`:

```kotlin
package app.buzer.android.ui.status

sealed interface OperationStatus {
    data object Idle : OperationStatus
    data class Loading(val label: String) : OperationStatus
    data class Failed(val message: String) : OperationStatus

    val isInFlight: Boolean
        get() = this is Loading

    val loadingLabel: String?
        get() = (this as? Loading)?.label

    val errorMessage: String?
        get() = (this as? Failed)?.message
}

sealed interface ScreenLoadState<out T> {
    data object Loading : ScreenLoadState<Nothing>
    data class Ready<T>(val data: T, val warning: String? = null, val refreshing: Boolean = false) : ScreenLoadState<T>
    data class Failed(val message: String) : ScreenLoadState<Nothing>

    val isBlocking: Boolean
        get() = this is Loading || this is Failed
}
```

- [x] **Step 4: Add reusable Compose components**

Create `client/androidApp/src/main/kotlin/app/buzer/android/ui/status/StatusComponents.kt`:

```kotlin
package app.buzer.android.ui.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun InsetSafeContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
    ) {
        content()
    }
}

@Composable
fun InlineLoadingStatus(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator()
        Text(message)
    }
}

@Composable
fun WarningStatus(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        if (onRetry != null) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text("Retry")
            }
        }
    }
}

@Composable
fun BlockingLoadingState(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        InlineLoadingStatus(message)
    }
}

@Composable
fun BlockingErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        WarningStatus(message = message, onRetry = onRetry)
    }
}

@Composable
fun LoadingActionButton(
    label: String,
    loadingLabel: String,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
    ) {
        Text(if (loading) loadingLabel else label)
    }
}

@Composable
fun ErrorAlertDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            if (retryLabel != null && onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text(retryLabel)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    )
}
```

- [x] **Step 5: Run focused test and verify GREEN**

Run:

```bash
cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.status.OperationStatusTest' --console=plain
```

Expected: PASS with `BUILD SUCCESSFUL`.

- [x] **Step 6: Commit shared status layer**

Run:

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/status/OperationStatus.kt \
  client/androidApp/src/main/kotlin/app/buzer/android/ui/status/StatusComponents.kt \
  client/androidApp/src/test/kotlin/app/buzer/android/ui/status/OperationStatusTest.kt
git commit -m "feat: add android ui status primitives"
```

---

## Task 2: Home Loading, Refresh, and Error States

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt`

- [x] **Step 1: Add failing Home state tests**

Append these tests to `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt`:

```kotlin
@Test
fun showsInitialLoadingBeforeFirstSnapshot() {
    val state = buildHomeScreenState(
        snapshot = null,
        refreshInFlight = true,
        refreshError = null
    )

    assertEquals(HomeContentState.Loading, state.contentState)
    assertEquals(null, state.statusMessage)
}

@Test
fun showsBlockingErrorWhenRefreshFailsBeforeFirstSnapshot() {
    val state = buildHomeScreenState(
        snapshot = null,
        refreshInFlight = false,
        refreshError = "Backend unavailable"
    )

    assertEquals(HomeContentState.Error("Backend unavailable"), state.contentState)
    assertEquals(null, state.statusMessage)
}

@Test
fun keepsExistingDataVisibleWhileRefreshing() {
    val snapshot = SnapshotViewState(
        invalidTasks = emptyList(),
        activeBlockers = emptyList(),
        inProgressTasks = emptyList(),
        startingToday = emptyList(),
        healthWarnings = emptyList()
    )

    val state = buildHomeScreenState(
        snapshot = snapshot,
        refreshInFlight = true,
        refreshError = null
    )

    assertEquals(HomeContentState.Ready, state.contentState)
    assertEquals("Refreshing...", state.statusMessage)
}

@Test
fun keepsExistingDataVisibleWithStaleWarningAfterRefreshFailure() {
    val snapshot = SnapshotViewState(
        invalidTasks = emptyList(),
        activeBlockers = emptyList(),
        inProgressTasks = emptyList(),
        startingToday = emptyList(),
        healthWarnings = emptyList()
    )

    val state = buildHomeScreenState(
        snapshot = snapshot,
        refreshInFlight = false,
        refreshError = "Network request failed"
    )

    assertEquals(HomeContentState.Ready, state.contentState)
    assertEquals("Refresh failed. Showing last known data.", state.statusMessage)
}
```

Also add the import if needed:

```kotlin
import app.buzer.android.ui.screens.HomeContentState
```

- [x] **Step 2: Run Home tests and verify RED**

Run:

```bash
cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.HomeScreenStateTest' --console=plain
```

Expected: FAIL at Kotlin compilation because `HomeContentState` and the new `buildHomeScreenState` parameters do not exist.

- [x] **Step 3: Update Home screen state model**

Modify `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt` near the existing state data classes:

```kotlin
sealed interface HomeContentState {
    data object Loading : HomeContentState
    data object Ready : HomeContentState
    data class Error(val message: String) : HomeContentState
}

data class HomeScreenState(
    val headline: String,
    val contentState: HomeContentState,
    val statusMessage: String?,
    val invalidCount: Int,
    val activeBlockerCount: Int,
    val inProgressCount: Int,
    val currentInProgressTask: HomeInProgressTask?,
    val startingTodayCount: Int,
    val healthWarningCount: Int
)
```

Replace `buildHomeScreenState` with:

```kotlin
fun buildHomeScreenState(
    snapshot: SnapshotViewState?,
    refreshInFlight: Boolean = false,
    refreshError: String? = null
): HomeScreenState =
    if (snapshot == null) {
        HomeScreenState(
            headline = "Today",
            contentState = if (refreshError != null) {
                HomeContentState.Error(refreshError)
            } else {
                HomeContentState.Loading
            },
            statusMessage = null,
            invalidCount = 0,
            activeBlockerCount = 0,
            inProgressCount = 0,
            currentInProgressTask = null,
            startingTodayCount = 0,
            healthWarningCount = 0
        )
    } else {
        val currentInProgressTask = snapshot.inProgressTasks
            .sortedWith(compareBy(nullsLast()) { it.dueDatetime })
            .firstOrNull()

        HomeScreenState(
            headline = "Today",
            contentState = HomeContentState.Ready,
            statusMessage = when {
                refreshInFlight -> "Refreshing..."
                refreshError != null -> "Refresh failed. Showing last known data."
                else -> null
            },
            invalidCount = snapshot.invalidTasks.size,
            activeBlockerCount = snapshot.activeBlockers.size,
            inProgressCount = snapshot.inProgressTasks.size,
            currentInProgressTask = currentInProgressTask?.let {
                HomeInProgressTask(
                    title = it.title,
                    dueDatetime = it.dueDatetime
                )
            },
            startingTodayCount = snapshot.startingToday.size,
            healthWarningCount = snapshot.healthWarnings.size
        )
    }
```

- [x] **Step 4: Add explicit Home retry callback and update Home UI rendering**

Update `HomeScreen` signature before changing the body:

```kotlin
fun HomeScreen(
    onAddTask: () -> Unit,
    onMorningReview: () -> Unit,
    onRetryRefresh: () -> Unit = {},
    state: HomeScreenState = buildHomeScreenState(null)
)
```

In `HomeScreen`, render `contentState` before the existing counts:

```kotlin
Text(state.headline, style = MaterialTheme.typography.headlineMedium)
state.statusMessage?.let { message ->
    if (message == "Refreshing...") {
        InlineLoadingStatus(message)
    } else {
        WarningStatus(message = message, onRetry = onRetryRefresh)
    }
}

when (val contentState = state.contentState) {
    HomeContentState.Loading -> {
        BlockingLoadingState("Loading tasks...")
        return@Column
    }
    is HomeContentState.Error -> {
        BlockingErrorState(
            message = contentState.message,
            onRetry = onRetryRefresh
        )
        return@Column
    }
    HomeContentState.Ready -> Unit
}
```

Add imports:

```kotlin
import app.buzer.android.ui.status.BlockingErrorState
import app.buzer.android.ui.status.BlockingLoadingState
import app.buzer.android.ui.status.InlineLoadingStatus
import app.buzer.android.ui.status.WarningStatus
```

- [x] **Step 5: Add activity refresh state**

In `MainActivity`, add:

```kotlin
private var homeRefreshInFlight by mutableStateOf(false)
private var homeRefreshError by mutableStateOf<String?>(null)
```

Update `refreshEnforcement()`:

```kotlin
private fun refreshEnforcement() {
    val coordinator = coordinator ?: run {
        AppLog.debug("main refresh skipped reason=no_coordinator")
        homeRefreshError = "Refresh failed"
        homeStatusMessage = "Refresh failed"
        return
    }
    AppLog.debug("main refresh requested")
    val job = refreshRunner.refresh {
        homeRefreshInFlight = true
        try {
            val snapshot = coordinator.refreshAndReconcile()
            snapshotViewState = SnapshotReducer.reduce(snapshot)
            homeRefreshError = null
            homeStatusMessage = null
            AppLog.debug(
                "main snapshot state updated invalid=${snapshotViewState?.invalidTasks?.size ?: 0} " +
                    "startingToday=${snapshotViewState?.startingToday?.size ?: 0}"
            )
        } finally {
            homeRefreshInFlight = false
        }
    }
    if (job != null) {
        homeRefreshInFlight = true
    }
}
```

Update `reportRefreshFailure`:

```kotlin
private fun reportRefreshFailure(error: Throwable) {
    AppLog.error("main refresh failed", error)
    homeRefreshError = error.message ?: "snapshot refresh failed"
    homeStatusMessage = "Refresh failed"
    BlockerErrorReporter(applicationContext).report(
        action = BlockerAction.REFRESH_SNAPSHOT,
        taskId = null,
        message = error.message ?: "snapshot refresh failed"
    )
}
```

Update `BuzerApp` parameters and call site:

```kotlin
fun BuzerApp(
    openAddTask: Boolean = false,
    snapshot: SnapshotViewState? = null,
    homeRefreshInFlight: Boolean = false,
    homeRefreshError: String? = null,
    homeStatusMessage: String? = null,
    onRetryHomeRefresh: () -> Unit = {},
    onTaskCreated: () -> Unit = {}
)
```

In `MainActivity.setContent`, pass:

```kotlin
homeRefreshInFlight = homeRefreshInFlight,
homeRefreshError = homeRefreshError,
onRetryHomeRefresh = ::refreshEnforcement,
```

In `BuzerApp`, build state:

```kotlin
state = buildHomeScreenState(
    snapshot = snapshot,
    refreshInFlight = homeRefreshInFlight,
    refreshError = homeRefreshError
).copy(statusMessage = homeStatusMessage ?: buildHomeScreenState(
    snapshot = snapshot,
    refreshInFlight = homeRefreshInFlight,
    refreshError = homeRefreshError
).statusMessage)
```

Then pass `onRetryRefresh = onRetryHomeRefresh`.

- [x] **Step 6: Apply inset-safe root to normal app**

In `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`, wrap current `Column` in `InsetSafeContent`:

```kotlin
InsetSafeContent {
    Column(modifier = Modifier.fillMaxSize()) {
        when (section) {
            AppSection.Home -> HomeScreen(
                onAddTask = {
                    AppLog.debug("ui navigate section=Add source=home")
                    section = AppSection.Add
                },
                onMorningReview = {
                    openMorningReview()
                },
                onRetryRefresh = onRetryHomeRefresh,
                state = buildHomeScreenState(
                    snapshot = snapshot,
                    refreshInFlight = homeRefreshInFlight,
                    refreshError = homeRefreshError
                ).copy(
                    statusMessage = homeStatusMessage ?: buildHomeScreenState(
                        snapshot = snapshot,
                        refreshInFlight = homeRefreshInFlight,
                        refreshError = homeRefreshError
                    ).statusMessage
                )
            )

            AppSection.Add -> AddTaskScreen(
                onBack = { section = AppSection.Home },
                onTaskCreated = onTaskCreated
            )
        }
    }
}
```

Add import:

```kotlin
import app.buzer.android.ui.status.InsetSafeContent
```

- [x] **Step 7: Run Home tests and broad unit tests**

Run:

```bash
cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.HomeScreenStateTest' --console=plain
cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain
```

Expected: both commands PASS with `BUILD SUCCESSFUL`.

- [x] **Step 8: Commit Home loading state**

Run:

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt \
  client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt \
  client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt \
  client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt
git commit -m "feat: show home loading and refresh states"
```

---

## Task 3: Add Task Project and Submit Feedback

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskProjectLoadFeedbackTest.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskSubmitStateTest.kt`
- Create or modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskSubmitState.kt`

- [x] **Step 1: Write failing submit-state tests**

Create `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskSubmitStateTest.kt`:

```kotlin
package app.buzer.android.ui.addtask

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddTaskSubmitStateTest {
    @Test
    fun submittingDisablesFormAndShowsSavingLabel() {
        val state = AddTaskSubmitState(submitting = true, projectsLoading = false, projectLoadError = null)

        assertTrue(state.formDisabled)
        assertEquals("Saving...", state.saveLabel)
        assertFalse(state.saveEnabled)
    }

    @Test
    fun saveEnabledOnlyWhenNotSubmittingProjectsLoadedAndNoLoadError() {
        assertTrue(AddTaskSubmitState(submitting = false, projectsLoading = false, projectLoadError = null).saveEnabled)
        assertFalse(AddTaskSubmitState(submitting = false, projectsLoading = true, projectLoadError = null).saveEnabled)
        assertFalse(AddTaskSubmitState(submitting = false, projectsLoading = false, projectLoadError = "No network").saveEnabled)
    }
}
```

- [x] **Step 2: Run focused test and verify RED**

Run:

```bash
cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.addtask.AddTaskSubmitStateTest' --console=plain
```

Expected: FAIL at Kotlin compilation because `AddTaskSubmitState` does not exist.

- [x] **Step 3: Implement submit state helper**

Create `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskSubmitState.kt`:

```kotlin
package app.buzer.android.ui.addtask

data class AddTaskSubmitState(
    val submitting: Boolean,
    val projectsLoading: Boolean,
    val projectLoadError: String?
) {
    val formDisabled: Boolean = submitting
    val saveLabel: String = if (submitting) "Saving..." else "Save task"
    val saveEnabled: Boolean = !submitting && !projectsLoading && projectLoadError == null
}
```

- [x] **Step 4: Run focused test and verify GREEN**

Run:

```bash
cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.addtask.AddTaskSubmitStateTest' --console=plain
```

Expected: PASS with `BUILD SUCCESSFUL`.

- [x] **Step 5: Add project-load and submit alert state to AddTaskScreen**

In `AddTaskScreen`, add state:

```kotlin
var projectLoadDialogMessage by remember { mutableStateOf<String?>(null) }
var submitDialogMessage by remember { mutableStateOf<String?>(null) }
var lastValidRequest by remember { mutableStateOf<CreateTaskRequest?>(null) }
```

After `projectFeedbackMessage`, add:

```kotlin
val submitState = AddTaskSubmitState(
    submitting = submitting,
    projectsLoading = projectsLoading,
    projectLoadError = projectLoadError
)
```

In project load catch blocks, set:

```kotlin
val message = error.message ?: "Project loading failed"
projectLoadError = message
projectLoadDialogMessage = message
```

In task create failure catch, set:

```kotlin
val message = error.message ?: "Task creation failed"
errorText = message
submitDialogMessage = message
```

- [x] **Step 6: Add Add Task dialogs and retry handlers**

Add helper function inside `AddTaskScreen`:

```kotlin
fun retryProjects() {
    scope.launch {
        projectsLoading = true
        projectLoadError = null
        projectLoadDialogMessage = null
        try {
            applyLoadedProjects(refreshProjectList())
        } catch (error: Exception) {
            AppLog.error(
                "add_task projects refresh_failed type=${error::class.simpleName} message=${error.message}",
                error
            )
            val message = error.message ?: "Project loading failed"
            projectLoadError = message
            projectLoadDialogMessage = message
        } finally {
            projectsLoading = false
        }
    }
}

fun submitRequest(request: CreateTaskRequest) {
    submitting = true
    errorText = null
    submitDialogMessage = null
    lastValidRequest = request
    scope.launch {
        try {
            AppLog.info(
                "add_task create_start titleLength=${request.title.length} " +
                    "project=${request.projectId} assignee=${request.assigneeId} " +
                    "due=${request.dueDatetime} duration=${request.durationMinutes}"
            )
            runSuccessfulAddTaskCreate(
                request = request,
                createTask = createTask,
                onTaskCreated = onTaskCreated,
                onBack = onBack
            )
            AppLog.info("add_task create_success")
        } catch (error: Exception) {
            AppLog.error(
                "add_task create_failed type=${error::class.simpleName} message=${error.message}",
                error
            )
            val message = error.message ?: "Task creation failed"
            errorText = message
            submitDialogMessage = message
        } finally {
            AppLog.debug("add_task create_finished submitting=false")
            submitting = false
        }
    }
}
```

Replace inline retry button `onClick` body with `retryProjects()`.

Replace submit coroutine launch with `submitRequest(result.request)`.

Add dialogs at the end of `AddTaskScreen`:

```kotlin
projectLoadDialogMessage?.let { message ->
    ErrorAlertDialog(
        title = "Projects did not load",
        message = message,
        onDismiss = { projectLoadDialogMessage = null },
        retryLabel = "Retry",
        onRetry = { retryProjects() }
    )
}

submitDialogMessage?.let { message ->
    ErrorAlertDialog(
        title = "Task was not saved",
        message = message,
        onDismiss = { submitDialogMessage = null },
        retryLabel = "Retry Save",
        onRetry = {
            submitDialogMessage = null
            when (
                val result = AddTaskFormValidator.validate(
                    title,
                    selectedProject,
                    selectedAssignee,
                    deadline,
                    durationMinutes
                )
            ) {
                is AddTaskFormResult.Invalid -> errorText = result.message
                is AddTaskFormResult.Valid -> submitRequest(result.request)
            }
        }
    )
}
```

Add imports:

```kotlin
import app.buzer.android.ui.addtask.AddTaskSubmitState
import app.buzer.android.ui.status.ErrorAlertDialog
import app.buzer.android.ui.status.InlineLoadingStatus
import app.buzer.android.ui.status.LoadingActionButton
```

- [x] **Step 7: Use loading components and disable duplicate form edits**

In the project picker area, when `projectsLoading` is true, render:

```kotlin
InlineLoadingStatus("Loading projects...")
```

Set important form controls disabled while `submitState.formDisabled`:

```kotlin
enabled = !submitState.formDisabled
```

Apply this to title, project picker, assignee buttons, deadline picker, duration input, date/time dialog confirm buttons, and retry buttons.

Replace Save `Button` with:

```kotlin
LoadingActionButton(
    label = "Save task",
    loadingLabel = "Saving...",
    loading = submitting,
    onClick = { /* existing validation and submitRequest call */ },
    enabled = submitState.saveEnabled,
    modifier = Modifier.fillMaxWidth()
)
```

- [x] **Step 8: Run Add Task tests and broad unit tests**

Run:

```bash
cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.addtask.*' --console=plain
cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain
```

Expected: both commands PASS with `BUILD SUCCESSFUL`.

- [x] **Step 9: Commit Add Task loading/error behavior**

Run:

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt \
  client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskSubmitState.kt \
  client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskSubmitStateTest.kt \
  client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskProjectLoadFeedbackTest.kt
git commit -m "feat: show add task loading and errors"
```

---

## Task 4: Blocker Checking and Action Progress

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/StartBlockerActivity.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/blockers/BlockerActionStateTest.kt`

- [x] **Step 1: Write failing blocker action state tests**

Create `client/androidApp/src/test/kotlin/app/buzer/android/ui/blockers/BlockerActionStateTest.kt`:

```kotlin
package app.buzer.android.ui.blockers

import app.buzer.android.ui.screens.BlockerActionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockerActionStateTest {
    @Test
    fun loadingActionDisablesAllBlockerActions() {
        val state = BlockerActionState.Loading("Starting...")

        assertTrue(state.actionsDisabled)
        assertEquals("Starting...", state.loadingLabel)
    }

    @Test
    fun idleActionAllowsButtons() {
        val state = BlockerActionState.Idle

        assertFalse(state.actionsDisabled)
        assertEquals(null, state.loadingLabel)
    }
}
```

- [x] **Step 2: Run focused test and verify RED**

Run:

```bash
cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.blockers.BlockerActionStateTest' --console=plain
```

Expected: FAIL at Kotlin compilation because `BlockerActionState` does not exist.

- [x] **Step 3: Add blocker action state**

In `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt`, add near the top:

```kotlin
sealed interface BlockerActionState {
    data object Idle : BlockerActionState
    data class Loading(val label: String) : BlockerActionState
    data class Failed(val message: String) : BlockerActionState

    val actionsDisabled: Boolean
        get() = this is Loading

    val loadingLabel: String?
        get() = (this as? Loading)?.label
}
```

- [x] **Step 4: Update StartBlockerScreen signature and UI**

Change `StartBlockerScreen` signature:

```kotlin
fun StartBlockerScreen(
    onBack: () -> Unit,
    onStart: () -> Unit = {},
    onPostpone: () -> Unit = {},
    showBack: Boolean = true,
    checking: Boolean = false,
    warningMessage: String? = null,
    actionState: BlockerActionState = BlockerActionState.Idle,
    errorMessage: String? = null,
    onDismissError: () -> Unit = {},
    onRetryAction: () -> Unit = {}
)
```

At the start of the column, add:

```kotlin
if (checking) {
    BlockingLoadingState("Checking task state...")
    return@Column
}
warningMessage?.let { WarningStatus(it) }
```

Replace buttons:

```kotlin
LoadingActionButton(
    label = "I'm starting",
    loadingLabel = if (actionState is BlockerActionState.Loading) actionState.label else "Starting...",
    loading = actionState is BlockerActionState.Loading,
    onClick = onStart,
    enabled = !actionState.actionsDisabled,
    modifier = Modifier.fillMaxWidth()
)
OutlinedButton(
    onClick = onPostpone,
    enabled = !actionState.actionsDisabled,
    modifier = Modifier.fillMaxWidth()
) {
    Text(if (actionState is BlockerActionState.Loading) "Working..." else "Postpone 5 min")
}
```

Add error dialog:

```kotlin
errorMessage?.let { message ->
    ErrorAlertDialog(
        title = "Action failed",
        message = message,
        onDismiss = onDismissError,
        retryLabel = "Retry",
        onRetry = onRetryAction
    )
}
```

Add imports:

```kotlin
import app.buzer.android.ui.status.BlockingLoadingState
import app.buzer.android.ui.status.ErrorAlertDialog
import app.buzer.android.ui.status.LoadingActionButton
import app.buzer.android.ui.status.WarningStatus
```

- [x] **Step 5: Update DeadlineBlockerScreen signature and UI**

Change `DeadlineBlockerScreen` signature with the same parameters:

```kotlin
checking: Boolean = false,
warningMessage: String? = null,
actionState: BlockerActionState = BlockerActionState.Idle,
errorMessage: String? = null,
onDismissError: () -> Unit = {},
onRetryAction: () -> Unit = {}
```

Before rendering deadline content, add:

```kotlin
if (checking) {
    BlockingLoadingState("Checking task state...")
    return@Column
}
warningMessage?.let { WarningStatus(it) }
```

Disable all Done/Extend/Reschedule/Save reschedule buttons when `actionState.actionsDisabled`.

Use `LoadingActionButton` for Done and Save reschedule:

```kotlin
LoadingActionButton(
    label = "Done",
    loadingLabel = if (actionState is BlockerActionState.Loading) actionState.label else "Completing...",
    loading = actionState is BlockerActionState.Loading,
    onClick = onDone,
    enabled = !actionState.actionsDisabled,
    modifier = Modifier.fillMaxWidth()
)
```

Add the same `ErrorAlertDialog` block as Start.

- [x] **Step 6: Wire StartBlockerActivity state**

In `StartBlockerActivity`, add state fields:

```kotlin
private var checking by mutableStateOf(true)
private var warningMessage by mutableStateOf<String?>(null)
private var actionState by mutableStateOf<BlockerActionState>(BlockerActionState.Idle)
private var errorMessage by mutableStateOf<String?>(null)
private var lastAction: (suspend () -> Unit)? = null
```

Import:

```kotlin
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.buzer.android.ui.screens.BlockerActionState
```

In `prepareAndShowBlocker`, set:

```kotlin
checking = true
warningMessage = null
```

Before `showBlocker`, set:

```kotlin
checking = false
```

In refresh failure catch, set:

```kotlin
warningMessage = "Could not refresh task state. This blocker may be stale."
```

Change `runActionOnce` to accept a loading label and preserve retry:

```kotlin
private fun runActionOnce(label: String, action: suspend () -> Unit) {
    if (actionInFlight) {
        AppLog.debug("start_blocker action ignored reason=in_flight")
        return
    }
    actionInFlight = true
    actionState = BlockerActionState.Loading(label)
    lastAction = action
    lifecycleScope.launch {
        try {
            action()
            finish()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val message = error.message ?: "Action failed"
            errorMessage = message
            actionState = BlockerActionState.Failed(message)
        } finally {
            actionInFlight = false
            if (actionState is BlockerActionState.Loading) {
                actionState = BlockerActionState.Idle
            }
        }
    }
}
```

Update screen calls:

```kotlin
StartBlockerScreen(
    onStart = {
        AppLog.info("start_blocker start_clicked taskId=$taskId")
        runActionOnce("Starting...") { coordinator.startTask(taskId) }
    },
    onPostpone = {
        AppLog.info("start_blocker postpone_clicked taskId=$taskId")
        runActionOnce("Postponing...") { coordinator.postponeStart(taskId) }
    },
    onBack = { finish() },
    showBack = false,
    checking = checking,
    warningMessage = warningMessage,
    actionState = actionState,
    errorMessage = errorMessage,
    onDismissError = { errorMessage = null; finish() },
    onRetryAction = {
        errorMessage = null
        lastAction?.let { retry -> runActionOnce("Retrying...") { retry() } }
    }
)
```

- [x] **Step 7: Wire DeadlineBlockerActivity state**

Apply the same pattern as Step 6 with deadline-specific labels:

- Done: `Completing...`
- Extend: `Extending...`
- Reschedule: `Rescheduling...`

Update `DeadlineBlockerScreen` call with `checking`, `warningMessage`, `actionState`, `errorMessage`, `onDismissError`, and `onRetryAction`.

- [x] **Step 8: Run blocker tests and broad unit tests**

Run:

```bash
cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.blockers.BlockerActionStateTest' --console=plain
cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain
```

Expected: both commands PASS with `BUILD SUCCESSFUL`.

- [x] **Step 9: Commit blocker loading behavior**

Run:

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/blockers/StartBlockerActivity.kt \
  client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt \
  client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt \
  client/androidApp/src/test/kotlin/app/buzer/android/ui/blockers/BlockerActionStateTest.kt
git commit -m "feat: show blocker checking and action progress"
```

---

## Task 5: Morning Review Loading and Action Feedback

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/MorningReviewScreenStateTest.kt`

- [x] **Step 1: Write failing Morning Review state tests**

Create `client/androidApp/src/test/kotlin/app/buzer/android/ui/MorningReviewScreenStateTest.kt`:

```kotlin
package app.buzer.android.ui

import app.buzer.android.ui.screens.MorningReviewContentState
import app.buzer.android.ui.screens.buildMorningReviewContentState
import kotlin.test.Test
import kotlin.test.assertEquals

class MorningReviewScreenStateTest {
    @Test
    fun showsLoadingWhenRefreshingWithoutCachedTasks() {
        val state = buildMorningReviewContentState(
            invalidTaskCount = 0,
            refreshing = true,
            refreshError = null,
            hasLoadedOnce = false
        )

        assertEquals(MorningReviewContentState.Loading, state)
    }

    @Test
    fun showsReadyWithRefreshWarningWhenCachedTasksExistAndRefreshFails() {
        val state = buildMorningReviewContentState(
            invalidTaskCount = 2,
            refreshing = false,
            refreshError = "Network request failed",
            hasLoadedOnce = true
        )

        assertEquals(
            MorningReviewContentState.Ready(warning = "Refresh failed. Showing last known tasks."),
            state
        )
    }

    @Test
    fun showsBlockingErrorWhenNoCachedDataAndRefreshFails() {
        val state = buildMorningReviewContentState(
            invalidTaskCount = 0,
            refreshing = false,
            refreshError = "Backend unavailable",
            hasLoadedOnce = false
        )

        assertEquals(MorningReviewContentState.Error("Backend unavailable"), state)
    }
}
```

- [x] **Step 2: Run focused test and verify RED**

Run:

```bash
cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.MorningReviewScreenStateTest' --console=plain
```

Expected: FAIL at Kotlin compilation because `MorningReviewContentState` and `buildMorningReviewContentState` do not exist.

- [x] **Step 3: Add Morning Review content state**

In `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`, add:

```kotlin
sealed interface MorningReviewContentState {
    data object Loading : MorningReviewContentState
    data class Ready(val warning: String? = null, val refreshing: Boolean = false) : MorningReviewContentState
    data class Error(val message: String) : MorningReviewContentState
}

fun buildMorningReviewContentState(
    invalidTaskCount: Int,
    refreshing: Boolean,
    refreshError: String?,
    hasLoadedOnce: Boolean
): MorningReviewContentState =
    when {
        refreshError != null && !hasLoadedOnce -> MorningReviewContentState.Error(refreshError)
        refreshError != null -> MorningReviewContentState.Ready(warning = "Refresh failed. Showing last known tasks.")
        refreshing && !hasLoadedOnce && invalidTaskCount == 0 -> MorningReviewContentState.Loading
        refreshing -> MorningReviewContentState.Ready(refreshing = true)
        else -> MorningReviewContentState.Ready()
    }
```

- [x] **Step 4: Update MorningReviewScreen signature and UI**

Add parameters:

```kotlin
contentState: MorningReviewContentState = MorningReviewContentState.Ready(),
repairing: Boolean = false,
snoozing: Boolean = false,
actionErrorMessage: String? = null,
onDismissActionError: () -> Unit = {},
onRetryAction: () -> Unit = {}
```

At the top of the column, after title:

```kotlin
when (val state = contentState) {
    MorningReviewContentState.Loading -> {
        BlockingLoadingState("Loading morning review...")
        return@Column
    }
    is MorningReviewContentState.Error -> {
        BlockingErrorState(message = state.message, onRetry = onSnooze)
        return@Column
    }
    is MorningReviewContentState.Ready -> {
        if (state.refreshing) InlineLoadingStatus("Refreshing...")
        state.warning?.let { WarningStatus(it) }
    }
}
```

Use loading state for repair save:

```kotlin
LoadingActionButton(
    label = "Save repair",
    loadingLabel = "Saving...",
    loading = repairing,
    onClick = { /* existing validation */ },
    enabled = !repairing && !snoozing,
    modifier = Modifier.fillMaxWidth()
)
```

Pass `snoozing` to `MorningReviewTaskList`.

Add error dialog:

```kotlin
actionErrorMessage?.let { message ->
    ErrorAlertDialog(
        title = "Action failed",
        message = message,
        onDismiss = onDismissActionError,
        retryLabel = "Retry",
        onRetry = onRetryAction
    )
}
```

- [x] **Step 5: Update MorningReviewTaskList snooze loading**

Change signature:

```kotlin
private fun MorningReviewTaskList(
    invalidTasks: List<TaskSnapshotItem>,
    onRepair: (TaskSnapshotItem) -> Unit,
    onSnooze: () -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    snoozing: Boolean
)
```

Replace Snooze button with:

```kotlin
LoadingActionButton(
    label = "Snooze 5 min",
    loadingLabel = "Snoozing...",
    loading = snoozing,
    onClick = onSnooze,
    modifier = Modifier.fillMaxWidth()
)
```

- [x] **Step 6: Wire MorningReviewActivity state**

In `MorningReviewActivity.onCreate`, add:

```kotlin
var refreshing by mutableStateOf(coordinator != null)
var hasLoadedOnce by mutableStateOf(false)
var refreshError by mutableStateOf<String?>(null)
var repairing by mutableStateOf(false)
var snoozing by mutableStateOf(false)
var actionErrorMessage by mutableStateOf<String?>(null)
var retryAction by mutableStateOf<(() -> Unit)?>(null)
```

When cached tasks load:

```kotlin
hasLoadedOnce = true
```

In refresh success:

```kotlin
hasLoadedOnce = true
refreshError = null
```

In refresh failure:

```kotlin
refreshError = error.message ?: "snapshot refresh failed"
```

In refresh finally:

```kotlin
refreshing = false
```

Wrap repair action:

```kotlin
fun runRepair(
    coordinator: EnforcementCoordinator,
    taskId: String,
    request: RescheduleTaskRequest
) {
    repairing = true
    actionErrorMessage = null
    retryAction = { runRepair(coordinator, taskId, request) }
    lifecycleScope.launch {
        try {
            AppLog.info("morning_review repair start taskId=$taskId duration=${request.durationMinutes}")
            when (coordinator.rescheduleTask(taskId, request)) {
                CoordinatorActionResult.Success -> {
                    invalidTasks = invalidTasks.filterNot { it.id == taskId }
                    AppLog.info(
                        "morning_review repair success taskId=$taskId remainingInvalid=${invalidTasks.size}"
                    )
                    if (invalidTasks.isEmpty()) finish()
                }
                is CoordinatorActionResult.FailedAndHidden -> {
                    AppLog.warn("morning_review repair failed_hidden taskId=$taskId")
                    finish()
                }
            }
        } catch (error: Exception) {
            actionErrorMessage = error.message ?: "Repair failed"
        } finally {
            repairing = false
        }
    }
}
```

Wrap snooze action:

```kotlin
fun runSnooze(coordinator: EnforcementCoordinator?) {
    snoozing = true
    actionErrorMessage = null
    retryAction = { runSnooze(coordinator) }
    lifecycleScope.launch {
        try {
            AppLog.info("morning_review snooze requested")
            scheduler.schedule(
                ScheduledAlarm(
                    taskId = EnforcementCoordinator.MORNING_REVIEW_TASK_ID,
                    kind = AlarmKind.MORNING_REVIEW,
                    triggerAtEpochMillis = System.currentTimeMillis() + FIVE_MINUTES_MILLIS
                )
            )
            if (coordinator == null) {
                AppLog.debug("morning_review snooze finish reason=no_coordinator")
                finish()
            } else {
                coordinator.dismissMorningReview()
                AppLog.info("morning_review snooze dismissed_on_backend")
                finish()
            }
        } catch (error: Exception) {
            actionErrorMessage = error.message ?: "Snooze failed"
        } finally {
            snoozing = false
        }
    }
}
```

Pass:

```kotlin
contentState = buildMorningReviewContentState(
    invalidTaskCount = invalidTasks.size,
    refreshing = refreshing,
    refreshError = refreshError,
    hasLoadedOnce = hasLoadedOnce
),
repairing = repairing,
snoozing = snoozing,
actionErrorMessage = actionErrorMessage,
onDismissActionError = { actionErrorMessage = null },
onRetryAction = {
    actionErrorMessage = null
    retryAction?.invoke()
}
```

- [x] **Step 7: Run Morning Review and broad tests**

Run:

```bash
cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.MorningReviewScreenStateTest' --console=plain
cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain
```

Expected: both commands PASS with `BUILD SUCCESSFUL`.

- [x] **Step 8: Commit Morning Review loading behavior**

Run:

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt \
  client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt \
  client/androidApp/src/test/kotlin/app/buzer/android/ui/MorningReviewScreenStateTest.kt
git commit -m "feat: show morning review loading and errors"
```

---

## Task 6: Final Verification, Device Install, and Handoff

**Files:**
- Modify: `docs/handoffs/2026-06-15-vps-deploy-and-home-in-progress.md`

- [x] **Step 1: Run full Android verification**

Run:

```bash
cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain
```

Expected: PASS with `BUILD SUCCESSFUL`.

- [x] **Step 2: Install on connected Pixel**

Run:

```bash
BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/adb-verify.sh install
```

Expected: PASS with `Installed debug build on 56061FDCR005J0.`

- [x] **Step 3: Verify system bar insets manually**

On Pixel 10:

1. Open Buzer.
2. Confirm Home title/content is not covered by the status bar.
3. Open Add Task.
4. Confirm title field and project picker are not covered by the status bar.
5. Trigger a start/deadline blocker in the same live-backend real-device flow used earlier in this branch.
6. Confirm blocker title and buttons are not covered by status/navigation bars. If a live blocker is not triggered during this session, record `SKIPPED: no fresh blocker was triggered after the insets change` in the handoff.

Capture evidence:

```bash
BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/adb-verify.sh screenshot /tmp/buzer-insets.png
```

Expected: screenshot command exits 0 and image shows content below status bar.

- [x] **Step 4: Verify Add Task project error path manually**

On Pixel 10:

1. Temporarily disconnect the phone from Tailscale or point debug config at an unreachable backend in a separate test build.
2. Open Add Task.
3. Confirm project loading is immediately visible.
4. Confirm project load failure shows an alert dialog.
5. Dismiss the dialog and confirm inline Retry remains visible.

If disrupting network/config is not acceptable during this session, record `SKIPPED: project load failure path not manually triggered because it would interrupt the active device/backend test setup` in the handoff.

- [x] **Step 5: Update handoff**

Append a `## 2026-06-15 Android Loading/Error/Insets Implementation` section to `docs/handoffs/2026-06-15-vps-deploy-and-home-in-progress.md` containing these concrete subsections:

- `### Task / Plan`: state that `docs/superpowers/plans/2026-06-15-android-loading-error-and-insets.md` was executed and list which task checkboxes were completed.
- `### Files Changed`: list every file changed by the implementation commits.
- `### Verification`: include one bullet per command run. Each bullet must contain the exact command, `Result: PASS` or `Result: FAIL`, and a short evidence line copied or summarized from output.
- `### Manual Verification`: include Android system-bar overlap and Add Task project loading/error checks, each with `PASS`, `FAIL`, or the exact `SKIPPED` sentence from Steps 3 or 4.
- `### Known Failures / Skipped Checks`: list remaining gaps, or write `None` if no gaps remain.

- [ ] **Step 6: Commit final handoff**

Run:

```bash
git add docs/handoffs/2026-06-15-vps-deploy-and-home-in-progress.md
git commit -m "docs: record android loading verification"
```

- [ ] **Step 7: Push branch**

Run:

```bash
git push
```

Expected: push succeeds to `origin/feature/project-aware-task-creation`.

---

## Plan Self-Review

Spec coverage:

- Immediate feedback: covered by shared status components and each screen/action task.
- Required-load behavior: Home, Add Task, and Morning Review tasks distinguish blocking no-data states from ready-with-warning states.
- Background refresh: Home and Morning Review tasks keep stale content visible with warning/status.
- Alert dialogs: Add Task, Morning Review, and blockers include dialog steps.
- Duplicate prevention: Add Task submit state and blocker action state disable duplicate actions.
- Insets: shared `InsetSafeContent`, app root wiring, and manual device verification cover system UI overlap.

Placeholder scan:

- The plan intentionally uses exact file paths, commands, expected outputs, state names, and code snippets. No vague recovery instructions remain.

Type consistency:

- `OperationStatus`, `ScreenLoadState`, `HomeContentState`, `AddTaskSubmitState`, `BlockerActionState`, and `MorningReviewContentState` are introduced before later tasks reference them.
