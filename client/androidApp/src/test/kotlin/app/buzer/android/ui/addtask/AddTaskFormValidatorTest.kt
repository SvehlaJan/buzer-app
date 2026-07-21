package app.buzer.android.ui.addtask

import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.ProjectItem
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AddTaskFormValidatorTest {
    private val me = ProjectCollaborator(id = "user-me", displayName = "Me", isCurrentUser = true)
    private val wife = ProjectCollaborator(id = "user-wife", displayName = "Wife", isCurrentUser = false)
    private val deadline = DateTimeSelection(LocalDate.of(2026, 6, 2), 18, 30)
    private val zone = ZoneId.of("Europe/Warsaw")
    private val assignableProject = ProjectItem(
        id = "project-family",
        name = "Family",
        canAssignTasks = true,
        shared = true,
        isInboxProject = false,
        collaborators = listOf(me, wife),
        collaboratorsReady = true
    )
    private val personalProject = ProjectItem(
        id = "project-personal",
        name = "Personal",
        canAssignTasks = false,
        shared = false,
        isInboxProject = false,
        collaborators = listOf(me),
        collaboratorsReady = true
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
            selectedAssignee = me,
            deadline = deadline,
            durationMinutes = 30,
            zoneId = zone
        )

        assertTrue(result is AddTaskFormResult.Valid)
        assertEquals("project-personal", result.request.projectId)
        assertEquals(null, result.request.assigneeId)
    }

    @Test
    fun trimsOptionalDescriptionIntoCreateRequest() {
        val result = AddTaskFormValidator.validate(
            title = "Buy milk",
            description = "  Gate code: 2468  ",
            selectedProject = assignableProject,
            selectedAssignee = me,
            deadline = deadline,
            durationMinutes = 45,
            zoneId = zone
        )

        assertTrue(result is AddTaskFormResult.Valid)
        assertEquals("Gate code: 2468", result.request.description)
    }

    @Test
    fun omitsBlankOptionalDescription() {
        val result = AddTaskFormValidator.validate(
            title = "Buy milk",
            description = "   ",
            selectedProject = assignableProject,
            selectedAssignee = me,
            deadline = deadline,
            durationMinutes = 45,
            zoneId = zone
        )

        assertTrue(result is AddTaskFormResult.Valid)
        assertEquals(null, result.request.description)
    }

    @Test
    fun rejectsBlankTitle() {
        val result = AddTaskFormValidator.validate("", assignableProject, me, deadline, 45, zone)

        assertEquals(
            AddTaskFormResult.Invalid("Title is required", AddTaskInvalidField.Title),
            result
        )
    }

    @Test
    fun rejectsMissingProject() {
        val result = AddTaskFormValidator.validate("Buy milk", null, me, deadline, 45, zone)

        assertEquals(
            AddTaskFormResult.Invalid("Project is required", AddTaskInvalidField.Project),
            result
        )
    }

    @Test
    fun rejectsMissingAssigneeForAssignableProject() {
        val result = AddTaskFormValidator.validate("Buy milk", assignableProject, null, deadline, 45, zone)

        assertEquals(
            AddTaskFormResult.Invalid("Assignee is required for this project", AddTaskInvalidField.Assignee),
            result
        )
    }

    @Test
    fun rejectsBlankAssigneeId() {
        val result = AddTaskFormValidator.validate(
            title = "Buy milk",
            selectedProject = assignableProject,
            selectedAssignee = ProjectCollaborator("", "Me", isCurrentUser = true),
            deadline = deadline,
            durationMinutes = 45,
            zoneId = zone
        )

        assertEquals(
            AddTaskFormResult.Invalid("Me assignee ID is not configured", AddTaskInvalidField.Assignee),
            result
        )
    }

    @Test
    fun rejectsMissingStartTime() {
        val result = AddTaskFormValidator.validate("Buy milk", assignableProject, me, null, 45, zone)

        assertEquals(
            AddTaskFormResult.Invalid("Start time is required", AddTaskInvalidField.Start),
            result
        )
    }

    @Test
    fun allowsUnscheduledNonSelfTask() {
        val result = AddTaskFormValidator.validate(
            title = "Ask about booking",
            selectedProject = assignableProject,
            selectedAssignee = wife,
            deadline = null,
            durationMinutes = null,
            zoneId = zone,
            recurrencePreset = app.buzer.shared.model.RecurrencePreset.weekly
        )

        assertTrue(result is AddTaskFormResult.Valid)
        assertEquals("user-wife", result.request.assigneeId)
        assertEquals(null, result.request.dueDatetime)
        assertEquals(null, result.request.durationMinutes)
        assertEquals(app.buzer.shared.model.RecurrencePreset.never, result.request.recurrencePreset)
    }

    @Test
    fun rejectsPartialScheduleForNonSelfTask() {
        val result = AddTaskFormValidator.validate(
            title = "Ask about booking",
            selectedProject = assignableProject,
            selectedAssignee = wife,
            deadline = deadline,
            durationMinutes = null,
            zoneId = zone
        )

        assertEquals(
            AddTaskFormResult.Invalid("Duration is required", AddTaskInvalidField.Duration),
            result
        )
    }

    @Test
    fun rejectsNonPositiveDurationForCurrentUser() {
        val result = AddTaskFormValidator.validate(
            title = "Buy milk",
            selectedProject = assignableProject,
            selectedAssignee = me,
            deadline = deadline,
            durationMinutes = 0,
            zoneId = zone
        )

        assertEquals(
            AddTaskFormResult.Invalid("Duration must be greater than zero", AddTaskInvalidField.Duration),
            result
        )
    }
}
