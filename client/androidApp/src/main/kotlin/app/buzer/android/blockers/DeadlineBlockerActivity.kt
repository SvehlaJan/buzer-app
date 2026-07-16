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
import app.buzer.android.ui.screens.BlockerActionState
import app.buzer.android.ui.screens.DeadlineBlockerScreen
import app.buzer.android.ui.status.setBuzerContent
import app.buzer.shared.diagnostics.AppLog
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
    private var blockerModel by mutableStateOf(buildBlockerUiModel(BlockerKind.Deadline, null))
    private var actionState by mutableStateOf<BlockerActionState>(BlockerActionState.Idle)
    private var errorMessage by mutableStateOf<String?>(null)
    private var lastAction: (suspend () -> CoordinatorActionResult)? = null

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
                message = error.message ?: "enforcement configuration failed"
            )
            finish()
            null
        }
    }

    private fun prepareAndShowBlocker(coordinator: EnforcementCoordinator, taskId: String, occurrenceKey: String) {
        AppLog.debug("deadline_blocker prepare taskId=$taskId occurrence=$occurrenceKey")
        checking = true
        warningMessage = null
        lifecycleScope.launch {
            try {
                coordinator.loadLatestSnapshot()?.let { snapshot ->
                    updateModelFromSnapshot(snapshot, taskId)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.error("deadline_blocker local snapshot load failed taskId=$taskId occurrence=$occurrenceKey", error)
                errorReporter.report(
                    action = BlockerAction.REFRESH_SNAPSHOT,
                    taskId = taskId,
                    message = error.message ?: "local snapshot load failed"
                )
            }

            try {
                val refreshedSnapshot = withTimeoutOrNull(BLOCKER_REFRESH_TIMEOUT_MILLIS) {
                    coordinator.refreshAndReconcile()
                }
                refreshedSnapshot?.let { updateModelFromSnapshot(it, taskId) }
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
                AppLog.error("deadline_blocker refresh failed taskId=$taskId occurrence=$occurrenceKey", error)
                warningMessage = "Could not refresh task state. This blocker may be stale."
                errorReporter.report(
                    action = BlockerAction.REFRESH_SNAPSHOT,
                    taskId = taskId,
                    message = error.message ?: "snapshot refresh failed"
                )
            }
            if (!isFinishing) showBlocker(coordinator, taskId, occurrenceKey)
            if (!isFinishing) checking = false
        }
    }

    private fun showBlocker(coordinator: EnforcementCoordinator, taskId: String, occurrenceKey: String) {
        AppLog.debug("deadline_blocker show taskId=$taskId occurrence=$occurrenceKey")
        setBuzerContent {
            DeadlineBlockerScreen(
                model = blockerModel,
                onDone = {
                    AppLog.info("deadline_blocker done_clicked taskId=$taskId occurrence=$occurrenceKey")
                    runActionOnce("Completing...", occurrenceKey) {
                        coordinator.completeTask(taskId, occurrenceKey)
                    }
                },
                onExtend = {
                    AppLog.info("deadline_blocker extend_clicked taskId=$taskId occurrence=$occurrenceKey")
                    runActionOnce("Extending...", occurrenceKey) { coordinator.extendTask(taskId, occurrenceKey) }
                },
                onReschedule = { request ->
                    AppLog.info(
                        "deadline_blocker reschedule_clicked taskId=$taskId occurrence=$occurrenceKey " +
                            "duration=${request.durationMinutes}"
                    )
                    runActionOnce("Rescheduling...", occurrenceKey) {
                        coordinator.rescheduleTask(taskId, occurrenceKey, request)
                    }
                },
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
                    errorMessage = null
                    lastAction?.let { retry -> runActionOnce("Retrying...", occurrenceKey) { retry() } }
                }
            )
        }
    }

    private fun updateModelFromSnapshot(snapshot: TasksSnapshotResponse, taskId: String) {
        val task = snapshot.findTask(taskId)
        blockerModel = buildBlockerUiModel(
            kind = BlockerKind.Deadline,
            task = task,
            timeZoneId = ZoneId.systemDefault().id
        )
    }

    private fun runActionOnce(
        label: String,
        occurrenceKey: String,
        action: suspend () -> CoordinatorActionResult
    ) {
        if (actionInFlight) {
            AppLog.debug("deadline_blocker action ignored reason=in_flight occurrence=$occurrenceKey")
            return
        }
        actionInFlight = true
        actionState = BlockerActionState.Loading(label)
        lastAction = action
        lifecycleScope.launch {
            try {
                when (val result = action()) {
                    CoordinatorActionResult.Success -> finish()
                    is CoordinatorActionResult.FailedAndHidden -> {
                        errorMessage = result.message
                        actionState = BlockerActionState.Failed(result.message)
                    }
                }
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

    private companion object {
        const val BLOCKER_REFRESH_TIMEOUT_MILLIS = 5_000L
    }
}

private fun TasksSnapshotResponse.findTask(taskId: String): TaskSnapshotItem? =
    tasks.firstOrNull { it.id == taskId }
