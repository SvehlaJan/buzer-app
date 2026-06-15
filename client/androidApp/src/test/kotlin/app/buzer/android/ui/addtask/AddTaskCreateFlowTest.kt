package app.buzer.android.ui.addtask

import app.buzer.shared.model.CreateTaskRequest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class AddTaskCreateFlowTest {
    @Test
    fun successfulCreateRefreshesEnforcementBeforeReturningHome() = runBlocking {
        val events = mutableListOf<String>()

        runSuccessfulAddTaskCreate(
            request = request(),
            createTask = { events += "create" },
            onTaskCreated = { events += "refresh" },
            onBack = { events += "back" }
        )

        assertEquals(listOf("create", "refresh", "back"), events)
    }

    private fun request(): CreateTaskRequest =
        CreateTaskRequest(
            title = "Test task",
            projectId = "project",
            assigneeId = "user-me",
            dueDatetime = "2026-06-15T20:30:00Z",
            durationMinutes = 5
        )
}
