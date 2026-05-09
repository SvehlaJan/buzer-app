package app.buzer.shared.time

import kotlin.time.Instant
import kotlin.time.Duration.Companion.minutes

object ScheduleCalculator {
    fun computeStartAt(dueDatetime: String, durationMinutes: Int): String {
        require(durationMinutes > 0) { "durationMinutes must be positive" }
        val due = Instant.parse(dueDatetime)
        return due.minus(durationMinutes.minutes).toString()
    }

    fun extendDeadlineByThirtyMinutes(dueDatetime: String): String {
        return Instant.parse(dueDatetime).plus(30.minutes).toString()
    }
}
