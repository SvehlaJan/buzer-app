package app.buzer.android.ui.taskeditor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.buzer.android.ui.theme.Spacing
import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.RecurrencePreset
import java.time.LocalDateTime

internal enum class EditorSection { None, Schedule, Notes, Checklist }

internal enum class EditorSheet { None, Project, Assignee, Recurrence }

/** Presentation-only state. It deliberately does not participate in a task draft fingerprint. */
internal data class TaskEditorScaffoldState(
    val expandedSection: EditorSection = EditorSection.None,
    val openSheet: EditorSheet = EditorSheet.None
)

internal data class TaskEditorSelectionResult(
    val draft: TaskEditorDraft,
    val state: TaskEditorScaffoldState
)

internal data class TaskEditorScheduleValidation(
    val isValid: Boolean,
    val focusSection: EditorSection? = null,
    val highlightDate: Boolean = false,
    val highlightStartTime: Boolean = false,
    val highlightDuration: Boolean = false
) {
    companion object {
        fun valid() = TaskEditorScheduleValidation(isValid = true)
    }
}

internal fun reduceSection(
    current: EditorSection,
    requested: EditorSection
): EditorSection = if (current == requested) EditorSection.None else requested

/** A validation focus is controlled by the screen owner until the failing draft field changes. */
internal fun resolvedExpandedSection(
    state: TaskEditorScaffoldState,
    validation: TaskEditorScheduleValidation
): EditorSection = validation.focusSection ?: state.expandedSection

internal fun persistValidationFocus(
    state: TaskEditorScaffoldState,
    validation: TaskEditorScheduleValidation
): TaskEditorScaffoldState = validation.focusSection?.let { focusedSection ->
    state.copy(expandedSection = focusedSection)
} ?: state

internal fun reduceSectionTap(
    state: TaskEditorScaffoldState,
    requested: EditorSection,
    validation: TaskEditorScheduleValidation
): TaskEditorScaffoldState = if (validation.focusSection != null) {
    persistValidationFocus(state, validation)
} else {
    state.copy(expandedSection = reduceSection(state.expandedSection, requested))
}

internal fun editorDismissActionsEnabled(saving: Boolean): Boolean = !saving

internal fun scheduleValidationMessage(validation: TaskEditorScheduleValidation): String? =
    if (!validation.isValid && validation.focusSection == EditorSection.Schedule) {
        "Set date, start time, and duration to save this task."
    } else {
        null
    }

internal fun selectionRowSupportingText(
    normalSupportingText: String?,
    fieldError: String?
): String? = fieldError ?: normalSupportingText

internal fun conciseInlineError(message: String?): String? = message?.trim()?.takeIf { it.isNotEmpty() }

internal fun selectProject(
    state: TaskEditorScaffoldState,
    draft: TaskEditorDraft,
    project: ProjectItem,
    currentUser: ProjectCollaborator
): TaskEditorSelectionResult {
    val selectableAssignees = projectAssignees(project, currentUser)
    val selectedAssignee = draft.assignee?.takeIf { candidate ->
        selectableAssignees.any { it.id == candidate.id }
    } ?: selectableAssignees.firstOrNull { it.isCurrentUser }

    return TaskEditorSelectionResult(
        draft = draft.copy(projectId = project.id, assignee = selectedAssignee),
        state = state.copy(openSheet = EditorSheet.None)
    )
}

internal fun selectAssignee(
    state: TaskEditorScaffoldState,
    draft: TaskEditorDraft,
    assignee: ProjectCollaborator
): TaskEditorSelectionResult = TaskEditorSelectionResult(
    draft = draft.copy(assignee = assignee),
    state = state.copy(openSheet = EditorSheet.None)
)

internal fun validateScheduleForSave(draft: TaskEditorDraft): TaskEditorScheduleValidation {
    val schedule = draft.schedule
    val complete = schedule.isComplete() && (schedule.durationMinutes ?: 0) > 0
    val empty = schedule.isEmpty()
    val scheduleIsValid = if (draft.assignee?.isCurrentUser == true) complete else complete || empty

    return if (scheduleIsValid) {
        TaskEditorScheduleValidation.valid()
    } else {
        TaskEditorScheduleValidation(
            isValid = false,
            focusSection = EditorSection.Schedule,
            highlightDate = schedule.date == null,
            highlightStartTime = schedule.hour == null || schedule.minute == null,
            highlightDuration = schedule.durationMinutes == null || schedule.durationMinutes <= 0
        )
    }
}

internal fun projectAssignees(
    project: ProjectItem?,
    currentUser: ProjectCollaborator
): List<ProjectCollaborator> = when {
    project == null -> emptyList()
    !project.canAssignTasks -> listOf(currentUser)
    else -> project.collaborators
}

/**
 * Add/Edit-only task editor shell. It is intentionally fed entirely by values and callbacks:
 * screen owners retain repositories, action IDs, clocks, draft persistence, and submission.
 */
