package app.buzer.android.enforcement.overlay

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.buzer.android.blockers.StartBlockerActivity
import app.buzer.android.blockers.BlockerTaskSnapshotGate
import app.buzer.android.enforcement.AlarmIntentFactory
import app.buzer.android.enforcement.EnforcementCoordinator
import app.buzer.android.enforcement.EnforcementDependencies
import app.buzer.android.enforcement.NotificationChannels
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.TaskActionRequest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that shows a blocker overlay above the current foreground app and routes
 * overlay actions to [EnforcementCoordinator]. Additive to the existing blocker activities and
 * full-screen notifications, which remain the fallback when overlay permission is unavailable.
 */
class OverlayBlockerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val requestGenerations = OverlayRequestGeneration()
    private var controller: BlockerOverlayController? = null
    private var initialDisplayJob: Job? = null
    private var watcherJob: Job? = null
    private var actionInFlight = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        val generation = replaceCurrentPresentation()

        val request = intent?.let { OverlayBlockerRequest.fromIntent(it) }
        if (request == null) {
            AppLog.warn("overlay_service invalid_request")
            stopOverlay(generation)
            return START_NOT_STICKY
        }

        val coordinator = try {
            EnforcementDependencies.coordinator(applicationContext)
        } catch (error: Exception) {
            AppLog.error("overlay_service coordinator create failed", error)
            stopOverlay(generation)
            return START_NOT_STICKY
        }

        initialDisplayJob = scope.launch {
            val cachedSnapshot = coordinator.loadLatestSnapshot()
            val freshSnapshot = try {
                withTimeoutOrNull(INITIAL_REFRESH_TIMEOUT_MS) {
                    coordinator.refreshAndReconcile()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                AppLog.warn(
                    "overlay_initial refresh_failed taskId=${request.taskId} " +
                        "occurrence=${request.occurrenceKey} kind=${request.kind} ${error.message}"
                )
                null
            }
            if (!requestGenerations.isCurrent(generation)) return@launch
            when (val decision = OverlayInitialDisplayGate.decide(request, freshSnapshot, cachedSnapshot)) {
                OverlayInitialDisplayDecision.DismissStaleOrResolved -> {
                    AppLog.info(
                        "overlay_service dismissed reason=stale_or_resolved taskId=${request.taskId} " +
                            "occurrence=${request.occurrenceKey} kind=${request.kind}"
                    )
                    stopOverlay(generation)
                }

                is OverlayInitialDisplayDecision.Show -> {
                    if (decision.isOfflineFallback) {
                        AppLog.warn(
                            "overlay_initial offline_fallback taskId=${request.taskId} " +
                                "occurrence=${request.occurrenceKey} kind=${request.kind}"
                        )
                    }
                    showOverlay(request.withTask(decision.task), coordinator, generation)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun showOverlay(
        request: OverlayBlockerRequest,
        coordinator: EnforcementCoordinator,
        generation: Long
    ) {
        if (!requestGenerations.isCurrent(generation)) return
        app.buzer.android.observability.DiagnosticContextStore.instance.updateUi {
            it.copy(
                screen = "OverlayBlocker",
                blockerType = request.kind.name,
                appSection = "Enforcement"
            )
        }
        app.buzer.android.observability.DiagnosticContextStore.instance.updateCorrelation {
            it.copy(
                taskId = request.taskId,
                occurrenceKey = request.occurrenceKey
            )
        }
        app.buzer.android.observability.ObservabilityRuntime.instance.setContext(
            app.buzer.shared.diagnostics.DiagnosticCorrelation(
                screen = "OverlayBlocker",
                blockerType = request.kind.name,
                taskId = request.taskId,
                occurrenceKey = request.occurrenceKey,
                appSection = "Enforcement"
            )
        )
        val controller = BlockerOverlayController(this).also { this.controller = it }
        val shown = controller.show(request) { action -> handleAction(action, request, coordinator, generation) }
        if (!shown) {
            this.controller = null
            stopOverlay(generation)
            return
        }
        startResolutionWatcher(request, coordinator, generation)
    }

    private fun startResolutionWatcher(
        request: OverlayBlockerRequest,
        coordinator: EnforcementCoordinator,
        generation: Long
    ) {
        watcherJob?.cancel()
        watcherJob = scope.launch {
            // ~30s of fast polling for the common quick resolution, then a calm 10s cadence;
            // total lifetime ~8 minutes. Each poll is a full backend refresh, so cadence is cost.
            repeat(MAX_WATCH_POLLS) { attempt ->
                delay(if (attempt < WATCH_FAST_POLLS) WATCH_FAST_POLL_INTERVAL_MS else WATCH_SLOW_POLL_INTERVAL_MS)
                if (!requestGenerations.isCurrent(generation) || controller == null) return@launch
                try {
                    val snapshot = withTimeout(WATCH_REFRESH_TIMEOUT_MS) {
                        coordinator.refreshAndReconcile()
                    }
                    if (BlockerTaskSnapshotGate.isBlockerResolved(
                            snapshot,
                            request.taskId,
                            request.occurrenceKey,
                            request.kind
                        )) {
                        AppLog.info(
                            "overlay_watcher resolved kind=${request.kind} taskId=${request.taskId} occurrence=${request.occurrenceKey} " +
                                "attempt=${attempt + 1}"
                        )
                        stopOverlay(generation)
                        return@launch
                    }
                } catch (timeout: TimeoutCancellationException) {
                    AppLog.warn(
                        "overlay_watcher refresh_timeout kind=${request.kind} " +
                        "taskId=${request.taskId} occurrence=${request.occurrenceKey} attempt=${attempt + 1}"
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    AppLog.warn(
                        "overlay_watcher refresh_failed kind=${request.kind} " +
                        "taskId=${request.taskId} occurrence=${request.occurrenceKey} " +
                            "attempt=${attempt + 1} ${error.message}"
                    )
                }
            }
            AppLog.warn(
                "overlay_watcher stopped reason=max_polls kind=${request.kind} taskId=${request.taskId} " +
                    "occurrence=${request.occurrenceKey}"
            )
        }
    }

    private fun handleAction(
        action: OverlayAction,
        request: OverlayBlockerRequest,
        coordinator: EnforcementCoordinator,
        generation: Long
    ) {
        AppLog.info(
            "overlay_action action=$action kind=${request.kind} taskId=${request.taskId} " +
                "occurrence=${request.occurrenceKey}"
        )
        if (!requestGenerations.isCurrent(generation) || actionInFlight) {
            AppLog.debug("overlay_action ignored reason=in_flight occurrence=${request.occurrenceKey}")
            return
        }
        actionInFlight = true
        scope.launch {
            try {
                // Bound the action so a hung/slow backend can never trap the full-screen
                // overlay. The coordinator reports backend failures internally; either way
                // the overlay dismisses, matching the blocker activities' finish-regardless flow.
                withTimeout(ACTION_TIMEOUT_MS) {
                    when (action) {
                        OverlayAction.START -> coordinator.startTask(
                            request.taskId,
                            actionRequest(request.occurrenceKey)
                        )
                        OverlayAction.POSTPONE -> coordinator.postponeStart(
                            request.taskId,
                            actionRequest(request.occurrenceKey)
                        )
                        OverlayAction.OPEN_FULL_BLOCKER -> {
                            startActivity(
                                Intent(this@OverlayBlockerService, StartBlockerActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    .putExtra(AlarmIntentFactory.EXTRA_TASK_ID, request.taskId)
                                    .putExtra(AlarmIntentFactory.EXTRA_OCCURRENCE_KEY, request.occurrenceKey)
                            )
                        }
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                AppLog.warn(
                    "overlay_action timeout action=$action taskId=${request.taskId} " +
                        "occurrence=${request.occurrenceKey} afterMs=$ACTION_TIMEOUT_MS"
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                AppLog.warn("overlay_action error action=$action occurrence=${request.occurrenceKey} ${error.message}")
            } finally {
                stopOverlay(generation)
            }
        }
    }

    private fun startForegroundCompat() {
        NotificationChannels.ensureCreated(this)
        val notification = Notification.Builder(this, NotificationChannels.BLOCKERS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Commitment blocker active")
            .setContentText("Review your commitment now.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun actionRequest(occurrenceKey: String): TaskActionRequest = TaskActionRequest(
        actionId = UUID.randomUUID().toString(),
        deviceId = EnforcementDependencies.deviceIdentityStore(applicationContext).deviceId(),
        expectedOccurrenceKey = occurrenceKey
    )

    private fun replaceCurrentPresentation(): Long {
        val generation = requestGenerations.next()
        initialDisplayJob?.cancel()
        initialDisplayJob = null
        watcherJob?.cancel()
        watcherJob = null
        controller?.remove()
        controller = null
        actionInFlight = false
        return generation
    }

    private fun stopOverlay(generation: Long) {
        if (!requestGenerations.isCurrent(generation)) return
        initialDisplayJob?.cancel()
        initialDisplayJob = null
        watcherJob?.cancel()
        watcherJob = null
        controller?.remove()
        controller = null
        app.buzer.android.observability.DiagnosticContextStore.instance.updateUi {
            it.copy(
                screen = null,
                blockerType = null
            )
        }
        app.buzer.android.observability.DiagnosticContextStore.instance.updateCorrelation {
            it.copy(
                taskId = null,
                occurrenceKey = null
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        initialDisplayJob?.cancel()
        initialDisplayJob = null
        watcherJob?.cancel()
        watcherJob = null
        controller?.remove()
        controller = null
        app.buzer.android.observability.DiagnosticContextStore.instance.updateUi {
            it.copy(
                screen = null,
                blockerType = null
            )
        }
        app.buzer.android.observability.DiagnosticContextStore.instance.updateCorrelation {
            it.copy(
                taskId = null,
                occurrenceKey = null
            )
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_TIMEOUT_MS = 15_000L
        private const val INITIAL_REFRESH_TIMEOUT_MS = 5_000L
        private const val WATCH_FAST_POLL_INTERVAL_MS = 2_000L
        private const val WATCH_SLOW_POLL_INTERVAL_MS = 10_000L
        private const val WATCH_FAST_POLLS = 15
        private const val WATCH_REFRESH_TIMEOUT_MS = 8_000L
        private const val MAX_WATCH_POLLS = 60

        fun intent(context: Context, request: OverlayBlockerRequest): Intent =
            request.writeTo(Intent(context, OverlayBlockerService::class.java))
    }
}

/** Starts [OverlayBlockerService] when overlay permission is granted; returns false otherwise. */
object OverlayBlockerServiceStarter {
    fun tryStart(context: Context, request: OverlayBlockerRequest): Boolean {
        if (!OverlayPermission.canDraw(context)) {
            AppLog.warn(
                "overlay_start skipped reason=no_permission kind=${request.kind} " +
                    "occurrence=${request.occurrenceKey}"
            )
            return false
        }
        return try {
            val intent = OverlayBlockerService.intent(context, request)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (error: RuntimeException) {
            AppLog.warn(
                "overlay_start failed kind=${request.kind} occurrence=${request.occurrenceKey} ${error.message}"
            )
            false
        }
    }
}
