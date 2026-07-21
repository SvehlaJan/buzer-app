package app.buzer.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.ui.components.ChecklistEditor
import app.buzer.android.ui.components.CommitmentEditor
import app.buzer.android.ui.components.commitmentSaveLabel
import app.buzer.android.ui.datetime.DateTimeDraftSelection
import app.buzer.android.ui.theme.Spacing
import app.buzer.shared.model.ChecklistMutation
import app.buzer.shared.model.ChecklistItem
import app.buzer.shared.model.NullablePatch
import app.buzer.shared.model.RecurrenceKind
import app.buzer.shared.model.RecurrencePreset
import app.buzer.shared.model.RootTaskPatch
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.UpdateTaskRequest
import app.buzer.shared.ui.commitment.ChecklistEditorDraft
import app.buzer.shared.ui.commitment.CommitmentEditorMode
import app.buzer.shared.ui.commitment.CommitmentEditorValidation
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.launch

internal data class ExistingChecklistDraft(
    val id: String,
    val childRevision: String,
    val title: String,
    val description: String?,
    val checked: Boolean
)

internal fun ChecklistItem.toEditorDraft() = ExistingChecklistDraft(
    id = id,
    childRevision = childRevision,
    title = title,
    description = description,
    checked = checked
)

/**
 * Authoritative-root editor. It deliberately preserves project/assignee unless a later repair
 * flow explicitly changes them; this screen never invents a parent or a child schedule.
 */
@Composable
fun EditTaskScreen(
    task: TaskSnapshotItem,
    deviceId: String,
    initialAddedDrafts: List<ChecklistEditorDraft> = emptyList(),
    onBack: () -> Unit,
    onUpdate: suspend (String, UpdateTaskRequest) -> CoordinatorActionResult,
    modifier: Modifier = Modifier
) {
    var title by remember(task.id) { mutableStateOf(task.title) }
    var description by remember(task.id) { mutableStateOf(task.description.orEmpty()) }
    var descriptionExpanded by remember(task.id) { mutableStateOf(task.description?.isNotBlank() == true) }
    var start by remember(task.id) { mutableStateOf(task.initialRepairStartDraft()) }
    var durationMinutes by remember(task.id) { mutableStateOf(task.durationMinutes ?: 30) }
    val customRecurrence = task.recurrence.kind == RecurrenceKind.custom
    var recurrencePreset by remember(task.id) { mutableStateOf(task.recurrence.preset) }
    var existingDrafts by remember(task.id) {
        mutableStateOf(task.checklistItems.sortedBy { it.order }.map(ChecklistItem::toEditorDraft))
    }
    var deletedChildren by remember(task.id) { mutableStateOf<Set<String>>(emptySet()) }
    var addedDrafts by remember(task.id) { mutableStateOf(initialAddedDrafts) }
    var pendingRequest by remember(task.id) { mutableStateOf<UpdateTaskRequest?>(null) }
    var submitting by remember(task.id) { mutableStateOf(false) }
    var message by remember(task.id) { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    CommitmentEditor(
        mode = CommitmentEditorMode.Repair,
        title = title,
        onTitleChange = { title = it; pendingRequest = null },
        titleEditable = true,
        description = description,
        onDescriptionChange = { description = it; pendingRequest = null },
        descriptionExpanded = descriptionExpanded,
        onDescriptionExpandedChange = { descriptionExpanded = it },
        projectContent = { Text(task.projectId ?: "Unchanged") },
        assigneeContent = { Text(task.assigneeId ?: "Unchanged") },
        start = start,
        onStartChange = { start = it; pendingRequest = null },
        durationMinutes = durationMinutes,
        onDurationChange = { durationMinutes = it; pendingRequest = null },
        recurrencePreset = recurrencePreset,
        onRecurrencePresetChange = { recurrencePreset = it; pendingRequest = null },
        checklistContent = {
            if (customRecurrence && recurrencePreset == null) {
                Text("Custom recurrence: ${task.recurrence.rule ?: task.recurrence.displayLabel}")
                Text("Choose one of the numbered options only to replace this custom rule.")
            }
            ExistingChecklistEditor(
                drafts = existingDrafts.filterNot { it.id in deletedChildren },
                onDraftsChange = { existingDrafts = it; pendingRequest = null },
                onDelete = { childId ->
                    deletedChildren = deletedChildren + childId
                    pendingRequest = null
                },
                enabled = !submitting
            )
            ChecklistEditor(
                drafts = addedDrafts,
                onDraftsChange = { addedDrafts = it; pendingRequest = null },
                nextClientMutationId = { UUID.randomUUID().toString() },
                enabled = !submitting,
                maxItems = (30 - existingDrafts.count { it.id !in deletedChildren }).coerceAtLeast(0)
            )
            ActivityLogSection(task)
        },
        validation = CommitmentEditorValidation(
            bannerMessage = message,
            titleError = "Required".takeIf { title.isBlank() },
            projectError = null,
            assigneeError = null,
            startError = "Required".takeIf { start.toSelection() == null },
            durationError = null
        ),
        submitting = submitting,
        enabled = !submitting,
        submitEnabled = !submitting && title.isNotBlank() && start.toSelection() != null,
        submitLabel = commitmentSaveLabel(start.toSelection(), durationMinutes),
        loadingLabel = "Saving...",
        onSubmit = {
            val selectedStart = start.toSelection() ?: return@CommitmentEditor
            val request = pendingRequest ?: updateRequest(
                task = task,
                title = title,
                description = description,
                selectedStart = selectedStart,
                durationMinutes = durationMinutes,
                recurrencePreset = recurrencePreset,
                existingDrafts = existingDrafts,
                deletedChildren = deletedChildren,
                addedDrafts = addedDrafts,
                deviceId = deviceId
            ).also { pendingRequest = it }
            submitting = true
            scope.launch {
                when (val result = onUpdate(task.id, request)) {
                    CoordinatorActionResult.Success -> onBack()
                    is CoordinatorActionResult.Stale -> {
                        message = "Task changed elsewhere. Your draft is still here; reapply it after review."
                    }
                    is CoordinatorActionResult.Pending -> message = "Saved; waiting for Todoist confirmation"
                    is CoordinatorActionResult.RetryAfter -> message = "Retry in ${result.retryAfterSeconds} seconds"
                    is CoordinatorActionResult.LocalReconciliationFailed -> message = result.message
                    is CoordinatorActionResult.FailedAndHidden -> message = result.message
                    CoordinatorActionResult.ChildActionInFlight -> message = "Checklist update is already in progress"
                }
                submitting = false
            }
        },
        onCancel = onBack,
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg)
    )
}

