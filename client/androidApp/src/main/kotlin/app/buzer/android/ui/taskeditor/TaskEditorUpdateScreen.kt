package app.buzer.android.ui.taskeditor

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.ui.status.ErrorAlertDialog
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.HierarchyStatus
import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.RecurrenceKind
import app.buzer.shared.model.RecurrencePreset
import app.buzer.shared.model.TaskSnapshotItem
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val GENERIC_TASK_EDITOR_UPDATE_ERROR = "Task update failed. Your draft is still here; retry."
internal const val TASK_EDITOR_UPDATE_PENDING_MESSAGE = "Saved; waiting for confirmation"
private const val PENDING_CONFIRMATION_RETRY_DELAY_MILLIS = 5_000L

/**
 * Choice data remains host-owned because each caller has its own loader and retry boundary.
 * The update editor uses it only to validate and render the current draft.
 */
internal data class TaskEditorUpdateChoices(
    val projects: List<ProjectItem> = emptyList(),
    val loading: Boolean = false,
    val errorMessage: String? = null,
    val membershipWasRefreshed: Boolean = false
) {
    fun selectedProject(draft: TaskEditorDraft): ProjectItem? =
        projects.firstOrNull { it.id == draft.projectId }

    fun currentUser(draft: TaskEditorDraft): ProjectCollaborator =
        selectedProject(draft)?.collaborators?.firstOrNull { it.isCurrentUser }
            ?: projects.asSequence().flatMap { it.collaborators.asSequence() }.firstOrNull { it.isCurrentUser }
            ?: draft.assignee?.takeIf { it.isCurrentUser }
            ?: ProjectCollaborator(id = "", displayName = "Me", isCurrentUser = true)

    /** Loaded choices keep Project repairable even when this snapshot's selection is missing. */
    fun projectChoicesAvailable(): Boolean = projects.isNotEmpty()

    fun collaboratorsAvailable(draft: TaskEditorDraft): Boolean = selectedProject(draft)?.let { project ->
        !project.canAssignTasks || project.collaboratorsReady
    } == true

    /** Mirrors the assignee projection sent by an update request for dirty-draft comparisons. */
    fun assigneeIdForFingerprint(draft: TaskEditorDraft): String? {
        val selectedProject = selectedProject(draft)
        return draft.assignee?.id?.takeIf {
            selectedProject?.canAssignTasks == true && it.isNotBlank()
        } ?: draft.assignee?.id?.takeIf { selectedProject == null && it.isNotBlank() }
    }
}

/** State and transitions shared by Edit, Repair, and blocker-update callers. */
internal data class TaskEditorUpdateState(
    val draft: TaskEditorDraft,
    val choices: TaskEditorUpdateChoices,
    val submitting: Boolean = false,
    val pendingConfirmation: Boolean = false,
    val confirmationAttempt: Int = 0,
    val resultMessage: String? = null,
    val successDelivered: Boolean = false,
    val discardConfirmationVisible: Boolean = false
) {
    private val selectedProject: ProjectItem?
        get() = choices.selectedProject(draft)

    val validation: TaskEditorValidation
        get() = validateTaskEditor(
            draft = draft,
            selectedProject = selectedProject,
            projectAvailable = choices.projectChoicesAvailable(),
            collaboratorsAvailable = choices.collaboratorsAvailable(draft)
        ).withUnavailableSelectedProjectError(
            selectedProject = selectedProject,
            choicesAvailable = choices.projectChoicesAvailable()
        )

    val visibleValidation: VisibleTaskEditorValidation
        get() = validation.visible(
            exposure = ValidationExposure.OnEntry,
            touchedFields = emptySet(),
            submitAttempted = false
        )

    val canSubmit: Boolean
        get() = !choices.loading && !submitting && !pendingConfirmation && validation.canSubmit

    val editingEnabled: Boolean
        get() = !submitting && !pendingConfirmation

    fun withDraft(next: TaskEditorDraft): TaskEditorUpdateState {
        val normalizedNext = normalizeTaskEditorUpdateDraft(previous = draft, next = next)
        return if (normalizedNext == draft) this else copy(
            draft = normalizedNext,
            pendingConfirmation = false,
            confirmationAttempt = 0,
            resultMessage = null,
            discardConfirmationVisible = false
        )
    }

    fun withChoices(next: TaskEditorUpdateChoices): TaskEditorUpdateState {
        if (pendingConfirmation) return copy(choices = next)
        val reconciledDraft = reconcileTaskEditorUpdateMembership(
            draft = draft,
            choices = next
        )
        val selectionChanged = reconciledDraft.projectId != draft.projectId ||
            reconciledDraft.assignee?.id != draft.assignee?.id
        return copy(
            draft = reconciledDraft,
            choices = next,
            pendingConfirmation = pendingConfirmation && !selectionChanged,
            resultMessage = resultMessage.takeUnless { selectionChanged },
            discardConfirmationVisible = discardConfirmationVisible && !selectionChanged
        )
    }

    fun beginSubmission(): TaskEditorUpdateState = if (canSubmit) {
        copy(
            submitting = true,
            pendingConfirmation = false,
            confirmationAttempt = 0,
            resultMessage = null,
            discardConfirmationVisible = false
        )
    } else {
        this
    }
}

