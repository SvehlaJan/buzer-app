package app.buzer.shared.validation

import kotlin.test.Test
import kotlin.test.assertEquals

class TaskValidationTest {
    @Test
    fun requiresAssigneeForCreatedTask() {
        val result = TaskValidation.validate(
            title = "Buy milk",
            creatorId = "self",
            assigneeId = null,
            dueDatetime = "2026-05-08T16:00:00Z",
            durationMinutes = 10,
            currentUserId = "self"
        )

        assertEquals(listOf("assignee_required_for_created_task"), result.errors)
    }

    @Test
    fun requiresDeadlineAndDurationWhenAssignedToSelf() {
        val result = TaskValidation.validate(
            title = "Book service",
            creatorId = "wife",
            assigneeId = "self",
            dueDatetime = null,
            durationMinutes = null,
            currentUserId = "self"
        )

        assertEquals(listOf("due_datetime_required", "duration_required"), result.errors)
    }
}