@Composable
internal fun TaskEditorScaffold(
    screenTitle: String,
    title: String,
    onTitleChange: (String) -> Unit,
    draft: TaskEditorDraft,
    now: LocalDateTime,
    projects: List<ProjectItem>,
    currentUser: ProjectCollaborator,
    checklistDoneCount: Int,
    checklistTotalCount: Int,
    checklistContent: @Composable () -> Unit,
    afterChecklistContent: @Composable () -> Unit = {},
    onScheduleChange: (TaskEditorSchedule) -> Unit,
    onNoSchedule: () -> Unit,
    onProjectSelected: (ProjectItem) -> Unit,
    onAssigneeSelected: (ProjectCollaborator) -> Unit,
    onRecurrencePresetSelected: (RecurrencePreset) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onRetryCollaborators: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    scheduleValidation: TaskEditorScheduleValidation = TaskEditorScheduleValidation.valid(),
    enabled: Boolean = true,
    saveEnabled: Boolean = enabled,
    saving: Boolean = false,
    saveLabel: String = saveActionLabel(draft),
    savingLabel: String = "Saving...",
    titleModifier: Modifier = Modifier,
    titleError: String? = null,
    projectError: String? = null,
    assigneeError: String? = null,
    checklistError: String? = null,
    recurrenceValue: String = recurrenceLabel(draft.recurrencePreset),
    selectedRecurrencePreset: RecurrencePreset? = draft.recurrencePreset
) {
    var state by remember { mutableStateOf(TaskEditorScaffoldState()) }
    LaunchedEffect(scheduleValidation.focusSection) {
        state = persistValidationFocus(state, scheduleValidation)
    }
    val selectedProject = projects.firstOrNull { it.id == draft.projectId }
    val assignees = projectAssignees(selectedProject, currentUser)
    val personalProject = selectedProject?.canAssignTasks == false
    val assigneeReady = personalProject || selectedProject?.collaboratorsReady == true
    val notesPreview = draft.description.trim().ifBlank { "Add notes" }
    val conciseTitleError = conciseInlineError(titleError)
    val conciseChecklistError = conciseInlineError(checklistError)
    val expandedSection = resolvedExpandedSection(state, scheduleValidation)
    val dismissActionsEnabled = editorDismissActionsEnabled(saving)

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen)
                .padding(bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    enabled = dismissActionsEnabled,
                    modifier = Modifier.semantics { contentDescription = "Back" }
                ) {
                    Text("‹", style = MaterialTheme.typography.headlineSmall)
                }
                Text(screenTitle, style = MaterialTheme.typography.titleLarge)
            }
            BorderlessTitleField(
                title = title,
                onTitleChange = onTitleChange,
                enabled = enabled,
                errorText = conciseTitleError,
                modifier = titleModifier
            )

            EditorDisclosureRow(
                label = "Schedule",
                value = collapsedScheduleSummary(draft, now),
                leadingSymbol = "◷",
                expanded = expandedSection == EditorSection.Schedule,
                isError = !scheduleValidation.isValid && scheduleValidation.focusSection == EditorSection.Schedule,
                enabled = enabled,
                onClick = { state = reduceSectionTap(state, EditorSection.Schedule, scheduleValidation) }
            )
            if (expandedSection == EditorSection.Schedule) {
                ScheduleEditor(
                    draft = draft,
                    now = now,
                    validation = scheduleValidation,
                    onScheduleChange = onScheduleChange,
                    onNoSchedule = onNoSchedule,
                    enabled = enabled
                )
            }

            EditorValueRow(
                label = "Project",
                value = selectedProject?.name ?: "Choose project",
                leadingSymbol = "□",
                enabled = enabled,
                supportingText = selectionRowSupportingText(
                    normalSupportingText = null,
                    fieldError = projectError
                ),
                isError = projectError != null,
                onClick = { state = state.copy(openSheet = EditorSheet.Project) }
            )
            EditorValueRow(
                label = "Assignee",
                value = when {
                    personalProject -> "Me"
                    draft.assignee != null -> draft.assignee.displayName
                    else -> "Choose assignee"
                },
                leadingSymbol = "◎",
                enabled = enabled && assigneeReady && !personalProject,
                showChevron = !personalProject,
                supportingText = selectionRowSupportingText(
                    normalSupportingText = when {
                        personalProject -> "Personal project"
                        !assigneeReady -> "Collaborators unavailable · Retry"
                        else -> null
                    },
                    fieldError = assigneeError
                ),
                isError = assigneeError != null,
                onClick = { state = state.copy(openSheet = EditorSheet.Assignee) }
            )
            if (!personalProject && !assigneeReady) {
                TextButton(
                    onClick = onRetryCollaborators,
                    enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = "Retry collaborators" }
                ) { Text("Retry collaborators") }
            }
            EditorValueRow(
                label = "Repeat",
                value = recurrenceValue,
                leadingSymbol = "↻",
                enabled = enabled && !(draft.schedule.isEmpty() && draft.assignee?.isCurrentUser == false),
                onClick = { state = state.copy(openSheet = EditorSheet.Recurrence) }
            )
            EditorDisclosureRow(
                label = "Notes",
                value = notesPreview,
                leadingSymbol = "≡",
                expanded = expandedSection == EditorSection.Notes,
                enabled = enabled,
                onClick = { state = reduceSectionTap(state, EditorSection.Notes, scheduleValidation) }
            )
            if (expandedSection == EditorSection.Notes) {
                BasicTextField(
                    value = draft.description,
                    onValueChange = onDescriptionChange,
                    enabled = enabled,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(132.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
                        .padding(Spacing.md)
                        .semantics { contentDescription = "Notes" }
                )
            }
            EditorDisclosureRow(
                label = "Checklist",
                value = "Checklist · $checklistDoneCount/$checklistTotalCount done",
                leadingSymbol = "✓",
                expanded = expandedSection == EditorSection.Checklist,
                supportingText = conciseChecklistError,
                isError = conciseChecklistError != null,
                enabled = enabled,
                onClick = { state = reduceSectionTap(state, EditorSection.Checklist, scheduleValidation) }
            )
            if (expandedSection == EditorSection.Checklist) {
                checklistContent()
            }
            afterChecklistContent()
            Spacer(Modifier.height(Spacing.xl))
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .navigationBarsPadding()
                .imePadding()
        ) {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.screen),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            OutlinedButton(
                onClick = onCancel,
                enabled = dismissActionsEnabled,
                modifier = Modifier.fillMaxWidth(0.48f)
            ) { Text("Cancel") }
            Button(
                onClick = onSave,
                enabled = saveEnabled && !saving,
                modifier = Modifier.fillMaxWidth(0.48f)
            ) { Text(if (saving) savingLabel else saveLabel) }
        }
        }
    }

    when (state.openSheet) {
        EditorSheet.None -> Unit
        EditorSheet.Project -> ProjectSelectionSheet(
            projects = projects,
            selectedProjectId = draft.projectId,
            onProjectSelected = { project ->
                onProjectSelected(project)
                state = state.copy(openSheet = EditorSheet.None)
            },
            onDismiss = { state = state.copy(openSheet = EditorSheet.None) }
        )
        EditorSheet.Assignee -> AssigneeSelectionSheet(
            assignees = assignees,
            selectedAssigneeId = draft.assignee?.id,
            collaboratorsReady = assigneeReady,
            onAssigneeSelected = { assignee ->
                onAssigneeSelected(assignee)
                state = state.copy(openSheet = EditorSheet.None)
            },
            onRetry = onRetryCollaborators,
            onDismiss = { state = state.copy(openSheet = EditorSheet.None) }
        )
        EditorSheet.Recurrence -> RecurrenceSelectionSheet(
            selectedPreset = selectedRecurrencePreset,
            onPresetSelected = { preset ->
                onRecurrencePresetSelected(preset)
                state = state.copy(openSheet = EditorSheet.None)
            },
            onDismiss = { state = state.copy(openSheet = EditorSheet.None) }
        )
    }
}

