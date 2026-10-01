package app.buzer.android

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import app.buzer.android.enforcement.BlockerAction
import app.buzer.android.enforcement.BlockerErrorReporter
import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.enforcement.EnforcementCoordinator
import app.buzer.android.enforcement.EnforcementDependencies
import app.buzer.android.enforcement.NotificationChannels
import app.buzer.android.blockers.MorningReviewActivity
import app.buzer.android.morningreview.MorningReviewAvailability
import app.buzer.android.morningreview.MorningReviewBoundaryTiming
import app.buzer.android.morningreview.MorningReviewContract
import app.buzer.android.morningreview.MorningReviewDependencies
import app.buzer.android.morningreview.MorningReviewRuntimeClock
import app.buzer.android.morningreview.MorningReviewReconcileGuard
import app.buzer.android.morningreview.MorningReviewEntryController
import app.buzer.android.morningreview.MorningReviewLocalStateStore
import app.buzer.android.morningreview.MorningReviewScheduleCalculator
import app.buzer.android.readiness.AndroidEnforcementReadinessChecker
import app.buzer.android.readiness.ForegroundReadinessAction
import app.buzer.android.readiness.ForegroundReadinessController
import app.buzer.android.readiness.ForegroundReadinessGateState
import app.buzer.android.readiness.ReadinessSettingsIntentFactory
import app.buzer.android.readiness.SharedPreferencesNotificationPermissionRequestHistory
import app.buzer.android.ui.BuzerApp
import app.buzer.android.ui.HomeDoneActionHandler
import app.buzer.android.ui.agenda.selectOwnerAgenda
import app.buzer.android.ui.navigation.ExternalTodoistLauncher
import app.buzer.android.ui.screens.ForegroundReadinessGate
import app.buzer.android.ui.status.setBuzerContent
import app.buzer.android.observability.ObservabilityRuntime
import app.buzer.android.widget.AddTaskWidgetProvider
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.TaskActionRequest
import app.buzer.shared.model.UpdateTaskRequest
import app.buzer.shared.model.FocusedChecklistMutationRequest
import app.buzer.shared.state.SnapshotReducer
import app.buzer.shared.state.SnapshotViewState
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

