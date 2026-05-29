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
import app.buzer.android.ui.screens.DeadlineBlockerScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class DeadlineBlockerActivity : ComponentActivity() {
    private lateinit var errorReporter: BlockerErrorReporter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureBlockerWindow()
        errorReporter = BlockerErrorReporter(applicationContext)
        val taskId = intent.getStringExtra(AlarmIntentFactory.EXTRA_TASK_ID)
        if (taskId == null) {
            errorReporter.report(
                action = BlockerAction.COMPLETE_TASK,
                taskId = null,
                message = "Deadline blocker missing task ID"
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
            coordinator.loadLatestSnapshot()

            try {
                val refreshedSnapshot = coordinator.refreshAndReconcile()
                if (BlockerTaskSnapshotGate.isTaskResolved(refreshedSnapshot, taskId)) {
                    finish()
                    return@launch
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
            DeadlineBlockerScreen(
                onDone = {
                    lifecycleScope.launch {
                        try {
                            coordinator.completeTask(taskId)
                        } finally {
                            finish()
                        }
                    }
                },
                onExtend = {
                    lifecycleScope.launch {
                        try {
                            coordinator.extendTask(taskId)
                        } finally {
                            finish()
                        }
                    }
                },
                onReschedule = {
                    errorReporter.report(
                        action = BlockerAction.RESCHEDULE_TASK,
                        taskId = taskId,
                        message = "Reschedule form is not implemented in this slice"
                    )
                    finish()
                },
                onBack = { finish() },
                showBack = false
            )
        }
    }
}
