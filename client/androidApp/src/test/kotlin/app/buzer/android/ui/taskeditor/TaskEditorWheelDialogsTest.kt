package app.buzer.android.ui.taskeditor

import kotlin.test.Test
import kotlin.test.assertEquals

class TaskEditorWheelDialogsTest {
    @Test
    fun durationWheelRoundsAcrossAnHourBoundaryInsteadOfShortening() {
        assertEquals(1 to 0, durationWheelStart(59))
        assertEquals(2 to 0, durationWheelStart(119))
    }
}
