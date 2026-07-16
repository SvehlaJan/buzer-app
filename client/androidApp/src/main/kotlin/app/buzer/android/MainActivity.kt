package app.buzer.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.buzer.android.enforcement.BlockerAction
import app.buzer.android.enforcement.BlockerErrorReporter
import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.enforcement.EnforcementCoordinator
import app.buzer.android.enforcement.EnforcementDependencies
import app.buzer.android.enforcement.NotificationChannels
import app.buzer.android.ui.BuzerApp
import app.buzer.android.ui.HomeDoneActionHandler
import app.buzer.android.ui.status.setBuzerContent
import app.buzer.android.widget.AddTaskWidgetProvider
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.state.SnapshotReducer
import app.buzer.shared.state.SnapshotViewState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var coordinator: EnforcementCoordinator? = null
    private var snapshotViewState by mutableStateOf<SnapshotViewState?>(null)
    private var homeRefreshInFlight by mutableStateOf(false)
    private var homeRefreshError by mutableStateOf<String?>(null)
    private val homeActionHandler: HomeDoneActionHandler by lazy {
        HomeDoneActionHandler(
            refreshSnapshot = {
                val coordinator = requireCoordinator("snapshot refresh")
                SnapshotReducer.reduce(coordinator.refreshAndReconcile())
            },
            completeTask = { taskId ->
                val coordinator = coordinator
                    ?: return@HomeDoneActionHandler CoordinatorActionResult.FailedAndHidden("Task completion failed")
                val occurrenceKey = snapshotViewState
                    ?.tasks
                    ?.firstOrNull { it.id == taskId }
                    ?.occurrenceKey
                    ?.takeIf { it.isNotBlank() }
                    ?: return@HomeDoneActionHandler CoordinatorActionResult.FailedAndHidden(
                        "Task occurrence is no longer current"
                    )
                coordinator.completeTask(taskId, occurrenceKey)
            },
            latestSnapshot = {
                coordinator?.loadLatestSnapshot()?.let(SnapshotReducer::reduce)
            },
            applySnapshot = { snapshotViewState = it },
            setStatusMessage = { homeRefreshError = it },
            setRefreshInFlight = { homeRefreshInFlight = it },
            reportRefreshFailure = ::reportRefreshFailure
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationChannels.ensureCreated(this)
        coordinator = runCatching { EnforcementDependencies.coordinator(applicationContext) }
            .onFailure {
                AppLog.error("main coordinator create failed", it)
                BlockerErrorReporter(applicationContext).report(
                    action = BlockerAction.REFRESH_SNAPSHOT,
                    taskId = null,
                    message = it.message ?: "enforcement configuration failed"
                )
            }
            .getOrNull()
        refreshEnforcement()
        val openAddTask = intent.getBooleanExtra(AddTaskWidgetProvider.EXTRA_OPEN_ADD_TASK, false)
        setBuzerContent {
            BuzerApp(
                openAddTask = openAddTask,
                snapshot = snapshotViewState,
                homeRefreshInFlight = homeRefreshInFlight,
                homeRefreshError = homeRefreshError,
                onRetryHomeRefresh = ::refreshEnforcement,
                onDoneCurrentTask = ::completeCurrentTask,
                onTaskCreated = ::refreshEnforcement
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshEnforcement()
    }

    private fun refreshEnforcement() {
        AppLog.debug("main refresh requested")
        lifecycleScope.launch {
            homeActionHandler.refresh()
            AppLog.debug(
                "main snapshot state updated invalid=${snapshotViewState?.invalidTasks?.size ?: 0} " +
                    "startingToday=${snapshotViewState?.startingToday?.size ?: 0}"
            )
        }
    }

    private fun reportRefreshFailure(error: Throwable) {
        AppLog.error("main refresh failed", error)
        homeRefreshError = error.message ?: "snapshot refresh failed"
        BlockerErrorReporter(applicationContext).report(
            action = BlockerAction.REFRESH_SNAPSHOT,
            taskId = null,
            message = error.message ?: "snapshot refresh failed"
        )
    }

    private fun completeCurrentTask(taskId: String) {
        AppLog.info("main current_done_clicked taskId=$taskId")
        lifecycleScope.launch {
            homeActionHandler.completeCurrentTask(taskId)
        }
    }

    private fun requireCoordinator(action: String): EnforcementCoordinator =
        coordinator ?: run {
            AppLog.debug("main refresh skipped reason=no_coordinator action=$action")
            throw IllegalStateException("Refresh failed")
        }
}
