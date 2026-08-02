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
import app.buzer.shared.model.TaskActionRequest
import app.buzer.shared.model.UpdateTaskRequest
import app.buzer.shared.model.FocusedChecklistMutationRequest
import app.buzer.shared.state.SnapshotReducer
import app.buzer.shared.state.SnapshotViewState
import java.util.UUID
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var coordinator: EnforcementCoordinator? = null
    private var snapshotViewState by mutableStateOf<SnapshotViewState?>(null)
    private var homeRefreshInFlight by mutableStateOf(false)
    private var homeRefreshError by mutableStateOf<String?>(null)
    private var homeActionStatusMessage by mutableStateOf<String?>(null)
    private val homeActionHandler: HomeDoneActionHandler by lazy {
        HomeDoneActionHandler(
            refreshSnapshot = { forceTodoist ->
                val coordinator = requireCoordinator("snapshot refresh")
                val snapshot = if (forceTodoist) {
                    coordinator.forceSyncAndReconcile()
                } else {
                    coordinator.refreshAndReconcile()
                }
                SnapshotReducer.reduce(snapshot)
            },
            completeTask = { taskId, request ->
                val coordinator = coordinator
                    ?: return@HomeDoneActionHandler CoordinatorActionResult.FailedAndHidden("Task completion failed")
                coordinator.completeTask(taskId, request)
            },
            latestSnapshot = {
                coordinator?.loadLatestSnapshot()?.let(SnapshotReducer::reduce)
            },
            applySnapshot = { snapshotViewState = it },
            setStatusMessage = { homeActionStatusMessage = it },
            setRefreshInFlight = { homeRefreshInFlight = it },
            clearRefreshFailure = { homeRefreshError = null },
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
        refreshEnforcement(forceTodoist = false)
        val openAddTask = intent.getBooleanExtra(AddTaskWidgetProvider.EXTRA_OPEN_ADD_TASK, false)
        setBuzerContent {
            BuzerApp(
                openAddTask = openAddTask,
                snapshot = snapshotViewState,
                homeRefreshInFlight = homeRefreshInFlight,
                homeRefreshError = homeRefreshError,
                homeActionStatusMessage = homeActionStatusMessage,
                onRetryHomeRefresh = ::forceRefreshEnforcement,
                onPullToRefresh = ::forceRefreshEnforcement,
                onDoneTask = ::completeHomeTask,
                onDoneCurrentTask = ::completeHomeTask,
                onTaskCreated = { returnedSnapshot ->
                    val applied = coordinator?.applyReturnedSnapshot(returnedSnapshot) ?: returnedSnapshot
                    snapshotViewState = SnapshotReducer.reduce(applied)
                },
                onToggleCurrentChild = ::toggleCurrentChild,
                deviceId = EnforcementDependencies.deviceIdentityStore(applicationContext).deviceId(),
                onUpdateTask = ::updateTask
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshEnforcement(forceTodoist = false)
    }

    private fun refreshEnforcement(forceTodoist: Boolean = false) {
        AppLog.debug("main refresh requested forceTodoist=$forceTodoist")
        lifecycleScope.launch {
            homeActionHandler.refresh(forceTodoist = forceTodoist)
            AppLog.debug(
                "main snapshot state updated invalid=${snapshotViewState?.invalidTasks?.size ?: 0} " +
                    "startingToday=${snapshotViewState?.startingToday?.size ?: 0}"
            )
        }
    }

    private fun forceRefreshEnforcement() {
        refreshEnforcement(forceTodoist = true)
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

    private fun completeHomeTask(taskId: String) {
        AppLog.info("main current_done_clicked taskId=$taskId")
        val occurrenceKey = snapshotViewState
            ?.tasks
            ?.firstOrNull { it.id == taskId }
            ?.occurrenceKey
            ?.takeIf { it.isNotBlank() }
            ?: run {
                homeActionStatusMessage = "Task occurrence is no longer current"
                return
            }
        val request = TaskActionRequest(
            actionId = UUID.randomUUID().toString(),
            deviceId = EnforcementDependencies.deviceIdentityStore(applicationContext).deviceId(),
            expectedOccurrenceKey = occurrenceKey
        )
        lifecycleScope.launch {
            homeActionHandler.completeTask(taskId, request)
        }
    }

    private suspend fun updateTask(taskId: String, request: UpdateTaskRequest): CoordinatorActionResult {
        val activeCoordinator = coordinator
            ?: return CoordinatorActionResult.FailedAndHidden("Task editing is unavailable")
        val result = activeCoordinator.updateTask(taskId, request)
        activeCoordinator.loadLatestSnapshot()?.let(SnapshotReducer::reduce)?.let { snapshotViewState = it }
        return result
    }

    private fun toggleCurrentChild(taskId: String, childId: String, checked: Boolean) {
        val task = snapshotViewState?.tasks?.firstOrNull { it.id == taskId } ?: return
        val child = task.checklistItems.firstOrNull { it.id == childId } ?: return
        val request = FocusedChecklistMutationRequest(
            actionId = UUID.randomUUID().toString(),
            deviceId = EnforcementDependencies.deviceIdentityStore(applicationContext).deviceId(),
            expectedOccurrenceKey = task.occurrenceKey,
            expectedChildRevision = child.childRevision
        )
        lifecycleScope.launch {
            val activeCoordinator = coordinator ?: return@launch
            if (checked) activeCoordinator.checkChild(taskId, childId, request)
            else activeCoordinator.uncheckChild(taskId, childId, request)
            activeCoordinator.loadLatestSnapshot()?.let(SnapshotReducer::reduce)?.let { snapshotViewState = it }
        }
    }

    private fun requireCoordinator(action: String): EnforcementCoordinator =
        coordinator ?: run {
            AppLog.debug("main refresh skipped reason=no_coordinator action=$action")
            throw IllegalStateException("Refresh failed")
        }
}

