package app.buzer.android.ui.taskeditor

import app.buzer.shared.model.ProjectCollaborator
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScheduleQuickChoicesTest {
    @Test
    fun emptyTimeRoundsTheInjectedClockUpToTheNextFiveMinuteSlot() {
        val choices = scheduleQuickChoices(
            draft = TaskEditorSchedule(),
            now = LocalDateTime.of(2026, 7, 21, 10, 1)
        )

        assertEquals(LocalTime.of(10, 5), choices.startTimes.first().time)
    }

    @Test
    fun dateChoicesDeduplicateTheSelectedCurrentDate() {
        val today = LocalDate.of(2026, 7, 21)
        val choices = scheduleQuickChoices(
            draft = TaskEditorSchedule(date = today, hour = null, minute = null, durationMinutes = null),
            now = LocalDateTime.of(2026, 7, 21, 10, 1)
        )

        assertEquals(3, choices.dates.size)
        assertEquals(choices.dates.size, choices.dates.map { it.date }.distinct().size)
        assertEquals(today, choices.dates.first().date)
    }

    @Test
    fun selectedNonTodayDateIsRetainedAlongsideTomorrowAndTheNextUsefulWorkday() {
        val now = LocalDateTime.of(2026, 7, 24, 10, 1)
        val selectedDate = LocalDate.of(2026, 7, 30)

        val choices = scheduleQuickChoices(
            draft = TaskEditorSchedule(date = selectedDate),
            now = now
        )

        assertEquals(
            listOf(selectedDate, LocalDate.of(2026, 7, 25), LocalDate.of(2026, 7, 27)),
            choices.dates.map { it.date }
        )
    }

    @Test
    fun selectedTimeIsRetainedAsTheFirstQuickChoice() {
        val selected = LocalTime.of(13, 20)
        val choices = scheduleQuickChoices(
            draft = TaskEditorSchedule(date = null, hour = selected.hour, minute = selected.minute, durationMinutes = null),
            now = LocalDateTime.of(2026, 7, 21, 10, 1)
        )

        assertEquals(selected, choices.startTimes.first().time)
    }

    @Test
    fun lateNightRoundingCarriesTheQuickTimeChoiceIntoTomorrow() {
        val today = LocalDate.of(2026, 7, 21)
        val draft = TaskEditorDraft(
            title = "Review finances",
            schedule = TaskEditorSchedule(date = today)
        )
        val choice = scheduleQuickChoices(
            draft = draft.schedule,
            now = LocalDateTime.of(2026, 7, 21, 23, 56)
        ).startTimes.first()

        assertEquals(today.plusDays(1), choice.date)
        assertEquals(LocalTime.MIDNIGHT, choice.time)
        assertEquals("Tomorrow 00:00", choice.label)
        assertEquals(
            draft.copy(schedule = TaskEditorSchedule(date = today.plusDays(1), hour = 0, minute = 0)),
            selectQuickStartTime(draft, choice)
        )
    }

    @Test
    fun canonicalDurationChoicesContainTheSelectedDuration() {
        assertEquals(listOf(15, 30, 60), durationChoices(selectedMinutes = 30))
        assertTrue(45 in durationChoices(selectedMinutes = 45))
    }

    @Test
    fun completeCrossMidnightScheduleHasAnUnambiguousSummary() {
        val summary = scheduleSummary(
            schedule = TaskEditorSchedule(LocalDate.of(2026, 7, 21), 23, 30, 60),
            now = LocalDateTime.of(2026, 7, 21, 10, 0)
        )

        assertEquals("Today · 23:30–Tomorrow 00:30 · 1 hr", summary)
    }

    @Test
    fun zeroDurationDoesNotProduceACompleteScheduleOrDerivedEndCopy() {
        val schedule = TaskEditorSchedule(LocalDate.of(2026, 7, 21), 10, 0, 0)
        val draft = TaskEditorDraft(title = "Review finances", schedule = schedule)
        val now = LocalDateTime.of(2026, 7, 21, 9, 0)

        assertEquals(false, schedule.isComplete())
        assertEquals(null, scheduleSummary(schedule, now))
        assertEquals("Set date, time & duration", collapsedScheduleSummary(draft, now))
        assertEquals("Save", saveActionLabel(draft))
    }

    @Test
    fun saveLabelUsesTaskForAnUnscheduledNonSelfDraftAndTimesForACompleteDraft() {
        val assignee = ProjectCollaborator(id = "wife", displayName = "Wife", isCurrentUser = false)
        val noSchedule = TaskEditorDraft(
            title = "Review finances",
            projectId = "project-shared",
            assignee = assignee,
            schedule = TaskEditorSchedule()
        )
        val scheduled = noSchedule.copy(
            schedule = TaskEditorSchedule(LocalDate.of(2026, 7, 21), 23, 30, 60)
        )

        assertEquals("Save", saveActionLabel(noSchedule))
        assertEquals("Save 23:30–00:30", saveActionLabel(scheduled))
    }

    @Test
    fun primaryActionLabelUsesTheHostVerbOnlyWhenTheScheduleRangeIsValid() {
        val complete = TaskEditorDraft(
            title = "Review finances",
            schedule = TaskEditorSchedule(LocalDate.of(2026, 7, 21), 10, 0, 30)
        )
        val incomplete = complete.copy(schedule = TaskEditorSchedule(date = LocalDate.of(2026, 7, 21)))

        assertEquals("Repair 10:00–10:30", primaryActionLabel("Repair", complete))
        assertEquals("Repair", primaryActionLabel("Repair", incomplete))
    }
}
