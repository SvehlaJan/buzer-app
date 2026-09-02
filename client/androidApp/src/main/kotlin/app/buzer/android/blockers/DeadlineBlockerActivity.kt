package app.buzer.android.blockers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.buzer.android.enforcement.AlarmIntentFactory
import app.buzer.android.enforcement.AlarmKind
import app.buzer.android.enforcement.BlockerAction
import app.buzer.android.enforcement.BlockerErrorReporter
import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.enforcement.EnforcementCoordinator
import app.buzer.android.enforcement.EnforcementDependencies
import app.buzer.android.ui.navigation.ExternalTodoistLauncher
import app.buzer.android.ui.screens.BlockerActionState
import app.buzer.android.ui.screens.DeadlineBlockerScreen
import app.buzer.android.ui.status.setBuzerContent
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.TaskActionRequest
import app.buzer.shared.model.ExtendTaskRequest
import app.buzer.shared.model.FocusedChecklistMutationRequest
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TasksSnapshotResponse
import app.buzer.shared.ui.blockers.BlockerKind
import app.buzer.shared.ui.blockers.buildBlockerUiModel
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class DeadlineBlockerActivity : ComponentActivity() {
    private lateinit var errorReporter: BlockerErrorReporter
    private var actionInFlight = false
    private var checking by mutableStateOf(true)
    private var warningMessage by mutableStateOf<String?>(null)
    private var deadlineTask by mutableStateOf<TaskSnapshotItem?>(null)
    private var fullSnapshotConfirmed by mutableStateOf(false)
    private var blockerModel by mutableStateOf(buildBlockerUiModel(BlockerKind.Deadline, null))
    private var actionState by mutableStateOf<BlockerActionState>(BlockerActionState.Idle)
    private var errorMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureBlockerWindow()
        errorReporter = BlockerErrorReporter(applicationContext)
        val taskId = intent.getStringExtra(AlarmIntentFactory.EXTRA_TASK_ID)
        val occurrenceKey = intent.getStringExtra(AlarmIntentFactory.EXTRA_OCCURRENCE_KEY)
        if (taskId == null || occurrenceKey.isNullOrBlank()) {
            AppLog.debug("deadline_blocker missing_alarm_identity")
            errorReporter.report(
                action = BlockerAction.COMPLETE_TASK,
                taskId = null,
                message = "Deadline blocker missing task ID"
            )
            finish()
            return
        }

        val coordinator = createCoordinator() ?: return
        prepareAndShowBlocker(coordinator, taskId, occurrenceKey)
    }

    private fun createCoordinator(): EnforcementCoordinator? {
        return try {
            EnforcementDependencies.coordinator(applicationContext)
        } catch (error: Exception) {
            AppLog.error("deadline_blocker coordinator create failed", error)
            errorReporter.report(
                action = BlockerAction.REFRESH_SNAPSHOT,
                taskId = null,
                message = error.message ?: "enforcement configuration failed",
                throwable = error
            )
            finish()
            null
        }
    }

    private fun prepareAndShowBlocker(coordinator: EnforcementCoordinator, taskId: String, occurrenceKey: String) {
        AppLog.debug("deadline_blocker prepare taskId=$taskId occurrence=$occurrenceKey")
        checking = true
        warningMessage = null
        fullSnapshotConfirmed = false
        lifecycleScope.launch {
            try {
                coordinator.loadLatestSnapshot()?.let { snapshot ->
                    updateModelFromSnapshot(snapshot, taskId, occurrenceKey, snapshotConfirmed = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.error("deadline_blocker local snapshot load failed taskId=$taskId occurrence=$occurrenceKey", error)
                errorReporter.report(
                    action = BlockerAction.REFRESH_SNAPSHOT,
                    taskId = taskId,
                    message = error.message ?: "local snapshot load failed",
                    throwable = error
                )
            }

            try {
                val refreshedSnapshot = withTimeoutOrNull(BLOCKER_REFRESH_TIMEOUT_MILLIS) {
                    coordinator.refreshAndReconcile()
                }
                refreshedSnapshot?.let {
                    updateModelFromSnapshot(it, taskId, occurrenceKey, snapshotConfirmed = true)
                }
                if (
                    refreshedSnapshot != null &&
                    BlockerTaskSnapshotGate.shouldHideAfterRefresh(
                        refreshedSnapshot,
                        taskId,
                        occurrenceKey,
                        AlarmKind.DEADLINE_BLOCKER
                    )
                ) {
                    AppLog.debug("deadline_blocker hidden reason=stale_or_resolved taskId=$taskId occurrence=$occurrenceKey")
                    finish()
                    return@launch
                }
                if (refreshedSnapshot == null) {
                    fullSnapshotConfirmed = false
                    AppLog.warn("deadline_blocker refresh_timeout taskId=$taskId occurrence=$occurrenceKey")
                    warningMessage = "Could not refresh task state. This blocker may be stale."
                    errorReporter.report(
                        action = BlockerAction.REFRESH_SNAPSHOT,
                        taskId = taskId,
                        message = "snapshot refresh timed out"
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                fullSnapshotConfirmed = false
                AppLog.error("deadline_blocker refresh failed taskId=$taskId occurrence=$occurrenceKey", error)
                warningMessage = "Could not refresh task state. This blocker may be stale."
                errorReporter.report(
                    action = BlockerAction.REFRESH_SNAPSHOT,
                    taskId = taskId,
                    message = error.message ?: "snapshot refresh failed",
                    throwable = error
                )
            }
            if (!isFinishing) showBlocker(coordinator, taskId, occurrenceKey)
            if (!isFinishing) checking = false
        }
    }

    private fun forceRefreshBlocker(coordinator: EnforcementCoordinator, taskId: String, occurrenceKey: String) {
        checking = true
        lifecycleScope.launch {
            try {
                val snapshot = coordinator.forceSyncAndReconcile()
                updateModelFromSnapshot(snapshot, taskId, occurrenceKey, snapshotConfirmed = true)
                if (
                    BlockerTaskSnapshotGate.shouldHideAfterRefresh(
                        snapshot,
                        taskId,
                        occurrenceKey,
                        AlarmKind.DEADLINE_BLOCKER
                    )
                ) {
                    finish()
                    return@launch
                }
                warningMessage = null
                errorMessage = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.error("deadline_blocker force refresh failed", error)
                warningMessage = "Refresh failed"
            } finally {
                checking = false
            }
        }
    }

    private fun showBlocker(coordinator: EnforcementCoordinator, taskId: String, occurrenceKey: String) {
        AppLog.debug("deadline_blocker show taskId=$taskId occurrence=$occurrenceKey")
        setBuzerContent {
            DeadlineBlockerScreen(
                model = blockerModel,
                onDone = {
                    AppLog.info("deadline_blocker done_clicked taskId=$taskId occurrence=$occurrenceKey")
                    val request = actionRequest(occurrenceKey)
                    runActionOnce("Completing...", occurrenceKey) {
                        coordinator.completeTask(taskId, request)
                    }
                },
                onExtend = {
                    AppLog.info("deadline_blocker extend_clicked taskId=$taskId occurrence=$occurrenceKey")
                    val expectedTaskRevision = deadlineTask?.taskRevision
                    if (expectedTaskRevision.isNullOrBlank()) {
                        warningMessage = "Task details are unavailable. Refresh and try again."
                    } else {
                        val request = extendRequest(occurrenceKey, expectedTaskRevision)
                        runActionOnce("Extending...", occurrenceKey) { coordinator.extendTask(taskId, request) }
                    }
                },
                onToggleChild = { child, checked ->
                    val request = FocusedChecklistMutationRequest(
                        expectedOccurrenceKey = occurrenceKey,
                        expectedChildRevision = child.childRevision
                    )
                    runActionOnce(
                        label = if (checked) "Checking..." else "Unchecking...",
                        occurrenceKey = occurrenceKey,
                        closeOnSuccess = false,
                        onAppliedSnapshot = {
                            coordinator.loadLatestSnapshot()?.let {
                                updateModelFromSnapshot(it, taskId, occurrenceKey)
                            }
                        }
                    ) {
                        if (checked) coordinator.checkChild(taskId, child.id, request)
                        else coordinator.uncheckChild(taskId, child.id, request)
                    }
                },
                onOpenTodoist = {
                    ExternalTodoistLauncher.launchTask(this, deadlineTask?.todoistTaskId ?: taskId)
                },
                task = deadlineTask,
                onUpdate = { request ->
                    val currentTask = deadlineTask
                    if (currentTask == null || currentTask.id != taskId || currentTask.occurrenceKey != occurrenceKey) {
                        CoordinatorActionResult.Warning(code = "task_unavailable", message = "Task details are unavailable. Refresh and try again.", snapshot = null)
                    } else {
                        AppLog.info("deadline_blocker update_clicked taskId=$taskId occurrence=$occurrenceKey")
                        coordinator.updateTask(taskId, request)
                    }
                },
                onRefreshUpdateWarning = {
                    val snapshot = coordinator.forceSyncAndReconcile()
                    updateModelFromSnapshot(snapshot, taskId, occurrenceKey, snapshotConfirmed = true)
                },
                onUpdateSuccess = { finish() },
                onRefreshTask = { forceRefreshBlocker(coordinator, taskId, occurrenceKey) },
                occurrenceKey = occurrenceKey,
                snapshotConfirmed = fullSnapshotConfirmed,
                onBack = { finish() },
                showBack = false,
                checking = checking,
                warningMessage = warningMessage,
                actionState = actionState,
                errorMessage = errorMessage,
                onDismissError = {
                    errorMessage = null
                    finish()
                },
                onRetryAction = {
                    forceRefreshBlocker(coordinator, taskId, occurrenceKey)
                }
            )
        }
    }

    private fun updateModelFromSnapshot(
        snapshot: TasksSnapshotResponse,
        taskId: String,
        occurrenceKey: String,
        snapshotConfirmed: Boolean? = null
    ) {
        val task = snapshot.findTask(taskId)
        deadlineTask = BlockerTaskSnapshotGate.currentTask(snapshot, taskId, occurrenceKey)
        snapshotConfirmed?.let { fullSnapshotConfirmed = it }
        blockerModel = buildBlockerUiModel(
            kind = BlockerKind.Deadline,
            task = task,
            timeZoneId = ZoneId.systemDefault().id
        )
    }

    private fun runActionOnce(
        label: String,
        occurrenceKey: String,
        closeOnSuccess: Boolean = true,
        onAppliedSnapshot: (suspend () -> Unit)? = null,
        action: suspend () -> CoordinatorActionResult
    ) {
        if (actionInFlight) {
            AppLog.debug("deadline_blocker action ignored reason=in_flight occurrence=$occurrenceKey")
            return
        }
        actionInFlight = true
        actionState = BlockerActionState.Loading(label)
        lifecycleScope.launch {
            try {
                when (val result = action()) {
                    CoordinatorActionResult.Success -> {
                        if (closeOnSuccess) finish() else onAppliedSnapshot?.invoke()
                    }
                    is CoordinatorActionResult.Warning -> {
                        onAppliedSnapshot?.invoke()
                        warningMessage = result.message
                    }
                    is CoordinatorActionResult.Conflict -> {
                        onAppliedSnapshot?.invoke()
                        warningMessage = result.message
                    }
                    is CoordinatorActionResult.TransportFailure -> {
                        warningMessage = result.message
                    }
                    is CoordinatorActionResult.LocalReconciliationFailed -> {
                        onAppliedSnapshot?.invoke()
                        warningMessage = result.message
                    }
                    CoordinatorActionResult.ChildActionInFlight -> {
                        warningMessage = "Checklist update is already in progress"
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = error.message ?: "Action failed"
                warningMessage = message
            } finally {
                actionInFlight = false
                actionState = BlockerActionState.Idle
            }
        }
    }

    private companion object {
        const val BLOCKER_REFRESH_TIMEOUT_MILLIS = 5_000L
    }

    private fun actionRequest(occurrenceKey: String, expectedTaskRevision: String? = null): TaskActionRequest = TaskActionRequest(
        expectedOccurrenceKey = occurrenceKey,
        expectedTaskRevision = expectedTaskRevision
    )

    private fun extendRequest(occurrenceKey: String, expectedTaskRevision: String): ExtendTaskRequest = ExtendTaskRequest(
        expectedOccurrenceKey = occurrenceKey,
        expectedTaskRevision = expectedTaskRevision
    )
}

private fun TasksSnapshotResponse.findTask(taskId: String): TaskSnapshotItem? =
    tasks.firstOrNull { it.id == taskId }
