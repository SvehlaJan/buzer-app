package app.buzer.android.ui.reschedule

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RescheduleInputParserTest {
    private val zone = ZoneId.of("Europe/Warsaw")

    @Test
    fun parsesLocalDeadlineAndPositiveDuration() {
        val result = RescheduleInputParser.parse(
            deadlineText = "2026-06-02 18:30",
            durationText = "45",
            zoneId = zone
        )

        assertTrue(result is RescheduleParseResult.Valid)
        assertEquals("2026-06-02T16:30:00Z", result.request.dueDatetime)
        assertEquals(45, result.request.durationMinutes)
    }

    @Test
    fun rejectsBlankDeadline() {
        val result = RescheduleInputParser.parse(
            deadlineText = " ",
            durationText = "45",
            zoneId = zone
        )

        assertEquals(
            RescheduleParseResult.Invalid("New deadline is required"),
            result
        )
    }

    @Test
    fun rejectsInvalidDeadlineFormat() {
        val result = RescheduleInputParser.parse(
            deadlineText = "tomorrow evening",
            durationText = "45",
            zoneId = zone
        )

        assertEquals(
            RescheduleParseResult.Invalid("New deadline must use YYYY-MM-DD HH:MM"),
            result
        )
    }

    @Test
    fun rejectsNonPositiveDuration() {
        val result = RescheduleInputParser.parse(
            deadlineText = "2026-06-02 18:30",
            durationText = "0",
            zoneId = zone
        )

        assertEquals(
            RescheduleParseResult.Invalid("Duration must be a positive whole number of minutes"),
            result
        )
    }
}
