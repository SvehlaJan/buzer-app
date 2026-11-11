package app.buzer.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleStartEffect
import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.observability.ScreenContext
import app.buzer.android.ui.navigation.ExternalTodoistLauncher
import app.buzer.android.ui.screens.AddTaskScreen
import app.buzer.android.ui.screens.EditTaskScreen
import app.buzer.android.ui.screens.HomeScreen
import app.buzer.android.ui.screens.HomeScreenState
import app.buzer.android.ui.screens.buildHomeScreenState
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.CreateTaskRequest
import app.buzer.shared.model.UpdateTaskRequest
import app.buzer.shared.state.SnapshotViewState
import java.time.Instant
import java.time.ZoneId

internal enum class AppSection {
    Home,
    Add,
    Edit
}

internal fun screenContextFor(section: AppSection, editingTaskId: String?): ScreenContext =
    ScreenContext(
        screen = section.name,
        taskId = editingTaskId.takeIf { section == AppSection.Edit }
    )

@Composable
fun BuzerApp(
    openAddTask: Boolean = false,
    snapshot: SnapshotViewState? = null,
    homeRefreshInFlight: Boolean = false,
    homeInteractionInFlight: Boolean = false,
    homeRefreshError: String? = null,
    homeActionStatusMessage: String? = null,
    onRetryHomeRefresh: () -> Unit = {},
    onPullToRefresh: () -> Unit = {},
    onOpenTodoist: () -> Unit = {},
    onDoneTask: (String) -> Unit = {},
    onDoneCurrentTask: (String) -> Unit = onDoneTask,
    onCreateTask: suspend (CreateTaskRequest) -> CoordinatorActionResult = {
        CoordinatorActionResult.Warning("unavailable", "Task creation is unavailable", null)
    },
    onToggleCurrentChild: (String, String, Boolean) -> Unit = { _, _, _ -> },
    deviceId: String = "",
    onUpdateTask: suspend (String, UpdateTaskRequest) -> CoordinatorActionResult = { _, _ ->
        CoordinatorActionResult.Warning("unavailable", "Task editing is unavailable", null)
    },
    onRefreshWarningSnapshot: suspend () -> Unit = {},
    morningReviewBannerVisible: Boolean = false,
    morningReviewBlockingCount: Int? = null,
    onOpenMorningReview: () -> Unit = {},
    onReportBug: () -> Unit = {},
    onScreenContextChanged: (ScreenContext?) -> Unit = {},
    nowProvider: () -> Instant = { Instant.now() },
    zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() }
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        val initialSection = if (openAddTask) AppSection.Add else AppSection.Home
        var section by remember { mutableStateOf(initialSection) }
        var editingTaskId by remember { mutableStateOf<String?>(null) }
        var overdueExpanded by rememberSaveable { mutableStateOf(false) }
        var unassignedExpanded by rememberSaveable { mutableStateOf(false) }

        // Cleared while stopped so a blocker activity on top does not report a stale Main screen.
        val screenContext = screenContextFor(section, editingTaskId)
        LifecycleStartEffect(screenContext) {
            onScreenContextChanged(screenContext)
            onStopOrDispose { onScreenContextChanged(null) }
        }

        val homeState = buildBuzerHomeState(
            snapshot = snapshot,
            homeRefreshInFlight = homeRefreshInFlight,
            homeInteractionInFlight = homeInteractionInFlight,
            homeRefreshError = homeRefreshError,
            homeActionStatusMessage = homeActionStatusMessage,
            now = nowProvider(),
            zoneId = zoneIdProvider(),
            overdueExpanded = overdueExpanded,
            unassignedExpanded = unassignedExpanded,
            morningReviewBannerVisible = morningReviewBannerVisible,
            ownerBlockingCount = morningReviewBlockingCount
        )
        Column(modifier = Modifier.fillMaxSize()) {
            when (section) {
                AppSection.Home -> HomeScreen(
                    state = homeState,
                    onRefreshTasks = onPullToRefresh,
                    onOpenTodoist = onOpenTodoist,
                    onAddTask = {
                        AppLog.debug("ui navigate section=Add source=home")
                        section = AppSection.Add
                    },
                    onEditTask = { taskId ->
                        AppLog.debug("ui navigate section=Edit source=home", mapOf("task_id" to taskId))
                        editingTaskId = taskId
                        section = AppSection.Edit
                    },
                    onDoneTask = onDoneTask,
                    onToggleChecklistItem = { taskId, itemId ->
                        val task = snapshot?.tasks?.firstOrNull { it.id == taskId }
                        val child = task?.checklistItems?.firstOrNull { it.id == itemId }
                        if (child != null) {
                            onToggleCurrentChild(taskId, itemId, !child.checked)
                        }
                    },
                    onOpenMorningReview = {
                        AppLog.info("ui navigate section=Review source=home mode=activity")
                        onOpenMorningReview()
                    },
                    onToggleOverdueExpanded = { overdueExpanded = !overdueExpanded },
                    onToggleUnassignedExpanded = { unassignedExpanded = !unassignedExpanded },
                    onReportBug = onReportBug
                )

                AppSection.Add -> AddTaskScreen(
                    onBack = { section = AppSection.Home },
                    onCreateTask = onCreateTask,
                    onRefreshWarning = onRefreshWarningSnapshot
                )

                AppSection.Edit -> {
                    val task = snapshot?.tasks?.firstOrNull { it.id == editingTaskId }
                    if (deviceId.isBlank()) {
                        section = AppSection.Home
                    } else if (task == null) {
                        Text("Loading created task...")
                    } else {
                        EditTaskScreen(
                            task = task,
                            initialAddedDrafts = emptyList(),
                            onBack = { section = AppSection.Home },
                            onUpdate = onUpdateTask,
                            onRefreshWarning = onRefreshWarningSnapshot
                        )
                    }
                }
            }
        }
    }
}

internal fun buildBuzerHomeState(
    snapshot: SnapshotViewState?,
    homeRefreshInFlight: Boolean,
    homeInteractionInFlight: Boolean = false,
    homeRefreshError: String?,
    homeActionStatusMessage: String? = null,
    morningReviewBannerVisible: Boolean = false,
    ownerBlockingCount: Int? = null,
    now: Instant,
    zoneId: ZoneId,
    overdueExpanded: Boolean = false,
    unassignedExpanded: Boolean = false
): HomeScreenState =
    buildHomeScreenState(
        snapshot = snapshot,
        refreshInFlight = homeRefreshInFlight,
        interactionInFlight = homeInteractionInFlight,
        refreshError = homeRefreshError,
        actionStatusMessage = homeActionStatusMessage,
        sessionWarning = homeActionStatusMessage,
        morningReviewBannerVisible = morningReviewBannerVisible,
        ownerBlockingCount = ownerBlockingCount,
        now = now,
        zoneId = zoneId,
        overdueExpanded = overdueExpanded,
        unassignedExpanded = unassignedExpanded
    )
