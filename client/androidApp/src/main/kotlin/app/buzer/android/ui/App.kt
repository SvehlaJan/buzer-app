package app.buzer.android.ui

import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import app.buzer.android.blockers.MorningReviewActivity
import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.ui.screens.AddTaskScreen
import app.buzer.android.ui.screens.EditTaskScreen
import app.buzer.android.ui.screens.HomeScreen
import app.buzer.android.ui.screens.HomeScreenState
import app.buzer.android.ui.screens.buildHomeScreenState
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.TasksSnapshotResponse
import app.buzer.shared.model.UpdateTaskRequest
import app.buzer.shared.state.SnapshotViewState
import java.time.Instant
import java.time.ZoneId

private enum class AppSection {
    Home,
    Add,
    Edit
}

@Composable
fun BuzerApp(
    openAddTask: Boolean = false,
    snapshot: SnapshotViewState? = null,
    homeRefreshInFlight: Boolean = false,
    homeRefreshError: String? = null,
    homeActionStatusMessage: String? = null,
    onRetryHomeRefresh: () -> Unit = {},
    onPullToRefresh: () -> Unit = {},
    onDoneTask: (String) -> Unit = {},
    onDoneCurrentTask: (String) -> Unit = onDoneTask,
    onTaskCreated: (TasksSnapshotResponse) -> Unit = {},
    onToggleCurrentChild: (String, String, Boolean) -> Unit = { _, _, _ -> },
    deviceId: String = "",
    onUpdateTask: suspend (String, UpdateTaskRequest) -> CoordinatorActionResult = { _, _ ->
        CoordinatorActionResult.FailedAndHidden("Task editing is unavailable")
    },
    nowProvider: () -> Instant = { Instant.now() },
    zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() }
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        val context = LocalContext.current
        val initialSection = if (openAddTask) AppSection.Add else AppSection.Home
        var section by remember { mutableStateOf(initialSection) }
        var editingTaskId by remember { mutableStateOf<String?>(null) }
        var overdueExpanded by rememberSaveable { mutableStateOf(false) }
        var unassignedExpanded by rememberSaveable { mutableStateOf(false) }

        fun openMorningReview() {
            AppLog.info("ui navigate section=Review source=home mode=activity")
            context.startActivity(Intent(context, MorningReviewActivity::class.java))
        }

        val homeState = buildBuzerHomeState(
            snapshot = snapshot,
            homeRefreshInFlight = homeRefreshInFlight,
            homeRefreshError = homeRefreshError,
            homeActionStatusMessage = homeActionStatusMessage,
            now = nowProvider(),
            zoneId = zoneIdProvider(),
            overdueExpanded = overdueExpanded,
            unassignedExpanded = unassignedExpanded
        )
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
                    onDoneCurrentTask = onDoneCurrentTask,
                    onEditTask = { taskId ->
                        editingTaskId = taskId
                        section = AppSection.Edit
                    },
                    onToggleCurrentChild = onToggleCurrentChild,
                    onRetryRefresh = onRetryHomeRefresh,
                    state = homeState
                )

                AppSection.Add -> AddTaskScreen(
                    onBack = { section = AppSection.Home },
                    onTaskCreated = onTaskCreated
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
                            deviceId = deviceId,
                            initialAddedDrafts = emptyList(),
                            onBack = { section = AppSection.Home },
                            onUpdate = onUpdateTask
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
    homeRefreshError: String?,
    homeActionStatusMessage: String? = null,
    now: Instant,
    zoneId: ZoneId,
    overdueExpanded: Boolean = false,
    unassignedExpanded: Boolean = false
): HomeScreenState =
    buildHomeScreenState(
        snapshot = snapshot,
        refreshInFlight = homeRefreshInFlight,
        refreshError = homeRefreshError,
        actionStatusMessage = homeActionStatusMessage,
        now = now,
        zoneId = zoneId,
        overdueExpanded = overdueExpanded,
        unassignedExpanded = unassignedExpanded
    )

