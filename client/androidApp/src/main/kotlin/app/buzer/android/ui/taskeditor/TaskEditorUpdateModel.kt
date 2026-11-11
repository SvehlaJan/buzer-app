package app.buzer.android.ui.taskeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.buzer.android.ui.datetime.DateTimeDraftSelection
import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.android.ui.theme.Spacing
import app.buzer.shared.model.ChecklistItem
import app.buzer.shared.model.ChecklistMutation
import app.buzer.shared.model.NullablePatch
import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.RecurrenceKind
import app.buzer.shared.model.RecurrencePreset
import app.buzer.shared.model.RootTaskPatch
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.UpdateTaskRequest
import app.buzer.shared.ui.taskeditor.ChecklistEditorDraft
import app.buzer.shared.ui.taskeditor.validateChecklistDrafts
import java.time.ZoneId
import java.util.UUID

internal const val MAX_CHECKLIST_ITEMS = 30

internal data class ExistingChecklistDraft(
    val id: String,
    val childRevision: String,
    val title: String,
    val description: String?,
    val checked: Boolean
)

internal fun ChecklistItem.toEditorDraft(): ExistingChecklistDraft = ExistingChecklistDraft(
    id = id,
    childRevision = childRevision,
    title = title,
    description = description,
    checked = checked
)

internal fun initialExistingChecklistDrafts(task: TaskSnapshotItem): List<ExistingChecklistDraft> =
    task.checklistItems.sortedBy { it.order }.map(ChecklistItem::toEditorDraft)

internal data class ChecklistDraftFingerprint(
    val stableId: String,
    val title: String,
    val description: String?,
    val checked: Boolean
)

internal class EditTaskDraftFingerprint(
    val title: String,
    val description: String,
    val projectId: String?,
    val assigneeId: String?,
    val start: DateTimeDraftSelection,
    val durationMinutes: Int?,
    val recurrencePreset: RecurrencePreset?,
    existingChildren: List<ChecklistDraftFingerprint>,
    deletedChildIds: Set<String>,
    addedChildren: List<ChecklistDraftFingerprint>
) {
    val existingChildren: List<ChecklistDraftFingerprint> = existingChildren.toList()
    val deletedChildIds: Set<String> = deletedChildIds.toSet()
    val addedChildren: List<ChecklistDraftFingerprint> = addedChildren.toList()

    fun copy(
        title: String = this.title,
        description: String = this.description,
        projectId: String? = this.projectId,
        assigneeId: String? = this.assigneeId,
        start: DateTimeDraftSelection = this.start,
        durationMinutes: Int? = this.durationMinutes,
        recurrencePreset: RecurrencePreset? = this.recurrencePreset,
        existingChildren: List<ChecklistDraftFingerprint> = this.existingChildren,
        deletedChildIds: Set<String> = this.deletedChildIds,
        addedChildren: List<ChecklistDraftFingerprint> = this.addedChildren
    ): EditTaskDraftFingerprint = EditTaskDraftFingerprint(
        title = title,
        description = description,
        projectId = projectId,
        assigneeId = assigneeId,
        start = start,
        durationMinutes = durationMinutes,
        recurrencePreset = recurrencePreset,
        existingChildren = existingChildren,
        deletedChildIds = deletedChildIds,
        addedChildren = addedChildren
    )

    override fun equals(other: Any?): Boolean = other is EditTaskDraftFingerprint &&
        title == other.title &&
        description == other.description &&
        projectId == other.projectId &&
        assigneeId == other.assigneeId &&
        start == other.start &&
        durationMinutes == other.durationMinutes &&
        recurrencePreset == other.recurrencePreset &&
        existingChildren == other.existingChildren &&
        deletedChildIds == other.deletedChildIds &&
        addedChildren == other.addedChildren

    override fun hashCode(): Int = listOf(
        title,
        description,
        projectId,
        assigneeId,
        start,
        durationMinutes,
        recurrencePreset,
        existingChildren,
        deletedChildIds,
        addedChildren
    ).hashCode()
}

internal fun editTaskDraftFingerprint(
    title: String,
    description: String,
    projectId: String?,
    assigneeId: String?,
    start: DateTimeDraftSelection,
    durationMinutes: Int?,
    recurrencePreset: RecurrencePreset?,
    existingChildren: List<ChecklistDraftFingerprint>,
    deletedChildIds: Set<String>,
    addedChildren: List<ChecklistDraftFingerprint>
): EditTaskDraftFingerprint = EditTaskDraftFingerprint(
    title = title,
    description = description,
    projectId = projectId,
    assigneeId = assigneeId,
    start = start,
    durationMinutes = durationMinutes,
    recurrencePreset = recurrencePreset,
    existingChildren = existingChildren,
    deletedChildIds = deletedChildIds,
    addedChildren = addedChildren
)

