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
        lifecycleScope.launch {
            try {
                coordinator.loadLatestSnapshot()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
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
                    finish()
                    return@launch
                }
                if (refreshedSnapshot == null) {
                    errorReporter.report(
                        action = BlockerAction.REFRESH_SNAPSHOT,
                        taskId = taskId,
                        message = "snapshot refresh timed out"
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
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
        setContent {
            StartBlockerScreen(
                onStart = {
                    runActionOnce { coordinator.startTask(taskId) }
                },
                onPostpone = {
                    runActionOnce { coordinator.postponeStart(taskId) }
                },
                onBack = { finish() },
                showBack = false
            )
        }
    }

    private fun runActionOnce(action: suspend () -> Unit) {
        if (actionInFlight) return
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
