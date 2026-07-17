package app.buzer.android.ui.addtask

import app.buzer.shared.model.CreateTaskRequest
import app.buzer.shared.model.MutationOutcome
import app.buzer.shared.model.MutationResponse
import app.buzer.shared.model.TasksSnapshotResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class AddTaskCreateFlowTest {
    @Test
    fun successfulCreateAppliesReturnedAuthorityBeforeReturningHome() = runBlocking {
        val events = mutableListOf<String>()
        var appliedSnapshot: TasksSnapshotResponse? = null

        runSuccessfulAddTaskCreate(
            request = request(),
            createTask = {
                events += "create"
                MutationResponse(
                    actionId = it.actionId,
                    outcome = MutationOutcome.completed,
                    snapshot = snapshot()
                )
            },
            onTaskCreated = { snapshot ->
                appliedSnapshot = snapshot
                events += "apply"
            },
            onBack = { events += "back" }
        )

        assertEquals(listOf("create", "apply", "back"), events)
        assertEquals(1, appliedSnapshot?.snapshotGeneration)
    }

    private fun request(): CreateTaskRequest =
        CreateTaskRequest(
            title = "Test task",
            projectId = "project",
            assigneeId = "user-me",
            dueDatetime = "2026-06-15T20:30:00Z",
            durationMinutes = 5,
            actionId = "action-create-test",
            deviceId = "device-test"
        )

    private fun snapshot() = TasksSnapshotResponse(
        scheduleVersion = 1,
        serverTime = "2026-06-15T20:00:00Z",
        tasks = emptyList(),
        health = emptyList(),
        snapshotGeneration = 1
    )
}
