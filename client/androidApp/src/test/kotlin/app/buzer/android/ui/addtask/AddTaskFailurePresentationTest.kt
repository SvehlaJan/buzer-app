package app.buzer.android.ui.addtask

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AddTaskFailurePresentationTest {
    @Test
    fun failedSaveUsesRetryableDialogWithoutInlineSystemError() {
        val presentation = AddTaskFailurePresentation.fromSaveFailure("500 backend failure")

        assertEquals("Task was not saved", presentation.dialogTitle)
        assertEquals("500 backend failure", presentation.dialogMessage)
        assertEquals("Retry", presentation.retryLabel)
        assertNull(presentation.inlineSystemError)
    }
}
