package app.buzer.android.ui.screens

import app.buzer.android.ui.datetime.DateTimeDraftSelection
import app.buzer.shared.model.RecurrencePreset
import kotlin.test.Test
import kotlin.test.assertEquals

class DraftExitPolicyTest {
    @Test
    fun untouchedDraftNavigatesHome() {
        val addDraft = AddTaskDraftFingerprint.empty(projectId = "project-personal")
        val editDraft = editDraft()

        assertEquals(DraftExitDecision.NavigateHome, decideDraftExit(addDraft, addDraft, submitting = false))
        assertEquals(DraftExitDecision.NavigateHome, decideDraftExit(editDraft, editDraft, submitting = false))
    }

    @Test
    fun changedAddTitleDescriptionAssigneeAndProjectRequireConfirmation() {
        val initial = addDraft()

        assertDirty(initial, initial.copy(title = "Buy milk"))
        assertDirty(initial, initial.copy(description = "From the market"))
        assertDirty(initial, initial.copy(assigneeId = "wife"))
        assertDirty(initial, initial.copy(projectId = "project-shared"))
    }

    @Test
    fun changedEditTitleDescriptionStartDurationAndRecurrenceRequireConfirmation() {
        val initial = editDraft()

        assertDirty(initial, initial.copy(title = "Updated root"))
        assertDirty(initial, initial.copy(description = "Updated description"))
        assertDirty(initial, initial.copy(start = start.copy(hour = 10)))
        assertDirty(initial, initial.copy(durationMinutes = 45))
        assertDirty(initial, initial.copy(recurrencePreset = RecurrencePreset.weekly))
    }

    @Test
    fun changedExistingChildTitleOrCheckedStateRequiresConfirmation() {
        val initial = editDraft()
        val child = initial.existingChildren.single()

        assertDirty(initial, initial.copy(existingChildren = listOf(child.copy(title = "Updated child"))))
        assertDirty(initial, initial.copy(existingChildren = listOf(child.copy(checked = true))))
    }

    @Test
    fun addedOrDeletedChildRequiresConfirmation() {
        val initial = editDraft()
        val addedChild = ChecklistDraftFingerprint("new-child", "New child", null, false)

        assertDirty(initial, initial.copy(addedChildren = listOf(addedChild)))
        assertDirty(initial, initial.copy(deletedChildIds = setOf("child-1")))
    }

    @Test
    fun reorderedChecklistItemsRequireConfirmation() {
        val first = ChecklistDraftFingerprint("child-1", "First", null, false)
        val second = ChecklistDraftFingerprint("child-2", "Second", null, false)
        val initial = addDraft().copy(checklistItems = listOf(first, second))

        assertDirty(initial, initial.copy(checklistItems = listOf(second, first)))
    }

    @Test
    fun returningTrackedValuesToBaselineNavigatesHome() {
        val initial = editDraft()
        val changed = initial.copy(
            title = "Updated root",
            description = "Updated description",
            start = start.copy(hour = 10),
            durationMinutes = 45,
            recurrencePreset = RecurrencePreset.weekly,
            existingChildren = listOf(initial.existingChildren.single().copy(checked = true)),
            deletedChildIds = setOf("child-1"),
            addedChildren = listOf(ChecklistDraftFingerprint("new-child", "New child", null, false))
        )
        val restored = changed.copy(
            title = initial.title,
            description = initial.description,
            start = initial.start,
            durationMinutes = initial.durationMinutes,
            recurrencePreset = initial.recurrencePreset,
            existingChildren = initial.existingChildren,
            deletedChildIds = initial.deletedChildIds,
            addedChildren = initial.addedChildren
        )

        assertDirty(initial, changed)
        assertEquals(DraftExitDecision.NavigateHome, decideDraftExit(initial, restored, submitting = false))
    }

    @Test
    fun restoredProjectBaselineNavigatesHomeButAnotherProjectRequiresConfirmation() {
        val initial = AddTaskDraftFingerprint.empty(projectId = "project-personal")

        assertEquals(DraftExitDecision.NavigateHome, decideDraftExit(initial, initial, submitting = false))
        assertDirty(initial, initial.copy(projectId = "project-shared"))
    }

