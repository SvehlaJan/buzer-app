package app.buzer.android.blockers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.buzer.android.enforcement.AlarmIntentFactory
import app.buzer.android.enforcement.AlarmKind
import app.buzer.android.enforcement.AndroidAlarmScheduler
import app.buzer.android.enforcement.ScheduledAlarm
import app.buzer.android.ui.screens.StartBlockerScreen

class StartBlockerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureBlockerWindow()
        val taskId = intent.getStringExtra(AlarmIntentFactory.EXTRA_TASK_ID)
        val scheduler = AndroidAlarmScheduler(applicationContext)

        setContent {
            StartBlockerScreen(
                onStart = { finish() },
                onPostpone = {
                    if (taskId != null) {
                        scheduler.schedule(
                            ScheduledAlarm(
                                taskId = taskId,
                                kind = AlarmKind.START_BLOCKER,
                                triggerAtEpochMillis = System.currentTimeMillis() + FIVE_MINUTES_MILLIS
                            )
                        )
                    }
                    finish()
                },
                onBack = { finish() },
                showBack = false
            )
        }
    }

    private companion object {
        const val FIVE_MINUTES_MILLIS = 5 * 60 * 1000L
    }
}
