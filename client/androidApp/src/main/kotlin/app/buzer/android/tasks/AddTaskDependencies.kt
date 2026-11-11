package app.buzer.android.tasks

import android.content.Context
import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.enforcement.EnforcementDependencies
import app.buzer.android.enforcement.FailureClassifier
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.CreateTaskRequest
import kotlinx.coroutines.CancellationException

object AddTaskDependencies {
    private var cachedProjectRepository: AddTaskProjectRepository? = null

    fun projectRepository(context: Context? = null): AddTaskProjectRepository {
        cachedProjectRepository?.let { return it }
        return AddTaskProjectRepository(
            EnforcementDependencies.apiClient(context)
        ).also { cachedProjectRepository = it }
    }

    fun projectSelectionStore(context: Context): ProjectSelectionStore =
        ProjectSelectionStore(context)

    fun createTaskAction(context: Context): suspend (CreateTaskRequest) -> CoordinatorActionResult = { request ->
        val appContext = context.applicationContext
        try {
            EnforcementDependencies.coordinator(appContext).createTask(request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val message = "add_task dependency create failed type=${error::class.simpleName} message=${error.message}"
            if (FailureClassifier.isOperationalFailure(error)) {
                AppLog.warn(message, error)
            } else {
                AppLog.error(message, error)
            }
            throw error
        }
    }
}
