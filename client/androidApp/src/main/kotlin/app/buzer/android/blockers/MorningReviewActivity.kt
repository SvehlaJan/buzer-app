package app.buzer.android.blockers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import app.buzer.android.enforcement.AlarmKind
import app.buzer.android.enforcement.AndroidAlarmScheduler
import app.buzer.android.enforcement.BlockerAction
import app.buzer.android.enforcement.BlockerErrorReporter
import app.buzer.android.enforcement.EnforcementDependencies
import app.buzer.android.enforcement.ScheduledAlarm
import app.buzer.android.ui.screens.MorningReviewScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MorningReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureBlockerWindow()
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

        if (coordinator != null) {
            lifecycleScope.launch {
                try {
                    coordinator.refreshAndReconcile()
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
                onResolveSelected = { finish() },
                onSnooze = {
                    scheduler.schedule(
                        ScheduledAlarm(
                            taskId = MORNING_REVIEW_TASK_ID,
                            kind = AlarmKind.MORNING_REVIEW,
                            triggerAtEpochMillis = System.currentTimeMillis() + FIVE_MINUTES_MILLIS
                        )
                    )
                    finish()
                },
                onBack = { finish() },
                showBack = false
            )
        }
    }

    private companion object {
        const val MORNING_REVIEW_TASK_ID = "morning-review"
        const val FIVE_MINUTES_MILLIS = 5 * 60 * 1000L
    }
}
