package app.buzer.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.buzer.android.enforcement.NotificationChannels
import app.buzer.android.enforcement.RescheduleAfterBoot
import app.buzer.android.ui.BuzerApp
import app.buzer.android.widget.AddTaskWidgetProvider

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationChannels.ensureCreated(this)
        val openAddTask = intent.getBooleanExtra(AddTaskWidgetProvider.EXTRA_OPEN_ADD_TASK, false)
        val bootRescheduleRequired = intent.getBooleanExtra(
            RescheduleAfterBoot.EXTRA_BOOT_RESCHEDULE_REQUIRED,
            false
        )
        setContent {
            BuzerApp(
                openAddTask = openAddTask,
                bootRescheduleRequired = bootRescheduleRequired
            )
        }
    }
}
