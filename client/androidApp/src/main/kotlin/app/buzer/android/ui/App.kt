package app.buzer.android.ui

import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import app.buzer.android.blockers.MorningReviewActivity
import app.buzer.android.ui.screens.AddTaskScreen
import app.buzer.android.ui.screens.HomeScreen
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.state.SnapshotViewState

private enum class AppSection {
    Home,
    Add
}

@Composable
fun BuzerApp(
    openAddTask: Boolean = false,
    snapshot: SnapshotViewState? = null,
    homeStatusMessage: String? = null,
    onTaskCreated: () -> Unit = {}
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val context = LocalContext.current
            val initialSection = if (openAddTask) AppSection.Add else AppSection.Home
            var section by remember { mutableStateOf(initialSection) }
            fun openMorningReview() {
                AppLog.info("ui navigate section=Review source=home mode=activity")
                context.startActivity(Intent(context, MorningReviewActivity::class.java))
            }

            Column(modifier = Modifier.fillMaxSize()) {
                when (section) {
                    AppSection.Home -> HomeScreen(
                        onAddTask = {
                            AppLog.debug("ui navigate section=Add source=home")
                            section = AppSection.Add
                        },
                        onMorningReview = {
                            openMorningReview()
                        },
                        state = app.buzer.android.ui.screens.buildHomeScreenState(snapshot)
                            .copy(statusMessage = homeStatusMessage)
                    )

                    AppSection.Add -> AddTaskScreen(
                        onBack = { section = AppSection.Home },
                        onTaskCreated = onTaskCreated
                    )
                }
            }
        }
    }
}
