package app.buzer.android.ui.pickers

import app.buzer.android.ui.datetime.DateTimeSelection
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class WheelDateTimeFieldTest {
    @Test
    fun convertsLocalDateTimeToDateTimeSelection() {
        val dateTime = LocalDateTime.of(2026, 6, 2, 18, 30)

        val selection = toDateTimeSelection(dateTime)

        assertEquals(LocalDate.of(2026, 6, 2), selection.date)
        assertEquals(18, selection.hour)
        assertEquals(30, selection.minute)
    }

    @Test
    fun convertsDateTimeSelectionToLocalDateTime() {
        val selection = DateTimeSelection(date = LocalDate.of(2026, 6, 2), hour = 18, minute = 30)

        assertEquals(LocalDateTime.of(2026, 6, 2, 18, 30), toLocalDateTime(selection))
    }

    @Test
    fun defaultsToOneHourFromNowWhenNoSelection() {
        val now = LocalDateTime.of(2026, 6, 2, 9, 0)

        assertEquals(LocalDateTime.of(2026, 6, 2, 10, 0), startDateTimeFor(selection = null, now = now))
    }

    @Test
    fun usesExistingSelectionWhenPresent() {
        val now = LocalDateTime.of(2026, 6, 2, 9, 0)
        val selection = DateTimeSelection(date = LocalDate.of(2026, 6, 3), hour = 8, minute = 15)

        assertEquals(LocalDateTime.of(2026, 6, 3, 8, 15), startDateTimeFor(selection, now))
    }
}
