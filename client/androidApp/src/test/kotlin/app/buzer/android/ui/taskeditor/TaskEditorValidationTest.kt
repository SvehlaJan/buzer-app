package app.buzer.android.ui.taskeditor

import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.RecurrenceKind
import app.buzer.shared.model.RecurrencePreset
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskEditorValidationTest {
    @Test
    fun updateValidationIsVisibleImmediatelyButCreateStaysQuietUntilInteraction() {
        val validation = validateTaskEditor(
            draft = TaskEditorDraft(title = ""),
            selectedProject = null,
            projectAvailable = false,
            collaboratorsAvailable = false
        )

        val updateVisible = validation.visible(
            exposure = ValidationExposure.OnEntry,
            touchedFields = emptySet(),
            submitAttempted = false
        )
        val createVisible = validation.visible(
            exposure = ValidationExposure.OnInteraction,
            touchedFields = emptySet(),
            submitAttempted = false
        )

        assertEquals("Fix the highlighted fields", updateVisible.bannerMessage)
        assertEquals("Title is required", updateVisible.titleError)
        assertEquals("Project is required", updateVisible.projectError)
        assertEquals("Assignee is required for this project", updateVisible.assigneeError)
        assertEquals("Start time is required", updateVisible.scheduleError)
        assertEquals(null, createVisible.bannerMessage)
        assertFalse(validation.canSubmit)
    }

    @Test
    fun nonSelfCanUseAnEmptyScheduleOnlyWithNeverRepeat() {
        val nonSelf = ProjectCollaborator(id = "wife", displayName = "Wife", isCurrentUser = false)
        val project = ProjectItem(
            id = "project-family",
            name = "Family",
            canAssignTasks = true,
            shared = true,
            isInboxProject = false,
            collaborators = listOf(nonSelf),
            collaboratorsReady = true
        )
        val emptySchedule = TaskEditorDraft(
            title = "Ask about booking",
            projectId = project.id,
            assignee = nonSelf,
            recurrencePreset = RecurrencePreset.never
        )

        val repeatingValidation = validateTaskEditor(
            draft = emptySchedule.copy(recurrencePreset = RecurrencePreset.weekly),
            selectedProject = project
        )
        val neverValidation = validateTaskEditor(
            draft = emptySchedule,
            selectedProject = project
        )

        assertEquals(
            "Repeat requires a schedule",
            repeatingValidation.visible(ValidationExposure.OnEntry, emptySet(), false).repeatError
        )
        assertFalse(repeatingValidation.canSubmit)
        assertTrue(neverValidation.canSubmit)
    }

    @Test
    fun changingACustomRecurrenceScheduleRequiresAnExplicitRepeatReplacement() {
        val me = ProjectCollaborator(id = "me", displayName = "Me", isCurrentUser = true)
        val project = ProjectItem(
            id = "project-personal",
            name = "Personal",
            canAssignTasks = false,
            shared = false,
            isInboxProject = false,
            collaborators = listOf(me),
            collaboratorsReady = true
        )
        val customDraft = TaskEditorDraft(
            title = "Review finances",
            projectId = project.id,
            assignee = me,
            schedule = TaskEditorSchedule(LocalDate.of(2026, 7, 22), 10, 0, 30),
            recurrenceKind = RecurrenceKind.custom
        ).copy(schedule = TaskEditorSchedule(LocalDate.of(2026, 7, 22), 11, 0, 30))

        val changedCustom = validateTaskEditor(customDraft, selectedProject = project)
        val replacementSelected = validateTaskEditor(
            customDraft.copy(
                recurrencePreset = RecurrencePreset.weekly,
                repeatReplacementSelected = true
            ),
            selectedProject = project
        )
        val supportedRecurrence = validateTaskEditor(
            customDraft.copy(recurrenceKind = RecurrenceKind.supported),
            selectedProject = project
        )

        assertEquals(
            "Choose a supported repeat before saving",
            changedCustom.visible(ValidationExposure.OnEntry, emptySet(), false).repeatError
        )
        assertFalse(changedCustom.canSubmit)
        assertTrue(replacementSelected.canSubmit)
        assertTrue(supportedRecurrence.canSubmit)
    }

    @Test
    fun unavailableCollaboratorsDisableSubmitUntilMembershipCanBeValidated() {
        val wife = ProjectCollaborator(id = "wife", displayName = "Wife", isCurrentUser = false)
        val draft = TaskEditorDraft(
            title = "Ask about booking",
            projectId = "project-family",
            assignee = wife,
            schedule = TaskEditorSchedule(LocalDate.of(2026, 7, 22), 10, 0, 30)
        )
        val loadingProject = ProjectItem(
            id = draft.projectId!!,
            name = "Family",
            canAssignTasks = true,
            shared = true,
            isInboxProject = false,
            collaboratorsReady = false
        )
        val resolvedProject = loadingProject.copy(
            collaborators = listOf(
                ProjectCollaborator(id = "me", displayName = "Me", isCurrentUser = true)
            ),
            collaboratorsReady = true
        )

        val loadingValidation = validateTaskEditor(draft, selectedProject = loadingProject)
        val resolvedValidation = validateTaskEditor(draft, selectedProject = resolvedProject)

        assertFalse(loadingValidation.canSubmit)
        assertEquals(setOf(TaskEditorField.Assignee), loadingValidation.unavailableFields)
        assertEquals(
            null,
            loadingValidation.visible(ValidationExposure.OnEntry, emptySet(), false).assigneeError
        )
        assertEquals(
            "Assignee is unavailable for this project",
            resolvedValidation.visible(ValidationExposure.OnEntry, emptySet(), false).assigneeError
        )
        assertFalse(resolvedValidation.canSubmit)
    }

    @Test
    fun resolvedPersonalProjectRejectsANonMemberAssignee() {
        val me = ProjectCollaborator(id = "me", displayName = "Me", isCurrentUser = true)
        val wife = ProjectCollaborator(id = "wife", displayName = "Wife", isCurrentUser = false)
        val project = ProjectItem(
            id = "project-personal",
            name = "Personal",
            canAssignTasks = false,
            shared = false,
            isInboxProject = false,
            collaborators = listOf(me),
            collaboratorsReady = true
        )
        val validation = validateTaskEditor(
            draft = TaskEditorDraft(
                title = "Ask about booking",
                projectId = project.id,
                assignee = wife
            ),
            selectedProject = project
        )
        val currentUserValidation = validateTaskEditor(
            draft = TaskEditorDraft(
                title = "Review finances",
                projectId = project.id,
                assignee = me,
                schedule = TaskEditorSchedule(LocalDate.of(2026, 7, 22), 10, 0, 30)
            ),
            selectedProject = project
        )

        assertEquals(
            "Assignee is unavailable for this project",
            validation.visible(ValidationExposure.OnEntry, emptySet(), false).assigneeError
        )
        assertFalse(validation.canSubmit)
        assertTrue(currentUserValidation.canSubmit)
    }

    @Test
    fun presentationKeepsTheHostVerbAndOptionalUpdateContextSeparateFromValidation() {
        val add = TaskEditorPresentation(screenTitle = "Add task", primaryVerb = "Add")
        val repair = TaskEditorPresentation(
            screenTitle = "Repair task",
            primaryVerb = "Repair",
            contextMessage = "This task needs attention",
            showUpdateFooter = true
        )

        assertEquals("Add task", add.screenTitle)
        assertEquals("Add", add.primaryVerb)
        assertEquals(null, add.contextMessage)
        assertFalse(add.showUpdateFooter)
        assertEquals("Repair", repair.primaryVerb)
        assertEquals("This task needs attention", repair.contextMessage)
        assertTrue(repair.showUpdateFooter)
    }

    @Test
    fun interactionShowsOnlyTouchedErrorsAndSubmitShowsEveryErrorUntilTheDraftIsValid() {
        val me = ProjectCollaborator(id = "me", displayName = "Me", isCurrentUser = true)
        val project = ProjectItem(
            id = "project-personal",
            name = "Personal",
            canAssignTasks = false,
            shared = false,
            isInboxProject = false,
            collaborators = listOf(me),
            collaboratorsReady = true
        )
        val invalidDraft = TaskEditorDraft(
            title = "",
            projectId = project.id,
            assignee = me
        )
        val interactionValidation = validateTaskEditor(invalidDraft, selectedProject = project)
        val validValidation = validateTaskEditor(
            invalidDraft.copy(
                title = "Review finances",
                schedule = TaskEditorSchedule(LocalDate.of(2026, 7, 22), 10, 0, 30)
            ),
            selectedProject = project
        )

        val touchedTitle = interactionValidation.visible(
            exposure = ValidationExposure.OnInteraction,
            touchedFields = setOf(TaskEditorField.Title),
            submitAttempted = false
        )
        val submitted = interactionValidation.visible(
            exposure = ValidationExposure.OnInteraction,
            touchedFields = emptySet(),
            submitAttempted = true
        )

        assertEquals("Title is required", touchedTitle.titleError)
        assertEquals(null, touchedTitle.scheduleError)
        assertEquals("Fix the highlighted fields", touchedTitle.bannerMessage)
        assertEquals("Title is required", submitted.titleError)
        assertEquals("Start time is required", submitted.scheduleError)
        assertEquals("Fix the highlighted fields", submitted.bannerMessage)
        assertEquals(null, validValidation.visible(ValidationExposure.OnEntry, emptySet(), false).bannerMessage)
        assertTrue(validValidation.canSubmit)
    }
}
