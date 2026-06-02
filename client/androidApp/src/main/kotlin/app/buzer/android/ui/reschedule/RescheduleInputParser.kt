package app.buzer.android.ui.reschedule

import app.buzer.shared.model.RescheduleTaskRequest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

sealed interface RescheduleParseResult {
    data class Valid(val request: RescheduleTaskRequest) : RescheduleParseResult
    data class Invalid(val message: String) : RescheduleParseResult
}

object RescheduleInputParser {
    private val formatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
            .withResolverStyle(ResolverStyle.STRICT)

    fun parse(
        deadlineText: String,
        durationText: String,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): RescheduleParseResult {
        val trimmedDeadline = deadlineText.trim()
        if (trimmedDeadline.isEmpty()) {
            return RescheduleParseResult.Invalid("New deadline is required")
        }

        val deadline = try {
            LocalDateTime.parse(trimmedDeadline, formatter)
        } catch (_: DateTimeParseException) {
            return RescheduleParseResult.Invalid("New deadline must use YYYY-MM-DD HH:MM")
        }

        val duration = durationText.trim().toIntOrNull()
        if (duration == null || duration <= 0) {
            return RescheduleParseResult.Invalid("Duration must be a positive whole number of minutes")
        }

        return RescheduleParseResult.Valid(
            RescheduleTaskRequest(
                dueDatetime = deadline.atZone(zoneId).toInstant().toString(),
                durationMinutes = duration
            )
        )
    }
}
