package app.buzer.android.blockers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.buzer.android.enforcement.AlarmKind
import app.buzer.android.enforcement.AndroidAlarmScheduler
import app.buzer.android.enforcement.ScheduledAlarm
import app.buzer.android.ui.screens.MorningReviewScreen

class MorningReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureBlockerWindow()
        val scheduler = AndroidAlarmScheduler(applicationContext)

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