    @Test
    fun submittingIgnoresExitEvenWhenDraftChanged() {
        val initial = addDraft()
        val changed = initial.copy(title = "Buy milk")

        assertEquals(DraftExitDecision.Ignore, decideDraftExit(initial, changed, submitting = true))
    }

    @Test
    fun screenFactoriesCaptureOnlyDraftData() {
        val addedChild = ChecklistDraftFingerprint("new-child", "New child", "Details", false)
        val existingChild = ChecklistDraftFingerprint("child-1", "Child", "Details", false)
        val add = addTaskDraftFingerprint(
            title = "Root",
            description = "Description",
            projectId = "project-personal",
            assigneeId = "me",
            start = start,
            durationMinutes = 30,
            recurrencePreset = RecurrencePreset.never,
            checklistItems = listOf(addedChild)
        )
        val edit = editTaskDraftFingerprint(
            title = "Root",
            description = "Description",
            start = start,
            durationMinutes = 30,
            recurrencePreset = RecurrencePreset.never,
            existingChildren = listOf(existingChild),
            deletedChildIds = setOf("deleted-child"),
            addedChildren = listOf(addedChild)
        )

        assertEquals(
            AddTaskDraftFingerprint(
                title = "Root",
                description = "Description",
                projectId = "project-personal",
                assigneeId = "me",
                start = start,
                durationMinutes = 30,
                recurrencePreset = RecurrencePreset.never,
                checklistItems = listOf(addedChild)
            ),
            add
        )
        assertEquals(
            EditTaskDraftFingerprint(
                title = "Root",
                description = "Description",
                start = start,
                durationMinutes = 30,
                recurrencePreset = RecurrencePreset.never,
                existingChildren = listOf(existingChild),
                deletedChildIds = setOf("deleted-child"),
                addedChildren = listOf(addedChild)
            ),
            edit
        )
    }

    @Test
    fun fingerprintsSnapshotMutableCollectionInputs() {
        val initialChild = ChecklistDraftFingerprint("child-1", "Child", null, false)
        val newChild = ChecklistDraftFingerprint("child-2", "New child", null, false)
        val checklistItems = mutableListOf(initialChild)
        val existingChildren = mutableListOf(initialChild)
        val deletedChildIds = mutableSetOf("deleted-child")
        val addedChildren = mutableListOf(newChild)
        val add = AddTaskDraftFingerprint(
            title = "Add",
            description = "",
            projectId = null,
            assigneeId = null,
            start = DateTimeDraftSelection.Empty,
            durationMinutes = 30,
            recurrencePreset = RecurrencePreset.never,
            checklistItems = checklistItems
        )
        val edit = EditTaskDraftFingerprint(
            title = "Edit",
            description = "",
            start = DateTimeDraftSelection.Empty,
            durationMinutes = 30,
            recurrencePreset = RecurrencePreset.never,
            existingChildren = existingChildren,
            deletedChildIds = deletedChildIds,
            addedChildren = addedChildren
        )

        checklistItems += newChild
        existingChildren.clear()
        deletedChildIds += "another-deleted-child"
        addedChildren.clear()

        assertEquals(listOf(initialChild), add.checklistItems)
        assertEquals(listOf(initialChild), edit.existingChildren)
        assertEquals(setOf("deleted-child"), edit.deletedChildIds)
        assertEquals(listOf(newChild), edit.addedChildren)
    }

    private fun addDraft() = AddTaskDraftFingerprint.empty(projectId = "project-personal")

    private fun editDraft() = EditTaskDraftFingerprint(
        title = "Root",
        description = "",
        start = start,
        durationMinutes = 30,
        recurrencePreset = RecurrencePreset.never,
        existingChildren = listOf(ChecklistDraftFingerprint("child-1", "Child", null, false)),
        deletedChildIds = emptySet(),
        addedChildren = emptyList()
    )

    private fun <T> assertDirty(initial: T, changed: T) {
        assertEquals(DraftExitDecision.ConfirmDiscard, decideDraftExit(initial, changed, submitting = false))
    }

    private companion object {
        val start = DateTimeDraftSelection(date = null, hour = 9, minute = 30)
    }
}
