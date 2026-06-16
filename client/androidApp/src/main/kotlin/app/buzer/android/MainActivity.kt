package app.buzer.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.buzer.android.enforcement.BlockerAction
import app.buzer.android.enforcement.BlockerErrorReporter
import app.buzer.android.enforcement.EnforcementCoordinator
import app.buzer.android.enforcement.EnforcementDependencies
import app.buzer.android.enforcement.EnforcementRefreshRunner
import app.buzer.android.enforcement.NotificationChannels
import app.buzer.android.ui.BuzerApp
import app.buzer.android.widget.AddTaskWidgetProvider
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.state.SnapshotReducer
import app.buzer.shared.state.SnapshotViewState

class MainActivity : ComponentActivity() {
    private var coordinator: EnforcementCoordinator? = null
    private var snapshotViewState by mutableStateOf<SnapshotViewState?>(null)
    private var homeRefreshInFlight by mutableStateOf(false)
    private var homeRefreshError by mutableStateOf<String?>(null)
    private val refreshRunner: EnforcementRefreshRunner by lazy {
        EnforcementRefreshRunner(lifecycleScope, ::reportRefreshFailure, AppLog::debug)
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
        refreshEnforcement()
        val openAddTask = intent.getBooleanExtra(AddTaskWidgetProvider.EXTRA_OPEN_ADD_TASK, false)
        setContent {
            BuzerApp(
                openAddTask = openAddTask,
                snapshot = snapshotViewState,
                homeRefreshInFlight = homeRefreshInFlight,
                homeRefreshError = homeRefreshError,
                onRetryHomeRefresh = ::refreshEnforcement,
                onTaskCreated = ::refreshEnforcement
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshEnforcement()
    }

    private fun refreshEnforcement() {
        val coordinator = coordinator ?: run {
            AppLog.debug("main refresh skipped reason=no_coordinator")
            homeRefreshInFlight = false
            homeRefreshError = "Refresh failed"
            return
        }
        AppLog.debug("main refresh requested")
        refreshRunner.refresh(
            onStarted = {
                homeRefreshInFlight = true
                homeRefreshError = null
            }
        ) {
            try {
                val snapshot = coordinator.refreshAndReconcile()
                snapshotViewState = SnapshotReducer.reduce(snapshot)
                homeRefreshError = null
                AppLog.debug(
                    "main snapshot state updated invalid=${snapshotViewState?.invalidTasks?.size ?: 0} " +
                        "startingToday=${snapshotViewState?.startingToday?.size ?: 0}"
                )
            } finally {
                homeRefreshInFlight = false
            }
        }
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
}
