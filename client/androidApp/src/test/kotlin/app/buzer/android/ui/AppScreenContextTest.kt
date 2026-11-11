package app.buzer.android.ui

import app.buzer.android.observability.ScreenContext
import org.junit.Test
import kotlin.test.assertEquals

class AppScreenContextTest {

    @Test
    fun homeAndAddReportTheSectionWithoutATask() {
        assertEquals(ScreenContext(screen = "Home", taskId = null), screenContextFor(AppSection.Home, null))
        assertEquals(ScreenContext(screen = "Add", taskId = null), screenContextFor(AppSection.Add, null))
    }

    @Test
    fun editReportsTheEditedTask() {
        assertEquals(
            ScreenContext(screen = "Edit", taskId = "task-42"),
            screenContextFor(AppSection.Edit, "task-42")
        )
    }

    @Test
    fun aStaleEditingTaskIdIsNotReportedOutsideTheEditor() {
        assertEquals(ScreenContext(screen = "Home", taskId = null), screenContextFor(AppSection.Home, "task-42"))
    }
}