/**
 * Uses the same assignee projection as the Update request so choice hydration cannot dirty a
 * pristine draft.
 */
internal fun editTaskDraftFingerprint(
    draft: TaskEditorDraft,
    choices: TaskEditorChoices,
    existingDrafts: List<ExistingChecklistDraft>,
    deletedChildren: Set<String>,
    addedDrafts: List<ChecklistEditorDraft>
): EditTaskDraftFingerprint = editTaskDraftFingerprint(
    title = draft.title,
    description = draft.description,
    projectId = draft.projectId,
    assigneeId = choices.assigneeIdForFingerprint(draft),
    start = DateTimeDraftSelection(
        draft.schedule.date,
        draft.schedule.hour,
        draft.schedule.minute
    ),
    durationMinutes = draft.schedule.durationMinutes,
    recurrencePreset = draft.recurrencePresetForUpdate(),
    existingChildren = existingDrafts.map { it.toFingerprint() },
    deletedChildIds = deletedChildren,
    addedChildren = addedDrafts.map { it.toFingerprint() }
)

/** The Update host replays this exact request while reconciliation is pending and the draft is untouched. */
internal data class CapturedEditTaskUpdate(
    val fingerprint: EditTaskDraftFingerprint,
    val request: UpdateTaskRequest
)

internal fun captureEditTaskUpdate(
    captured: CapturedEditTaskUpdate?,
    fingerprint: EditTaskDraftFingerprint,
    createRequest: () -> UpdateTaskRequest
): CapturedEditTaskUpdate = captured?.takeIf { it.fingerprint == fingerprint }
    ?: CapturedEditTaskUpdate(fingerprint = fingerprint, request = createRequest())

internal data class TaskEditorUpdateSession(
    val baselineTask: TaskSnapshotItem,
    val latestTask: TaskSnapshotItem,
    val baselineAddedDrafts: List<ChecklistEditorDraft>,
    val state: TaskEditorUpdateState,
    val existingDrafts: List<ExistingChecklistDraft>,
    val deletedChildren: Set<String> = emptySet(),
    val addedDrafts: List<ChecklistEditorDraft> = emptyList(),
    val capturedUpdate: CapturedEditTaskUpdate? = null
) {
    val activeExistingDrafts: List<ExistingChecklistDraft>
        get() = existingDrafts.filterNot { it.id in deletedChildren }

    val checklistDoneCount: Int
        get() = activeExistingDrafts.count { it.checked }

    val checklistTotalCount: Int
        get() = activeExistingDrafts.size + addedDrafts.size

    fun baselineFingerprint(choices: TaskEditorChoices = state.choices): EditTaskDraftFingerprint {
        val baselineDraft = initialTaskEditorUpdateState(baselineTask, choices).draft
        return editTaskDraftFingerprint(
            draft = baselineDraft,
            choices = choices,
            existingDrafts = initialExistingChecklistDrafts(baselineTask),
            deletedChildren = emptySet(),
            addedDrafts = baselineAddedDrafts
        )
    }

    fun currentFingerprint(choices: TaskEditorChoices = state.choices): EditTaskDraftFingerprint =
        editTaskDraftFingerprint(
            draft = state.draft,
            choices = choices,
            existingDrafts = existingDrafts,
            deletedChildren = deletedChildren,
            addedDrafts = addedDrafts
        )

    fun isDirty(choices: TaskEditorChoices = state.choices): Boolean =
        currentFingerprint(choices) != baselineFingerprint(choices)

    fun withDraft(next: TaskEditorDraft): TaskEditorUpdateSession {
        val nextState = state.withDraft(next)
        return if (nextState == state) this else copy(
            state = nextState,
            capturedUpdate = null
        )
    }

    fun withExistingDrafts(next: List<ExistingChecklistDraft>): TaskEditorUpdateSession =
        if (next == existingDrafts) this else copy(
            existingDrafts = next.toList(),
            state = state.copy(resultMessage = null, discardConfirmationVisible = false),
            capturedUpdate = null
        )

    fun withDeletedChild(childId: String): TaskEditorUpdateSession =
        if (childId in deletedChildren) this else copy(
            deletedChildren = deletedChildren + childId,
            state = state.copy(resultMessage = null, discardConfirmationVisible = false),
            capturedUpdate = null
        )

    fun withAddedDrafts(next: List<ChecklistEditorDraft>): TaskEditorUpdateSession =
        if (next == addedDrafts) this else copy(
            addedDrafts = next.toList(),
            state = state.copy(resultMessage = null, discardConfirmationVisible = false),
            capturedUpdate = null
        )

    fun withChoices(next: TaskEditorChoices): TaskEditorUpdateSession {
        val nextState = state.withChoices(next)
        val selectionChanged = nextState.draft.projectId != state.draft.projectId ||
            nextState.draft.assignee?.id != state.draft.assignee?.id
        return copy(
            state = nextState,
            capturedUpdate = capturedUpdate.takeUnless { selectionChanged }
        )
    }

    fun prepareSubmission(
        choices: TaskEditorChoices = state.choices,
        zoneId: ZoneId = ZoneId.systemDefault(),
        nextMutationId: () -> String = { UUID.randomUUID().toString() }
    ): TaskEditorUpdateSession? {
        val current = if (state.choices == choices) this else withChoices(choices)
        val mutationsAllowed = taskEditorUpdateChecklistAccess(
            task = current.latestTask,
            hostEnabled = true
        ).enabled
        val checklistCheck = checklistUpdateContentValidation(
            checklistMutationsAllowed = mutationsAllowed,
            activeExistingDrafts = current.activeExistingDrafts,
            addedDrafts = current.addedDrafts
        )
        if (!current.state.canSubmit || !checklistCheck.canSubmit) return null
        val project = choices.selectedProject(current.state.draft) ?: return null
        val nextCapture = captureEditTaskUpdate(
            captured = current.capturedUpdate,
            fingerprint = current.currentFingerprint(choices)
        ) {
            buildUpdateTaskRequest(
                task = current.baselineTask,
                title = current.state.draft.title,
                description = current.state.draft.description,
                project = project,
                selectedAssignee = current.state.draft.assignee,
                schedule = current.state.draft.schedule,
                recurrencePreset = current.state.draft.recurrencePresetForUpdate(),
                existingDrafts = if (mutationsAllowed) current.existingDrafts else emptyList(),
                deletedChildren = if (mutationsAllowed) current.deletedChildren else emptySet(),
                addedDrafts = if (mutationsAllowed) current.addedDrafts else emptyList(),
                zoneId = zoneId,
                nextMutationId = nextMutationId
            )
        }
        return current.copy(
            state = current.state.beginSubmission(),
            capturedUpdate = nextCapture
        )
    }
}

