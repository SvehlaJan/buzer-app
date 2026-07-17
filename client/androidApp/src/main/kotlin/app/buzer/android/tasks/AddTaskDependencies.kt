package app.buzer.android.tasks

import android.content.Context
import app.buzer.android.BuildConfig
import app.buzer.android.ui.addtask.AddTaskAssigneeOption
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.api.BuzerApiClient
import app.buzer.shared.model.CreateTaskRequest
import app.buzer.shared.model.MutationResponse

object AddTaskDependencies {
    private var cachedProjectRepository: AddTaskProjectRepository? = null

    fun assigneeOptions(): List<AddTaskAssigneeOption> = listOf(
        AddTaskAssigneeOption("Me", BuildConfig.BUZER_ASSIGNEE_ME_ID),
        AddTaskAssigneeOption("Wife", BuildConfig.BUZER_ASSIGNEE_WIFE_ID)
    )

    fun projectRepository(): AddTaskProjectRepository {
        cachedProjectRepository?.let { return it }
        val baseUrl = BuildConfig.BUZER_BACKEND_BASE_URL.trimEnd('/')
        require(baseUrl.isNotBlank()) {
            "BUZER_BACKEND_BASE_URL is missing from client/local.properties"
        }
        require(BuildConfig.BUZER_BACKEND_TOKEN.isNotBlank()) {
            "BUZER_BACKEND_TOKEN is missing from client/local.properties"
        }
        return AddTaskProjectRepository(
            BuzerApiClient(
                baseUrl = baseUrl,
                token = BuildConfig.BUZER_BACKEND_TOKEN
            )
        ).also { cachedProjectRepository = it }
    }

    fun projectSelectionStore(context: Context): ProjectSelectionStore =
        ProjectSelectionStore(context)

    fun createTaskAction(): suspend (CreateTaskRequest) -> MutationResponse = { request ->
        val baseUrl = BuildConfig.BUZER_BACKEND_BASE_URL.trimEnd('/')
        AppLog.info(
            "add_task dependency create requested baseUrlConfigured=${baseUrl.isNotBlank()} " +
                "tokenConfigured=${BuildConfig.BUZER_BACKEND_TOKEN.isNotBlank()} " +
                "titleLength=${request.title.length} project=${request.projectId} assignee=${request.assigneeId} " +
                "due=${request.dueDatetime} duration=${request.durationMinutes}"
        )
        require(baseUrl.isNotBlank()) {
            "BUZER_BACKEND_BASE_URL is missing from client/local.properties"
        }
        require(BuildConfig.BUZER_BACKEND_TOKEN.isNotBlank()) {
            "BUZER_BACKEND_TOKEN is missing from client/local.properties"
        }
        try {
            val response = BuzerApiClient(
                baseUrl = baseUrl,
                token = BuildConfig.BUZER_BACKEND_TOKEN
            ).createTask(request)
            AppLog.info("add_task dependency create completed outcome=${response.outcome}")
            response
        } catch (error: Throwable) {
            AppLog.error("add_task dependency create failed type=${error::class.simpleName} message=${error.message}", error)
            throw error
        }
    }
}
