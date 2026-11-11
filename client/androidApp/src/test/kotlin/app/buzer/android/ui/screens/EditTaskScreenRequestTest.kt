package app.buzer.android.ui.screens

import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.ui.datetime.DateTimeDraftSelection
import app.buzer.android.ui.taskeditor.CapturedEditTaskUpdate
import app.buzer.android.ui.taskeditor.ChecklistDraftFingerprint
import app.buzer.android.ui.taskeditor.ExistingChecklistDraft
import app.buzer.android.ui.taskeditor.TaskEditorChoices
import app.buzer.android.ui.taskeditor.TaskEditorSchedule
import app.buzer.android.ui.taskeditor.buildUpdateTaskRequest
import app.buzer.android.ui.taskeditor.captureEditTaskUpdate
import app.buzer.android.ui.taskeditor.editTaskDraftFingerprint
import app.buzer.android.ui.taskeditor.initialTaskEditorUpdateSession
import app.buzer.android.ui.taskeditor.initialTaskEditorUpdateState
import app.buzer.android.ui.taskeditor.reconcileTaskEditorUpdateSession
import app.buzer.android.ui.taskeditor.reduceTaskEditorUpdateResult
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
import app.buzer.shared.ui.taskeditor.ChecklistEditorDraft
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
        val request = buildUpdateTaskRequest(
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
            addedDrafts = listOf(ChecklistEditorDraft("add-1", "New child"))
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
        val request = buildUpdateTaskRequest(
            task = task(),
            title = "Updated root",
            description = "",
            project = sharedProject,
            selectedAssignee = wife,
            schedule = TaskEditorSchedule(LocalDate.of(2026, 7, 16), 10, 0, 30),
            recurrencePreset = RecurrencePreset.never,
            existingDrafts = emptyList(),
            deletedChildren = emptySet(),
            addedDrafts = emptyList()
        )

        assertEquals("project-test", request.rootPatch?.projectId)
        assertEquals(NullablePatch.Value("user-wife"), request.rootPatch?.assigneeId)
    }

    @Test
    fun unscheduledWifeWritesExplicitNullSchedulesAndNeverRecurrence() {
        val request = buildUpdateTaskRequest(
            task = task().copy(recurrence = TaskRecurrence(RecurrenceKind.supported, RecurrencePreset.weekly, null)),
            title = "Ask about booking",
            description = "",
            project = sharedProject,
            selectedAssignee = wife,
            schedule = TaskEditorSchedule(),
            recurrencePreset = RecurrencePreset.weekly,
            existingDrafts = emptyList(),
            deletedChildren = emptySet(),
            addedDrafts = emptyList()
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
        val request = buildUpdateTaskRequest(
            task = task(),
            title = "Personal task",
            description = "",
            project = personalProject,
            selectedAssignee = me,
            schedule = TaskEditorSchedule(LocalDate.of(2026, 7, 16), 10, 0, 30),
            recurrencePreset = RecurrencePreset.never,
            existingDrafts = emptyList(),
            deletedChildren = emptySet(),
            addedDrafts = emptyList()
        )

        assertEquals(NullablePatch.Value(null), request.rootPatch?.assigneeId)
    }

    @Test
    fun pendingEditReplayReusesCapturedRequestAndDeliversSuccessOnce() = runBlocking {
        val updateTask = task().copy(projectId = sharedProject.id, assigneeId = me.id)
        val state = initialTaskEditorUpdateState(
            task = updateTask,
            choices = TaskEditorChoices(projects = listOf(sharedProject))
        )
        val fingerprint = editTaskDraftFingerprint(
            title = state.draft.title,
            description = state.draft.description,
            projectId = state.draft.projectId,
            assigneeId = state.draft.assignee?.id,
            start = DateTimeDraftSelection(
                state.draft.schedule.date,
                state.draft.schedule.hour,
                state.draft.schedule.minute
            ),
            durationMinutes = state.draft.schedule.durationMinutes,
            recurrencePreset = state.draft.recurrencePreset,
            existingChildren = emptyList(),
            deletedChildIds = emptySet(),
            addedChildren = emptyList()
        )
        var captured: CapturedEditTaskUpdate? = null
        var requestConstructionCount = 0
        val dispatchedRequests = mutableListOf<app.buzer.shared.model.UpdateTaskRequest>()

        suspend fun submitCurrentEdit(): CoordinatorActionResult {
            captured = captureEditTaskUpdate(
                captured = captured,
                fingerprint = fingerprint,
                createRequest = {
                    requestConstructionCount += 1
                    buildUpdateTaskRequest(
                        task = updateTask,
                        title = state.draft.title,
                        description = state.draft.description,
                        project = sharedProject,
                        selectedAssignee = me,
                        schedule = state.draft.schedule,
                        recurrencePreset = state.draft.recurrencePreset,
                        existingDrafts = emptyList(),
                        deletedChildren = emptySet(),
                        addedDrafts = emptyList()
                    )
                }
            )
            val request = requireNotNull(captured).request
            dispatchedRequests += request
            return if (dispatchedRequests.size == 1) {
                CoordinatorActionResult.Warning(
                    code = "busy",
                    message = "Server busy",
                    snapshot = null
                )
            } else {
                CoordinatorActionResult.Success
            }
        }

        val firstResult = reduceTaskEditorUpdateResult(
            state.beginSubmission(),
            submitCurrentEdit()
        )
        val confirmed = reduceTaskEditorUpdateResult(
            firstResult.state,
            submitCurrentEdit()
        )
        var successCallbacks = 0
        if (confirmed.shouldInvokeSuccess) successCallbacks += 1
        val repeatedSuccess = reduceTaskEditorUpdateResult(
            confirmed.state,
            CoordinatorActionResult.Success
        )
        if (repeatedSuccess.shouldInvokeSuccess) successCallbacks += 1

        assertEquals(1, requestConstructionCount)
        assertEquals(2, dispatchedRequests.size)
        assertSame(dispatchedRequests.first(), dispatchedRequests.last())
        assertEquals(1, successCallbacks)
    }

    @Test
    fun captureEditTaskUpdateReusesChildMutationIdsWhenUntouchedAndMintsFreshIdsOnChecklistOrRootEdits() {
        val baseTask = task().copy(projectId = sharedProject.id, assigneeId = me.id)
        val choices = TaskEditorChoices(projects = listOf(sharedProject))
        val schedule = TaskEditorSchedule(LocalDate.of(2026, 7, 16), 10, 0, 45)
        var mintedCounter = 0

        fun buildAndCapture(
            previous: CapturedEditTaskUpdate?,
            title: String,
            existing: List<ExistingChecklistDraft>,
            deleted: Set<String>,
            added: List<ChecklistEditorDraft>
        ): CapturedEditTaskUpdate {
            val fp = editTaskDraftFingerprint(
                title = title,
                description = "",
                projectId = sharedProject.id,
                assigneeId = me.id,
                start = DateTimeDraftSelection(schedule.date, schedule.hour, schedule.minute),
                durationMinutes = schedule.durationMinutes,
                recurrencePreset = RecurrencePreset.daily,
                existingChildren = existing.map { ChecklistDraftFingerprint(it.id, it.title, it.description, it.checked) },
                deletedChildIds = deleted,
                addedChildren = added.map { ChecklistDraftFingerprint(it.clientMutationId, it.title, it.description, false) }
            )
            return captureEditTaskUpdate(previous, fp) {
                buildUpdateTaskRequest(
                    task = baseTask,
                    title = title,
                    description = "",
                    project = sharedProject,
                    selectedAssignee = me,
                    schedule = schedule,
                    recurrencePreset = RecurrencePreset.daily,
                    existingDrafts = existing,
                    deletedChildren = deleted,
                    addedDrafts = added,
                    nextMutationId = { "mut-${++mintedCounter}" }
                )
            }
        }

        val initialExisting = listOf(
            ExistingChecklistDraft("child-1", "child-r1", "Edited child", null, checked = true),
            ExistingChecklistDraft("child-2", "child-r2", "Second", null, checked = true)
        )
        val initialDeleted = setOf("child-2")
        val initialAdded = listOf(ChecklistEditorDraft("ui-row-1", "Added item"))

        val firstCapture = buildAndCapture(
            previous = null,
            title = "Root",
            existing = initialExisting,
            deleted = initialDeleted,
            added = initialAdded
        )
        val replayedCapture = buildAndCapture(
            previous = firstCapture,
            title = "Root",
            existing = initialExisting,
            deleted = initialDeleted,
            added = initialAdded
        )
        val firstIds = firstCapture.request.childMutations.map { it.clientMutationId }
        assertEquals(4, firstIds.size)
        assertSame(firstCapture.request, replayedCapture.request)
        assertEquals(firstIds, replayedCapture.request.childMutations.map { it.clientMutationId })

        // Editing an added checklist item (same UI row key "ui-row-1") mints fresh clientMutationIds, including Add.
        val afterAddedChange = buildAndCapture(
            previous = replayedCapture,
            title = "Root",
            existing = initialExisting,
            deleted = initialDeleted,
            added = listOf(ChecklistEditorDraft("ui-row-1", "Added item v2"))
        )
        val addedChangedIds = afterAddedChange.request.childMutations.map { it.clientMutationId }
        assertTrue(addedChangedIds.intersect(firstIds.toSet()).isEmpty())

        // Editing an existing checklist item mints fresh clientMutationIds.
        val afterExistingChange = buildAndCapture(
            previous = afterAddedChange,
            title = "Root",
            existing = listOf(
                ExistingChecklistDraft("child-1", "child-r1", "Edited child v2", null, checked = true),
                ExistingChecklistDraft("child-2", "child-r2", "Second", null, checked = true)
            ),
            deleted = initialDeleted,
            added = listOf(ChecklistEditorDraft("ui-row-1", "Added item v2"))
        )
        val existingChangedIds = afterExistingChange.request.childMutations.map { it.clientMutationId }
        assertTrue(existingChangedIds.intersect(addedChangedIds.toSet()).isEmpty())

        // Restoring/changing deleted checklist items mints fresh clientMutationIds.
        val afterDeletedChange = buildAndCapture(
            previous = afterExistingChange,
            title = "Root",
            existing = listOf(
                ExistingChecklistDraft("child-1", "child-r1", "Edited child v2", null, checked = true),
                ExistingChecklistDraft("child-2", "child-r2", "Second", null, checked = true)
            ),
            deleted = emptySet(),
            added = listOf(ChecklistEditorDraft("ui-row-1", "Added item v2"))
        )
        val deletedChangedIds = afterDeletedChange.request.childMutations.map { it.clientMutationId }
        assertTrue(deletedChangedIds.intersect(existingChangedIds.toSet()).isEmpty())

        // Editing a root field mints fresh clientMutationIds.
        val afterRootChange = buildAndCapture(
            previous = afterDeletedChange,
            title = "Root updated",
            existing = listOf(
                ExistingChecklistDraft("child-1", "child-r1", "Edited child v2", null, checked = true),
                ExistingChecklistDraft("child-2", "child-r2", "Second", null, checked = true)
            ),
            deleted = emptySet(),
            added = listOf(ChecklistEditorDraft("ui-row-1", "Added item v2"))
        )
        val rootChangedIds = afterRootChange.request.childMutations.map { it.clientMutationId }
        assertNotEquals(deletedChangedIds, rootChangedIds)
        assertTrue(rootChangedIds.intersect(deletedChangedIds.toSet()).isEmpty())
    }

    @Test
    fun authoritativeSnapshotReseedsCleanSessionAndPreservesDirtyRootOrChecklistDrafts() {
        val choices = TaskEditorChoices(projects = listOf(sharedProject))
        val initialTask = task().copy(projectId = sharedProject.id, assigneeId = me.id)
        val cleanSession = initialTaskEditorUpdateSession(initialTask, choices)
        assertFalse(cleanSession.isDirty())

        val refreshedTask = initialTask.copy(
            title = "Refreshed root",
            taskRevision = "root-r2",
            checklistItems = listOf(
                ChecklistItem("child-1", "Refreshed child", null, true, 1, "child-r2")
            )
        )
        val reseeded = reconcileTaskEditorUpdateSession(cleanSession, refreshedTask, choices)
        assertEquals("Refreshed root", reseeded.state.draft.title)
        assertEquals(listOf(ExistingChecklistDraft("child-1", "child-r2", "Refreshed child", null, true)), reseeded.existingDrafts)
        assertFalse(reseeded.isDirty())

        val dirtyChecklistSession = reseeded.withAddedDrafts(listOf(ChecklistEditorDraft("add-1", "Draft item")))
        assertTrue(dirtyChecklistSession.isDirty())
        val nextTask = refreshedTask.copy(title = "Another remote edit", taskRevision = "root-r3")
        val preserved = reconcileTaskEditorUpdateSession(dirtyChecklistSession, nextTask, choices)
        assertEquals("Refreshed root", preserved.state.draft.title)
        assertEquals(listOf(ChecklistEditorDraft("add-1", "Draft item")), preserved.addedDrafts)
        assertTrue(preserved.isDirty())
    }

    private fun requestFor(recurrencePreset: app.buzer.shared.model.RecurrencePreset?) = buildUpdateTaskRequest(
        task = task(),
        title = "Root",
        description = "",
        project = sharedProject,
        selectedAssignee = me,
        schedule = TaskEditorSchedule(LocalDate.of(2026, 7, 16), 9, 0, 30),
        recurrencePreset = recurrencePreset,
        existingDrafts = listOf(ExistingChecklistDraft("child-1", "child-r1", "Child", null, false)),
        deletedChildren = emptySet(),
        addedDrafts = emptyList()
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
