package app.buzer.android.ui.taskeditor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.ui.components.ChecklistEditor
import app.buzer.android.ui.components.SessionWarningBanner
import app.buzer.android.ui.navigation.ExternalTodoistLauncher
import app.buzer.android.ui.status.WarningStatus
import app.buzer.android.ui.theme.Spacing
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.HierarchyStatus
import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.RecurrenceKind
import app.buzer.shared.model.RecurrencePreset
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.UpdateTaskRequest
import app.buzer.shared.ui.taskeditor.ChecklistEditorDraft
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal const val GENERIC_TASK_EDITOR_UPDATE_ERROR = "Task update failed. Your draft is still here; retry."

/** State and transitions shared by Edit, Repair, and blocker-update callers. */
internal data class TaskEditorUpdateState(
    val draft: TaskEditorDraft,
    val choices: TaskEditorChoices,
    val submitting: Boolean = false,
    val resultMessage: String? = null,
    val successDelivered: Boolean = false,
    val discardConfirmationVisible: Boolean = false,
    val warningRefreshInFlight: Boolean = false
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
        get() = !choices.loading && !submitting && !warningRefreshInFlight && validation.canSubmit

    val editingEnabled: Boolean
        get() = !submitting && !warningRefreshInFlight

    fun withDraft(next: TaskEditorDraft): TaskEditorUpdateState {
        val normalizedNext = normalizeTaskEditorUpdateDraft(previous = draft, next = next)
        return if (normalizedNext == draft) this else copy(
            draft = normalizedNext,
            resultMessage = null,
            discardConfirmationVisible = false
        )
    }

    fun withChoices(next: TaskEditorChoices): TaskEditorUpdateState {
        val reconciledDraft = reconcileTaskEditorUpdateMembership(
            draft = draft,
            choices = next
        )
        val selectionChanged = reconciledDraft.projectId != draft.projectId ||
            reconciledDraft.assignee?.id != draft.assignee?.id
        return copy(
            draft = reconciledDraft,
            choices = next,
            resultMessage = resultMessage.takeUnless { selectionChanged },
            discardConfirmationVisible = discardConfirmationVisible && !selectionChanged
        )
    }

    fun beginSubmission(): TaskEditorUpdateState = if (canSubmit) {
        copy(
            submitting = true,
            resultMessage = null,
            discardConfirmationVisible = false
        )
    } else {
        this
    }

    /** The warning-refresh button is disabled while any refresh is already in flight. */
    fun beginWarningRefresh(): TaskEditorUpdateState = if (warningRefreshInFlight) {
        this
    } else {
        copy(warningRefreshInFlight = true)
    }

    /**
     * Clears the warning only when the authoritative refresh actually succeeded; a failed
     * refresh keeps the existing warning message visible and simply unlocks the surface again.
     */
    fun completeWarningRefresh(succeeded: Boolean): TaskEditorUpdateState = if (succeeded) {
        copy(warningRefreshInFlight = false, resultMessage = null)
    } else {
        copy(warningRefreshInFlight = false)
    }
}

/** Validation contributed by the update editor's direct-checklist state. */
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
    state.submitting -> TaskEditorUpdateCancelRequest.Ignore
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
    choices: TaskEditorChoices
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
    choices: TaskEditorChoices
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
    previous.recurrenceKind == RecurrenceKind.custom &&
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
    resultMessage = GENERIC_TASK_EDITOR_UPDATE_ERROR
)

/**
 * Deepened stateful Update host. Owns root + direct-checklist draft state, eager validation,
 * dirty-draft fingerprinting, replay-safe [UpdateTaskRequest] construction, discard confirmation,
 * and inline project-choice loading status across Edit, Repair, and Deadline Blocker Reschedule.
 */
