package app.buzer.android.blockers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import app.buzer.android.enforcement.AlarmIntentFactory
import app.buzer.android.enforcement.BlockerAction
import app.buzer.android.enforcement.BlockerErrorReporter
import app.buzer.android.enforcement.EnforcementCoordinator
import app.buzer.android.enforcement.EnforcementDependencies
import app.buzer.android.ui.screens.StartBlockerScreen
import app.buzer.shared.diagnostics.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class StartBlockerActivity : ComponentActivity() {
    private lateinit var errorReporter: BlockerErrorReporter
    private var actionInFlight = false

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
                if (refreshedSnapshot != null && BlockerTaskSnapshotGate.isTaskResolved(refreshedSnapshot, taskId)) {
                    AppLog.debug("start_blocker hidden reason=task_resolved taskId=$taskId")
                    finish()
                    return@launch
                }
                if (refreshedSnapshot == null) {
                    AppLog.warn("start_blocker refresh_timeout taskId=$taskId")
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
                errorReporter.report(
                    action = BlockerAction.REFRESH_SNAPSHOT,
                    taskId = taskId,
                    message = error.message ?: "snapshot refresh failed"
                )
            }
            if (!isFinishing) showBlocker(coordinator, taskId)
        }
    }

    private fun showBlocker(coordinator: EnforcementCoordinator, taskId: String) {
        AppLog.debug("start_blocker show taskId=$taskId")
        setContent {
            StartBlockerScreen(
                onStart = {
                    AppLog.info("start_blocker start_clicked taskId=$taskId")
                    runActionOnce { coordinator.startTask(taskId) }
                },
                onPostpone = {
                    AppLog.info("start_blocker postpone_clicked taskId=$taskId")
                    runActionOnce { coordinator.postponeStart(taskId) }
                },
                onBack = { finish() },
                showBack = false
            )
        }
    }

    private fun runActionOnce(action: suspend () -> Unit) {
        if (actionInFlight) {
            AppLog.debug("start_blocker action ignored reason=in_flight")
            return
        }
        actionInFlight = true
        lifecycleScope.launch {
            try {
                action()
            } finally {
                finish()
            }
        }
    }

    private companion object {
        const val BLOCKER_REFRESH_TIMEOUT_MILLIS = 5_000L
    }
}
