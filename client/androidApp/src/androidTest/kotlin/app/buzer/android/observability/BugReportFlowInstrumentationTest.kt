package app.buzer.android.observability

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.buzer.android.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BugReportFlowInstrumentationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val fakeObservability = RecordingAndroidObservability()

    @Before
    fun setUp() {
        ObservabilityRuntime.resetForTests(fakeObservability)
    }

    @After
    fun tearDown() {
        ObservabilityRuntime.resetForTests()
    }

    @Test
    fun reportBugFallbackActionInvokesObservabilityShowFeedback() {
        val readinessButton = composeRule.onAllNodesWithTag("readiness-report-bug")
        if (readinessButton.fetchSemanticsNodes().isNotEmpty()) {
            readinessButton[0].assertIsDisplayed().performClick()
        } else {
            composeRule.onNodeWithContentDescription("Report a bug").assertIsDisplayed().performClick()
        }

        assertEquals(1, fakeObservability.feedbackShowCount)
    }
}
