package app.buzer.android.ui.datetime

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class DateTimeSelectionTest {
    @Test
    fun convertsLocalSelectionToIsoInstant() {
        val selection = DateTimeSelection(
            date = LocalDate.of(2026, 6, 2),
            hour = 18,
            minute = 30
        )

        assertEquals(
            "2026-06-02T16:30:00Z",
            selection.toIsoInstantString(ZoneId.of("Europe/Warsaw"))
        )
    }

    @Test
    fun formatsLocalSelectionForDisplay() {
        val selection = DateTimeSelection(
            date = LocalDate.of(2026, 6, 2),
            hour = 8,
            minute = 5
        )

        assertEquals("2026-06-02 08:05", selection.formatForDisplay())
    }
}
