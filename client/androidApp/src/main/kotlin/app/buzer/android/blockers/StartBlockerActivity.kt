package app.buzer.android.blockers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.buzer.android.enforcement.AlarmIntentFactory
import app.buzer.android.enforcement.AlarmKind
import app.buzer.android.enforcement.BlockerAction
import app.buzer.android.enforcement.BlockerErrorReporter
import app.buzer.android.enforcement.EnforcementCoordinator
import app.buzer.android.enforcement.EnforcementDependencies
import app.buzer.android.ui.navigation.ExternalTodoistLauncher
import app.buzer.android.ui.screens.StartBlockerScreen
import app.buzer.android.ui.status.setBuzerContent
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.TaskActionRequest
import app.buzer.shared.ui.blockers.BlockerKind
import app.buzer.shared.ui.blockers.buildBlockerUiModel
import java.time.ZoneId
import kotlinx.coroutines.launch

class StartBlockerActivity : ComponentActivity() {
    private lateinit var errorReporter: BlockerErrorReporter
    private var host: BlockerHostController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureBlockerWindow()
        errorReporter = BlockerErrorReporter(applicationContext)
        val target = readTarget(intent) ?: return
        val coordinator = createCoordinator() ?: return
        val host = BlockerHostController(
            kind = AlarmKind.START_BLOCKER,
            source = coordinator.asBlockerSnapshotSource(),
            errorReporter = errorReporter,
            scope = lifecycleScope,
            onFinish = ::finish
        ).also { this.host = it }
        host.bindTarget(target.taskId, target.occurrenceKey, target.initialWarningMessage)
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
        val target = readTarget(intent) ?: return
        AppLog.debug("start_blocker rebind taskId=${target.taskId} occurrence=${target.occurrenceKey}")
        host.bindTarget(target.taskId, target.occurrenceKey, target.initialWarningMessage)
        host.refresh(explicitUserRefresh = false)
    }

    private fun readTarget(intent: Intent): BlockerTarget? {
        val taskId = intent.getStringExtra(AlarmIntentFactory.EXTRA_TASK_ID)
        val occurrenceKey = intent.getStringExtra(AlarmIntentFactory.EXTRA_OCCURRENCE_KEY)
        if (taskId == null || occurrenceKey.isNullOrBlank()) {
            AppLog.debug("start_blocker missing_alarm_identity")
            errorReporter.report(
                action = BlockerAction.START_TASK,
                taskId = null,
                message = "Start blocker missing task ID"
            )
            finish()
            return null
        }
        return BlockerTarget(taskId, occurrenceKey, intent.getStringExtra(EXTRA_INITIAL_WARNING_MESSAGE))
    }

    private fun createCoordinator(): EnforcementCoordinator? {
        return try {
            EnforcementDependencies.coordinator(applicationContext)
        } catch (error: Exception) {
            AppLog.error("start_blocker coordinator create failed", error)
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
            StartBlockerScreen(
                model = buildBlockerUiModel(
                    kind = BlockerKind.Start,
                    task = host.latestTask,
                    timeZoneId = ZoneId.systemDefault().id
                ),
                onStart = {
                    AppLog.info("start_blocker start_clicked taskId=$taskId occurrence=$occurrenceKey")
                    host.runMutation("Starting...") {
                        coordinator.startTask(taskId, TaskActionRequest(expectedOccurrenceKey = occurrenceKey))
                    }
                },
                onPostpone = {
                    AppLog.info("start_blocker postpone_clicked taskId=$taskId occurrence=$occurrenceKey")
                    host.runMutation("Postponing...") {
                        coordinator.postponeStart(taskId, TaskActionRequest(expectedOccurrenceKey = occurrenceKey))
                    }
                },
                onRefreshTask = { host.refresh(explicitUserRefresh = true) },
                onOpenTodoist = {
                    ExternalTodoistLauncher.launchTask(this, host.latestTask?.todoistTaskId ?: taskId)
                },
                onBack = { finish() },
                showBack = false,
                checking = host.checking,
                warningMessage = host.warningMessage,
                actionState = host.actionState
            )
        }
    }

    private data class BlockerTarget(
        val taskId: String,
        val occurrenceKey: String,
        val initialWarningMessage: String?
    )

    companion object {
        /** A warning shown in the sticky banner on open, for example after a failed overlay action. */
        const val EXTRA_INITIAL_WARNING_MESSAGE = "extra_initial_warning_message"

        fun createIntent(
            context: Context,
            taskId: String,
            occurrenceKey: String,
            initialWarningMessage: String? = null
        ): Intent = Intent(context, StartBlockerActivity::class.java)
            .putExtra(AlarmIntentFactory.EXTRA_TASK_ID, taskId)
            .putExtra(AlarmIntentFactory.EXTRA_OCCURRENCE_KEY, occurrenceKey)
            .apply { initialWarningMessage?.let { putExtra(EXTRA_INITIAL_WARNING_MESSAGE, it) } }
    }
}
