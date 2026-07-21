package app.buzer.android.ui.taskeditor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.buzer.android.ui.theme.Spacing
import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.RecurrencePreset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectSelectionSheet(
    projects: List<ProjectItem>,
    selectedProjectId: String?,
    onProjectSelected: (ProjectItem) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filteredProjects = projects.filter { project ->
        project.name.contains(query.trim(), ignoreCase = true)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetHeading("Project")
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screen, vertical = Spacing.sm)
                .semantics { contentDescription = "Search projects" }
        )
        HorizontalDivider()
        SheetList {
            filteredProjects.forEach { project ->
                ImmediateSelectionRow(
                    label = project.name,
                    selected = project.id == selectedProjectId,
                    onClick = { onProjectSelected(project) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssigneeSelectionSheet(
    assignees: List<ProjectCollaborator>,
    selectedAssigneeId: String?,
    collaboratorsReady: Boolean,
    onAssigneeSelected: (ProjectCollaborator) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetHeading("Assignee")
        if (!collaboratorsReady) {
            Column(
                modifier = Modifier.padding(Spacing.screen),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Text("Collaborators are unavailable. Retry before choosing an assignee.")
                Button(onClick = onRetry, modifier = Modifier.semantics { contentDescription = "Retry collaborators" }) {
                    Text("Retry")
                }
            }
        } else {
            SheetList {
                assignees.forEach { assignee ->
                    ImmediateSelectionRow(
                        label = assignee.displayName,
                        identityHint = assignee.email,
                        selected = assignee.id == selectedAssigneeId,
                        onClick = { onAssigneeSelected(assignee) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecurrenceSelectionSheet(
    selectedPreset: RecurrencePreset,
    onPresetSelected: (RecurrencePreset) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetHeading("Repeat")
        SheetList {
            RecurrencePreset.entries.forEach { preset ->
                ImmediateSelectionRow(
                    label = recurrenceLabel(preset),
                    selected = preset == selectedPreset,
                    onClick = { onPresetSelected(preset) }
                )
            }
        }
    }
}

@Composable
private fun SheetHeading(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.sm)
    )
}

@Composable
private fun SheetList(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .verticalScroll(rememberScrollState())
    ) {
        content()
        Spacer(Modifier.height(Spacing.xl))
    }
}

@Composable
private fun ImmediateSelectionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    identityHint: String? = null
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = if (selected) "$label, selected" else label }
            .padding(horizontal = Spacing.screen, vertical = Spacing.md),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = Spacing.xl)
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            identityHint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (selected) {
            Text(
                "✓",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
    HorizontalDivider()
}