/** Validation contributed by an editable host slot such as the direct-checklist editor. */
internal data class TaskEditorUpdateContentValidation(
    val canSubmit: Boolean = true,
    val message: String? = null
)

/** The host uses this pure disposition for both Back and Cancel. */
internal enum class TaskEditorUpdateCancelRequest { Ignore, ConfirmDiscard, Cancel }

internal fun taskEditorUpdateCancelRequest(
    state: TaskEditorUpdateState,
    isDirty: Boolean
): TaskEditorUpdateCancelRequest = when {
    state.submitting || state.pendingConfirmation -> TaskEditorUpdateCancelRequest.Ignore
    isDirty -> TaskEditorUpdateCancelRequest.ConfirmDiscard
    else -> TaskEditorUpdateCancelRequest.Cancel
}

/** Nested Todoist checklist data cannot be safely mutated, but root task fields remain editable. */
internal data class TaskEditorUpdateChecklistAccess(
    val enabled: Boolean,
    val warningMessage: String? = null
)

internal fun taskEditorUpdateChecklistAccess(
    task: TaskSnapshotItem,
    hostEnabled: Boolean
): TaskEditorUpdateChecklistAccess = if (task.hierarchyStatus == HierarchyStatus.unsupported) {
    TaskEditorUpdateChecklistAccess(
        enabled = false,
        warningMessage = task.hierarchyViolation ?: "This task has unsupported nested checklist items"
    )
} else {
    TaskEditorUpdateChecklistAccess(enabled = hostEnabled)
}

internal fun effectiveTaskEditorUpdateValidation(
    state: TaskEditorUpdateState,
    contentValidation: TaskEditorUpdateContentValidation
): VisibleTaskEditorValidation = state.visibleValidation.copy(
    canSubmit = state.canSubmit && contentValidation.canSubmit,
    bannerMessage = state.visibleValidation.bannerMessage ?: contentValidation.message
)

internal data class TaskEditorUpdateTransition(
    val state: TaskEditorUpdateState,
    val shouldInvokeSuccess: Boolean = false
)

internal fun initialTaskEditorUpdateState(
    task: TaskSnapshotItem,
    choices: TaskEditorUpdateChoices
): TaskEditorUpdateState = TaskEditorUpdateState(
    draft = initialTaskEditorUpdateDraft(task),
    choices = choices
).withChoices(choices)

internal fun initialTaskEditorUpdateDraft(task: TaskSnapshotItem): TaskEditorDraft {
    val schedule = task.dueDatetime?.toTaskEditorUpdateSchedule()
        ?: task.dueDate?.toTaskEditorUpdateDateSchedule()
        ?: TaskEditorSchedule()
    val fallbackAssignee = task.assigneeId?.let { assigneeId ->
        ProjectCollaborator(
            id = assigneeId,
            displayName = if (task.isAssignedToUser) "Me" else assigneeId,
            isCurrentUser = task.isAssignedToUser
        )
    } ?: ProjectCollaborator(
        id = "",
        displayName = "Me",
        isCurrentUser = task.isAssignedToUser
    )
    return TaskEditorDraft(
        title = task.title,
        description = task.description.orEmpty(),
        projectId = task.projectId,
        assignee = fallbackAssignee,
        schedule = schedule.copy(durationMinutes = task.durationMinutes),
        recurrencePreset = task.recurrence.preset ?: RecurrencePreset.never,
        recurrenceKind = task.recurrence.kind,
        originalSchedule = schedule.copy(durationMinutes = task.durationMinutes)
    )
}

/**
 * A custom Todoist recurrence remains unchanged until the user explicitly replaces it. Every
 * other editor draft has a concrete preset, including a snapshot with no recurrence (Never).
 */
internal fun TaskEditorDraft.recurrencePresetForUpdate(): RecurrencePreset? =
    recurrencePreset.takeUnless {
        recurrenceKind == RecurrenceKind.custom && !repeatReplacementSelected
    }

