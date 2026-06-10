package app.buzer.android.ui.screens

import app.buzer.android.enforcement.PermissionHealthItem
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

data class HomeScreenState(
    val headline: String,
    val statusMessage: String?,
    val invalidCount: Int,
    val activeBlockerCount: Int,
    val startingTodayCount: Int,
    val healthWarningCount: Int,
    val permissionHealth: List<PermissionHealthItem>
)

fun buildHomeScreenState(snapshot: SnapshotViewState?): HomeScreenState =
    if (snapshot == null) {
        HomeScreenState(
            headline = "Today",
            statusMessage = null,
            invalidCount = 0,
            activeBlockerCount = 0,
            startingTodayCount = 0,
            healthWarningCount = 0,
            permissionHealth = emptyList()
        )
    } else {
        HomeScreenState(
            headline = "Today",
            statusMessage = null,
            invalidCount = snapshot.invalidTasks.size,
            activeBlockerCount = snapshot.activeBlockers.size,
            startingTodayCount = snapshot.startingToday.size,
            healthWarningCount = snapshot.healthWarnings.size,
            permissionHealth = emptyList()
        )
    }

@Composable
fun HomeScreen(
    onAddTask: () -> Unit,
    onMorningReview: () -> Unit,
    onOpenOverlaySettings: () -> Unit = {},
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
        Text("Starting today: ${state.startingTodayCount}")
        Text("Health warnings: ${state.healthWarningCount}")
        state.permissionHealth
            .filterNot { it.key == "all" }
            .forEach { item ->
                Text(item.message)
                if (item.key == "overlay") {
                    OutlinedButton(onClick = onOpenOverlaySettings, modifier = Modifier.fillMaxWidth()) {
                        Text("Open overlay settings")
                    }
                }
            }

        Button(onClick = onAddTask, modifier = Modifier.fillMaxWidth()) {
            Text("Add task")
        }

        OutlinedButton(onClick = onMorningReview, modifier = Modifier.fillMaxWidth()) {
            Text("Morning review")
        }
    }
}