class MainActivity : ComponentActivity() {
    private var coordinator: EnforcementCoordinator? = null
    private var snapshotViewState by mutableStateOf<SnapshotViewState?>(null)
    private var homeRefreshInFlight by mutableStateOf(false)
    private var homeInteractionInFlight by mutableStateOf(false)
    private var homeRefreshError by mutableStateOf<String?>(null)
    private var homeActionStatusMessage by mutableStateOf<String?>(null)
    private var morningReviewAvailability by mutableStateOf<MorningReviewAvailability?>(null)
    private val morningReviewEntryController = MorningReviewEntryController()
    private var morningReviewReevaluationJob: Job? = null
    private val morningReviewStateStore by lazy { MorningReviewLocalStateStore.fromContext(applicationContext) }
    private val morningReviewCalculator = MorningReviewScheduleCalculator()
    private val morningReviewScheduler by lazy { MorningReviewDependencies.scheduler(applicationContext) }
    private var foregroundReadinessState by mutableStateOf<ForegroundReadinessGateState>(
        ForegroundReadinessGateState.Error("Device readiness has not been checked")
    )
    private val readinessController by lazy {
        ForegroundReadinessController(
            checker = AndroidEnforcementReadinessChecker(applicationContext),
            requestHistory = SharedPreferencesNotificationPermissionRequestHistory(applicationContext),
            reportError = { marker, error -> AppLog.error("$marker foreground readiness", error) }
        )
    }
    private val readinessSettingsIntentFactory by lazy {
        ReadinessSettingsIntentFactory(packageName)
    }
    private val morningReviewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        applyReadinessState(readinessController.refresh())
        if (morningReviewEntryController.onResult()) refreshMorningReviewAvailability()
    }
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        applyReadinessState(readinessController.onExternalResult())
    }
    private val readinessSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        applyReadinessState(readinessController.onExternalResult())
    }
    private val homeActionHandler: HomeDoneActionHandler by lazy {
        HomeDoneActionHandler(
            refreshSnapshot = { forceTodoist ->
                val coordinator = requireCoordinator("snapshot refresh")
                val snapshot = if (forceTodoist) {
                    coordinator.forceSyncAndReconcile()
                } else {
                    coordinator.refreshAndReconcile()
                }
                SnapshotReducer.reduce(snapshot)
            },
            completeTask = { taskId, request ->
                val coordinator = coordinator
                    ?: return@HomeDoneActionHandler CoordinatorActionResult.Warning("unavailable", "Task completion failed", null)
                coordinator.completeTask(taskId, request)
            },
            latestSnapshot = {
                coordinator?.loadLatestSnapshot()?.let(SnapshotReducer::reduce)
            },
            applySnapshot = {
                snapshotViewState = it
                refreshMorningReviewAvailability()
            },
            setStatusMessage = { homeActionStatusMessage = it },
            setRefreshInFlight = { homeRefreshInFlight = it },
            setInteractionInFlight = { homeInteractionInFlight = it },
            clearRefreshFailure = { homeRefreshError = null },
            reportRefreshFailure = ::reportRefreshFailure
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationChannels.ensureCreated(this)
        foregroundReadinessState = readinessController.refresh()
        coordinator = runCatching { EnforcementDependencies.coordinator(applicationContext) }
            .onFailure {
                AppLog.error("main coordinator create failed", it)
                BlockerErrorReporter(applicationContext).report(
                    action = BlockerAction.REFRESH_SNAPSHOT,
                    taskId = null,
                    message = it.message ?: "enforcement configuration failed",
                    throwable = it
                )
            }
            .getOrNull()
        refreshEnforcement(forceTodoist = false)
        refreshMorningReviewAvailability()
        val openAddTask = intent.getBooleanExtra(AddTaskWidgetProvider.EXTRA_OPEN_ADD_TASK, false)
        setBuzerContent {
            Box(modifier = Modifier.fillMaxSize()) {
                BuzerApp(
                    openAddTask = openAddTask,
                    snapshot = snapshotViewState,
                    homeRefreshInFlight = homeRefreshInFlight,
                    homeInteractionInFlight = homeInteractionInFlight,
                    homeRefreshError = homeRefreshError,
                    homeActionStatusMessage = homeActionStatusMessage,
                    onRetryHomeRefresh = ::forceRefreshEnforcement,
                    onPullToRefresh = ::forceRefreshEnforcement,
                    onOpenTodoist = { ExternalTodoistLauncher.launchToday(this@MainActivity) },
                    onDoneTask = ::completeHomeTask,
                    onDoneCurrentTask = ::completeHomeTask,
                    onTaskCreated = { returnedSnapshot ->
                        val applied = coordinator?.applyReturnedSnapshot(returnedSnapshot) ?: returnedSnapshot
                        snapshotViewState = SnapshotReducer.reduce(applied)
                        refreshMorningReviewAvailability()
                    },
                    onToggleCurrentChild = ::toggleCurrentChild,
                    deviceId = EnforcementDependencies.deviceIdentityStore(applicationContext).deviceId(),
                    onUpdateTask = ::updateTask,
                    onRefreshWarningSnapshot = ::refreshWarningSnapshot,
                    morningReviewBannerVisible = morningReviewAvailability?.bannerVisible ?: false,
                    morningReviewBlockingCount = morningReviewBlockingCount(),
                    onOpenMorningReview = ::openHomeMorningReview,
                    onReportBug = { ObservabilityRuntime.instance.showFeedback() },
                    onScreenContextChanged = { context -> ObservabilityRuntime.instance.setScreenContext(context) }
                )
                ForegroundReadinessGate(
                    state = foregroundReadinessState,
                    onPrimaryAction = ::performForegroundReadinessAction,
                    onReportBug = { ObservabilityRuntime.instance.showFeedback() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        morningReviewEntryController.onResume()
        applyReadinessState(readinessController.refresh())
        refreshEnforcement(forceTodoist = false)
    }

    override fun onPause() {
        morningReviewEntryController.onPause()
        morningReviewReevaluationJob?.cancel()
        morningReviewReevaluationJob = null
        super.onPause()
    }

    private fun applyReadinessState(state: ForegroundReadinessGateState) {
        foregroundReadinessState = state
        if (state == ForegroundReadinessGateState.Ready) {
            refreshMorningReviewAvailability()
        } else {
            morningReviewReevaluationJob?.cancel()
            morningReviewReevaluationJob = null
        }
    }

    private fun performForegroundReadinessAction() {
        try {
            val action = readinessController.beginPrimaryAction() ?: return
            foregroundReadinessState = readinessController.state
            when (action) {
                ForegroundReadinessAction.RequestNotificationPermission ->
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                is ForegroundReadinessAction.OpenSettings -> {
                    val intent = readinessSettingsIntentFactory.create(action.destination)
                    if (intent.resolveActivity(packageManager) == null) {
                        throw ActivityNotFoundException("No activity resolves ${intent.action}")
                    }
                    readinessSettingsLauncher.launch(intent)
                }
                ForegroundReadinessAction.Retry ->
                    applyReadinessState(readinessController.refresh())
            }
        } catch (error: Exception) {
            applyReadinessState(readinessController.onLaunchFailure(error))
        }
    }

    private fun refreshEnforcement(forceTodoist: Boolean = false) {
        AppLog.debug("main refresh requested forceTodoist=$forceTodoist")
        lifecycleScope.launch {
            homeActionHandler.refresh(forceTodoist = forceTodoist)
            AppLog.debug(
                "main snapshot state updated invalid=${snapshotViewState?.invalidTasks?.size ?: 0} " +
                    "startingToday=${snapshotViewState?.startingToday?.size ?: 0}"
            )
        }
    }

    private fun forceRefreshEnforcement() {
        refreshEnforcement(forceTodoist = true)
    }

    private fun openHomeMorningReview() {
        if (foregroundReadinessState != ForegroundReadinessGateState.Ready) {
            morningReviewReevaluationJob?.cancel()
            morningReviewReevaluationJob = null
            return
        }
        if (!morningReviewEntryController.openHome()) return
        morningReviewLauncher.launch(
            MorningReviewContract.homeIntent(Intent(this, MorningReviewActivity::class.java))
        )
    }

    private fun refreshMorningReviewAvailability() {
        if (foregroundReadinessState != ForegroundReadinessGateState.Ready) {
            morningReviewReevaluationJob?.cancel()
            morningReviewReevaluationJob = null
            return
        }
        val clock = MorningReviewRuntimeClock.snapshot()
        val now = clock.instant
        val zoneId = clock.zoneId
        MorningReviewReconcileGuard.reconcile(applicationContext) { morningReviewScheduler.reconcile(now, zoneId) }
        val availability = morningReviewCalculator.availability(
            state = morningReviewStateStore.read(),
            ownerBlockingCount = morningReviewBlockingCount(),
            now = now,
            zoneId = zoneId
        )
        morningReviewAvailability = availability
        val decision = morningReviewEntryController.evaluate(availability)
        armMorningReviewReevaluation(availability)
        if (decision.launchAutomated) {
            morningReviewLauncher.launch(
                MorningReviewContract.automatedIntent(Intent(this, MorningReviewActivity::class.java))
            )
        }
    }

    private fun armMorningReviewReevaluation(availability: MorningReviewAvailability) {
        morningReviewReevaluationJob?.cancel()
        if (!morningReviewEntryController.isForeground) return
        val delayMillis = Duration.between(MorningReviewRuntimeClock.now(), availability.nextReevaluationAt).toMillis()
        morningReviewReevaluationJob = lifecycleScope.launch {
            when (val timing = MorningReviewBoundaryTiming.from(delayMillis)) {
                is MorningReviewBoundaryTiming.Delay -> delay(timing.milliseconds)
                MorningReviewBoundaryTiming.ReevaluateImmediately -> yield()
            }
            refreshMorningReviewAvailability()
        }
    }

    private fun morningReviewBlockingCount(): Int = snapshotViewState
        ?.let { selectOwnerAgenda(it, MorningReviewRuntimeClock.now(), MorningReviewRuntimeClock.zoneId()).blocking.size }
        ?: 0

    private fun reportRefreshFailure(error: Throwable) {
        AppLog.error("main refresh failed", error)
        homeRefreshError = error.message ?: "snapshot refresh failed"
        BlockerErrorReporter(applicationContext).report(
            action = BlockerAction.REFRESH_SNAPSHOT,
            taskId = null,
            message = error.message ?: "snapshot refresh failed",
            throwable = error
        )
    }

    private fun completeHomeTask(taskId: String) {
        AppLog.info("main current_done_clicked taskId=$taskId")
        val occurrenceKey = snapshotViewState
            ?.tasks
            ?.firstOrNull { it.id == taskId }
            ?.occurrenceKey
            ?.takeIf { it.isNotBlank() }
            ?: run {
                homeActionStatusMessage = "Task occurrence is no longer current"
                return
            }
        val request = TaskActionRequest(
            expectedOccurrenceKey = occurrenceKey
        )
        lifecycleScope.launch {
            homeActionHandler.completeTask(taskId, request)
        }
    }

    private suspend fun updateTask(taskId: String, request: UpdateTaskRequest): CoordinatorActionResult {
        val activeCoordinator = coordinator
            ?: return CoordinatorActionResult.Warning("unavailable", "Task editing is unavailable", null)
        val result = activeCoordinator.updateTask(taskId, request)
        activeCoordinator.loadLatestSnapshot()?.let(SnapshotReducer::reduce)?.let {
            snapshotViewState = it
            refreshMorningReviewAvailability()
        }
        return result
    }

    /**
     * The authoritative Refresh action behind a mutation-warning banner (Add/Edit). Throws on
     * failure so the caller keeps the warning visible; applies and reconciles on success only.
     */
    private suspend fun refreshWarningSnapshot() {
        val activeCoordinator = requireCoordinator("mutation warning refresh")
        snapshotViewState = SnapshotReducer.reduce(activeCoordinator.forceSyncAndReconcile())
        refreshMorningReviewAvailability()
    }

    private fun toggleCurrentChild(taskId: String, childId: String, checked: Boolean) {
        val task = snapshotViewState?.tasks?.firstOrNull { it.id == taskId } ?: return
        val child = task.checklistItems.firstOrNull { it.id == childId } ?: return
        val request = FocusedChecklistMutationRequest(
            expectedOccurrenceKey = task.occurrenceKey,
            expectedChildRevision = child.childRevision
        )
        lifecycleScope.launch {
            val activeCoordinator = coordinator ?: return@launch
            if (checked) activeCoordinator.checkChild(taskId, childId, request)
            else activeCoordinator.uncheckChild(taskId, childId, request)
            activeCoordinator.loadLatestSnapshot()?.let(SnapshotReducer::reduce)?.let {
                snapshotViewState = it
                refreshMorningReviewAvailability()
            }
        }
    }

    private fun requireCoordinator(action: String): EnforcementCoordinator =
        coordinator ?: run {
            AppLog.debug("main refresh skipped reason=no_coordinator action=$action")
            throw IllegalStateException("Refresh failed")
        }
}
