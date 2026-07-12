package app.buzer.shared.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScheduleCalculatorTest {
    @Test
    fun treatsTodoistDueDatetimeAsBlockStart() {
        assertEquals(
            "2026-05-08T16:00:00Z",
            ScheduleCalculator.computeStartAt("2026-05-08T16:00:00Z", 45)
        )
    }

    @Test
    fun rejectsNonPositiveDuration() {
        assertFailsWith<IllegalArgumentException> {
            ScheduleCalculator.computeStartAt("2026-05-08T16:00:00Z", 0)
        }
    }
}
