package app.buzer.android.ui.screens

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

data class HomeScreenState(
    val headline: String,
    val statusMessage: String?,
    val invalidCount: Int,
    val activeBlockerCount: Int,
    val inProgressCount: Int,
    val currentInProgressTask: HomeInProgressTask?,
    val startingTodayCount: Int,
    val healthWarningCount: Int
)

fun buildHomeScreenState(snapshot: SnapshotViewState?): HomeScreenState =
    if (snapshot == null) {
        HomeScreenState(
            headline = "Today",
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
            statusMessage = null,
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
            Text(message)
        }
        Text("Invalid: ${state.invalidCount}")
        Text("Active blockers: ${state.activeBlockerCount}")
        state.currentInProgressTask?.let { task ->
            Text("In progress: ${task.title}", style = MaterialTheme.typography.titleMedium)
            task.dueDatetime?.let { dueDatetime ->
                Text("Due: $dueDatetime")
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
