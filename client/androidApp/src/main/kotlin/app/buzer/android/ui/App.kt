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
import app.buzer.android.ui.screens.DeadlineBlockerScreen
import app.buzer.android.ui.screens.HomeScreen
import app.buzer.android.ui.screens.MorningReviewScreen
import app.buzer.android.ui.screens.StartBlockerScreen
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.state.SnapshotViewState

private enum class AppSection {
    Home,
    Add,
    Review,
    Start,
    Deadline
}

@Composable
fun BuzerApp(
    openAddTask: Boolean = false,
    bootRescheduleRequired: Boolean = false,
    snapshot: SnapshotViewState? = null
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
                if (bootRescheduleRequired) {
                    HomeScreen(
                        onAddTask = {
                            AppLog.debug("ui navigate section=Add source=home bootRescheduleRequired=true")
                            section = AppSection.Add
                        },
                        onMorningReview = {
                            openMorningReview()
                        },
                        onStartBlocker = {
                            AppLog.debug("ui navigate section=Start source=home mode=static_compose_route")
                            section = AppSection.Start
                        },
                        onDeadlineBlocker = {
                            AppLog.debug("ui navigate section=Deadline source=home mode=static_compose_route")
                            section = AppSection.Deadline
                        },
                        state = app.buzer.android.ui.screens.buildHomeScreenState(snapshot)
                    )
                } else when (section) {
                    AppSection.Home -> HomeScreen(
                        onAddTask = {
                            AppLog.debug("ui navigate section=Add source=home")
                            section = AppSection.Add
                        },
                        onMorningReview = {
                            openMorningReview()
                        },
                        onStartBlocker = {
                            AppLog.debug("ui navigate section=Start source=home mode=static_compose_route")
                            section = AppSection.Start
                        },
                        onDeadlineBlocker = {
                            AppLog.debug("ui navigate section=Deadline source=home mode=static_compose_route")
                            section = AppSection.Deadline
                        },
                        state = app.buzer.android.ui.screens.buildHomeScreenState(snapshot)
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
