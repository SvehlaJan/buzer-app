package app.buzer.android.ui.screens

import app.buzer.android.ui.status.BlockingErrorState
import app.buzer.android.ui.status.BlockingLoadingState
import app.buzer.android.ui.status.InlineLoadingStatus
import app.buzer.android.ui.status.WarningStatus
import app.buzer.shared.state.SnapshotViewState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class HomeInProgressTask(
    val title: String,
    val dueDatetime: String?
)

sealed interface HomeContentState {
    data object Loading : HomeContentState
    data object Ready : HomeContentState
    data class Error(val message: String) : HomeContentState
}

data class HomeScreenState(
    val headline: String,
    val contentState: HomeContentState,
    val statusMessage: String?,
    val invalidCount: Int,
    val activeBlockerCount: Int,
    val inProgressCount: Int,
    val currentInProgressTask: HomeInProgressTask?,
    val startingTodayCount: Int,
    val healthWarningCount: Int
)

fun buildHomeScreenState(
    snapshot: SnapshotViewState?,
    refreshInFlight: Boolean = false,
    refreshError: String? = null
): HomeScreenState =
    if (snapshot == null) {
        HomeScreenState(
            headline = "Today",
            contentState = if (refreshError != null) {
                HomeContentState.Error(refreshError)
            } else {
                HomeContentState.Loading
            },
            statusMessage = null,
            invalidCount = 0,
            activeBlockerCount = 0,
            inProgressCount = 0,
            currentInProgressTask = null,
            startingTodayCount = 0,
            healthWarningCount = 0
        )
    } else {
        val currentInProgressTask = snapshot.inProgressTasks
            .sortedWith(compareBy(nullsLast()) { it.dueDatetime })
            .firstOrNull()

        HomeScreenState(
            headline = "Today",
            contentState = HomeContentState.Ready,
            statusMessage = when {
                refreshInFlight -> "Refreshing..."
                refreshError != null -> "Refresh failed. Showing last known data."
                else -> null
            },
            invalidCount = snapshot.invalidTasks.size,
            activeBlockerCount = snapshot.activeBlockers.size,
            inProgressCount = snapshot.inProgressTasks.size,
            currentInProgressTask = currentInProgressTask?.let {
                HomeInProgressTask(
                    title = it.title,
                    dueDatetime = it.dueDatetime
                )
            },
            startingTodayCount = snapshot.startingToday.size,
            healthWarningCount = snapshot.healthWarnings.size
        )
    }

@Composable
fun HomeScreen(
    onAddTask: () -> Unit,
    onMorningReview: () -> Unit,
    onRetryRefresh: () -> Unit = {},
    state: HomeScreenState = buildHomeScreenState(null)
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(state.headline, style = MaterialTheme.typography.headlineMedium)
        state.statusMessage?.let { message ->
            if (message == "Refreshing...") {
                InlineLoadingStatus(message)
            } else {
                WarningStatus(message = message, onRetry = onRetryRefresh)
            }
        }

        when (val contentState = state.contentState) {
            HomeContentState.Loading -> BlockingLoadingState("Loading tasks...")
            is HomeContentState.Error -> BlockingErrorState(
                message = contentState.message,
                onRetry = onRetryRefresh
            )

            HomeContentState.Ready -> {
                Text("Invalid: ${state.invalidCount}")
                Text("Active blockers: ${state.activeBlockerCount}")
                state.currentInProgressTask?.let { task ->
                    Text("In progress: ${task.title}", style = MaterialTheme.typography.titleMedium)
                    task.dueDatetime?.let { dueDatetime ->
                        Text("Starts: $dueDatetime")
                    }
                    if (state.inProgressCount > 1) {
                        Text("${state.inProgressCount - 1} more in progress")
                    }
                }
                Text("Starting today: ${state.startingTodayCount}")
                Text("Health warnings: ${state.healthWarningCount}")

                Button(onClick = onAddTask, modifier = Modifier.fillMaxWidth()) {
                    Text("Add task")
                }

                OutlinedButton(onClick = onMorningReview, modifier = Modifier.fillMaxWidth()) {
                    Text("Morning review")
                }
            }
        }
    }
}