@Composable
private fun BorderlessTitleField(
    title: String,
    onTitleChange: (String) -> Unit,
    enabled: Boolean,
    errorText: String?,
    modifier: Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        BasicTextField(
            value = title,
            onValueChange = onTitleChange,
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.sm)
                .semantics { contentDescription = "Task title" }
        )
        HorizontalDivider()
        errorText?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(top = Spacing.xs)
                    .semantics { contentDescription = "Title error: $message" }
            )
        }
    }
}

@Composable
private fun EditorDisclosureRow(
    label: String,
    value: String,
    leadingSymbol: String,
    expanded: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    supportingText: String? = null,
    isError: Boolean = false
) {
    EditorValueRow(
        label = label,
        value = value,
        leadingSymbol = leadingSymbol,
        enabled = enabled,
        showChevron = true,
        trailingValue = if (expanded) "⌃" else "⌄",
        supportingText = supportingText,
        isError = isError,
        onClick = onClick
    )
}

@Composable
private fun EditorValueRow(
    label: String,
    value: String,
    leadingSymbol: String,
    enabled: Boolean,
    onClick: () -> Unit,
    showChevron: Boolean = true,
    trailingValue: String? = null,
    supportingText: String? = null,
    isError: Boolean = false
) {
    val contentColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.65f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$label: $value" }
            .padding(vertical = Spacing.md)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                leadingSymbol,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterStart)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.xl, end = Spacing.xl)
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = contentColor)
                Text(
                    value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                supportingText?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (trailingValue != null) {
                Text(
                    trailingValue,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            } else if (showChevron) {
                Text(
                    "›",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = Spacing.md))
    }
}

internal fun recurrenceLabel(preset: RecurrencePreset): String = when (preset) {
    RecurrencePreset.never -> "Does not repeat"
    RecurrencePreset.daily -> "Daily"
    RecurrencePreset.weekdays -> "Weekdays"
    RecurrencePreset.weekly -> "Weekly"
    RecurrencePreset.monthly -> "Monthly"
    RecurrencePreset.yearly -> "Yearly"
}
