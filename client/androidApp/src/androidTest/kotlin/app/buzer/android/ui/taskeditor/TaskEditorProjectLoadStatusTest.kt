package app.buzer.android.ui.taskeditor

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.buzer.android.ui.theme.BuzerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TaskEditorProjectLoadStatusTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun failureUsesInlineWarningAndForwardsRetry() {
        var retryCount = 0
        composeRule.setContent {
            BuzerTheme {
                TaskEditorProjectLoadStatus(
                    loading = false,
                    message = "Network unavailable",
                    onRetry = { retryCount++ }
                )
            }
        }

        composeRule.onNodeWithText("Projects unavailable: Network unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.runOnIdle { assertEquals(1, retryCount) }
    }
}
