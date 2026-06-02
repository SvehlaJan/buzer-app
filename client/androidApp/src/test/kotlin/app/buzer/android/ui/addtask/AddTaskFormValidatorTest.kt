package app.buzer.android.ui.addtask

import app.buzer.android.ui.datetime.DateTimeSelection
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AddTaskFormValidatorTest {
    private val me = AddTaskAssigneeOption(label = "Me", id = "user-me")
    private val deadline = DateTimeSelection(LocalDate.of(2026, 6, 2), 18, 30)
    private val zone = ZoneId.of("Europe/Warsaw")

    @Test
    fun buildsStrictCreateTaskRequest() {
        val result = AddTaskFormValidator.validate(
            title = " Buy milk ",
            selectedAssignee = me,
            deadline = deadline,
            durationText = "45",
            zoneId = zone
        )

        assertTrue(result is AddTaskFormResult.Valid)
        assertEquals("Buy milk", result.request.title)
        assertEquals(null, result.request.projectId)
        assertEquals("user-me", result.request.assigneeId)
        assertEquals("2026-06-02T16:30:00Z", result.request.dueDatetime)
        assertEquals(45, result.request.durationMinutes)
    }

    @Test
    fun rejectsBlankTitle() {
        val result = AddTaskFormValidator.validate("", me, deadline, "45", zone)

        assertEquals(AddTaskFormResult.Invalid("Title is required"), result)
    }

    @Test
    fun rejectsMissingAssignee() {
        val result = AddTaskFormValidator.validate("Buy milk", null, deadline, "45", zone)

        assertEquals(AddTaskFormResult.Invalid("Assignee is required"), result)
    }

    @Test
    fun rejectsBlankAssigneeId() {
        val result = AddTaskFormValidator.validate(
            title = "Buy milk",
            selectedAssignee = AddTaskAssigneeOption("Me", ""),
            deadline = deadline,
            durationText = "45",
            zoneId = zone
        )

        assertEquals(AddTaskFormResult.Invalid("Me assignee ID is not configured"), result)
    }

    @Test
    fun rejectsMissingDeadline() {
        val result = AddTaskFormValidator.validate("Buy milk", me, null, "45", zone)

        assertEquals(AddTaskFormResult.Invalid("Deadline is required"), result)
    }

    @Test
    fun rejectsInvalidDuration() {
        val result = AddTaskFormValidator.validate("Buy milk", me, deadline, "0", zone)

        assertEquals(
            AddTaskFormResult.Invalid("Duration must be a positive whole number of minutes"),
            result
        )
    }
}
