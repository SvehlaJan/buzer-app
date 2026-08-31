package app.buzer.android.ui

import app.buzer.android.ui.navigation.ExternalTodoistLauncher
import app.buzer.android.ui.navigation.TodoistTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalTodoistLauncherTest {

    @Test
    fun generatesCorrectAppAndWebUrisForTask() {
        val target = TodoistTarget.Task("12345")

        assertEquals("todoist://task?id=12345", target.appUriString)
        assertEquals("https://todoist.com/app/task/12345", target.webUriString)
    }

    @Test
    fun generatesCorrectAppAndWebUrisForProject() {
        val target = TodoistTarget.Project("proj-999")

        assertEquals("todoist://project?id=proj-999", target.appUriString)
        assertEquals("https://todoist.com/app/project/proj-999", target.webUriString)
    }

    @Test
    fun generatesCorrectAppAndWebUrisForToday() {
        val target = TodoistTarget.Today

        assertEquals("todoist://today", target.appUriString)
        assertEquals("https://todoist.com/app/today", target.webUriString)
    }
}
