package app.buzer.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.buzer.android.ui.theme.Spacing
import app.buzer.shared.ui.commitment.ChecklistEditorDraft
import app.buzer.shared.ui.commitment.validateChecklistDrafts

/** Direct-child editor shared by Add and repair; nesting and schedule fields intentionally do not exist. */
@Composable
fun ChecklistEditor(
    drafts: List<ChecklistEditorDraft>,
    onDraftsChange: (List<ChecklistEditorDraft>) -> Unit,
    nextClientMutationId: () -> String,
    enabled: Boolean,
    maxItems: Int = 30,
    modifier: Modifier = Modifier
) {
    val validation = validateChecklistDrafts(drafts)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text("Checklist (${drafts.size}/$maxItems)")
        validation.bannerMessage?.let { Text(it) }
        drafts.forEachIndexed { index, draft ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { title ->
                        onDraftsChange(drafts.mapIndexed { currentIndex, current ->
                            if (currentIndex == index) current.copy(title = title, failureCode = null) else current
                        })
                    },
                    label = { Text("Checklist item ${index + 1}") },
                    isError = draft.clientMutationId in validation.invalidDraftIds || draft.failureCode != null,
                    supportingText = draft.failureCode?.let { { Text(it) } },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.description.orEmpty(),
                    onValueChange = { description ->
                        onDraftsChange(drafts.mapIndexed { currentIndex, current ->
                            if (currentIndex == index) {
                                current.copy(description = description.trim().takeIf { it.isNotEmpty() }, failureCode = null)
                            } else current
                        })
                    },
                    label = { Text("Item details (optional)") },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = { onDraftsChange(drafts.filterIndexed { currentIndex, _ -> currentIndex != index }) },
                    enabled = enabled
                ) { Text("Remove item") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedButton(
                onClick = {
                    if (drafts.size < maxItems) {
                        onDraftsChange(drafts + ChecklistEditorDraft(nextClientMutationId(), ""))
                    }
                },
                enabled = enabled && drafts.size < maxItems
            ) { Text("Add checklist item") }
        }
    }
}
