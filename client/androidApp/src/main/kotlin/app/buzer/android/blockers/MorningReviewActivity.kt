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
import app.buzer.android.morningreview.MorningReviewRuntimeClock
import app.buzer.android.morningreview.MorningReviewResult
import app.buzer.android.morningreview.MorningReviewSessionController
import app.buzer.android.morningreview.MorningReviewSessionState
import app.buzer.android.ui.screens.MorningReviewScreen
import app.buzer.android.ui.screens.buildMorningReviewScreenState
import app.buzer.android.ui.status.setBuzerContent
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.TaskActionRequest
import app.buzer.shared.model.UpdateTaskRequest
import app.buzer.shared.state.SnapshotReducer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val FORCED_SYNC_RETRY_DELAY_MILLIS = 30_000L

class MorningReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fun cancelHome() {
            setResult(RESULT_CANCELED, MorningReviewContract.resultIntent(MorningReviewResult.Cancelled))
            finish()
        }
        val source = MorningReviewContract.entrySource(intent)
        if (source == MorningReviewEntrySource.Automated) {
            configureBlockerWindow()
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit
            })
        } else {
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    cancelHome()
                }
            })
        }

        val errorReporter = BlockerErrorReporter(applicationContext)
        val scheduler = MorningReviewDependencies.scheduler(applicationContext)
        val notificationCleaner = AndroidBlockerNotificationCleaner(applicationContext)
        var sessionState by mutableStateOf(MorningReviewSessionState())
        val session = MorningReviewSessionController({ sessionState = it }, sessionState)

        val coordinator = try {
            EnforcementDependencies.coordinator(applicationContext)
        } catch (error: Exception) {
            val message = error.message ?: "enforcement configuration failed"
            session.forceSyncFailed(message)
            AppLog.error("morning_review coordinator create failed", error)
            errorReporter.report(BlockerAction.REFRESH_SNAPSHOT, null, message, throwable = error)
            null
        }

        fun reloadCached(currentCoordinator: EnforcementCoordinator) {
            session.cache(currentCoordinator.loadLatestSnapshot()?.let(SnapshotReducer::reduce))
        }

        try {
            coordinator?.let(::reloadCached)
        } catch (error: Exception) {
            val message = error.message ?: "local snapshot load failed"
            session.forceSyncFailed(message)
            AppLog.error("morning_review cached snapshot load failed", error)
            errorReporter.report(BlockerAction.REFRESH_SNAPSHOT, null, message, throwable = error)
        }

        // The 30 s retry absorbs Todoist's top-of-the-hour spike for the opening sync only; a
        // user-tapped Refresh passes 0 so its single retry is immediate.
        fun runForcedRefresh(retryDelayMillis: Long = FORCED_SYNC_RETRY_DELAY_MILLIS) {
            val currentCoordinator = coordinator ?: return
            lifecycleScope.launch {
                session.forceSyncWithRetry(retryDelayMillis) {
                    SnapshotReducer.reduce(currentCoordinator.forceSyncAndReconcile())
                }?.let { error ->
                    val message = error.message ?: "snapshot refresh failed"
                    AppLog.error("morning_review forced sync failed", error)
                    // The inline refresh warning already tells the owner; no system notification.
                    errorReporter.report(
                        BlockerAction.REFRESH_SNAPSHOT,
                        null,
                        message,
                        showNotification = false,
                        throwable = error
                    )
                }
            }
        }

        /**
         * The authoritative Refresh action behind the Repair sub-editor's own mutation-warning
         * banner. Throws on failure so the caller keeps the warning visible; applies and caches
         * the fresh snapshot on success only, distinct from the session-level [runForcedRefresh].
         */
        suspend fun refreshRepairWarningSnapshot() {
            val currentCoordinator = coordinator
                ?: throw IllegalStateException("Task editing is unavailable")
            session.cache(SnapshotReducer.reduce(currentCoordinator.forceSyncAndReconcile()))
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
            lifecycleScope.launch {
                session.runTerminalAction(
                    result = result,
                    operation = operation,
                    onFinish = {
                    clearCurrentNotification()
                    finishWith(result)
                    },
                    retryAction = { runLocalAction(label, operation, result) }
                )?.let { error ->
                    AppLog.error("morning_review $label failed", error)
                }
            }
        }

        fun acknowledge() = runLocalAction(
            label = "acknowledge",
            operation = { MorningReviewRuntimeClock.snapshot().let { scheduler.acknowledge(it.instant, it.zoneId) } },
            result = MorningReviewResult.Acknowledged
        )

        fun snooze() = runLocalAction(
            label = "snooze",
            operation = { MorningReviewRuntimeClock.snapshot().let { scheduler.snooze(it.instant, it.zoneId) } },
            result = MorningReviewResult.Snoozed
        )

        fun runOverdueDone(taskId: String) {
            val currentCoordinator = coordinator ?: run {
                session.localActionFailed("Task completion is unavailable") { runOverdueDone(taskId) }
                return
            }
            if (!session.beginAction()) return
            val task = sessionState.snapshot?.tasks?.firstOrNull { it.id == taskId }
            val occurrenceKey = task?.occurrenceKey?.takeIf { it.isNotBlank() } ?: run {
                session.localActionFailed("Task occurrence is no longer current") { runOverdueDone(taskId) }
                return
            }
            val request = TaskActionRequest(
                expectedOccurrenceKey = occurrenceKey
            )
            lifecycleScope.launch {
                try {
                    val execute: suspend () -> CoordinatorActionResult = { currentCoordinator.completeTask(taskId, request) }
                    session.handleOverdueResult(execute(), { reloadCached(currentCoordinator) }, execute)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val execute: suspend () -> CoordinatorActionResult = { currentCoordinator.completeTask(taskId, request) }
                    session.retryOverdue(error.message ?: "Task completion failed", { reloadCached(currentCoordinator) }, execute)
                }
            }
        }

        runForcedRefresh()
        setBuzerContent {
            val screenClock = MorningReviewRuntimeClock.snapshot()
            MorningReviewScreen(
                screenState = buildMorningReviewScreenState(
                    snapshot = sessionState.snapshot,
                    loading = sessionState.loading,
                    refreshInFlight = sessionState.refreshInFlight,
                    refreshError = sessionState.refreshError,
                    actionInFlight = sessionState.actionInFlight,
                    actionError = sessionState.actionError,
                    now = screenClock.instant, zoneId = screenClock.zoneId, showBack = source == MorningReviewEntrySource.Home
                ),
                taskLookup = { taskId -> sessionState.snapshot?.tasks?.firstOrNull { it.id == taskId } },
                onBack = {
                    cancelHome()
                },
                onRepair = { taskId: String, request: UpdateTaskRequest ->
                    val currentCoordinator = coordinator
                        ?: return@MorningReviewScreen CoordinatorActionResult.Warning("unavailable", "Task editing is unavailable", null)
                    if (!session.beginAction()) return@MorningReviewScreen CoordinatorActionResult.Warning("in_flight", "Another action is in progress", null)
                    try {
                        currentCoordinator.updateTask(taskId, request).also { result ->
                            session.handleRepairResult(result) { reloadCached(currentCoordinator) }
                        }
                    } finally {
                        session.finishAction()
                    }
                },
                onRepairSuccess = { _ -> coordinator?.let(::reloadCached) },
                onRefreshRepairWarning = ::refreshRepairWarningSnapshot,
                onStart = ::acknowledge,
                onSnooze = ::snooze,
                onOverdueDone = ::runOverdueDone,
                onOverdueEdit = {},
                onRetryRefresh = { runForcedRefresh(retryDelayMillis = 0L) },
                onOpenTodoist = { app.buzer.android.ui.navigation.ExternalTodoistLauncher.launchToday(this@MorningReviewActivity) }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
