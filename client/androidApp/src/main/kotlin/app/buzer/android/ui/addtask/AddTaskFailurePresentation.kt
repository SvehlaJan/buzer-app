package app.buzer.android.ui.addtask

data class AddTaskFailurePresentation(
    val dialogTitle: String,
    val dialogMessage: String,
    val retryLabel: String,
    val inlineSystemError: String?
) {
    companion object {
        fun fromSaveFailure(message: String): AddTaskFailurePresentation =
            AddTaskFailurePresentation(
                dialogTitle = "Task was not saved",
                dialogMessage = message,
                retryLabel = "Retry",
                inlineSystemError = null
            )
    }
}
