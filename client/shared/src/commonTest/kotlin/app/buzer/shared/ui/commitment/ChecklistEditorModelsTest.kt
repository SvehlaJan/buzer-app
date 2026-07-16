package app.buzer.shared.ui.commitment

import app.buzer.shared.model.ChecklistMutationOutcome
import app.buzer.shared.model.MutationOutcome
import app.buzer.shared.model.RecurrenceKind
import app.buzer.shared.model.RecurrencePreset
import app.buzer.shared.model.TaskRecurrence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChecklistEditorModelsTest {
    @Test
    fun recurrenceChoicesHaveTheFixedNumberedOrder() {
        assertEquals(
            listOf(
                1 to RecurrencePreset.never,
                2 to RecurrencePreset.daily,
                3 to RecurrencePreset.weekdays,
                4 to RecurrencePreset.weekly,
                5 to RecurrencePreset.monthly,
                6 to RecurrencePreset.yearly
            ),
            recurrenceChoices.map { it.number to it.preset }
        )
    }

    @Test
    fun customRecurrenceIsReadOnlyUntilExplicitSupportedReplacementIsChosen() {
        val custom = TaskRecurrence(kind = RecurrenceKind.custom, preset = null, rule = "every second Friday")

        val state = recurrenceEditorState(custom)

        assertTrue(state.isReadOnly)
        assertFalse(state.canEditStartOrRecurrence)
        val replacement = state.withReplacement(RecurrencePreset.weekly)
        assertTrue(replacement.canEditStartOrRecurrence)
        assertNull(replacement.customRule)
    }

    @Test
    fun checklistValidationCapsChildrenAndRequiresStableNonblankDrafts() {
        val duplicate = ChecklistEditorDraft("draft-1", "First")
        val validation = validateChecklistDrafts(
            List(31) { index -> if (index == 1 || index == 2) duplicate else ChecklistEditorDraft("draft-$index", "Task $index") } +
                ChecklistEditorDraft("blank", " ") +
                ChecklistEditorDraft("", "Missing identity")
        )

        assertEquals("A checklist can have at most 30 items", validation.bannerMessage)
        assertTrue(validation.invalidDraftIds.contains("draft-1"))
        assertTrue(validation.invalidDraftIds.contains("blank"))
        assertTrue(validation.invalidDraftIds.contains(""))
    }

    @Test
    fun failedChecklistDraftsAreRetainedWithTheirOriginalIdentity() {
        val drafts = listOf(
            ChecklistEditorDraft("draft-ok", "Saved"),
            ChecklistEditorDraft("draft-failed", "Keep me", "Unsaved detail")
        )

        val retained = retainFailedChecklistDrafts(
            drafts,
            listOf(
                ChecklistMutationOutcome("draft-ok", "child-1", MutationOutcome.completed),
                ChecklistMutationOutcome("draft-failed", null, MutationOutcome.failed, "simulated_failure")
            )
        )

        assertEquals(listOf("draft-failed"), retained.map { it.clientMutationId })
        assertEquals("Keep me", retained.single().title)
        assertEquals("simulated_failure", retained.single().failureCode)
    }

    @Test
    fun typedChecklistMutationStateCoversEveryIntentWithRequiredConcurrencyFields() {
        val mutations = listOf(
            ChecklistEditorMutationState.Add("add-1", "Add", "Description"),
            ChecklistEditorMutationState.Edit("edit-1", "child-1", "r:2", "Edit", null),
            ChecklistEditorMutationState.Delete("delete-1", "child-1", "r:2"),
            ChecklistEditorMutationState.Check("check-1", "child-1", "r:2"),
            ChecklistEditorMutationState.Uncheck("uncheck-1", "child-1", "r:2")
        )

        assertEquals(listOf("add-1", "edit-1", "delete-1", "check-1", "uncheck-1"), mutations.map { it.clientMutationId })
        assertFailsWith<IllegalArgumentException> {
            ChecklistEditorMutationState.Edit("edit-bad", "child-1", " ", "Edit")
        }
        assertFailsWith<IllegalArgumentException> {
            ChecklistEditorMutationState.Check(" ", "child-1", "r:2")
        }
    }

    @Test
    fun failedMutationRetentionPreservesEveryTypedIntent() {
        val mutations = listOf(
            ChecklistEditorMutationState.Add("add-1", "Add"),
            ChecklistEditorMutationState.Edit("edit-1", "child-1", "r:2", "Edit"),
            ChecklistEditorMutationState.Delete("delete-1", "child-1", "r:2"),
            ChecklistEditorMutationState.Check("check-1", "child-1", "r:2"),
            ChecklistEditorMutationState.Uncheck("uncheck-1", "child-1", "r:2")
        )

        val retained = retainFailedChecklistMutations(
            mutations,
            listOf(
                ChecklistMutationOutcome("add-1", "child-new", MutationOutcome.failed, "add_failed"),
                ChecklistMutationOutcome("edit-1", "child-1", MutationOutcome.stale_child, "edit_stale"),
                ChecklistMutationOutcome("delete-1", "child-1", MutationOutcome.failed, "network_failed"),
                ChecklistMutationOutcome("check-1", "child-1", MutationOutcome.stale_child, "stale_child"),
                ChecklistMutationOutcome("uncheck-1", "child-1", MutationOutcome.failed, "uncheck_failed")
            )
        )

        assertEquals(listOf("add-1", "edit-1", "delete-1", "check-1", "uncheck-1"), retained.map { it.clientMutationId })
        assertTrue(retained[0] is ChecklistEditorMutationState.Add)
        assertTrue(retained[1] is ChecklistEditorMutationState.Edit)
        assertTrue(retained[2] is ChecklistEditorMutationState.Delete)
        assertTrue(retained[3] is ChecklistEditorMutationState.Check)
        assertTrue(retained[4] is ChecklistEditorMutationState.Uncheck)
        assertEquals(listOf("add_failed", "edit_stale", "network_failed", "stale_child", "uncheck_failed"), retained.map { it.failureCode })
    }
}
