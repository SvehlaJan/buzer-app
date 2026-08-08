package app.buzer.android.blockers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.buzer.android.enforcement.AndroidBlockerNotificationCleaner
import app.buzer.android.enforcement.BlockerAction
import app.buzer.android.enforcement.BlockerErrorReporter
import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.enforcement.EnforcementDependencies
import app.buzer.android.enforcement.EnforcementCoordinator
import app.buzer.android.enforcement.AlarmIntentFactory
import app.buzer.android.morningreview.MORNING_REVIEW_TASK_ID
import app.buzer.android.morningreview.MorningReviewContract
import app.buzer.android.morningreview.MorningReviewEntrySource
import app.buzer.android.morningreview.MorningReviewDependencies
import app.buzer.android.morningreview.MorningReviewResult
import app.buzer.android.ui.screens.MorningReviewContentState
import app.buzer.android.ui.screens.MorningReviewScreen
import app.buzer.android.ui.status.setBuzerContent
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.TaskActionRequest
import app.buzer.shared.model.UpdateTaskRequest
import app.buzer.shared.state.SnapshotReducer
import app.buzer.shared.state.SnapshotViewState
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MorningReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = MorningReviewContract.entrySource(intent)
        if (source == MorningReviewEntrySource.Automated) {
            configureBlockerWindow()
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit
            })
        }

        val errorReporter = BlockerErrorReporter(applicationContext)
        val scheduler = MorningReviewDependencies.scheduler(applicationContext)
        val notificationCleaner = AndroidBlockerNotificationCleaner(applicationContext)
        val zone = ZoneId.systemDefault()
        var snapshotViewState by mutableStateOf<SnapshotViewState?>(null)
        var refreshing by mutableStateOf(false)
        var refreshError by mutableStateOf<String?>(null)
        var actionError by mutableStateOf<String?>(null)
        var actionInFlight by mutableStateOf(false)
        var retryAction by mutableStateOf<(() -> Unit)?>(null)

        val coordinator = try {
            EnforcementDependencies.coordinator(applicationContext)
        } catch (error: Exception) {
            val message = error.message ?: "enforcement configuration failed"
            refreshError = message
            AppLog.error("morning_review coordinator create failed", error)
            errorReporter.report(BlockerAction.REFRESH_SNAPSHOT, null, message)
            null
        }

        fun reloadCached(currentCoordinator: EnforcementCoordinator) {
            currentCoordinator.loadLatestSnapshot()?.let(SnapshotReducer::reduce)?.let { snapshotViewState = it }
        }

        try {
            coordinator?.let(::reloadCached)
        } catch (error: Exception) {
            val message = error.message ?: "local snapshot load failed"
            refreshError = message
            AppLog.error("morning_review cached snapshot load failed", error)
            errorReporter.report(BlockerAction.REFRESH_SNAPSHOT, null, message)
        }

        fun runForcedRefresh() {
            val currentCoordinator = coordinator ?: return
            if (refreshing || actionInFlight) return
            refreshing = true
            refreshError = null
            lifecycleScope.launch {
                try {
                    snapshotViewState = SnapshotReducer.reduce(currentCoordinator.forceSyncAndReconcile())
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val message = error.message ?: "snapshot refresh failed"
                    refreshError = message
                    AppLog.error("morning_review forced sync failed", error)
                    errorReporter.report(BlockerAction.REFRESH_SNAPSHOT, null, message)
                } finally {
                    refreshing = false
                }
            }
        }

        fun finishWith(result: MorningReviewResult) {
            setResult(RESULT_OK, MorningReviewContract.resultIntent(result))
            finish()
        }

        fun clearCurrentNotification() {
            intent.getStringExtra(AlarmIntentFactory.EXTRA_OCCURRENCE_KEY)
                ?.takeIf { it.isNotBlank() }
                ?.let { notificationCleaner.cancelForTask(MORNING_REVIEW_TASK_ID, it) }
        }

        fun runLocalAction(label: String, operation: () -> Unit, result: MorningReviewResult) {
            if (actionInFlight) return
            actionInFlight = true
            actionError = null
            retryAction = { runLocalAction(label, operation, result) }
            lifecycleScope.launch {
                try {
                    operation()
                    clearCurrentNotification()
                    finishWith(result)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    actionError = error.message ?: "$label failed"
                    AppLog.error("morning_review $label failed", error)
                } finally {
                    actionInFlight = false
                }
            }
        }

        fun acknowledge() = runLocalAction(
            label = "acknowledge",
            operation = { scheduler.acknowledge(Instant.now(), zone) },
            result = MorningReviewResult.Acknowledged
        )

        fun snooze() = runLocalAction(
            label = "snooze",
            operation = { scheduler.snooze(Instant.now(), zone) },
            result = MorningReviewResult.Snoozed
        )

        fun runOverdueDone(taskId: String) {
            val currentCoordinator = coordinator ?: run {
                actionError = "Task completion is unavailable"
                return
            }
            if (actionInFlight) return
            val task = snapshotViewState?.tasks?.firstOrNull { it.id == taskId }
            val occurrenceKey = task?.occurrenceKey?.takeIf { it.isNotBlank() } ?: run {
                actionError = "Task occurrence is no longer current"
                return
            }
            actionInFlight = true
            actionError = null
            retryAction = { runOverdueDone(taskId) }
            lifecycleScope.launch {
                try {
                    when (val result = currentCoordinator.completeTask(
                        taskId,
                        TaskActionRequest(UUID.randomUUID().toString(), EnforcementDependencies.deviceIdentityStore(applicationContext).deviceId(), occurrenceKey)
                    )) {
                        CoordinatorActionResult.Success -> reloadCached(currentCoordinator)
                        else -> actionError = result.messageOrNull() ?: "Task completion did not finish"
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    actionError = error.message ?: "Task completion failed"
                } finally {
                    actionInFlight = false
                }
            }
        }

        runForcedRefresh()
        setBuzerContent {
            MorningReviewScreen(
                invalidTasks = snapshotViewState?.invalidTasks.orEmpty(),
                onBack = {
                    setResult(RESULT_CANCELED, MorningReviewContract.resultIntent(MorningReviewResult.Cancelled))
                    finish()
                },
                onRepair = { taskId: String, request: UpdateTaskRequest ->
                    val currentCoordinator = coordinator
                        ?: return@MorningReviewScreen CoordinatorActionResult.FailedAndHidden("Task editing is unavailable")
                    if (actionInFlight) return@MorningReviewScreen CoordinatorActionResult.FailedAndHidden("Another action is in progress")
                    actionInFlight = true
                    try {
                        currentCoordinator.updateTask(taskId, request).also { result ->
                            if (result == CoordinatorActionResult.Success) reloadCached(currentCoordinator)
                        }
                    } finally {
                        actionInFlight = false
                    }
                },
                onRepairSuccess = { _ -> coordinator?.let(::reloadCached) },
                onStart = ::acknowledge,
                onSnooze = ::snooze,
                onOverdueDone = ::runOverdueDone,
                showBack = source == MorningReviewEntrySource.Home,
                contentState = when {
                    snapshotViewState == null && refreshing -> MorningReviewContentState.Loading
                    snapshotViewState == null && refreshError != null -> MorningReviewContentState.Error(refreshError!!)
                    else -> MorningReviewContentState.Ready(refreshing = refreshing)
                },
                snoozing = actionInFlight,
                actionErrorMessage = actionError,
                onDismissActionError = { actionError = null },
                onRetryAction = { actionError = null; retryAction?.invoke() },
                onRetryRefresh = ::runForcedRefresh
            )
        }
    }
}

private fun CoordinatorActionResult.messageOrNull(): String? = when (this) {
    is CoordinatorActionResult.FailedAndHidden -> message
    is CoordinatorActionResult.Stale -> message
    is CoordinatorActionResult.LocalReconciliationFailed -> message
    is CoordinatorActionResult.RetryAfter -> message
    is CoordinatorActionResult.Pending -> "Saved; waiting for Todoist confirmation"
    CoordinatorActionResult.ChildActionInFlight -> "Another action is in progress"
    CoordinatorActionResult.Success -> null
}
