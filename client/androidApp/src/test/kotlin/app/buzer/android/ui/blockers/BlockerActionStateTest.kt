package app.buzer.android.ui.blockers

import app.buzer.android.ui.addtask.AddTaskAssigneeOption
import app.buzer.android.ui.screens.BlockerActionState
import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.android.ui.screens.descriptionAwareRescheduleRequest
import app.buzer.android.ui.screens.rescheduleRequestFromEditorStart
import app.buzer.android.ui.screens.updateTaskRequestFromEditorStart
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.RescheduleTaskRequest
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockerActionStateTest {
    @Test
    fun loadingActionDisablesAllBlockerActions() {
        val state = BlockerActionState.Loading("Starting...")

        assertTrue(state.actionsDisabled)
        assertEquals("Starting...", state.loadingLabel)
    }

    @Test
    fun idleActionAllowsButtons() {
        val state = BlockerActionState.Idle

        assertFalse(state.actionsDisabled)
        assertEquals(null, state.loadingLabel)
    }

    @Test
    fun unchangedDescriptionIsOmittedFromRescheduleRequest() {
        val request = descriptionAwareRescheduleRequest(
            dueDatetime = "2026-07-10T08:00:00Z",
            durationMinutes = 30,
            initialDescription = "Existing details",
            editedDescription = " Existing details "
        )

        assertEquals(RescheduleTaskRequest.DescriptionUpdate.Unchanged, request.descriptionUpdate)
        assertEquals(null, request.description)
    }

    @Test
    fun blankEditedDescriptionClearsDescriptionOnRescheduleRequest() {
        val request = descriptionAwareRescheduleRequest(
            dueDatetime = "2026-07-10T08:00:00Z",
            durationMinutes = 30,
            initialDescription = "Existing details",
            editedDescription = " "
        )

        assertEquals(RescheduleTaskRequest.DescriptionUpdate.Clear, request.descriptionUpdate)
        assertEquals(null, request.description)
    }

    @Test
    fun nonBlankEditedDescriptionIsTrimmedOnRescheduleRequest() {
        val request = descriptionAwareRescheduleRequest(
            dueDatetime = "2026-07-10T08:00:00Z",
            durationMinutes = 30,
            initialDescription = "Existing details",
            editedDescription = " Updated details "
        )

        assertEquals(RescheduleTaskRequest.DescriptionUpdate.Set, request.descriptionUpdate)
        assertEquals("Updated details", request.description)
    }

    @Test
    fun rescheduleRequestUsesEditorStartAsTodoistDueDatetime() {
        val request = rescheduleRequestFromEditorStart(
            start = DateTimeSelection(
                date = LocalDate.of(2026, 7, 10),
                hour = 8,
                minute = 5
            ),
            durationMinutes = 45,
            initialDescription = "Existing details",
            editedDescription = "Updated details",
            zoneId = ZoneOffset.UTC
        )

        assertEquals("2026-07-10T08:05:00Z", request.dueDatetime)
        assertEquals(45, request.durationMinutes)
        assertEquals(RescheduleTaskRequest.DescriptionUpdate.Set, request.descriptionUpdate)
    }

    @Test
    fun updateTaskRequestUsesAllEditedTodoistFields() {
        val request = updateTaskRequestFromEditorStart(
            actionId = "action-update-test",
            deviceId = "device-test",
            expectedOccurrenceKey = "occurrence-test",
            expectedTaskRevision = "opaque-root-revision",
            title = " Edited title ",
            description = " Updated details ",
            selectedProject = ProjectItem(
                id = "project-family",
                name = "Family",
                canAssignTasks = true,
                shared = true,
                isInboxProject = false
            ),
            selectedAssignee = AddTaskAssigneeOption(label = "Me", id = "user-me"),
            start = DateTimeSelection(
                date = LocalDate.of(2026, 7, 10),
                hour = 8,
                minute = 5
            ),
            durationMinutes = 45,
            zoneId = ZoneOffset.UTC
        )

        val patch = requireNotNull(request.rootPatch)
        assertEquals("action-update-test", request.actionId)
        assertEquals("device-test", request.deviceId)
        assertEquals("occurrence-test", request.expectedOccurrenceKey)
        assertEquals("opaque-root-revision", patch.expectedTaskRevision)
        assertEquals("Edited title", patch.title)
        assertEquals("project-family", patch.projectId)
        assertEquals("2026-07-10T08:05:00Z", patch.dueDatetime)
        assertEquals(45, patch.durationMinutes)
    }
}
