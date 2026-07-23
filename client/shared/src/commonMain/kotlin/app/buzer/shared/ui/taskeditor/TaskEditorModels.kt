package app.buzer.shared.ui.taskeditor

import app.buzer.shared.model.RecurrenceKind
import app.buzer.shared.model.RecurrencePreset
import app.buzer.shared.model.TaskRecurrence

data class RecurrenceChoice(
    val number: Int,
    val preset: RecurrencePreset,
    val label: String
)

val recurrenceChoices = listOf(
    RecurrenceChoice(1, RecurrencePreset.never, "Never"),
    RecurrenceChoice(2, RecurrencePreset.daily, "Daily"),
    RecurrenceChoice(3, RecurrencePreset.weekdays, "Weekdays"),
    RecurrenceChoice(4, RecurrencePreset.weekly, "Weekly"),
    RecurrenceChoice(5, RecurrencePreset.monthly, "Monthly"),
    RecurrenceChoice(6, RecurrencePreset.yearly, "Yearly")
)

data class RecurrenceEditorState(
    val selectedPreset: RecurrencePreset?,
    val customRule: String?,
    val isReadOnly: Boolean,
    val canEditStartOrRecurrence: Boolean
) {
    fun withReplacement(preset: RecurrencePreset): RecurrenceEditorState =
        copy(
            selectedPreset = preset,
            customRule = null,
            isReadOnly = false,
            canEditStartOrRecurrence = true
        )
}

fun recurrenceEditorState(recurrence: TaskRecurrence): RecurrenceEditorState {
    val isCustom = recurrence.kind == RecurrenceKind.custom
    return RecurrenceEditorState(
        selectedPreset = recurrence.preset,
        customRule = recurrence.rule,
        isReadOnly = isCustom,
        canEditStartOrRecurrence = !isCustom
    )
}

data class ChecklistEditorDraft(
    val clientMutationId: String,
    val title: String,
    val description: String? = null,
    val failureCode: String? = null
)

data class ChecklistDraftValidation(
    val bannerMessage: String? = null,
    val invalidDraftIds: Set<String> = emptySet()
)

sealed class ChecklistEditorMutationState {
    abstract val clientMutationId: String
    abstract val failureCode: String?

    abstract fun withFailure(failureCode: String?): ChecklistEditorMutationState

    data class Add(
        override val clientMutationId: String,
        val title: String,
        val description: String? = null,
        override val failureCode: String? = null
    ) : ChecklistEditorMutationState() {
        init {
            requireChecklistMutationField("clientMutationId", clientMutationId)
            requireChecklistMutationField("title", title)
        }

        override fun withFailure(failureCode: String?): Add = copy(failureCode = failureCode)
    }

    data class Edit(
        override val clientMutationId: String,
        val childId: String,
        val expectedChildRevision: String,
        val title: String,
        val description: String? = null,
        override val failureCode: String? = null
    ) : ChecklistEditorMutationState() {
        init {
            requireChecklistMutationIdentity(clientMutationId, childId, expectedChildRevision)
            requireChecklistMutationField("title", title)
        }

        override fun withFailure(failureCode: String?): Edit = copy(failureCode = failureCode)
    }

    data class Delete(
        override val clientMutationId: String,
        val childId: String,
        val expectedChildRevision: String,
        override val failureCode: String? = null
    ) : ChecklistEditorMutationState() {
        init {
            requireChecklistMutationIdentity(clientMutationId, childId, expectedChildRevision)
        }

        override fun withFailure(failureCode: String?): Delete = copy(failureCode = failureCode)
    }

    data class Check(
        override val clientMutationId: String,
        val childId: String,
        val expectedChildRevision: String,
        override val failureCode: String? = null
    ) : ChecklistEditorMutationState() {
        init {
            requireChecklistMutationIdentity(clientMutationId, childId, expectedChildRevision)
        }

        override fun withFailure(failureCode: String?): Check = copy(failureCode = failureCode)
    }

    data class Uncheck(
        override val clientMutationId: String,
        val childId: String,
        val expectedChildRevision: String,
        override val failureCode: String? = null
    ) : ChecklistEditorMutationState() {
        init {
            requireChecklistMutationIdentity(clientMutationId, childId, expectedChildRevision)
        }

        override fun withFailure(failureCode: String?): Uncheck = copy(failureCode = failureCode)
    }
}

fun validateChecklistMutationBatch(mutations: List<ChecklistEditorMutationState>) {
    val duplicateIds = mutations
        .groupBy { it.clientMutationId }
        .filterValues { it.size > 1 }
        .keys
    require(duplicateIds.isEmpty()) {
        "clientMutationId values must be unique: ${duplicateIds.sorted().joinToString()}"
    }
}

private fun requireChecklistMutationIdentity(
    clientMutationId: String,
    childId: String,
    expectedChildRevision: String
) {
    requireChecklistMutationField("clientMutationId", clientMutationId)
    requireChecklistMutationField("childId", childId)
    requireChecklistMutationField("expectedChildRevision", expectedChildRevision)
}

private fun requireChecklistMutationField(name: String, value: String) {
    require(value.isNotBlank()) { "$name must not be blank" }
}

fun validateChecklistDrafts(drafts: List<ChecklistEditorDraft>): ChecklistDraftValidation {
    val invalidIds = buildSet {
        drafts.filter { it.clientMutationId.isBlank() || it.title.isBlank() }.forEach { add(it.clientMutationId) }
        drafts.groupBy { it.clientMutationId }
            .filterValues { it.size > 1 }
            .keys
            .forEach(::add)
    }
    return ChecklistDraftValidation(
        bannerMessage = if (drafts.size > 30) "A checklist can have at most 30 items" else null,
        invalidDraftIds = invalidIds
    )
}
