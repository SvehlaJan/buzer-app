package app.buzer.android.ui.components

import app.buzer.android.ui.datetime.DateTimeSelection
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class CommitmentEditorStateTest {
    @Test
    fun saveLabelUsesSelectedStartAndDuration() {
        val start = DateTimeSelection(LocalDate.of(2026, 7, 8), 14, 30)

        assertEquals("Save 14:30-15:00", commitmentSaveLabel(start, 30))
    }

    @Test
    fun saveLabelFallsBackWhenStartMissing() {
        assertEquals("Save task", commitmentSaveLabel(null, 30))
    }
}
