package app.buzer.android.tasks

import app.buzer.android.BuildConfig
import app.buzer.android.ui.addtask.AddTaskAssigneeOption
import app.buzer.shared.api.BuzerApiClient
import app.buzer.shared.model.CreateTaskRequest

object AddTaskDependencies {
    fun assigneeOptions(): List<AddTaskAssigneeOption> = listOf(
        AddTaskAssigneeOption("Me", BuildConfig.BUZER_ASSIGNEE_ME_ID),
        AddTaskAssigneeOption("Wife", BuildConfig.BUZER_ASSIGNEE_WIFE_ID)
    )

    fun createTaskAction(): suspend (CreateTaskRequest) -> Unit = { request ->
        val baseUrl = BuildConfig.BUZER_BACKEND_BASE_URL.trimEnd('/')
        require(baseUrl.isNotBlank()) {
            "BUZER_BACKEND_BASE_URL is missing from client/local.properties"
        }
        require(BuildConfig.BUZER_BACKEND_TOKEN.isNotBlank()) {
            "BUZER_BACKEND_TOKEN is missing from client/local.properties"
        }
        BuzerApiClient(
            baseUrl = baseUrl,
            token = BuildConfig.BUZER_BACKEND_TOKEN
        ).createTask(request)
    }
}