internal fun initialTaskEditorUpdateSession(
    task: TaskSnapshotItem,
    choices: TaskEditorChoices,
    initialAddedDrafts: List<ChecklistEditorDraft> = emptyList()
): TaskEditorUpdateSession = TaskEditorUpdateSession(
    baselineTask = task,
    latestTask = task,
    baselineAddedDrafts = initialAddedDrafts.toList(),
    state = initialTaskEditorUpdateState(task, choices),
    existingDrafts = initialExistingChecklistDrafts(task),
    deletedChildren = emptySet(),
    addedDrafts = initialAddedDrafts.toList(),
    capturedUpdate = null
)

internal fun reconcileTaskEditorUpdateSession(
    session: TaskEditorUpdateSession,
    task: TaskSnapshotItem,
    choices: TaskEditorChoices,
    initialAddedDrafts: List<ChecklistEditorDraft> = session.baselineAddedDrafts
): TaskEditorUpdateSession {
    val idleWithoutOverlay = !session.state.submitting &&
        session.state.resultMessage == null &&
        !session.state.discardConfirmationVisible
    val candidateSession = initialTaskEditorUpdateSession(
        task = task,
        choices = choices,
        initialAddedDrafts = initialAddedDrafts
    )
    val canReseed = idleWithoutOverlay && (
        !session.isDirty() ||
            session.currentFingerprint(choices) == candidateSession.baselineFingerprint(choices)
    )
    if (canReseed) {
        return candidateSession
    }
    return session.withChoices(choices).copy(
        latestTask = task
    )
}

internal fun checklistUpdateContentValidation(
    checklistMutationsAllowed: Boolean,
    activeExistingDrafts: List<ExistingChecklistDraft>,
    addedDrafts: List<ChecklistEditorDraft>
): TaskEditorUpdateContentValidation {
    if (!checklistMutationsAllowed) return TaskEditorUpdateContentValidation()
    val addedValidation = validateChecklistDrafts(addedDrafts)
    val existingBlank = activeExistingDrafts.any { it.title.isBlank() }
    val tooMany = activeExistingDrafts.size + addedDrafts.size > MAX_CHECKLIST_ITEMS
    val message = addedValidation.bannerMessage
        ?: "A checklist can have at most $MAX_CHECKLIST_ITEMS items".takeIf { tooMany }
        ?: "Each checklist item needs a title".takeIf {
            addedValidation.invalidDraftIds.isNotEmpty() || existingBlank
        }
    return TaskEditorUpdateContentValidation(
        canSubmit = message == null && addedValidation.invalidDraftIds.isEmpty() && !existingBlank && !tooMany,
        message = message
    )
}