internal fun reconcileTaskEditorUpdateMembership(
    draft: TaskEditorDraft,
    choices: TaskEditorUpdateChoices
): TaskEditorDraft {
    val project = choices.selectedProject(draft) ?: return draft
    val currentUser = choices.currentUser(draft)
    if (!project.canAssignTasks) return draft.copy(assignee = currentUser)
    if (!choices.membershipWasRefreshed || !project.collaboratorsReady) {
        return draft.copy(
            assignee = project.collaborators.firstOrNull { it.id == draft.assignee?.id } ?: draft.assignee
        )
    }
    val candidates = project.collaborators
    val selectedAssignee = draft.assignee?.takeIf { candidate ->
        candidates.any { it.id == candidate.id }
    } ?: candidates.firstOrNull { it.isCurrentUser }
    return draft.copy(assignee = selectedAssignee)
}

/** A non-self no-schedule choice is an explicit replacement for an unsupported custom rule. */
internal fun normalizeTaskEditorUpdateDraft(
    previous: TaskEditorDraft,
    next: TaskEditorDraft
): TaskEditorDraft = if (
    previous.recurrenceKind == app.buzer.shared.model.RecurrenceKind.custom &&
        previous.schedule != next.schedule &&
        next.schedule.isEmpty() &&
        next.assignee?.isCurrentUser == false
) {
    next.copy(
        recurrencePreset = RecurrencePreset.never,
        repeatReplacementSelected = true
    )
} else {
    next
}

internal fun reduceTaskEditorUpdateResult(
    state: TaskEditorUpdateState,
    result: CoordinatorActionResult
): TaskEditorUpdateTransition = when (result) {
    CoordinatorActionResult.Success -> TaskEditorUpdateTransition(
        state = state.copy(
            submitting = false,
            pendingConfirmation = false,
            resultMessage = null,
            successDelivered = true
        ),
        shouldInvokeSuccess = !state.successDelivered
    )
    is CoordinatorActionResult.Warning,
    is CoordinatorActionResult.Conflict,
    is CoordinatorActionResult.TransportFailure,
    is CoordinatorActionResult.LocalReconciliationFailed,
    CoordinatorActionResult.ChildActionInFlight -> TaskEditorUpdateTransition(
        state = state.copy(
            submitting = false,
            pendingConfirmation = false,
            confirmationAttempt = 0,
            resultMessage = when (result) {
                is CoordinatorActionResult.Warning -> result.message
                is CoordinatorActionResult.Conflict -> result.message
                is CoordinatorActionResult.TransportFailure -> result.message
                is CoordinatorActionResult.LocalReconciliationFailed -> result.message
                CoordinatorActionResult.ChildActionInFlight -> "Checklist update is already in progress"
                CoordinatorActionResult.Success -> null
            }
        )
    )
}

internal fun failTaskEditorUpdateSubmission(state: TaskEditorUpdateState): TaskEditorUpdateState = state.copy(
    submitting = false,
    pendingConfirmation = false,
    confirmationAttempt = 0,
    resultMessage = GENERIC_TASK_EDITOR_UPDATE_ERROR
)

/**
 * Stateful Update host. It owns form state, eager validation, result transitions, and discard
 * confirmation; screen wrappers retain only their choice loading and full-request construction.
 */
