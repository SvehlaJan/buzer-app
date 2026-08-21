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
import app.buzer.android.ui.screens.StartBlockerScreen
import app.buzer.android.ui.status.setBuzerContent
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.TaskActionRequest
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TasksSnapshotResponse
import app.buzer.shared.ui.blockers.BlockerKind
import app.buzer.shared.ui.blockers.buildBlockerUiModel
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class StartBlockerActivity : ComponentActivity() {
    private lateinit var errorReporter: BlockerErrorReporter
    private var actionInFlight = false
    private var checking by mutableStateOf(true)
    private var warningMessage by mutableStateOf<String?>(null)
    private var blockerModel by mutableStateOf(buildBlockerUiModel(BlockerKind.Start, null))
    private var actionState by mutableStateOf<BlockerActionState>(BlockerActionState.Idle)
    private var errorMessage by mutableStateOf<String?>(null)
    private var lastAction: (suspend () -> CoordinatorActionResult)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureBlockerWindow()
        errorReporter = BlockerErrorReporter(applicationContext)
        val taskId = intent.getStringExtra(AlarmIntentFactory.EXTRA_TASK_ID)
        val occurrenceKey = intent.getStringExtra(AlarmIntentFactory.EXTRA_OCCURRENCE_KEY)
        app.buzer.android.observability.DiagnosticContextStore.instance.updateUi {
            it.copy(
                screen = "StartBlocker",
                currentActivity = "StartBlockerActivity",
                blockerType = "StartBlocker",
                appSection = "Enforcement"
            )
        }
        app.buzer.android.observability.DiagnosticContextStore.instance.updateCorrelation {
            it.copy(
                taskId = taskId,
                occurrenceKey = occurrenceKey
            )
        }
        app.buzer.android.observability.ObservabilityRuntime.instance.setContext(
            app.buzer.shared.diagnostics.DiagnosticCorrelation(
                screen = "StartBlocker",
                blockerType = "StartBlocker",
                taskId = taskId,
                occurrenceKey = occurrenceKey,
                appSection = "Enforcement"
            )
        )
        if (taskId == null || occurrenceKey.isNullOrBlank()) {
            AppLog.debug("start_blocker missing_alarm_identity")
            errorReporter.report(
                action = BlockerAction.START_TASK,
                taskId = null,
                message = "Start blocker missing task ID"
            )
            finish()
            return
        }

        val coordinator = createCoordinator() ?: return
        prepareAndShowBlocker(coordinator, taskId, occurrenceKey)
    }

    override fun onDestroy() {
        app.buzer.android.observability.DiagnosticContextStore.instance.updateUi {
            it.copy(
                screen = null,
                blockerType = null,
                currentActivity = null
            )
        }
        super.onDestroy()
    }

    private fun createCoordinator(): EnforcementCoordinator? {
        return try {
            EnforcementDependencies.coordinator(applicationContext)
        } catch (error: Exception) {
            AppLog.error("start_blocker coordinator create failed", error)
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
        AppLog.debug("start_blocker prepare taskId=$taskId occurrence=$occurrenceKey")
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
                AppLog.error("start_blocker local snapshot load failed taskId=$taskId occurrence=$occurrenceKey", error)
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
                        AlarmKind.START_BLOCKER
                    )
                ) {
                    AppLog.debug("start_blocker hidden reason=stale_or_resolved taskId=$taskId occurrence=$occurrenceKey")
                    finish()
                    return@launch
                }
                if (refreshedSnapshot == null) {
                    AppLog.warn("start_blocker refresh_timeout taskId=$taskId occurrence=$occurrenceKey")
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
                AppLog.error("start_blocker refresh failed taskId=$taskId occurrence=$occurrenceKey", error)
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
        AppLog.debug("start_blocker show taskId=$taskId occurrence=$occurrenceKey")
        setBuzerContent {
            StartBlockerScreen(
                model = blockerModel,
                onStart = {
                    AppLog.info("start_blocker start_clicked taskId=$taskId occurrence=$occurrenceKey")
                    val request = actionRequest(occurrenceKey)
                    runActionOnce("Starting...", occurrenceKey) { coordinator.startTask(taskId, request) }
                },
                onPostpone = {
                    AppLog.info("start_blocker postpone_clicked taskId=$taskId occurrence=$occurrenceKey")
                    val request = actionRequest(occurrenceKey)
                    runActionOnce("Postponing...", occurrenceKey) { coordinator.postponeStart(taskId, request) }
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
            kind = BlockerKind.Start,
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
            AppLog.debug("start_blocker action ignored reason=in_flight occurrence=$occurrenceKey")
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
                    else -> {
                        errorMessage = "Action requires attention"
                        actionState = BlockerActionState.Failed(errorMessage!!)
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

    private fun actionRequest(occurrenceKey: String): TaskActionRequest = TaskActionRequest(
        actionId = UUID.randomUUID().toString(),
        deviceId = EnforcementDependencies.deviceIdentityStore(applicationContext).deviceId(),
        expectedOccurrenceKey = occurrenceKey
    )
}

private fun TasksSnapshotResponse.findTask(taskId: String): TaskSnapshotItem? =
    tasks.firstOrNull { it.id == taskId }