internal fun updateRequest(
    task: TaskSnapshotItem,
    title: String,
    description: String,
    selectedStart: app.buzer.android.ui.datetime.DateTimeSelection,
    durationMinutes: Int,
    recurrencePreset: RecurrencePreset?,
    existingDrafts: List<ExistingChecklistDraft>,
    deletedChildren: Set<String>,
    addedDrafts: List<ChecklistEditorDraft>,
    deviceId: String
): UpdateTaskRequest {
    val originalById = task.checklistItems.associateBy { it.id }
    val changedExisting = existingDrafts.filterNot { it.id in deletedChildren }.flatMap { draft ->
        val original = originalById.getValue(draft.id)
        buildList {
            if (draft.title.trim() != original.title || draft.description != original.description) {
                add(ChecklistMutation.Edit(UUID.randomUUID().toString(), draft.id, draft.childRevision, draft.title.trim(), draft.description))
            }
            if (draft.checked != original.checked) {
                add(
                    if (draft.checked) ChecklistMutation.Check(UUID.randomUUID().toString(), draft.id, draft.childRevision)
                    else ChecklistMutation.Uncheck(UUID.randomUUID().toString(), draft.id, draft.childRevision)
                )
            }
        }
    }
    val deletions = task.checklistItems.filter { it.id in deletedChildren }.map {
        ChecklistMutation.Delete(UUID.randomUUID().toString(), it.id, it.childRevision)
    }
    val recurrenceChange = when {
        task.recurrence.kind == RecurrenceKind.custom -> recurrencePreset
        recurrencePreset != task.recurrence.preset -> recurrencePreset
        else -> null
    }
    return UpdateTaskRequest(
        actionId = UUID.randomUUID().toString(),
        deviceId = deviceId,
        expectedOccurrenceKey = task.occurrenceKey,
        rootPatch = RootTaskPatch(
            expectedTaskRevision = task.taskRevision,
            title = title.trim(),
            description = NullablePatch.Value(description.trim().takeIf { it.isNotEmpty() }),
            dueDatetime = NullablePatch.Value(selectedStart.toIsoInstantString(ZoneId.systemDefault())),
            durationMinutes = NullablePatch.Value(durationMinutes),
            recurrencePreset = recurrenceChange,
            timeZone = ZoneId.systemDefault().id
        ),
        childMutations = changedExisting + deletions + addedDrafts.map { draft ->
            ChecklistMutation.Add(draft.clientMutationId, draft.title.trim(), draft.description)
        }
    )
}

@Composable
internal fun ExistingChecklistEditor(
    drafts: List<ExistingChecklistDraft>,
    onDraftsChange: (List<ExistingChecklistDraft>) -> Unit,
    onDelete: (String) -> Unit,
    enabled: Boolean
) {
    if (drafts.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
        Text("Existing checklist")
        drafts.forEach { draft ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Checkbox(
                    checked = draft.checked,
                    onCheckedChange = { checked -> onDraftsChange(drafts.map { if (it.id == draft.id) it.copy(checked = checked) else it }) },
                    enabled = enabled
                )
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { title -> onDraftsChange(drafts.map { if (it.id == draft.id) it.copy(title = title) else it }) },
                    label = { Text("Checklist item") }, enabled = enabled, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.description.orEmpty(),
                    onValueChange = { description -> onDraftsChange(drafts.map { if (it.id == draft.id) it.copy(description = description.takeIf(String::isNotBlank)) else it }) },
                    label = { Text("Item details (optional)") }, enabled = enabled, modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = { onDelete(draft.id) }, enabled = enabled) { Text("Remove item") }
            }
        }
    }
}
