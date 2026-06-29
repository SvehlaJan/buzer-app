package app.buzer.android.blockers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.buzer.android.enforcement.AlarmIntentFactory
import app.buzer.android.enforcement.AlarmKind
import app.buzer.android.enforcement.BlockerAction
import app.buzer.android.enforcement.BlockerErrorReporter
import app.buzer.android.enforcement.EnforcementCoordinator
import app.buzer.android.enforcement.EnforcementDependencies
import app.buzer.android.ui.screens.BlockerActionState
import app.buzer.android.ui.screens.StartBlockerScreen
import app.buzer.shared.diagnostics.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class StartBlockerActivity : ComponentActivity() {
    private lateinit var errorReporter: BlockerErrorReporter
    private var actionInFlight = false
    private var checking by mutableStateOf(true)
    private var warningMessage by mutableStateOf<String?>(null)
    private var actionState by mutableStateOf<BlockerActionState>(BlockerActionState.Idle)
    private var errorMessage by mutableStateOf<String?>(null)
    private var lastAction: (suspend () -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureBlockerWindow()
        errorReporter = BlockerErrorReporter(applicationContext)
        val taskId = intent.getStringExtra(AlarmIntentFactory.EXTRA_TASK_ID)
        if (taskId == null) {
            AppLog.debug("start_blocker missing_task_id")
            errorReporter.report(
                action = BlockerAction.START_TASK,
                taskId = null,
                message = "Start blocker missing task ID"
            )
            finish()
            return
        }

        val coordinator = createCoordinator() ?: return
        prepareAndShowBlocker(coordinator, taskId)
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

    private fun prepareAndShowBlocker(coordinator: EnforcementCoordinator, taskId: String) {
        AppLog.debug("start_blocker prepare taskId=$taskId")
        checking = true
        warningMessage = null
        showBlocker(coordinator, taskId)
        lifecycleScope.launch {
            try {
                coordinator.loadLatestSnapshot()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.error("start_blocker local snapshot load failed taskId=$taskId", error)
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
                if (
                    refreshedSnapshot != null &&
                    BlockerTaskSnapshotGate.shouldHideAfterRefresh(
                        refreshedSnapshot,
                        taskId,
                        AlarmKind.START_BLOCKER
                    )
                ) {
                    AppLog.debug("start_blocker hidden reason=task_resolved taskId=$taskId")
                    finish()
                    return@launch
                }
                if (refreshedSnapshot == null) {
                    AppLog.warn("start_blocker refresh_timeout taskId=$taskId")
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
                AppLog.error("start_blocker refresh failed taskId=$taskId", error)
                warningMessage = "Could not refresh task state. This blocker may be stale."
                errorReporter.report(
                    action = BlockerAction.REFRESH_SNAPSHOT,
                    taskId = taskId,
                    message = error.message ?: "snapshot refresh failed"
                )
            }
            if (!isFinishing) checking = false
        }
    }

    private fun showBlocker(coordinator: EnforcementCoordinator, taskId: String) {
        AppLog.debug("start_blocker show taskId=$taskId")
        setContent {
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
                onDismissError = {
                    errorMessage = null
                    finish()
                },
                onRetryAction = {
                    errorMessage = null
                    lastAction?.let { retry -> runActionOnce("Retrying...") { retry() } }
                }
            )
        }
    }

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

    private companion object {
        const val BLOCKER_REFRESH_TIMEOUT_MILLIS = 5_000L
    }
}
