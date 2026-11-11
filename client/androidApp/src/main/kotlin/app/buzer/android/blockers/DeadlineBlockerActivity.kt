package app.buzer.android.blockers

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.buzer.android.enforcement.AlarmIntentFactory
import app.buzer.android.enforcement.AlarmKind
import app.buzer.android.enforcement.BlockerAction
import app.buzer.android.enforcement.BlockerErrorReporter
import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.enforcement.EnforcementCoordinator
import app.buzer.android.enforcement.EnforcementDependencies
import app.buzer.android.ui.navigation.ExternalTodoistLauncher
import app.buzer.android.ui.screens.DeadlineBlockerScreen
import app.buzer.android.ui.status.setBuzerContent
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.ExtendTaskRequest
import app.buzer.shared.model.FocusedChecklistMutationRequest
import app.buzer.shared.model.TaskActionRequest
import app.buzer.shared.ui.blockers.BlockerKind
import app.buzer.shared.ui.blockers.buildBlockerUiModel
import java.time.ZoneId
import kotlinx.coroutines.launch

class DeadlineBlockerActivity : ComponentActivity() {
    private lateinit var errorReporter: BlockerErrorReporter
    private var host: BlockerHostController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureBlockerWindow()
        errorReporter = BlockerErrorReporter(applicationContext)
        val target = readTarget(intent) ?: return
        val coordinator = createCoordinator() ?: return
        val host = BlockerHostController(
            kind = AlarmKind.DEADLINE_BLOCKER,
            source = coordinator.asBlockerSnapshotSource(),
            errorReporter = errorReporter,
            scope = lifecycleScope,
            onFinish = ::finish
        ).also { this.host = it }
        host.bindTarget(target.first, target.second, initialWarningMessage = null)
        lifecycleScope.launch {
            // Render only after the first refresh so a resolved blocker never flashes on screen.
            host.refresh(explicitUserRefresh = false).join()
            if (!isFinishing) showBlocker(coordinator, host)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val host = host ?: return
        val (taskId, occurrenceKey) = readTarget(intent) ?: return
        AppLog.debug("deadline_blocker rebind taskId=$taskId occurrence=$occurrenceKey")
        host.bindTarget(taskId, occurrenceKey, initialWarningMessage = null)
        host.refresh(explicitUserRefresh = false)
    }

    private fun readTarget(intent: Intent): Pair<String, String>? {
        val taskId = intent.getStringExtra(AlarmIntentFactory.EXTRA_TASK_ID)
        val occurrenceKey = intent.getStringExtra(AlarmIntentFactory.EXTRA_OCCURRENCE_KEY)
        if (taskId == null || occurrenceKey.isNullOrBlank()) {
            AppLog.debug("deadline_blocker missing_alarm_identity")
            errorReporter.report(
                action = BlockerAction.COMPLETE_TASK,
                taskId = null,
                message = "Deadline blocker missing task ID"
            )
            finish()
            return null
        }
        return taskId to occurrenceKey
    }

    private fun createCoordinator(): EnforcementCoordinator? {
        return try {
            EnforcementDependencies.coordinator(applicationContext)
        } catch (error: Exception) {
            AppLog.error("deadline_blocker coordinator create failed", error)
            errorReporter.report(
                action = BlockerAction.REFRESH_SNAPSHOT,
                taskId = null,
                message = error.message ?: "enforcement configuration failed",
                throwable = error
            )
            finish()
            null
        }
    }

    private fun showBlocker(coordinator: EnforcementCoordinator, host: BlockerHostController) {
        setBuzerContent {
            val taskId = host.taskId.orEmpty()
            val occurrenceKey = host.occurrenceKey.orEmpty()
            DeadlineBlockerScreen(
                model = buildBlockerUiModel(
                    kind = BlockerKind.Deadline,
                    task = host.latestTask,
                    timeZoneId = ZoneId.systemDefault().id
                ),
                onDone = {
                    AppLog.info("deadline_blocker done_clicked taskId=$taskId occurrence=$occurrenceKey")
                    host.runMutation("Completing...") {
                        coordinator.completeTask(taskId, TaskActionRequest(expectedOccurrenceKey = occurrenceKey))
                    }
                },
                onExtend = {
                    AppLog.info("deadline_blocker extend_clicked taskId=$taskId occurrence=$occurrenceKey")
                    val expectedTaskRevision = host.currentTask?.taskRevision
                    if (expectedTaskRevision.isNullOrBlank()) {
                        host.warningMessage = "Task details are unavailable. Refresh and try again."
                    } else {
                        host.runMutation("Extending...") {
                            coordinator.extendTask(
                                taskId,
                                ExtendTaskRequest(
                                    expectedOccurrenceKey = occurrenceKey,
                                    expectedTaskRevision = expectedTaskRevision
                                )
                            )
                        }
                    }
                },
                onToggleChild = { child, checked ->
                    val request = FocusedChecklistMutationRequest(
                        expectedOccurrenceKey = occurrenceKey,
                        expectedChildRevision = child.childRevision
                    )
                    host.runMutation(
                        loadingLabel = if (checked) "Checking..." else "Unchecking...",
                        closeOnSuccess = false
                    ) {
                        if (checked) coordinator.checkChild(taskId, child.id, request)
                        else coordinator.uncheckChild(taskId, child.id, request)
                    }
                },
                onOpenTodoist = {
                    ExternalTodoistLauncher.launchTask(this, host.currentTask?.todoistTaskId ?: taskId)
                },
                task = host.currentTask,
                onUpdate = { request ->
                    if (host.currentTask == null) {
                        CoordinatorActionResult.Warning(
                            code = "task_unavailable",
                            message = "Task details are unavailable. Refresh and try again.",
                            snapshot = null
                        )
                    } else {
                        AppLog.info("deadline_blocker update_clicked taskId=$taskId occurrence=$occurrenceKey")
                        coordinator.updateTask(taskId, request)
                    }
                },
                onRefreshUpdateWarning = { host.forceSyncForEditor() },
                onUpdateSuccess = { finish() },
                onRefreshTask = { host.refresh(explicitUserRefresh = true) },
                occurrenceKey = occurrenceKey,
                snapshotConfirmed = host.snapshotConfirmed,
                onBack = { finish() },
                showBack = false,
                checking = host.checking,
                warningMessage = host.warningMessage,
                actionState = host.actionState
            )
        }
    }
}
