package app.buzer.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.buzer.android.ui.screens.AddTaskScreen
import app.buzer.android.ui.screens.DeadlineBlockerScreen
import app.buzer.android.ui.screens.HomeScreen
import app.buzer.android.ui.screens.MorningReviewScreen
import app.buzer.android.ui.screens.StartBlockerScreen

private enum class AppSection {
    Home,
    Add,
    Review,
    Start,
    Deadline
}

@Composable
fun BuzerApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            var section by remember { mutableStateOf(AppSection.Home) }

            Column(modifier = Modifier.fillMaxSize()) {
                when (section) {
                    AppSection.Home -> HomeScreen(
                        onAddTask = { section = AppSection.Add },
                        onMorningReview = { section = AppSection.Review },
                        onStartBlocker = { section = AppSection.Start },
                        onDeadlineBlocker = { section = AppSection.Deadline }
                    )

                    AppSection.Add -> AddTaskScreen(onBack = { section = AppSection.Home })
                    AppSection.Review -> MorningReviewScreen(onBack = { section = AppSection.Home })
                    AppSection.Start -> StartBlockerScreen(onBack = { section = AppSection.Home })
                    AppSection.Deadline -> DeadlineBlockerScreen(onBack = { section = AppSection.Home })
                }
            }
        }
    }
}
