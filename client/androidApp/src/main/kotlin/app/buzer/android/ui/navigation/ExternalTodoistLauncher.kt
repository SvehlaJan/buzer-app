package app.buzer.android.ui.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.buzer.shared.diagnostics.AppLog

sealed interface TodoistTarget {
    data class Task(val todoistTaskId: String) : TodoistTarget
    data class Project(val projectId: String) : TodoistTarget
    data object Today : TodoistTarget

    val appUriString: String
        get() = when (this) {
            is Task -> "todoist://task?id=$todoistTaskId"
            is Project -> "todoist://project?id=$projectId"
            Today -> "todoist://today"
        }

    val webUriString: String
        get() = when (this) {
            is Task -> "https://todoist.com/app/task/$todoistTaskId"
            is Project -> "https://todoist.com/app/project/$projectId"
            Today -> "https://todoist.com/app/today"
        }
}

interface TodoistLauncher {
    fun launch(context: Context, target: TodoistTarget = TodoistTarget.Today): Boolean
}

object ExternalTodoistLauncher : TodoistLauncher {

    fun createAppIntent(target: TodoistTarget): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(target.appUriString)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun createWebIntent(target: TodoistTarget): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(target.webUriString)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    override fun launch(context: Context, target: TodoistTarget): Boolean {
        val appIntent = createAppIntent(target)
        val packageManager = context.packageManager
        val canHandleAppIntent = packageManager != null &&
            packageManager.queryIntentActivities(appIntent, 0).isNotEmpty()

        if (canHandleAppIntent) {
            try {
                context.startActivity(appIntent)
                AppLog.info(
                    message = "todoist_launched",
                    attributes = mapOf("target" to target.appUriString, "method" to "app")
                )
                return true
            } catch (error: Exception) {
                AppLog.warn(
                    message = "todoist_app_launch_failed",
                    throwable = error,
                    attributes = mapOf("target" to target.appUriString)
                )
            }
        }

        return launchWebFallback(context, target)
    }

    fun launchTask(context: Context, todoistTaskId: String): Boolean =
        launch(context, TodoistTarget.Task(todoistTaskId))

    fun launchProject(context: Context, projectId: String): Boolean =
        launch(context, TodoistTarget.Project(projectId))

    fun launchToday(context: Context): Boolean =
        launch(context, TodoistTarget.Today)

    private fun launchWebFallback(context: Context, target: TodoistTarget): Boolean {
        val webIntent = createWebIntent(target)
        return try {
            context.startActivity(webIntent)
            AppLog.info(
                message = "todoist_launched",
                attributes = mapOf("target" to target.webUriString, "method" to "web")
            )
            true
        } catch (error: Exception) {
            AppLog.error(
                message = "todoist_web_launch_failed",
                throwable = error,
                attributes = mapOf("target" to target.webUriString)
            )
            false
        }
    }
}
