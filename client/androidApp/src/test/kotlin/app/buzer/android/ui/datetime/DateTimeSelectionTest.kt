package app.buzer.android.ui.datetime

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test
    fun dateOnlyDraftShowsDateAndMissingTime() {
        val draft = DateTimeDraftSelection(date = LocalDate.of(2026, 7, 13), hour = null, minute = null)

        assertEquals("2026-07-13", draft.dateLabel)
        assertEquals("No time", draft.timeLabel)
        assertEquals("2026-07-13, no time", draft.receiptLabel)
        assertNull(draft.toSelection())
    }

    @Test
    fun completeDraftConvertsToSelection() {
        val draft = DateTimeDraftSelection(date = LocalDate.of(2026, 7, 13), hour = 8, minute = 5)

        assertEquals("2026-07-13", draft.dateLabel)
        assertEquals("08:05", draft.timeLabel)
        assertEquals("2026-07-13 08:05", draft.receiptLabel)
        assertEquals(DateTimeSelection(LocalDate.of(2026, 7, 13), 8, 5), draft.toSelection())
    }
}
