package app.buzer.android.ui.addtask

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddTaskSubmitStateTest {
    @Test
    fun submittingDisablesFormAndShowsSavingLabel() {
        val state = AddTaskSubmitState(submitting = true, projectsLoading = false, projectLoadError = null)

        assertTrue(state.formDisabled)
        assertEquals("Saving...", state.saveLabel)
        assertFalse(state.saveEnabled)
    }

    @Test
    fun saveEnabledOnlyWhenNotSubmittingProjectsLoadedAndNoLoadError() {
        assertTrue(AddTaskSubmitState(submitting = false, projectsLoading = false, projectLoadError = null).saveEnabled)
        assertFalse(AddTaskSubmitState(submitting = false, projectsLoading = true, projectLoadError = null).saveEnabled)
        assertFalse(AddTaskSubmitState(submitting = false, projectsLoading = false, projectLoadError = "No network").saveEnabled)
    }
}
