package app.buzer.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import app.buzer.android.enforcement.BlockerAction
import app.buzer.android.enforcement.BlockerErrorReporter
import app.buzer.android.enforcement.EnforcementCoordinator
import app.buzer.android.enforcement.EnforcementDependencies
import app.buzer.android.enforcement.NotificationChannels
import app.buzer.android.enforcement.RescheduleAfterBoot
import app.buzer.android.ui.BuzerApp
import app.buzer.android.widget.AddTaskWidgetProvider
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var coordinator: EnforcementCoordinator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationChannels.ensureCreated(this)
        coordinator = runCatching { EnforcementDependencies.coordinator(applicationContext) }
            .onFailure {
                BlockerErrorReporter(applicationContext).report(
                    action = BlockerAction.REFRESH_SNAPSHOT,
                    taskId = null,
                    message = it.message ?: "enforcement configuration failed"
                )
            }
            .getOrNull()
        refreshEnforcement()
        val openAddTask = intent.getBooleanExtra(AddTaskWidgetProvider.EXTRA_OPEN_ADD_TASK, false)
        val bootRescheduleRequired = intent.getBooleanExtra(
            RescheduleAfterBoot.EXTRA_BOOT_RESCHEDULE_REQUIRED,
            false
        )
        setContent {
            BuzerApp(
                openAddTask = openAddTask,
                bootRescheduleRequired = bootRescheduleRequired
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshEnforcement()
    }

    private fun refreshEnforcement() {
        val coordinator = coordinator ?: return
        lifecycleScope.launch {
            runCatching { coordinator.refreshAndReconcile() }
                .onFailure {
                    BlockerErrorReporter(applicationContext).report(
                        action = BlockerAction.REFRESH_SNAPSHOT,
                        taskId = null,
                        message = it.message ?: "snapshot refresh failed"
                    )
                }
        }
    }
}
