package app.buzer.android.ui.pickers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WheelDurationFieldTest {
    @Test
    fun combinesHoursAndMinutesIntoTotalMinutes() {
        assertEquals(90, totalDurationMinutes(hours = 1, minutes = 30))
        assertEquals(0, totalDurationMinutes(hours = 0, minutes = 0))
        assertEquals(415, totalDurationMinutes(hours = 6, minutes = 55))
    }

    @Test
    fun startsWheelAtExactMinuteStep() {
        assertEquals(1 to 30, durationWheelStart(90))
        assertEquals(0 to 0, durationWheelStart(0))
    }

    @Test
    fun roundsWheelStartToNearestFiveMinuteStep() {
        assertEquals(0 to 5, durationWheelStart(7))
        assertEquals(0 to 10, durationWheelStart(12))
    }

    @Test
    fun clampsWheelStartToMaxRange() {
        assertEquals(6 to 55, durationWheelStart(999))
    }

    @Test
    fun formatsDurationForDisplay() {
        assertEquals("1h 30m", formatDuration(90))
        assertEquals("0h 5m", formatDuration(5))
    }

    @Test
    fun disallowsZeroDuration() {
        assertFalse(canConfirmDuration(hours = 0, minutes = 0))
        assertTrue(canConfirmDuration(hours = 0, minutes = 5))
    }
}
