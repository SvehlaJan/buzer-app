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
import app.buzer.android.ui.screens.buildMorningReviewContentState
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.RescheduleTaskRequest
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.state.SnapshotDiagnostics
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
        var initialRefreshError: String? = null
        val coordinator = try {
            EnforcementDependencies.coordinator(applicationContext)
        } catch (error: Exception) {
            AppLog.error("morning_review coordinator create failed", error)
            val message = error.message ?: "enforcement configuration failed"
            initialRefreshError = message
            errorReporter.report(
                action = BlockerAction.REFRESH_SNAPSHOT,
                taskId = null,
                message = message
            )
            null
        }
        var refreshing by mutableStateOf(coordinator != null)
        var hasLoadedOnce by mutableStateOf(false)
        var refreshError by mutableStateOf<String?>(initialRefreshError)
        var repairing by mutableStateOf(false)
        var snoozing by mutableStateOf(false)
        var actionErrorMessage by mutableStateOf<String?>(null)
        var retryAction by mutableStateOf<(() -> Unit)?>(null)

        try {
            coordinator?.loadLatestSnapshot()
                ?.let(SnapshotReducer::reduce)
                ?.invalidTasks
                ?.let {
                    invalidTasks = it
                    hasLoadedOnce = true
                    AppLog.debug("morning_review cached invalid=${it.size}")
                }
        } catch (error: Exception) {
            AppLog.error("morning_review cached snapshot load failed", error)
            refreshError = error.message ?: "local snapshot load failed"
            errorReporter.report(
                action = BlockerAction.REFRESH_SNAPSHOT,
                taskId = null,
                message = refreshError ?: "local snapshot load failed"
            )
        }
        if (coordinator == null) {
            refreshing = false
            AppLog.debug("morning_review opened without coordinator")
        }

        fun runRefresh(currentCoordinator: EnforcementCoordinator?) {
            if (currentCoordinator == null) {
                refreshError = refreshError ?: "enforcement configuration failed"
                refreshing = false
                return
            }
            refreshing = true
            refreshError = null
            lifecycleScope.launch {
                try {
                    val snapshot = currentCoordinator.refreshAndReconcile()
                    invalidTasks = SnapshotReducer.reduce(snapshot).invalidTasks
                    hasLoadedOnce = true
                    refreshError = null
                    AppLog.debug(
                        "morning_review refreshed invalid=${invalidTasks.size} ${SnapshotDiagnostics.describe(snapshot)}"
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val message = error.message ?: "snapshot refresh failed"
                    refreshError = message
                    AppLog.error("morning_review refresh failed", error)
                    errorReporter.report(
                        action = BlockerAction.REFRESH_SNAPSHOT,
                        taskId = null,
                        message = message
                    )
                } finally {
                    refreshing = false
                }
            }
        }

        fun runRepair(
            currentCoordinator: EnforcementCoordinator,
            taskId: String,
            request: RescheduleTaskRequest
        ) {
            repairing = true
            actionErrorMessage = null
            retryAction = { runRepair(currentCoordinator, taskId, request) }
            lifecycleScope.launch {
                try {
                    AppLog.info("morning_review repair start taskId=$taskId duration=${request.durationMinutes}")
                    when (val result = currentCoordinator.rescheduleTask(taskId, request)) {
                        CoordinatorActionResult.Success -> {
                            invalidTasks = invalidTasks.filterNot { it.id == taskId }
                            AppLog.info(
                                "morning_review repair success taskId=$taskId remainingInvalid=${invalidTasks.size}"
                            )
                            if (invalidTasks.isEmpty()) finish()
                        }
                        is CoordinatorActionResult.FailedAndHidden -> {
                            AppLog.warn("morning_review repair failed_hidden taskId=$taskId")
                            actionErrorMessage = result.message
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    actionErrorMessage = error.message ?: "Repair failed"
                } finally {
                    repairing = false
                }
            }
        }

        fun runSnooze(currentCoordinator: EnforcementCoordinator?) {
            snoozing = true
            actionErrorMessage = null
            retryAction = { runSnooze(currentCoordinator) }
            lifecycleScope.launch {
                try {
                    AppLog.info("morning_review snooze requested")
                    scheduler.schedule(
                        ScheduledAlarm(
                            taskId = EnforcementCoordinator.MORNING_REVIEW_TASK_ID,
                            kind = AlarmKind.MORNING_REVIEW,
                            triggerAtEpochMillis = System.currentTimeMillis() + FIVE_MINUTES_MILLIS
                        )
                    )
                    if (currentCoordinator == null) {
                        AppLog.debug("morning_review snooze finish reason=no_coordinator")
                        finish()
                    } else {
                        when (val result = currentCoordinator.dismissMorningReview()) {
                            CoordinatorActionResult.Success -> {
                                AppLog.info("morning_review snooze dismissed_on_backend")
                                finish()
                            }
                            is CoordinatorActionResult.FailedAndHidden -> {
                                AppLog.warn("morning_review snooze failed_hidden")
                                actionErrorMessage = result.message
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    actionErrorMessage = error.message ?: "Snooze failed"
                } finally {
                    snoozing = false
                }
            }
        }

        if (coordinator != null) {
            runRefresh(coordinator)
        }

        setContent {
            MorningReviewScreen(
                invalidTasks = invalidTasks,
                onRepair = { taskId, request ->
                    val currentCoordinator = coordinator
                    if (currentCoordinator == null) {
                        AppLog.debug("morning_review repair skipped reason=no_coordinator taskId=$taskId")
                        finish()
                    } else {
                        runRepair(currentCoordinator, taskId, request)
                    }
                },
                onSnooze = { runSnooze(coordinator) },
                onBack = { finish() },
                showBack = false,
                contentState = buildMorningReviewContentState(
                    invalidTaskCount = invalidTasks.size,
                    refreshing = refreshing,
                    refreshError = refreshError,
                    hasLoadedOnce = hasLoadedOnce
                ),
                repairing = repairing,
                snoozing = snoozing,
                actionErrorMessage = actionErrorMessage,
                onDismissActionError = { actionErrorMessage = null },
                onRetryAction = {
                    actionErrorMessage = null
                    retryAction?.invoke()
                },
                onRetryRefresh = { runRefresh(coordinator) }
            )
        }
    }

    private companion object {
        const val FIVE_MINUTES_MILLIS = 5 * 60 * 1000L
    }
}
