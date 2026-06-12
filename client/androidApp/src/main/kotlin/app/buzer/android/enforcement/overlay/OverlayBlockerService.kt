package app.buzer.android.enforcement.overlay

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.buzer.android.blockers.DeadlineBlockerActivity
import app.buzer.android.enforcement.AlarmIntentFactory
import app.buzer.android.enforcement.EnforcementCoordinator
import app.buzer.android.enforcement.EnforcementDependencies
import app.buzer.android.enforcement.NotificationChannels
import app.buzer.shared.diagnostics.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that shows a blocker overlay above the current foreground app and routes
 * overlay actions to [EnforcementCoordinator]. Additive to the existing blocker activities and
 * full-screen notifications, which remain the fallback when overlay permission is unavailable.
 */
class OverlayBlockerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controller: BlockerOverlayController? = null
    private var actionInFlight = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        val request = intent?.let { OverlayBlockerRequest.fromIntent(it) }
        if (request == null) {
            AppLog.warn("overlay_service invalid_request")
            stopOverlay()
            return START_NOT_STICKY
        }

        val coordinator = try {
            EnforcementDependencies.coordinator(applicationContext)
        } catch (error: Exception) {
            AppLog.error("overlay_service coordinator create failed", error)
            stopOverlay()
            return START_NOT_STICKY
        }

        val controller = BlockerOverlayController(this).also { this.controller = it }
        controller.show(request) { action -> handleAction(action, request, coordinator) }
        return START_NOT_STICKY
    }

    private fun handleAction(
        action: OverlayAction,
        request: OverlayBlockerRequest,
        coordinator: EnforcementCoordinator
    ) {
        AppLog.info("overlay_action action=$action kind=${request.kind} taskId=${request.taskId}")
        if (action == OverlayAction.OPEN_FULL) {
            launchFullBlocker(request)
            stopOverlay()
            return
        }
        if (actionInFlight) {
            AppLog.debug("overlay_action ignored reason=in_flight")
            return
        }
        actionInFlight = true
        scope.launch {
            try {
                when (action) {
                    OverlayAction.START -> coordinator.startTask(request.taskId)
                    OverlayAction.POSTPONE -> coordinator.postponeStart(request.taskId)
                    OverlayAction.COMPLETE -> coordinator.completeTask(request.taskId)
                    OverlayAction.EXTEND -> coordinator.extendTask(request.taskId)
                    OverlayAction.DISMISS -> coordinator.dismissMorningReview()
                    OverlayAction.OPEN_FULL -> Unit
                }
            } finally {
                // Backend failures are reported inside the coordinator; either way, dismiss.
                stopOverlay()
            }
        }
    }

    private fun launchFullBlocker(request: OverlayBlockerRequest) {
        val intent = Intent(this, DeadlineBlockerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(AlarmIntentFactory.EXTRA_TASK_ID, request.taskId)
        }
        runCatching { startActivity(intent) }
            .onFailure { AppLog.warn("overlay_service open_full failed ${it.message}") }
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

    private fun stopOverlay() {
        controller?.remove()
        controller = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        controller?.remove()
        controller = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 4711

        fun intent(context: Context, request: OverlayBlockerRequest): Intent =
            request.writeTo(Intent(context, OverlayBlockerService::class.java))
    }
}

/** Starts [OverlayBlockerService] when overlay permission is granted; returns false otherwise. */
object OverlayBlockerServiceStarter {
    fun tryStart(context: Context, request: OverlayBlockerRequest): Boolean {
        if (!OverlayPermission.canDraw(context)) {
            AppLog.warn("overlay_start skipped reason=no_permission kind=${request.kind}")
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
            AppLog.warn("overlay_start failed kind=${request.kind} ${error.message}")
            false
        }
    }
}
