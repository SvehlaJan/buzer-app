package app.buzer.android.blockers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.buzer.android.enforcement.AlarmKind
import app.buzer.android.enforcement.AndroidAlarmScheduler
import app.buzer.android.enforcement.BlockerAction
import app.buzer.android.enforcement.BlockerErrorReporter
import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.enforcement.EnforcementDependencies
import app.buzer.android.enforcement.EnforcementCoordinator
import app.buzer.android.enforcement.ScheduledAlarm
import app.buzer.android.ui.screens.MorningReviewScreen
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.state.SnapshotReducer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MorningReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureBlockerWindow()
        var invalidTasks by mutableStateOf(emptyList<TaskSnapshotItem>())
        val errorReporter = BlockerErrorReporter(applicationContext)
        val scheduler = AndroidAlarmScheduler(applicationContext)
        val coordinator = try {
            EnforcementDependencies.coordinator(applicationContext)
        } catch (error: Exception) {
            errorReporter.report(
                action = BlockerAction.REFRESH_SNAPSHOT,
                taskId = null,
                message = error.message ?: "enforcement configuration failed"
            )
            null
        }
        coordinator?.loadLatestSnapshot()
            ?.let(SnapshotReducer::reduce)
            ?.invalidTasks
            ?.let { invalidTasks = it }

        if (coordinator != null) {
            lifecycleScope.launch {
                try {
                    val snapshot = coordinator.refreshAndReconcile()
                    invalidTasks = SnapshotReducer.reduce(snapshot).invalidTasks
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    errorReporter.report(
                        action = BlockerAction.REFRESH_SNAPSHOT,
                        taskId = null,
                        message = error.message ?: "snapshot refresh failed"
                    )
                }
            }
        }

        setContent {
            MorningReviewScreen(
                invalidTasks = invalidTasks,
                onRepair = { taskId, request ->
                    val currentCoordinator = coordinator
                    if (currentCoordinator == null) {
                        finish()
                    } else {
                        lifecycleScope.launch {
                            when (currentCoordinator.rescheduleTask(taskId, request)) {
                                CoordinatorActionResult.Success -> {
                                    invalidTasks = invalidTasks.filterNot { it.id == taskId }
                                    if (invalidTasks.isEmpty()) finish()
                                }
                                is CoordinatorActionResult.FailedAndHidden -> finish()
                            }
                        }
                    }
                },
                onSnooze = {
                    scheduler.schedule(
                        ScheduledAlarm(
                            taskId = EnforcementCoordinator.MORNING_REVIEW_TASK_ID,
                            kind = AlarmKind.MORNING_REVIEW,
                            triggerAtEpochMillis = System.currentTimeMillis() + FIVE_MINUTES_MILLIS
                        )
                    )
                    if (coordinator == null) {
                        finish()
                    } else {
                        lifecycleScope.launch {
                            coordinator.dismissMorningReview()
                            finish()
                        }
                    }
                },
                onBack = { finish() },
                showBack = false
            )
        }
    }

    private companion object {
        const val FIVE_MINUTES_MILLIS = 5 * 60 * 1000L
    }
}
