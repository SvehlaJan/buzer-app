package app.buzer.android.ui.addtask

import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.shared.model.ProjectItem
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AddTaskFormValidatorTest {
    private val me = AddTaskAssigneeOption(label = "Me", id = "user-me")
    private val deadline = DateTimeSelection(LocalDate.of(2026, 6, 2), 18, 30)
    private val zone = ZoneId.of("Europe/Warsaw")
    private val assignableProject = ProjectItem(
        id = "project-family",
        name = "Family",
        canAssignTasks = true,
        shared = true,
        isInboxProject = false
    )
    private val personalProject = ProjectItem(
        id = "project-personal",
        name = "Personal",
        canAssignTasks = false,
        shared = false,
        isInboxProject = false
    )

    @Test
    fun buildsAssignedCreateTaskRequestForAssignableProject() {
        val result = AddTaskFormValidator.validate(
            title = " Buy milk ",
            selectedProject = assignableProject,
            selectedAssignee = me,
            deadline = deadline,
            durationMinutes = 45,
            zoneId = zone
        )

        assertTrue(result is AddTaskFormResult.Valid)
        assertEquals("Buy milk", result.request.title)
        assertEquals("project-family", result.request.projectId)
        assertEquals("user-me", result.request.assigneeId)
        assertEquals("2026-06-02T16:30:00Z", result.request.dueDatetime)
        assertEquals(45, result.request.durationMinutes)
    }

    @Test
    fun buildsPersonalCreateTaskRequestForNonAssignableProject() {
        val result = AddTaskFormValidator.validate(
            title = " Personal task ",
            selectedProject = personalProject,
            selectedAssignee = null,
            deadline = deadline,
            durationMinutes = 30,
            zoneId = zone
        )

        assertTrue(result is AddTaskFormResult.Valid)
        assertEquals("project-personal", result.request.projectId)
        assertEquals(null, result.request.assigneeId)
    }

    @Test
    fun rejectsBlankTitle() {
        val result = AddTaskFormValidator.validate("", assignableProject, me, deadline, 45, zone)

        assertEquals(AddTaskFormResult.Invalid("Title is required"), result)
    }

    @Test
    fun rejectsMissingProject() {
        val result = AddTaskFormValidator.validate("Buy milk", null, me, deadline, 45, zone)

        assertEquals(AddTaskFormResult.Invalid("Project is required"), result)
    }

    @Test
    fun rejectsMissingAssigneeForAssignableProject() {
        val result = AddTaskFormValidator.validate("Buy milk", assignableProject, null, deadline, 45, zone)

        assertEquals(AddTaskFormResult.Invalid("Assignee is required for this project"), result)
    }

    @Test
    fun rejectsBlankAssigneeId() {
        val result = AddTaskFormValidator.validate(
            title = "Buy milk",
            selectedProject = assignableProject,
            selectedAssignee = AddTaskAssigneeOption("Me", ""),
            deadline = deadline,
            durationMinutes = 45,
            zoneId = zone
        )

        assertEquals(AddTaskFormResult.Invalid("Me assignee ID is not configured"), result)
    }

    @Test
    fun rejectsMissingDeadline() {
        val result = AddTaskFormValidator.validate("Buy milk", assignableProject, me, null, 45, zone)

        assertEquals(AddTaskFormResult.Invalid("Deadline is required"), result)
    }
}
