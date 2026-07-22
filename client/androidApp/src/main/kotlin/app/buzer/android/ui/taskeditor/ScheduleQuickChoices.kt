package app.buzer.android.ui.taskeditor

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

internal data class ScheduleDateChoice(
    val date: LocalDate,
    val label: String
)

internal data class ScheduleTimeChoice(
    val date: LocalDate,
    val time: LocalTime,
    val label: String
)

internal data class ScheduleQuickChoices(
    val dates: List<ScheduleDateChoice>,
    val startTimes: List<ScheduleTimeChoice>,
    val durationMinutes: List<Int>
)

internal fun scheduleQuickChoices(
    draft: TaskEditorSchedule,
    now: LocalDateTime
): ScheduleQuickChoices = ScheduleQuickChoices(
    dates = dateChoices(selectedDate = draft.date, now = now),
    startTimes = startTimeChoices(
        selectedDate = draft.date,
        selectedTime = draft.selectedTime(),
        now = now
    ),
    durationMinutes = durationChoices(selectedMinutes = draft.durationMinutes)
)

internal fun dateChoices(
    selectedDate: LocalDate?,
    now: LocalDateTime
): List<ScheduleDateChoice> {
    val today = now.toLocalDate()
    val tomorrow = today.plusDays(1)
    val nextWorkday = nextUsefulWorkday(after = today.plusDays(2))
    val candidates = if (selectedDate != null && selectedDate != today) {
        listOf(selectedDate, tomorrow, nextWorkday, today)
    } else {
        listOf(today, tomorrow, nextWorkday)
    }
    val dates = mutableSetOf<LocalDate>()
    val labels = mutableSetOf<String>()

    return candidates.mapNotNull { date ->
        val label = dateChoiceLabel(date, today)
        if (dates.add(date) && labels.add(label)) ScheduleDateChoice(date, label) else null
    }.take(3)
}

internal fun startTimeChoices(
    selectedDate: LocalDate?,
    selectedTime: LocalTime?,
    now: LocalDateTime
): List<ScheduleTimeChoice> {
    val initial = selectedTime?.let { time ->
        (selectedDate ?: now.toLocalDate()).atTime(time)
    } ?: initialQuickTimeDateTime(selectedDate, now)

    return (0..2)
        .map { initial.plusMinutes(it * 30L) }
        .distinct()
        .map { dateTime ->
            ScheduleTimeChoice(
                date = dateTime.toLocalDate(),
                time = dateTime.toLocalTime(),
                label = timeChoiceLabel(dateTime, now.toLocalDate())
            )
        }
}

internal fun selectQuickStartTime(
    draft: TaskEditorDraft,
    choice: ScheduleTimeChoice
): TaskEditorDraft = draft.copy(
    schedule = draft.schedule.copy(
        date = choice.date,
        hour = choice.time.hour,
        minute = choice.time.minute
    )
)

private fun initialQuickTimeDateTime(
    selectedDate: LocalDate?,
    now: LocalDateTime
): LocalDateTime {
    val roundedNow = roundUpToFiveMinutes(now)
    return when {
        selectedDate == null -> roundedNow
        selectedDate == now.toLocalDate() && roundedNow.toLocalDate() != selectedDate -> roundedNow
        else -> selectedDate.atTime(roundedNow.toLocalTime())
    }
}

private fun timeChoiceLabel(dateTime: LocalDateTime, today: LocalDate): String =
    if (dateTime.toLocalDate() == today) {
        formatTime(dateTime.toLocalTime())
    } else {
        "${relativeDateLabel(dateTime.toLocalDate(), today)} ${formatTime(dateTime.toLocalTime())}"
    }

internal fun durationChoices(selectedMinutes: Int?): List<Int> {
    val canonical = listOf(15, 30, 60)
    if (selectedMinutes == null) return canonical

    return (canonical + selectedMinutes)
        .distinct()
        .sortedWith(compareBy<Int> { abs(it - selectedMinutes) }.thenBy { it })
        .take(3)
        .sorted()
}

internal fun roundUpToFiveMinutes(now: LocalDateTime): LocalDateTime {
    val normalized = now.withSecond(0).withNano(0)
    val remainder = now.minute % 5
    val needsNextStep = remainder != 0 || now.second != 0 || now.nano != 0
    return if (needsNextStep) normalized.plusMinutes((5 - remainder).toLong()) else normalized
}

internal fun scheduleSummary(
    schedule: TaskEditorSchedule,
    now: LocalDateTime
): String? {
    if (!schedule.isComplete()) return null
    val start = schedule.startDateTime() ?: return null
    val duration = schedule.durationMinutes ?: return null
    val end = start.plusMinutes(duration.toLong())
    val startDateLabel = relativeDateLabel(start.toLocalDate(), now.toLocalDate())
    val endPrefix = if (end.toLocalDate() == start.toLocalDate()) "" else "${relativeDateLabel(end.toLocalDate(), now.toLocalDate())} "

    return "$startDateLabel · ${formatTime(start.toLocalTime())}–$endPrefix${formatTime(end.toLocalTime())} · ${formatDurationLabel(duration)}"
}

internal fun collapsedScheduleSummary(
    draft: TaskEditorDraft,
    now: LocalDateTime
): String = when {
    draft.schedule.isComplete() -> scheduleSummary(draft.schedule, now) ?: "Set date, time & duration"
    draft.schedule.isEmpty() && draft.assignee?.isCurrentUser == false -> "No schedule"
    else -> "Set date, time & duration"
}

internal fun primaryActionLabel(verb: String, draft: TaskEditorDraft): String =
    scheduleRangeLabel(draft.schedule)?.let { "$verb $it" } ?: verb

internal fun scheduleRangeLabel(schedule: TaskEditorSchedule): String? {
    val start = schedule.startDateTime() ?: return null
    val duration = schedule.durationMinutes?.takeIf { it > 0 } ?: return null
    return "${formatTime(start.toLocalTime())}–${formatTime(start.plusMinutes(duration.toLong()).toLocalTime())}"
}

/** Temporary bridge for the Add/Edit-only scaffold until hosts provide their own action verb. */
internal fun saveActionLabel(draft: TaskEditorDraft): String = primaryActionLabel("Save", draft)

internal fun formatDurationLabel(minutes: Int): String {
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return when {
        hours == 0 -> "$remainingMinutes min"
        remainingMinutes == 0 -> "$hours ${if (hours == 1) "hr" else "hrs"}"
        else -> "$hours ${if (hours == 1) "hr" else "hrs"} $remainingMinutes min"
    }
}

private fun nextUsefulWorkday(after: LocalDate): LocalDate =
    generateSequence(after) { it.plusDays(1) }
        .first { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }

private fun dateChoiceLabel(date: LocalDate, today: LocalDate): String =
    relativeDateLabel(date, today)

private fun relativeDateLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    else -> date.format(DateTimeFormatter.ofPattern("EEE, d MMM"))
}

private fun formatTime(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)
