package app.buzer.android.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import app.buzer.android.ui.screens.HomeScreenState
import app.buzer.android.ui.screens.buildHomeScreenState
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.state.SnapshotViewState
import java.time.OffsetDateTime

private enum class AppSection {
    Home,
    Add
}

@Composable
fun BuzerApp(
    openAddTask: Boolean = false,
    snapshot: SnapshotViewState? = null,
    homeRefreshInFlight: Boolean = false,
    homeRefreshError: String? = null,
    onRetryHomeRefresh: () -> Unit = {},
    onDoneCurrentTask: (String) -> Unit = {},
    onTaskCreated: () -> Unit = {},
    nowProvider: () -> OffsetDateTime = { OffsetDateTime.now() }
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        val context = LocalContext.current
        val initialSection = if (openAddTask) AppSection.Add else AppSection.Home
        var section by remember { mutableStateOf(initialSection) }
        fun openMorningReview() {
            AppLog.info("ui navigate section=Review source=home mode=activity")
            context.startActivity(Intent(context, MorningReviewActivity::class.java))
        }

        val homeState = buildBuzerHomeState(
            snapshot = snapshot,
            homeRefreshInFlight = homeRefreshInFlight,
            homeRefreshError = homeRefreshError,
            now = nowProvider()
        )
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
                    onDoneCurrentTask = onDoneCurrentTask,
                    onRetryRefresh = onRetryHomeRefresh,
                    state = homeState
                )

                AppSection.Add -> AddTaskScreen(
                    onBack = { section = AppSection.Home },
                    onTaskCreated = onTaskCreated
                )
            }
        }
    }
}

internal fun buildBuzerHomeState(
    snapshot: SnapshotViewState?,
    homeRefreshInFlight: Boolean,
    homeRefreshError: String?,
    now: OffsetDateTime
): HomeScreenState =
    buildHomeScreenState(
        snapshot = snapshot,
        refreshInFlight = homeRefreshInFlight,
        refreshError = homeRefreshError,
        now = now
    )
