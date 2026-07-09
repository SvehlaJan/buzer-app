package app.buzer.shared.ui.commitment

import kotlin.test.Test
import kotlin.test.assertEquals

class CommitmentFormattersTest {
    @Test
    fun formatsBlockRangeForSameDay() {
        assertEquals("14:30-15:00", formatBlockRange(MinuteOfDay(14, 30), 30))
    }

    @Test
    fun wrapsBlockRangeAcrossMidnight() {
        assertEquals("23:50-00:20", formatBlockRange(MinuteOfDay(23, 50), 30))
    }

    @Test
    fun formatsSubmitLabelFromBlockRange() {
        assertEquals("Save 14:30-15:00", formatSaveLabel(MinuteOfDay(14, 30), 30))
    }

    @Test
    fun saveLabelFallsBackWhenStartMissing() {
        assertEquals("Save task", formatSaveLabel(null, 30))
    }

    @Test
    fun formatsDurationCompactly() {
        assertEquals("30 min", formatDurationLabel(30))
        assertEquals("1h 30m", formatDurationLabel(90))
    }
}
