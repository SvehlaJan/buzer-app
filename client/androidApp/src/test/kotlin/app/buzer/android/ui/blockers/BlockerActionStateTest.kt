package app.buzer.android.ui.blockers

import app.buzer.android.ui.screens.BlockerActionState
import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.android.ui.screens.descriptionAwareRescheduleRequest
import app.buzer.android.ui.screens.rescheduleRequestFromEditorStart
import app.buzer.android.ui.screens.updateRequest
import app.buzer.android.ui.taskeditor.TaskEditorSchedule
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.NullablePatch
import app.buzer.shared.model.RecurrencePreset
import app.buzer.shared.model.RescheduleTaskRequest
import app.buzer.shared.model.TaskSnapshotItem
import java.time.LocalDate
import java.time.ZoneId
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
        val task = TaskSnapshotItem(
            id = "task-update-test",
            todoistTaskId = "todoist-update-test",
            title = "Original title",
            projectId = "project-original",
            assigneeId = "user-me",
            creatorId = null,
            dueDatetime = "2026-07-10T08:00:00Z",
            durationMinutes = 30,
            startAt = null,
            state = CommitmentState.invalid,
            isAssignedToUser = true,
            isCreatedByUser = false,
            isCompleted = false,
            isDeleted = false,
            validationErrors = listOf("missing_duration"),
            startedAt = null,
            startDismissCount = 0,
            deadlineExtensionCount = 0,
            escalationSentAt = null,
            updatedAt = "2026-07-10T07:00:00Z",
            occurrenceKey = "occurrence-test",
            taskRevision = "opaque-root-revision"
        )
        val request = updateRequest(
            task = task,
            title = " Edited title ",
            description = " Updated details ",
            project = ProjectItem(
                id = "project-family",
                name = "Family",
                canAssignTasks = true,
                shared = true,
                isInboxProject = false
            ),
            selectedAssignee = ProjectCollaborator(id = "user-me", displayName = "Me", isCurrentUser = true),
            schedule = TaskEditorSchedule(LocalDate.of(2026, 7, 10), 8, 5, 45),
            recurrencePreset = RecurrencePreset.never,
            existingDrafts = emptyList(),
            deletedChildren = emptySet(),
            addedDrafts = emptyList(),
            deviceId = "device-test"
        )

        val patch = requireNotNull(request.rootPatch)
        assertTrue(request.actionId.isNotBlank())
        assertEquals("device-test", request.deviceId)
        assertEquals("occurrence-test", request.expectedOccurrenceKey)
        assertEquals("opaque-root-revision", patch.expectedTaskRevision)
        assertEquals("Edited title", patch.title)
        assertEquals("project-family", patch.projectId)
        assertEquals(
            NullablePatch.Value(
                LocalDate.of(2026, 7, 10)
                    .atTime(8, 5)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toString()
            ),
            patch.dueDatetime
        )
        assertEquals(NullablePatch.Value(45), patch.durationMinutes)
    }
}
