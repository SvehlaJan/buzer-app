package app.buzer.android.ui.addtask

data class AddTaskSubmitState(
    val submitting: Boolean,
    val projectsLoading: Boolean,
    val projectLoadError: String?
) {
    val formDisabled: Boolean = submitting
    val saveEnabled: Boolean = !submitting && !projectsLoading && projectLoadError == null
}