@Composable
internal fun TaskEditorUpdateScreen(
    task: TaskSnapshotItem,
    choices: TaskEditorChoices,
    presentation: TaskEditorPresentation,
    now: LocalDateTime = LocalDateTime.now(),
    initialAddedDrafts: List<ChecklistEditorDraft> = emptyList(),
    onRetryChoices: () -> Unit,
    onRefreshWarning: suspend () -> Unit = {},
    onUpdate: suspend (UpdateTaskRequest) -> CoordinatorActionResult,
    onSuccess: () -> Unit,
    onCancel: () -> Unit,
    footerContent: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentTask by rememberUpdatedState(task)
    val currentChoices by rememberUpdatedState(choices)
    val currentInitialAddedDrafts by rememberUpdatedState(initialAddedDrafts)
    var session by remember(task.id) {
        mutableStateOf(initialTaskEditorUpdateSession(task, choices, initialAddedDrafts))
    }

    LaunchedEffect(task, choices, initialAddedDrafts) {
        session = reconcileTaskEditorUpdateSession(
            session = session,
            task = task,
            choices = choices,
            initialAddedDrafts = initialAddedDrafts
        )
    }

    val state = session.state
    val checklistMutationsAllowed = taskEditorUpdateChecklistAccess(
        task = session.latestTask,
        hostEnabled = true
    ).enabled
    val currentContentValidation = checklistUpdateContentValidation(
        checklistMutationsAllowed = checklistMutationsAllowed,
        activeExistingDrafts = session.activeExistingDrafts,
        addedDrafts = session.addedDrafts
    )
    val validation = effectiveTaskEditorUpdateValidation(state, currentContentValidation)

    fun requestCancel() {
        when (taskEditorUpdateCancelRequest(state, session.isDirty(choices))) {
            TaskEditorUpdateCancelRequest.Ignore -> Unit
            TaskEditorUpdateCancelRequest.ConfirmDiscard -> {
                focusManager.clearFocus(force = true)
                session = session.copy(state = state.copy(discardConfirmationVisible = true))
            }
            TaskEditorUpdateCancelRequest.Cancel -> onCancel()
        }
    }

    fun submit() {
        val nextSession = session.prepareSubmission(choices = choices) ?: return
        val request = requireNotNull(nextSession.capturedUpdate).request
        session = nextSession
        scope.launch {
            try {
                val result = onUpdate(request)
                val transition = reduceTaskEditorUpdateResult(session.state, result)
                session = session.copy(state = transition.state)
                if (transition.shouldInvokeSuccess) onSuccess()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.error(
                    "task_editor_update failed type=${error::class.simpleName} message=${error.message}",
                    error
                )
                session = session.copy(state = failTaskEditorUpdateSubmission(session.state))
            }
        }
    }

    fun refreshWarning() {
        if (session.state.warningRefreshInFlight) return
        session = session.copy(state = session.state.beginWarningRefresh())
        scope.launch {
            val refreshSucceeded = try {
                onRefreshWarning()
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.warn(
                    "task_editor_update warning_refresh_failed type=${error::class.simpleName} message=${error.message}"
                )
                false
            }
            val completed = session.copy(
                state = session.state.completeWarningRefresh(succeeded = refreshSucceeded)
            )
            session = if (refreshSucceeded) {
                reconcileTaskEditorUpdateSession(
                    session = completed,
                    task = currentTask,
                    choices = currentChoices,
                    initialAddedDrafts = currentInitialAddedDrafts
                )
            } else {
                completed
            }
        }
    }

    BackHandler(enabled = state.discardConfirmationVisible) {
        session = session.copy(state = session.state.copy(discardConfirmationVisible = false))
    }
    BackHandler(enabled = !state.discardConfirmationVisible) {
        requestCancel()
    }

    Box(modifier = modifier.fillMaxSize()) {
        TaskEditorScaffold(
            presentation = presentation,
            draft = state.draft,
            onDraftChange = { next -> session = session.withDraft(next) },
            now = now,
            projects = choices.projects,
            currentUser = choices.currentUser(state.draft),
            checklistDoneCount = session.checklistDoneCount,
            checklistTotalCount = session.checklistTotalCount,
            checklistContent = {
                val checklistAccess = taskEditorUpdateChecklistAccess(
                    task = session.latestTask,
                    hostEnabled = state.editingEnabled
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("taskEditorUpdateChecklistSection"),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    checklistAccess.warningMessage?.let { warning ->
                        WarningStatus(
                            message = warning,
                            modifier = Modifier.testTag("taskEditorUpdateChecklistAccess")
                        )
                    }
                    currentContentValidation.message?.let { Text(it) }
                    ExistingChecklistEditor(
                        drafts = session.activeExistingDrafts,
                        onDraftsChange = { updatedVisible ->
                            val byId = updatedVisible.associateBy { it.id }
                            session = session.withExistingDrafts(
                                session.existingDrafts.map { byId[it.id] ?: it }
                            )
                        },
                        onDelete = { childId -> session = session.withDeletedChild(childId) },
                        enabled = checklistAccess.enabled
                    )
                    ChecklistEditor(
                        drafts = session.addedDrafts,
                        onDraftsChange = { next -> session = session.withAddedDrafts(next) },
                        nextClientMutationId = { UUID.randomUUID().toString() },
                        enabled = checklistAccess.enabled,
                        maxItems = (MAX_CHECKLIST_ITEMS - session.activeExistingDrafts.size).coerceAtLeast(0)
                    )
                }
            },
            footerContent = footerContent,
            onRetryCollaborators = onRetryChoices,
            onPrimaryAction = ::submit,
            onCancel = ::requestCancel,
            validation = validation,
            modifier = Modifier.fillMaxSize(),
            enabled = state.editingEnabled,
            primaryActionInProgress = state.submitting,
            titleModifier = Modifier.testTag("editTaskTitleInput"),
            warningBanner = state.resultMessage?.let { msg ->
                {
                    SessionWarningBanner(
                        message = msg,
                        onRefresh = ::refreshWarning,
                        onOpenTodoist = {
                            ExternalTodoistLauncher.launchTask(context, session.latestTask.todoistTaskId)
                        },
                        refreshing = state.warningRefreshInFlight
                    )
                }
            }
        )
        TaskEditorProjectLoadStatus(
            loading = choices.loading,
            message = choices.errorMessage,
            onRetry = onRetryChoices
        )
    }

    if (state.discardConfirmationVisible) {
        AlertDialog(
            onDismissRequest = {
                session = session.copy(state = session.state.copy(discardConfirmationVisible = false))
            },
            title = { Text("Discard changes?") },
            text = { Text("Your task edits have not been saved.") },
            confirmButton = {
                TextButton(onClick = onCancel) { Text("Discard") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        session = session.copy(state = session.state.copy(discardConfirmationVisible = false))
                    }
                ) {
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
    runCatching { LocalDate.parse(this) }
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
