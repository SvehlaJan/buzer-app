package app.buzer.android.tasks

import android.content.Context

class ProjectSelectionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun getSelectedProjectId(): String? =
        preferences.getString(KEY_SELECTED_PROJECT_ID, null)

    fun setSelectedProjectId(projectId: String) {
        preferences.edit().putString(KEY_SELECTED_PROJECT_ID, projectId).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "buzer_add_task"
        private const val KEY_SELECTED_PROJECT_ID = "selected_project_id"
    }
}
