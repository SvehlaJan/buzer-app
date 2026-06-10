package app.buzer.android.enforcement.overlay

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.buzer.android.blockers.DeadlineBlockerActivity
import app.buzer.android.blockers.MorningReviewActivity
import app.buzer.android.blockers.StartBlockerActivity
import app.buzer.android.enforcement.AlarmIntentFactory
import app.buzer.android.enforcement.AlarmKind
import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.enforcement.EnforcementCoordinator
import app.buzer.android.enforcement.EnforcementDependencies
import app.buzer.android.enforcement.NotificationChannels
import app.buzer.shared.diagnostics.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class OverlayBlockerService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var controller: BlockerOverlayController
    private var currentRequest: OverlayBlockerRequest? = null
    private var actionInFlight = false

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureCreated(this)
        controller = BlockerOverlayController(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent?.let(OverlayBlockerRequest::fromIntent)
        if (request == null) {
            AppLog.warn("overlay_service stop reason=invalid_request")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        currentRequest = request
        startInForeground(request)
        showOverlay(request)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AppLog.info("overlay_service destroyed kind=${currentRequest?.kind} taskId=${currentRequest?.taskId}")
        controller.remove()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(request: OverlayBlockerRequest) {
        val notification = Notification.Builder(this, NotificationChannels.BLOCKERS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title(request.kind))
            .setContentText("Buzer blocker overlay is active.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun showOverlay(request: OverlayBlockerRequest) {
        try {
            controller.show(
                request = request,
                actions = BlockerOverlayController.Actions(
                    onStart = { runTaskAction("start") { it.startTask(requiredTaskId(request)) } },
                    onPostpone = { runTaskAction("postpone") { it.postponeStart(requiredTaskId(request)) } },
                    onDone = { runTaskAction("done") { it.completeTask(requiredTaskId(request)) } },
                    onExtend = { runTaskAction("extend") { it.extendTask(requiredTaskId(request)) } },
                    onOpenFullBlocker = { openFullBlocker(request) },
                    onDismissMorning = { runTaskAction("dismiss_morning") { it.dismissMorningReview() } }
                )
            )
            AppLog.info("overlay_service shown kind=${request.kind} taskId=${request.taskId}")
        } catch (error: RuntimeException) {
            AppLog.error("overlay_service show failed kind=${request.kind} taskId=${request.taskId}", error)
            stopSelf()
        }
    }

    private fun runTaskAction(actionName: String, action: suspend (EnforcementCoordinator) -> CoordinatorActionResult) {
        val request = currentRequest ?: return
        if (actionInFlight) {
            AppLog.debug("overlay_action ignored reason=in_flight action=$actionName")
            return
        }
        actionInFlight = true
        serviceScope.launch {
            try {
                AppLog.info("overlay_action start action=$actionName kind=${request.kind} taskId=${request.taskId}")
                val coordinator = EnforcementDependencies.coordinator(applicationContext)
                val result = withTimeoutOrNull(ACTION_TIMEOUT_MILLIS) {
                    action(coordinator)
                }
                if (result == null) {
                    AppLog.warn("overlay_action timeout action=$actionName taskId=${request.taskId}")
                } else {
                    when (result) {
                        CoordinatorActionResult.Success -> {
                            AppLog.info("overlay_action success action=$actionName taskId=${request.taskId}")
                        }
                        is CoordinatorActionResult.FailedAndHidden -> {
                            AppLog.warn(
                                "overlay_action failed_hidden action=$actionName taskId=${request.taskId} " +
                                    "message=${result.message}"
                            )
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                AppLog.error("overlay_action failed action=$actionName taskId=${request.taskId}", error)
            } finally {
                withContext(Dispatchers.Main) {
                    stopSelf()
                }
            }
        }
    }

    private fun openFullBlocker(request: OverlayBlockerRequest) {
        val targetClass = when (request.kind) {
            AlarmKind.MORNING_REVIEW -> MorningReviewActivity::class.java
            AlarmKind.START_BLOCKER -> StartBlockerActivity::class.java
            AlarmKind.DEADLINE_BLOCKER -> DeadlineBlockerActivity::class.java
            AlarmKind.PRE_START_WARNING -> return
        }
        val intent = Intent(this, targetClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            request.taskId?.let { putExtra(AlarmIntentFactory.EXTRA_TASK_ID, it) }
        }
        AppLog.info("overlay_open_full_blocker kind=${request.kind} taskId=${request.taskId}")
        startActivity(intent)
        stopSelf()
    }

    private fun requiredTaskId(request: OverlayBlockerRequest): String {
        return requireNotNull(request.taskId) { "task id required for ${request.kind}" }
    }

    private fun title(kind: AlarmKind): String {
        return when (kind) {
            AlarmKind.MORNING_REVIEW -> "Morning review"
            AlarmKind.START_BLOCKER -> "Start your task"
            AlarmKind.DEADLINE_BLOCKER -> "Task deadline"
            AlarmKind.PRE_START_WARNING -> "Task starts soon"
        }
    }

    private companion object {
        const val FOREGROUND_NOTIFICATION_ID = 42_503
        const val ACTION_TIMEOUT_MILLIS = 15_000L
    }
}
