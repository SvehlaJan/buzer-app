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

class StartBlockerActivity : ComponentActivity() {
    private lateinit var errorReporter: BlockerErrorReporter

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
        refreshSnapshot(coordinator, taskId)

        setContent {
            StartBlockerScreen(
                onStart = {
                    lifecycleScope.launch {
                        try {
                            coordinator.startTask(taskId)
                        } finally {
                            finish()
                        }
                    }
                },
                onPostpone = {
                    lifecycleScope.launch {
                        try {
                            coordinator.postponeStart(taskId)
                        } finally {
                            finish()
                        }
                    }
                },
                onBack = { finish() },
                showBack = false
            )
        }
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

    private fun refreshSnapshot(coordinator: EnforcementCoordinator, taskId: String) {
        lifecycleScope.launch {
            try {
                coordinator.refreshAndReconcile()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                errorReporter.report(
                    action = BlockerAction.REFRESH_SNAPSHOT,
                    taskId = taskId,
                    message = error.message ?: "snapshot refresh failed"
                )
            }
        }
    }
}
