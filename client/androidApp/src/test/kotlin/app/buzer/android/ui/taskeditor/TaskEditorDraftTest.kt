package app.buzer.android.ui.taskeditor

import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.RecurrencePreset
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskEditorDraftTest {
    @Test
    fun scheduleDistinguishesCompleteEmptyAndPartialValues() {
        assertEquals(true, TaskEditorSchedule(LocalDate.of(2026, 7, 21), 10, 15, 30).isComplete())
        assertEquals(true, TaskEditorSchedule().isEmpty())
        assertEquals(false, TaskEditorSchedule(date = LocalDate.of(2026, 7, 21)).isComplete())
        assertEquals(false, TaskEditorSchedule(durationMinutes = 30).isEmpty())
    }

    @Test
    fun noScheduleClearsOnlyANonSelfDraftAndDisablesRecurrence() {
        val scheduledNonSelf = draft(
            assignee = collaborator(isCurrentUser = false),
            schedule = TaskEditorSchedule(LocalDate.of(2026, 7, 21), 10, 15, 30),
            recurrencePreset = RecurrencePreset.weekly
        )

        assertEquals(
            scheduledNonSelf.copy(
                schedule = TaskEditorSchedule(),
                recurrencePreset = RecurrencePreset.never
            ),
            setNoSchedule(scheduledNonSelf)
        )
    }

    @Test
    fun noScheduleLeavesASelfDraftUntouched() {
        val scheduledSelf = draft(
            assignee = collaborator(isCurrentUser = true),
            schedule = TaskEditorSchedule(LocalDate.of(2026, 7, 21), 10, 15, 30),
            recurrencePreset = RecurrencePreset.weekly
        )

        assertEquals(scheduledSelf, setNoSchedule(scheduledSelf))
    }

    @Test
    fun applyingCustomValuesUpdatesOnlyTheMatchingScheduleField() {
        val initial = draft(
            schedule = TaskEditorSchedule(LocalDate.of(2026, 7, 21), 10, 15, 30)
        )

        assertEquals(
            initial.copy(schedule = initial.schedule.copy(date = LocalDate.of(2026, 7, 22))),
            reduceCustomDate(initial, pending = LocalDate.of(2026, 7, 22), apply = true)
        )
        assertEquals(
            initial.copy(schedule = initial.schedule.copy(hour = 11, minute = 20)),
            reduceCustomTime(initial, pending = LocalTime.of(11, 20), apply = true)
        )
        assertEquals(
            initial.copy(schedule = initial.schedule.copy(durationMinutes = 45)),
            reduceCustomDuration(initial, pending = 45, apply = true)
        )
    }

    @Test
    fun cancellingCustomValuesPreservesTheWholeDraft() {
        val initial = draft(
            schedule = TaskEditorSchedule(LocalDate.of(2026, 7, 21), 10, 15, 30)
        )

        assertEquals(initial, reduceCustomDate(initial, pending = LocalDate.of(2026, 7, 22), apply = false))
        assertEquals(initial, reduceCustomTime(initial, pending = LocalTime.of(11, 20), apply = false))
        assertEquals(initial, reduceCustomDuration(initial, pending = 45, apply = false))
    }

    private fun draft(
        assignee: ProjectCollaborator = collaborator(isCurrentUser = false),
        schedule: TaskEditorSchedule = TaskEditorSchedule(),
        recurrencePreset: RecurrencePreset = RecurrencePreset.never
    ) = TaskEditorDraft(
        title = "Review finances",
        projectId = "project-personal",
        assignee = assignee,
        schedule = schedule,
        recurrencePreset = recurrencePreset
    )

    private fun collaborator(isCurrentUser: Boolean) = ProjectCollaborator(
        id = if (isCurrentUser) "me" else "wife",
        displayName = if (isCurrentUser) "Me" else "Wife",
        isCurrentUser = isCurrentUser
    )
}
