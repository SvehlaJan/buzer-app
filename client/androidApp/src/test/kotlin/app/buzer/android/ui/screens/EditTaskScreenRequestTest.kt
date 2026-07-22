package app.buzer.android.ui.screens

import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.android.ui.taskeditor.TaskEditorSchedule
import app.buzer.shared.model.ChecklistItem
import app.buzer.shared.model.ChecklistMutation
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.NullablePatch
import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.RecurrenceKind
import app.buzer.shared.model.RecurrencePreset
import app.buzer.shared.model.TaskRecurrence
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.ui.commitment.ChecklistEditorDraft
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class EditTaskScreenRequestTest {
    @Test
    fun customRecurrenceIsPreservedUntilUserChoosesReplacement() {
        val request = requestFor(recurrencePreset = null)

        assertNull(request.rootPatch?.recurrencePreset)
    }

    @Test
    fun explicitSupportedChoiceReplacesACustomRecurrence() {
        val request = requestFor(recurrencePreset = RecurrencePreset.daily)

        assertEquals(RecurrencePreset.daily, request.rootPatch?.recurrencePreset)
    }

    @Test
    fun editRequestIncludesRootAndExplicitDirectChildMutations() {
        val request = updateRequest(
            task = task(),
            title = "Updated root",
            description = "New details",
            project = sharedProject,
            selectedAssignee = me,
            schedule = TaskEditorSchedule(LocalDate.of(2026, 7, 16), 10, 0, 45),
            recurrencePreset = app.buzer.shared.model.RecurrencePreset.daily,
            existingDrafts = listOf(
                ExistingChecklistDraft("child-1", "child-r1", "Edited child", "detail", checked = true),
                ExistingChecklistDraft("child-2", "child-r2", "Second", null, checked = false)
            ),
            deletedChildren = setOf("child-2"),
            addedDrafts = listOf(ChecklistEditorDraft("add-1", "New child")),
            deviceId = "device-1"
        )

        assertEquals("root-r1", request.rootPatch?.expectedTaskRevision)
        assertEquals(app.buzer.shared.model.RecurrencePreset.daily, request.rootPatch?.recurrencePreset)
        assertEquals(4, request.childMutations.size)
        assertIs<ChecklistMutation.Edit>(request.childMutations[0])
        assertIs<ChecklistMutation.Check>(request.childMutations[1])
        assertIs<ChecklistMutation.Delete>(request.childMutations[2])
        assertIs<ChecklistMutation.Add>(request.childMutations[3])
    }

    @Test
    fun movingProjectAndSelectingWifeWritesTypedProjectAndAssigneePatches() {
        val request = updateRequest(
            task = task(),
            title = "Updated root",
            description = "",
            project = sharedProject,
            selectedAssignee = wife,
            schedule = TaskEditorSchedule(LocalDate.of(2026, 7, 16), 10, 0, 30),
            recurrencePreset = RecurrencePreset.never,
            existingDrafts = emptyList(),
            deletedChildren = emptySet(),
            addedDrafts = emptyList(),
            deviceId = "device-1"
        )

        assertEquals("project-test", request.rootPatch?.projectId)
        assertEquals(NullablePatch.Value("user-wife"), request.rootPatch?.assigneeId)
    }

    @Test
    fun unscheduledWifeWritesExplicitNullSchedulesAndNeverRecurrence() {
        val request = updateRequest(
            task = task().copy(recurrence = TaskRecurrence(RecurrenceKind.supported, RecurrencePreset.weekly, null)),
            title = "Ask about booking",
            description = "",
            project = sharedProject,
            selectedAssignee = wife,
            schedule = TaskEditorSchedule(),
            recurrencePreset = RecurrencePreset.weekly,
            existingDrafts = emptyList(),
            deletedChildren = emptySet(),
            addedDrafts = emptyList(),
            deviceId = "device-1"
        )

        assertEquals(NullablePatch.Value(null), request.rootPatch?.dueDatetime)
        assertEquals(NullablePatch.Value(null), request.rootPatch?.durationMinutes)
        assertEquals(RecurrencePreset.never, request.rootPatch?.recurrencePreset)
    }

    @Test
    fun personalProjectWritesAnExplicitUnassignedPatch() {
        val personalProject = ProjectItem(
            id = "project-personal",
            name = "Personal",
            canAssignTasks = false,
            shared = false,
            isInboxProject = false,
            collaborators = listOf(me),
            collaboratorsReady = true
        )
        val request = updateRequest(
            task = task(),
            title = "Personal task",
            description = "",
            project = personalProject,
            selectedAssignee = me,
            schedule = TaskEditorSchedule(LocalDate.of(2026, 7, 16), 10, 0, 30),
            recurrencePreset = RecurrencePreset.never,
            existingDrafts = emptyList(),
            deletedChildren = emptySet(),
            addedDrafts = emptyList(),
            deviceId = "device-1"
        )

        assertEquals(NullablePatch.Value(null), request.rootPatch?.assigneeId)
    }

    private fun requestFor(recurrencePreset: app.buzer.shared.model.RecurrencePreset?) = updateRequest(
        task = task(),
        title = "Root",
        description = "",
        project = sharedProject,
        selectedAssignee = me,
        schedule = TaskEditorSchedule(LocalDate.of(2026, 7, 16), 9, 0, 30),
        recurrencePreset = recurrencePreset,
        existingDrafts = listOf(ExistingChecklistDraft("child-1", "child-r1", "Child", null, false)),
        deletedChildren = emptySet(),
        addedDrafts = emptyList(),
        deviceId = "device-1"
    )

    private fun task() = TaskSnapshotItem(
        id = "root", todoistTaskId = "todoist-root", title = "Root", projectId = "project", assigneeId = null,
        creatorId = null, dueDatetime = "2026-07-16T09:00:00Z", durationMinutes = 30, startAt = null,
        state = CommitmentState.planned, isAssignedToUser = true, isCreatedByUser = true, isCompleted = false,
        isDeleted = false, validationErrors = emptyList(), startedAt = null, startDismissCount = 0,
        deadlineExtensionCount = 0, escalationSentAt = null, updatedAt = "2026-07-16T08:00:00Z",
        occurrenceKey = "occurrence-1", taskRevision = "root-r1",
        recurrence = TaskRecurrence(kind = RecurrenceKind.custom, preset = null, rule = "every 2 days"),
        checklistItems = listOf(
            ChecklistItem("child-1", "Child", null, false, 1, "child-r1"),
            ChecklistItem("child-2", "Second", null, true, 2, "child-r2")
        )
    )

    private companion object {
        val me = ProjectCollaborator(id = "user-me", displayName = "Me", isCurrentUser = true)
        val wife = ProjectCollaborator(id = "user-wife", displayName = "Wife", isCurrentUser = false)
        val sharedProject = ProjectItem(
            id = "project-test",
            name = "Shared",
            canAssignTasks = true,
            shared = true,
            isInboxProject = false,
            collaborators = listOf(me, wife),
            collaboratorsReady = true
        )
    }
}
