package app.buzer.android.ui.addtask

import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.shared.model.CreateTaskRequest
import app.buzer.shared.model.TasksSnapshotResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AddTaskCreateFlowTest {
    @Test
    fun successfulCreateNavigatesBack() = runBlocking {
        val events = mutableListOf<String>()

        val outcome = runAddTaskCreate(
            request = request(),
            onCreateTask = {
                events += "create"
                CoordinatorActionResult.Success
            },
            onBack = { events += "back" }
        )

        assertEquals(AddTaskCreateOutcome.NavigatedBack, outcome)
        assertEquals(listOf("create", "back"), events)
    }

    @Test
    fun nonSuccessCreateOutcomesKeepDraftOpenAndSurfaceWarningMessage() = runBlocking {
        var navigatedBack = false
        val results = listOf(
            CoordinatorActionResult.Warning("todoist_delivery_unknown", "Delivery uncertain", snapshot()) to "Delivery uncertain",
            CoordinatorActionResult.Conflict("stale_task", "Task changed remotely", snapshot()) to "Task changed remotely",
            CoordinatorActionResult.TransportFailure(false, "Offline") to "Offline",
            CoordinatorActionResult.LocalReconciliationFailed(snapshot(), "Disk full") to "Disk full",
            CoordinatorActionResult.ChildActionInFlight to "Checklist update is already in progress"
        )

        for ((result, expectedMessage) in results) {
            val outcome = runAddTaskCreate(
                request = request(),
                onCreateTask = { result },
                onBack = { navigatedBack = true }
            )
            assertEquals(AddTaskCreateOutcome.ShowWarning(expectedMessage), outcome)
            assertFalse(navigatedBack)
        }
    }

    private fun request(): CreateTaskRequest =
        CreateTaskRequest(
            title = "Test task",
            projectId = "project",
            assigneeId = "user-me",
            dueDatetime = "2026-06-15T20:30:00Z",
            durationMinutes = 5
        )

    private fun snapshot() = TasksSnapshotResponse(
        scheduleVersion = 1,
        serverTime = "2026-06-15T20:00:00Z",
        tasks = emptyList(),
        health = emptyList(),
        snapshotGeneration = 1
    )
}
