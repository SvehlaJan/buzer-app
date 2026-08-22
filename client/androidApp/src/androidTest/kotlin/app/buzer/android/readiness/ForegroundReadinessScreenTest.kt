package app.buzer.android.readiness

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.espresso.Espresso.pressBack
import app.buzer.android.ui.screens.ForegroundReadinessGate
import app.buzer.android.ui.theme.BuzerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ForegroundReadinessScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun blockingStateShowsTitleAndStepLabel() {
        setGate(exactAlarmsState())

        composeRule.onNodeWithText("Allow exact alarms").assertIsDisplayed()
        composeRule.onNodeWithText("Step 2 of 5").assertIsDisplayed()
    }

    @Test
    fun titleHasHeadingSemanticsAndReceivesInitialFocus() {
        setGate(exactAlarmsState())

        composeRule.onNodeWithText("Allow exact alarms")
            .assertIsDisplayed()
            .assertHeading()
            .assertIsFocused()
    }

    @Test
    fun onlyOneEnabledPrimaryActionExists() {
        setGate(exactAlarmsState())

        composeRule.onAllNodes(hasTestTag("readiness-primary-action") and isEnabled()).assertCountEquals(1)
    }

    @Test
    fun reportBugActionInvokesCallback() {
        var reportCount = 0
        setGate(exactAlarmsState(), onReportBug = { reportCount++ })
        composeRule.onNodeWithTag("readiness-report-bug").assertIsDisplayed().performClick()
        assertEquals(1, reportCount)
    }

    @Test
    fun noEscapeHatchControlsExist() {
        setGate(exactAlarmsState())

        listOf("Back", "Close", "Skip", "Later", "Dismiss").forEach { forbidden ->
            composeRule.onNodeWithText(forbidden).assertDoesNotExist()
        }
    }

    @Test
    fun systemBackDoesNotDismissTheGate() {
        setGate(exactAlarmsState())
        composeRule.onNodeWithText("Allow exact alarms").assertIsDisplayed()

        pressBack()

        composeRule.onNodeWithText("Allow exact alarms").assertIsDisplayed()
        composeRule.onNodeWithTag("readiness-primary-action").assertIsDisplayed()
    }

    @Test
    fun primaryButtonInvokesCallbackOnceForRuntimeNotificationState() {
        var invocations = 0
        setGate(runtimeNotificationState(), onPrimaryAction = { invocations++ })

        composeRule.onNodeWithTag("readiness-primary-action").performClick()

        assertEquals(1, invocations)
    }

    @Test
    fun primaryButtonInvokesCallbackOnceForSettingsActionState() {
        var invocations = 0
        setGate(overlayState(), onPrimaryAction = { invocations++ })

        composeRule.onNodeWithTag("readiness-primary-action").performClick()

        assertEquals(1, invocations)
    }

    @Test
    fun primaryButtonInvokesCallbackOnceForErrorState() {
        var invocations = 0
        setGate(
            ForegroundReadinessGateState.Error("Buzer couldn't verify device readiness."),
            onPrimaryAction = { invocations++ }
        )

        composeRule.onNodeWithTag("readiness-primary-action").performClick()

        assertEquals(1, invocations)
    }

    @Test
    fun changingFromExactAlarmsToFullScreenMovesFocusToTheNewHeading() {
        val state = mutableStateOf<ForegroundReadinessGateState>(exactAlarmsState())
        setGateState({ state.value })

        composeRule.onNodeWithText("Allow exact alarms").assertIsFocused()

        state.value = fullScreenIntentState()

        composeRule.onNodeWithText("Allow full-screen alerts")
            .assertIsDisplayed()
            .assertHeading()
            .assertIsFocused()
        composeRule.onNodeWithText("Allow exact alarms").assertDoesNotExist()
    }

    @Test
    fun errorRendersRetryOnlyWithNoStepLabel() {
        setGate(ForegroundReadinessGateState.Error("Buzer couldn't verify device readiness."))

        composeRule.onNodeWithText("Couldn't verify device readiness").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag("readiness-step").assertDoesNotExist()
        composeRule.onAllNodes(hasTestTag("readiness-primary-action") and isEnabled()).assertCountEquals(1)
    }

    @Test
    fun largeFontScaleCanScrollToThePrimaryActionWithoutClipping() {
        val configuration = composeRule.activity.resources.configuration
        val originalFontScale = configuration.fontScale
        try {
            configuration.fontScale = 2f
            setGate(batteryExemptionState())

            composeRule.onNode(hasScrollAction())
                .performScrollToNode(hasTestTag("readiness-primary-action"))
            composeRule.onNodeWithTag("readiness-primary-action").assertIsDisplayed()
        } finally {
            configuration.fontScale = originalFontScale
        }
    }

    private fun setGate(
        state: ForegroundReadinessGateState,
        onPrimaryAction: () -> Unit = {},
        onReportBug: () -> Unit = {}
    ) = setGateState({ state }, onPrimaryAction, onReportBug)

    private fun setGateState(
        state: () -> ForegroundReadinessGateState,
        onPrimaryAction: () -> Unit = {},
        onReportBug: () -> Unit = {}
    ) = composeRule.setContent {
        BuzerTheme {
            ForegroundReadinessGate(
                state = state(),
                onPrimaryAction = onPrimaryAction,
                onReportBug = onReportBug
            )
        }
    }

    private fun exactAlarmsState() = ForegroundReadinessGateState.Blocking(
        EnforcementReadiness.NeedsSettingsAction(
            ReadinessRequirement.ExactAlarms,
            ReadinessSettingsDestination.ExactAlarms
        )
    )

    private fun fullScreenIntentState() = ForegroundReadinessGateState.Blocking(
        EnforcementReadiness.NeedsSettingsAction(
            ReadinessRequirement.FullScreenIntent,
            ReadinessSettingsDestination.FullScreenIntent
        )
    )

    private fun overlayState() = ForegroundReadinessGateState.Blocking(
        EnforcementReadiness.NeedsSettingsAction(
            ReadinessRequirement.Overlay,
            ReadinessSettingsDestination.Overlay
        )
    )

    private fun batteryExemptionState() = ForegroundReadinessGateState.Blocking(
        EnforcementReadiness.NeedsSettingsAction(
            ReadinessRequirement.BatteryExemption,
            ReadinessSettingsDestination.BatteryExemption
        )
    )

    private fun runtimeNotificationState() = ForegroundReadinessGateState.Blocking(
        EnforcementReadiness.NeedsRuntimePermission(ReadinessRequirement.NotificationRuntime)
    )

    private fun SemanticsNodeInteraction.assertHeading() = assert(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
    )
}
