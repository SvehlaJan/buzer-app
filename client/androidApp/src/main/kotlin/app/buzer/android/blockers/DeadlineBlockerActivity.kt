package app.buzer.android.blockers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.buzer.android.enforcement.AlarmIntentFactory
import app.buzer.android.enforcement.AlarmKind
import app.buzer.android.enforcement.AndroidAlarmScheduler
import app.buzer.android.enforcement.ScheduledAlarm
import app.buzer.android.ui.screens.DeadlineBlockerScreen

class DeadlineBlockerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureBlockerWindow()
        val taskId = intent.getStringExtra(AlarmIntentFactory.EXTRA_TASK_ID)
        val scheduler = AndroidAlarmScheduler(applicationContext)

        setContent {
            DeadlineBlockerScreen(
                onDone = {
                    if (taskId != null) scheduler.cancelAllForTask(taskId)
                    finish()
                },
                onExtend = {
                    if (taskId != null) {
                        scheduler.schedule(
                            ScheduledAlarm(
                                taskId = taskId,
                                kind = AlarmKind.DEADLINE_BLOCKER,
                                triggerAtEpochMillis = System.currentTimeMillis() + THIRTY_MINUTES_MILLIS
                            )
                        )
                    }
                    finish()
                },
                onReschedule = { finish() },
                onBack = { finish() },
                showBack = false
            )
        }
    }

    private companion object {
        const val THIRTY_MINUTES_MILLIS = 30 * 60 * 1000L
    }
}
