package app.buzer.shared.ui.commitment

import app.buzer.shared.model.ChecklistMutationOutcome
import app.buzer.shared.model.MutationOutcome
import app.buzer.shared.model.RecurrenceKind
import app.buzer.shared.model.RecurrencePreset
import app.buzer.shared.model.TaskRecurrence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertTrue(state.withReplacement(RecurrencePreset.weekly).canEditStartOrRecurrence)
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
}