@Composable
internal fun TaskEditorUpdateScreen(
    task: TaskSnapshotItem,
    choices: TaskEditorUpdateChoices,
    presentation: TaskEditorPresentation,
    now: LocalDateTime,
    checklistDoneCount: Int,
    checklistTotalCount: Int,
    checklistContent: @Composable (enabled: Boolean, errorMessage: String?) -> Unit,
    footerContent: @Composable () -> Unit,
    onRetryChoices: () -> Unit,
    contentValidation: () -> TaskEditorUpdateContentValidation = { TaskEditorUpdateContentValidation() },
    isDirty: (TaskEditorDraft) -> Boolean,
    /** Must reuse its captured action ID so pending retries only refresh backend authority. */
    onUpdate: suspend (TaskEditorDraft) -> CoordinatorActionResult,
    onSuccess: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var state by remember(task.id) { mutableStateOf(initialTaskEditorUpdateState(task, choices)) }
    val currentContentValidation = contentValidation()
    val validation = effectiveTaskEditorUpdateValidation(state, currentContentValidation)

    LaunchedEffect(task.id, choices) {
        val pristine = state.draft == initialTaskEditorUpdateDraft(task) &&
            !state.submitting &&
            !state.pendingConfirmation &&
            state.resultMessage == null &&
            !state.discardConfirmationVisible
        state = if (pristine) {
            initialTaskEditorUpdateState(task, choices)
        } else {
            state.withChoices(choices)
        }
    }

    fun requestCancel() {
        when (taskEditorUpdateCancelRequest(state, isDirty(state.draft))) {
            TaskEditorUpdateCancelRequest.Ignore -> Unit
            TaskEditorUpdateCancelRequest.ConfirmDiscard -> {
                focusManager.clearFocus(force = true)
                state = state.copy(discardConfirmationVisible = true)
            }
            TaskEditorUpdateCancelRequest.Cancel -> onCancel()
        }
    }

    fun submit() {
        if (!state.canSubmit || !contentValidation().canSubmit) return
        state = state.beginSubmission()
        val submittingDraft = state.draft
        scope.launch {
            try {
                val transition = reduceTaskEditorUpdateResult(state, onUpdate(submittingDraft))
                state = transition.state
                if (transition.shouldInvokeSuccess) onSuccess()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.error(
                    "task_editor_update failed type=${error::class.simpleName} message=${error.message}",
                    error
                )
                state = failTaskEditorUpdateSubmission(state)
            }
        }
    }

    BackHandler(enabled = state.discardConfirmationVisible) {
        state = state.copy(discardConfirmationVisible = false)
    }
    BackHandler(enabled = !state.discardConfirmationVisible) {
        requestCancel()
    }

    LaunchedEffect(state.pendingConfirmation, state.confirmationAttempt) {
        if (!state.pendingConfirmation) return@LaunchedEffect
        delay(PENDING_CONFIRMATION_RETRY_DELAY_MILLIS)
        if (!state.pendingConfirmation) return@LaunchedEffect
        try {
            val transition = reduceTaskEditorUpdateResult(state, onUpdate(state.draft))
            state = transition.state
            if (transition.shouldInvokeSuccess) onSuccess()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLog.error(
                "task_editor_update confirmation_failed type=${error::class.simpleName} message=${error.message}",
                error
            )
            state = failTaskEditorUpdateSubmission(state)
        }
    }

    TaskEditorScaffold(
        presentation = presentation,
        draft = state.draft,
        onDraftChange = { next -> state = state.withDraft(next) },
        now = now,
        projects = choices.projects,
        currentUser = choices.currentUser(state.draft),
        checklistDoneCount = checklistDoneCount,
        checklistTotalCount = checklistTotalCount,
        checklistContent = { checklistContent(state.editingEnabled, currentContentValidation.message) },
        footerContent = footerContent,
        onRetryCollaborators = onRetryChoices,
        onPrimaryAction = ::submit,
        onCancel = ::requestCancel,
        validation = validation,
        modifier = modifier,
        enabled = state.editingEnabled,
        primaryActionInProgress = state.submitting
    )

    if (state.pendingConfirmation) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Task update") },
            text = { Text(TASK_EDITOR_UPDATE_PENDING_MESSAGE) },
            confirmButton = {}
        )
    } else {
        state.resultMessage?.let { message ->
        ErrorAlertDialog(
            title = "Task update",
            message = message,
            onDismiss = { state = state.copy(resultMessage = null) },
            retryLabel = "Retry".takeUnless { state.pendingConfirmation },
            onRetry = ::submit
        )
        }
    }
    if (state.discardConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { state = state.copy(discardConfirmationVisible = false) },
            title = { Text("Discard changes?") },
            text = { Text("Your task edits have not been saved.") },
            confirmButton = {
                TextButton(onClick = onCancel) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { state = state.copy(discardConfirmationVisible = false) }) {
                    Text("Keep editing")
                }
            }
        )
    }
}

private fun String.toTaskEditorUpdateSchedule(): TaskEditorSchedule? {
    val local = runCatching { Instant.parse(this).atZone(ZoneId.systemDefault()).toLocalDateTime() }
        .recoverCatching { LocalDateTime.parse(this) }
        .getOrNull()
        ?: return null
    return TaskEditorSchedule(date = local.toLocalDate(), hour = local.hour, minute = local.minute)
}

private fun String.toTaskEditorUpdateDateSchedule(): TaskEditorSchedule? =
    runCatching { java.time.LocalDate.parse(this) }
        .getOrNull()
        ?.let { TaskEditorSchedule(date = it) }

private fun TaskEditorValidation.withUnavailableSelectedProjectError(
    selectedProject: ProjectItem?,
    choicesAvailable: Boolean
): TaskEditorValidation = if (
    selectedProject == null && choicesAvailable &&
        errors[TaskEditorField.Project] == null
) {
    copy(errors = errors + (TaskEditorField.Project to "Project is unavailable"))
} else {
    this
}
