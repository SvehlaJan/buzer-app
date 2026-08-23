package app.buzer.android.observability

import android.app.AlarmManager
import android.app.NotificationManager
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.buzer.android.MainActivity
import app.buzer.android.blockers.FakeBackendClient
import app.buzer.android.morningreview.MorningReviewLocalState
import app.buzer.android.morningreview.MorningReviewLocalStateStore
import app.buzer.android.morningreview.MorningReviewRuntimeClock
import app.buzer.android.readiness.ReadinessDeviceController
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BugReportFlowInstrumentationTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val fakeObservability = RecordingAndroidObservability()
    private val fakeBackend = FakeBackendClient()
    private val readinessDeviceController = ReadinessDeviceController()

    @Before
    fun setUp() {
        ObservabilityRuntime.resetForTests(fakeObservability)
        resetStartupState()
    }

    @After
    fun tearDown() {
        ObservabilityRuntime.resetForTests()
    }

    @Test
    fun reportBugFallbackActionInvokesObservabilityShowFeedback() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            composeRule.waitUntil(timeoutMillis = 15_000) {
                runCatching {
                    composeRule.onNodeWithContentDescription("Report a bug").assertIsDisplayed()
                }.isSuccess
            }
            composeRule.onNodeWithContentDescription("Report a bug").assertIsDisplayed().performClick()

            assertEquals(1, fakeObservability.feedbackShowCount)
        } finally {
            scenario.close()
        }
    }

    private fun resetStartupState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MorningReviewRuntimeClock.setForTests(null)
        fakeBackend.reset()
        File(context.filesDir, "latest_snapshot.json").delete()
        MorningReviewLocalStateStore.fromContext(context).replace(
            MorningReviewLocalState(lastAcknowledgedDate = LocalDate.now(ZoneId.systemDefault()))
        )
        context.getSystemService(NotificationManager::class.java).cancelAll()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(AlarmManager::class.java).cancelAll()
        }
        readinessDeviceController.clearNotificationRequestHistory()
        readinessDeviceController.grantAll()
    }
}
