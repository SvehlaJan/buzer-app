package app.buzer.android.readiness

import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.buzer.android.MainActivity
import app.buzer.android.blockers.FakeBackendClient
import app.buzer.android.morningreview.MorningReviewLocalState
import app.buzer.android.morningreview.MorningReviewLocalStateStore
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Coverage for the foreground-enforcement-readiness plan's `MainActivity` gate (wired in Task 6).
 * Originally written in Task 5 as RED coverage before that gate existed; now exercises the real
 * gate end to end.
 *
 * [settingsIntents_arePackageScopedForEveryDestination] exercises [ReadinessSettingsIntentFactory]
 * directly rather than through `MainActivity`.
 *
 * ExactAlarms is not independently observable on this build: `AndroidManifest.xml` declares
 * `USE_EXACT_ALARM`, which makes `AlarmManager.canScheduleExactAlarms()` unconditionally `true`
 * regardless of device app-op state (see [ReadinessDeviceController.setExactAlarms] for the full
 * explanation). That capability is therefore excluded from
 * [missingCapabilities_advanceInRequiredOrderOnEveryForeground]'s denial/walk sequence, and
 * [missingCapability_neverLaunchesSettingsWithoutTap_andBackCannotDismiss] targets Overlay
 * instead of ExactAlarms as its one always-missing capability.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundReadinessInstrumentationTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fakeBackend = FakeBackendClient()
    private val deviceController = ReadinessDeviceController()
    private val trackedScenarios = mutableListOf<ActivityScenario<out Activity>>()

    @After
    fun tearDown() {
        trackedScenarios.toList().forEach { it.close() }
        trackedScenarios.clear()
        // Leave the device in a fully-granted state so later test classes (which assume
        // notifications/alarms/overlay/battery access are available) are not poisoned by a
        // denial this class made.
        deviceController.grantAll()
        deviceController.clearNotificationRequestHistory()
        resetTestState()
    }

    @Test
    fun healthyStartup_showsHomeWithoutReadinessFlash() {
        resetTestState()
        deviceController.grantAll()
        deviceController.clearNotificationRequestHistory()

        launchMain()

        var gateObservedBeforeHome = false
        composeRule.waitUntil(timeoutMillis = READINESS_TIMEOUT_MILLIS) {
            if (composeRule.onAllNodesWithTag(READINESS_TITLE_TAG).fetchSemanticsNodes().isNotEmpty()) {
                gateObservedBeforeHome = true
            }
            homeMarkerVisible()
        }
        assertFalse(
            "Readiness gate must never render while every capability is already granted",
            gateObservedBeforeHome
        )
        composeRule.onAllNodesWithTag(READINESS_TITLE_TAG).assertCountEquals(0)
    }

    @Test
    fun missingCapabilities_advanceInRequiredOrderOnEveryForeground() {
        resetTestState()
        // ExactAlarms is excluded: it can never be observed as missing on this build (see
        // ReadinessDeviceController.setExactAlarms), so the walk goes straight from Notifications
        // to Full-screen -- correctly skipping the always-ready step in between.
        DENIABLE_CAPABILITIES.forEach(deviceController::deny)
        // Persist request history first so the very first checkpoint resolves to the app
        // Settings step instead of a runtime permission dialog this test cannot drive.
        deviceController.markNotificationRequested()

        val scenario = launchMain()

        waitForReadinessGate(step = 1, title = "Turn on Buzer notifications")

        deviceController.grant(DeviceReadinessCapability.Notifications)
        foregroundTransition(scenario)
        waitForReadinessGate(step = 3, title = "Allow full-screen alerts")

        deviceController.grant(DeviceReadinessCapability.FullScreenIntent)
        foregroundTransition(scenario)
        waitForReadinessGate(step = 4, title = "Allow display over other apps")

        deviceController.grant(DeviceReadinessCapability.Overlay)
        foregroundTransition(scenario)
        waitForReadinessGate(step = 5, title = "Exclude Buzer from battery optimization")

        deviceController.grant(DeviceReadinessCapability.BatteryExemption)
        foregroundTransition(scenario)
        waitForHomeWithoutGate()
    }

    @Test
    fun missingCapability_neverLaunchesSettingsWithoutTap_andBackCannotDismiss() {
        resetTestState()
        deviceController.grantAll()
        // Overlay stands in for "any one capability missing, mid-sequence, with no special-case
        // behavior" -- ExactAlarms can't be used here since it can never be observed as missing
        // on this build (see ReadinessDeviceController.setExactAlarms).
        deviceController.deny(DeviceReadinessCapability.Overlay)

        val scenario = launchMain()

        waitForReadinessGate(step = 4, title = "Allow display over other apps")
        assertEquals(Lifecycle.State.RESUMED, scenario.state)

        // Idling Compose alone -- with no tap on the primary action -- must never auto-launch
        // Settings or otherwise dismiss the gate.
        composeRule.waitForIdle()
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
        composeRule.onNodeWithTag(READINESS_TITLE_TAG).assertTextEquals("Allow display over other apps")

        pressBack()

        composeRule.onNodeWithTag(READINESS_TITLE_TAG).assertTextEquals("Allow display over other apps")
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
    }

    @Test
    fun restoringOverlay_preservesDirtyEditDraft() {
        resetTestState()
        deviceController.grantAll()
        deviceController.clearNotificationRequestHistory()

        val scenario = launchMain()
        waitForAnyText("Incomplete checklist task")

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Tomorrow task"))
        composeRule.onNodeWithText("Tomorrow task").performClick()
        waitForText("Edit task")
        waitForContentDescription("Task title")
        val draftSuffix = " readiness-draft-${System.nanoTime()}"
        composeRule.onNodeWithContentDescription("Task title").performTextInput(draftSuffix)

        deviceController.deny(DeviceReadinessCapability.Overlay)
        foregroundTransition(scenario)

        waitForReadinessGate(step = 4, title = "Allow display over other apps")

        deviceController.grant(DeviceReadinessCapability.Overlay)
        foregroundTransition(scenario)

        waitForText("Edit task")
        waitForContentDescription("Task title")
        composeRule.onNodeWithContentDescription("Task title").assertTextContains(draftSuffix, substring = true)
    }

    @Test
    fun settingsIntents_arePackageScopedForEveryDestination() {
        val packageName = context.packageName
        val factory = ReadinessSettingsIntentFactory(packageName)

        val appNotifications = factory.create(ReadinessSettingsDestination.AppNotifications)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, appNotifications.action)
        assertEquals(packageName, appNotifications.getStringExtra(Settings.EXTRA_APP_PACKAGE))

        val channelId = "blockers"
        val channel = factory.create(ReadinessSettingsDestination.NotificationChannel(channelId))
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, channel.action)
        assertEquals(packageName, channel.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(channelId, channel.getStringExtra(Settings.EXTRA_CHANNEL_ID))

        val packageUriDestinations = listOf(
            ReadinessSettingsDestination.ExactAlarms to Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            ReadinessSettingsDestination.FullScreenIntent to Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            ReadinessSettingsDestination.Overlay to Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            ReadinessSettingsDestination.BatteryExemption to Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        )
        packageUriDestinations.forEach { (destination, expectedAction) ->
            val intent = factory.create(destination)
            assertEquals(expectedAction, intent.action)
            assertEquals("package", intent.data?.scheme)
            assertEquals(packageName, intent.data?.schemeSpecificPart)
        }

        // Prove the scoping travels with the factory's package, not a hardcoded id: a factory
        // built for a different package must scope its intents to that package instead.
        val otherPackageName = "com.example.other"
        val otherFactory = ReadinessSettingsIntentFactory(otherPackageName)
        val otherOverlay = otherFactory.create(ReadinessSettingsDestination.Overlay)
        assertEquals(otherPackageName, otherOverlay.data?.schemeSpecificPart)
        assertNotEquals(packageName, otherOverlay.data?.schemeSpecificPart)
    }

    // -- lifecycle helpers -------------------------------------------------------------------

    private fun launchMain(): ActivityScenario<MainActivity> =
        track(ActivityScenario.launch(MainActivity::class.java))

    private fun <T : Activity> track(scenario: ActivityScenario<T>): ActivityScenario<T> {
        trackedScenarios += scenario
        return scenario
    }

    /**
     * Simulates the app leaving and re-entering the foreground (the trigger for a fresh
     * readiness re-check), matching the CREATED -> RESUMED transition used elsewhere in this
     * suite for "the app comes back to the foreground" scenarios.
     */
    private fun foregroundTransition(scenario: ActivityScenario<MainActivity>) {
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
    }

    // -- device / backend reset --------------------------------------------------------------

    private fun resetTestState() {
        fakeBackend.setFailActions(false)
        fakeBackend.setFailSnapshot(false)
        fakeBackend.reset()
        File(context.filesDir, "latest_snapshot.json").delete()
        // Acknowledge today's Morning Review so it never contests the readiness gate: readiness
        // must be the only startup gate under test here.
        MorningReviewLocalStateStore.fromContext(context).replace(
            MorningReviewLocalState(lastAcknowledgedDate = LocalDate.now(ZoneId.systemDefault()))
        )
        context.getSystemService(NotificationManager::class.java).cancelAll()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(AlarmManager::class.java).cancelAll()
        }
    }

    // -- Compose polling helpers (bounded waitUntil only, never a fixed sleep) --------------

    private fun waitForReadinessGate(step: Int, title: String) {
        val expectedStepLabel = "Step $step of 5"
        try {
            composeRule.waitUntil(timeoutMillis = READINESS_TIMEOUT_MILLIS) {
                runCatching {
                    composeRule.onNodeWithTag(READINESS_TITLE_TAG).assertTextEquals(title)
                }.isSuccess
            }
        } catch (timeout: Throwable) {
            throw AssertionError(
                "Expected foreground readiness gate step \"$expectedStepLabel\" ($title) but it " +
                    "was not observed within ${READINESS_TIMEOUT_MILLIS}ms -- " +
                    "homeMarkerVisible=${homeMarkerVisible()}.",
                timeout
            )
        }
        composeRule.onNodeWithTag(READINESS_STEP_TAG).assertTextEquals(expectedStepLabel)
        composeRule.onNodeWithTag(READINESS_TITLE_TAG).assertTextEquals(title)
    }

    private fun waitForHomeWithoutGate() {
        composeRule.waitUntil(timeoutMillis = READINESS_TIMEOUT_MILLIS) { homeMarkerVisible() }
        composeRule.onAllNodesWithTag(READINESS_TITLE_TAG).assertCountEquals(0)
    }

    private fun homeMarkerVisible(): Boolean =
        composeRule.onAllNodesWithText("Incomplete checklist task", substring = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = READINESS_TIMEOUT_MILLIS) {
            runCatching {
                composeRule.onNodeWithText(text, substring = true).assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun waitForAnyText(text: String) {
        composeRule.waitUntil(timeoutMillis = READINESS_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForContentDescription(contentDescription: String) {
        composeRule.waitUntil(timeoutMillis = READINESS_TIMEOUT_MILLIS) {
            runCatching {
                composeRule.onNodeWithContentDescription(contentDescription).assertIsDisplayed()
            }.isSuccess
        }
    }

    private companion object {
        const val READINESS_TIMEOUT_MILLIS = 15_000L
        const val READINESS_STEP_TAG = "readiness-step"
        const val READINESS_TITLE_TAG = "readiness-title"

        // ExactAlarms omitted: unrevocable on this build, see ReadinessDeviceController.setExactAlarms.
        val DENIABLE_CAPABILITIES = listOf(
            DeviceReadinessCapability.Notifications,
            DeviceReadinessCapability.FullScreenIntent,
            DeviceReadinessCapability.Overlay,
            DeviceReadinessCapability.BatteryExemption
        )
    }
}