internal fun buildUpdateTaskRequest(
    task: TaskSnapshotItem,
    title: String,
    description: String,
    project: ProjectItem,
    selectedAssignee: ProjectCollaborator?,
    schedule: TaskEditorSchedule,
    recurrencePreset: RecurrencePreset?,
    existingDrafts: List<ExistingChecklistDraft>,
    deletedChildren: Set<String>,
    addedDrafts: List<ChecklistEditorDraft>,
    zoneId: ZoneId = ZoneId.systemDefault(),
    nextMutationId: () -> String = { UUID.randomUUID().toString() }
): UpdateTaskRequest {
    check(schedule.isEmpty() || schedule.isComplete()) { "Update requests require a complete or empty schedule" }
    val originalById = task.checklistItems.associateBy { it.id }
    val changedExisting = existingDrafts.filterNot { it.id in deletedChildren }.flatMap { draft ->
        val original = originalById[draft.id] ?: return@flatMap emptyList()
        buildList {
            if (draft.title.trim() != original.title || draft.description != original.description) {
                add(
                    ChecklistMutation.Edit(
                        clientMutationId = nextMutationId(),
                        childId = draft.id,
                        expectedChildRevision = draft.childRevision,
                        title = draft.title.trim(),
                        description = draft.description
                    )
                )
            }
            if (draft.checked != original.checked) {
                add(
                    if (draft.checked) {
                        ChecklistMutation.Check(
                            clientMutationId = nextMutationId(),
                            childId = draft.id,
                            expectedChildRevision = draft.childRevision
                        )
                    } else {
                        ChecklistMutation.Uncheck(
                            clientMutationId = nextMutationId(),
                            childId = draft.id,
                            expectedChildRevision = draft.childRevision
                        )
                    }
                )
            }
        }
    }
    val deletions = task.checklistItems.filter { it.id in deletedChildren }.map {
        ChecklistMutation.Delete(
            clientMutationId = nextMutationId(),
            childId = it.id,
            expectedChildRevision = it.childRevision
        )
    }
    val additions = addedDrafts.map { draft ->
        ChecklistMutation.Add(
            clientMutationId = nextMutationId(),
            title = draft.title.trim(),
            description = draft.description
        )
    }
    val scheduleIsEmpty = schedule.isEmpty()
    val selectedStart = schedule.startDateTime()?.let { DateTimeSelection(it.toLocalDate(), it.hour, it.minute) }
    val requestedRecurrence = if (scheduleIsEmpty) RecurrencePreset.never else recurrencePreset
    val recurrenceChange = when {
        scheduleIsEmpty -> RecurrencePreset.never
        task.recurrence.kind == RecurrenceKind.custom -> requestedRecurrence
        requestedRecurrence != task.recurrence.preset -> requestedRecurrence
        else -> null
    }
    return UpdateTaskRequest(
        expectedOccurrenceKey = task.occurrenceKey,
        rootPatch = RootTaskPatch(
            expectedTaskRevision = task.taskRevision,
            title = title.trim(),
            description = NullablePatch.Value(description.trim().takeIf { it.isNotEmpty() }),
            projectId = project.id,
            assigneeId = NullablePatch.Value(assigneeIdForProject(project, selectedAssignee)),
            dueDatetime = NullablePatch.Value(selectedStart?.toIsoInstantString(zoneId)),
            durationMinutes = NullablePatch.Value(schedule.durationMinutes),
            recurrencePreset = recurrenceChange,
            timeZone = zoneId.id.takeIf {
                selectedStart != null && recurrenceChange != null && recurrenceChange != RecurrencePreset.never
            }
        ),
        childMutations = changedExisting + deletions + additions
    )
}

internal fun assigneeIdForProject(
    project: ProjectItem?,
    assignee: ProjectCollaborator?
): String? = assignee?.id?.takeIf { project?.canAssignTasks == true && it.isNotBlank() }

private fun ExistingChecklistDraft.toFingerprint(): ChecklistDraftFingerprint =
    ChecklistDraftFingerprint(id, title, description, checked)

private fun ChecklistEditorDraft.toFingerprint(): ChecklistDraftFingerprint =
    ChecklistDraftFingerprint(clientMutationId, title, description, false)

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
                    onCheckedChange = { checked ->
                        onDraftsChange(drafts.map { if (it.id == draft.id) it.copy(checked = checked) else it })
                    },
                    enabled = enabled
                )
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { title ->
                        onDraftsChange(drafts.map { if (it.id == draft.id) it.copy(title = title) else it })
                    },
                    label = { Text("Checklist item") },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.description.orEmpty(),
                    onValueChange = { description ->
                        onDraftsChange(
                            drafts.map {
                                if (it.id == draft.id) it.copy(description = description.takeIf(String::isNotBlank)) else it
                            }
                        )
                    },
                    label = { Text("Item details (optional)") },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = { onDelete(draft.id) }, enabled = enabled) { Text("Remove item") }
            }
        }
    }
}
