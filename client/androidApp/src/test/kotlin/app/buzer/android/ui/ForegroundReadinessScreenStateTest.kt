package app.buzer.android.ui

import app.buzer.android.readiness.EnforcementReadiness
import app.buzer.android.readiness.ForegroundReadinessAction
import app.buzer.android.readiness.ForegroundReadinessGateState
import app.buzer.android.readiness.ReadinessRequirement
import app.buzer.android.readiness.ReadinessSettingsDestination
import app.buzer.android.ui.screens.buildForegroundReadinessScreenState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForegroundReadinessScreenStateTest {
    @Test
    fun readyGateProducesNoScreen() {
        assertNull(buildForegroundReadinessScreenState(ForegroundReadinessGateState.Ready))
    }

    @Test
    fun runtimeNotificationPermissionLocksCopyAndAction() {
        val state = buildForegroundReadinessScreenState(
            ForegroundReadinessGateState.Blocking(
                EnforcementReadiness.NeedsRuntimePermission(ReadinessRequirement.NotificationRuntime)
            )
        )

        requireNotNull(state)
        assertEquals("1 of 5", state.stepLabel)
        assertEquals("Allow notifications", state.title)
        assertEquals("Allow notifications", state.primaryLabel)
        assertTrue(state.primaryEnabled)
        assertEquals(ForegroundReadinessAction.RequestNotificationPermission, state.action)
        assertTrue(state.body.isNotBlank())
        assertTrue(state.body.contains("notif", ignoreCase = true))
    }

    @Test
    fun globalNotificationsSettingsLocksCopyAndAction() {
        val state = buildForegroundReadinessScreenState(
            ForegroundReadinessGateState.Blocking(
                EnforcementReadiness.NeedsSettingsAction(
                    ReadinessRequirement.AppNotifications,
                    ReadinessSettingsDestination.AppNotifications
                )
            )
        )

        requireNotNull(state)
        assertEquals("1 of 5", state.stepLabel)
        assertEquals("Turn on Buzer notifications", state.title)
        assertEquals("Open settings", state.primaryLabel)
        assertTrue(state.primaryEnabled)
        assertEquals(
            ForegroundReadinessAction.OpenSettings(ReadinessSettingsDestination.AppNotifications),
            state.action
        )
        assertTrue(state.body.isNotBlank())
    }

    @Test
    fun channelSettingsUsesChannelNameInTitleAndBody() {
        val state = buildForegroundReadinessScreenState(
            ForegroundReadinessGateState.Blocking(
                EnforcementReadiness.NeedsSettingsAction(
                    ReadinessRequirement.NotificationChannel("commitment-alerts", "Commitment alerts"),
                    ReadinessSettingsDestination.NotificationChannel("commitment-alerts")
                )
            )
        )

        requireNotNull(state)
        assertEquals("1 of 5", state.stepLabel)
        assertEquals("Enable Commitment alerts", state.title)
        assertEquals("Open settings", state.primaryLabel)
        assertTrue(state.primaryEnabled)
        assertEquals(
            ForegroundReadinessAction.OpenSettings(
                ReadinessSettingsDestination.NotificationChannel("commitment-alerts")
            ),
            state.action
        )
        assertTrue(state.body.contains("Commitment alerts"))
    }

    @Test
    fun exactAlarmsLocksStepTwoDespiteFourthWhenBranchPosition() {
        // ReadinessRequirement declares three notification-flavoured variants (all step 1) before
        // ExactAlarms, so ExactAlarms is the FOURTH branch encountered while its capability.step is 2.
        // If stepLabel were ever derived from ordinal branch/list position instead of
        // requirement.capability.step, this would wrongly read "4 of 5".
        val state = buildForegroundReadinessScreenState(
            ForegroundReadinessGateState.Blocking(
                EnforcementReadiness.NeedsSettingsAction(
                    ReadinessRequirement.ExactAlarms,
                    ReadinessSettingsDestination.ExactAlarms
                )
            )
        )

        requireNotNull(state)
        assertEquals("2 of 5", state.stepLabel)
        assertEquals("Allow exact alarms", state.title)
        assertEquals("Open settings", state.primaryLabel)
        assertEquals(
            ForegroundReadinessAction.OpenSettings(ReadinessSettingsDestination.ExactAlarms),
            state.action
        )
        assertTrue(state.body.contains("alarm", ignoreCase = true))
    }

    @Test
    fun fullScreenIntentLocksStepThree() {
        val state = buildForegroundReadinessScreenState(
            ForegroundReadinessGateState.Blocking(
                EnforcementReadiness.NeedsSettingsAction(
                    ReadinessRequirement.FullScreenIntent,
                    ReadinessSettingsDestination.FullScreenIntent
                )
            )
        )

        requireNotNull(state)
        assertEquals("3 of 5", state.stepLabel)
        assertEquals("Allow full-screen alerts", state.title)
        assertEquals("Open settings", state.primaryLabel)
        assertEquals(
            ForegroundReadinessAction.OpenSettings(ReadinessSettingsDestination.FullScreenIntent),
            state.action
        )
        assertTrue(state.body.contains("screen", ignoreCase = true))
    }

    @Test
    fun overlayLocksStepFour() {
        val state = buildForegroundReadinessScreenState(
            ForegroundReadinessGateState.Blocking(
                EnforcementReadiness.NeedsSettingsAction(
                    ReadinessRequirement.Overlay,
                    ReadinessSettingsDestination.Overlay
                )
            )
        )

        requireNotNull(state)
        assertEquals("4 of 5", state.stepLabel)
        assertEquals("Allow display over other apps", state.title)
        assertEquals("Open settings", state.primaryLabel)
        assertEquals(
            ForegroundReadinessAction.OpenSettings(ReadinessSettingsDestination.Overlay),
            state.action
        )
        assertTrue(state.body.contains("other apps", ignoreCase = true))
    }

    @Test
    fun batteryExemptionLocksStepFive() {
        val state = buildForegroundReadinessScreenState(
            ForegroundReadinessGateState.Blocking(
                EnforcementReadiness.NeedsSettingsAction(
                    ReadinessRequirement.BatteryExemption,
                    ReadinessSettingsDestination.BatteryExemption
                )
            )
        )

        requireNotNull(state)
        assertEquals("5 of 5", state.stepLabel)
        assertEquals("Exclude Buzer from battery optimization", state.title)
        assertEquals("Open settings", state.primaryLabel)
        assertEquals(
            ForegroundReadinessAction.OpenSettings(ReadinessSettingsDestination.BatteryExemption),
            state.action
        )
        assertTrue(state.body.contains("battery", ignoreCase = true))
    }

    @Test
    fun errorHasNoStepLabelAndOnlyRetryAction() {
        val state = buildForegroundReadinessScreenState(
            ForegroundReadinessGateState.Error("Buzer couldn't verify device readiness.")
        )

        requireNotNull(state)
        assertNull(state.stepLabel)
        assertEquals("Couldn't verify device readiness", state.title)
        assertEquals("Retry", state.primaryLabel)
        assertTrue(state.primaryEnabled)
        assertEquals(ForegroundReadinessAction.Retry, state.action)
        assertEquals("Buzer couldn't verify device readiness.", state.body)
    }

    @Test
    fun inFlightRuntimePermissionRetainsContentButDisablesAndRelabelsPrimary() {
        val idle = buildForegroundReadinessScreenState(
            ForegroundReadinessGateState.Blocking(
                EnforcementReadiness.NeedsRuntimePermission(ReadinessRequirement.NotificationRuntime)
            )
        )
        val inFlight = buildForegroundReadinessScreenState(
            ForegroundReadinessGateState.Blocking(
                readiness = EnforcementReadiness.NeedsRuntimePermission(ReadinessRequirement.NotificationRuntime),
                actionInFlight = true
            )
        )

        requireNotNull(idle)
        requireNotNull(inFlight)
        assertEquals(idle.title, inFlight.title)
        assertEquals(idle.body, inFlight.body)
        assertEquals(idle.stepLabel, inFlight.stepLabel)
        assertEquals(idle.action, inFlight.action)
        assertFalse(inFlight.primaryEnabled)
        assertEquals("Requesting permission…", inFlight.primaryLabel)
    }

    @Test
    fun inFlightSettingsActionRetainsContentButDisablesAndRelabelsPrimary() {
        val idle = buildForegroundReadinessScreenState(
            ForegroundReadinessGateState.Blocking(
                EnforcementReadiness.NeedsSettingsAction(
                    ReadinessRequirement.ExactAlarms,
                    ReadinessSettingsDestination.ExactAlarms
                )
            )
        )
        val inFlight = buildForegroundReadinessScreenState(
            ForegroundReadinessGateState.Blocking(
                readiness = EnforcementReadiness.NeedsSettingsAction(
                    ReadinessRequirement.ExactAlarms,
                    ReadinessSettingsDestination.ExactAlarms
                ),
                actionInFlight = true
            )
        )

        requireNotNull(idle)
        requireNotNull(inFlight)
        assertEquals(idle.title, inFlight.title)
        assertEquals(idle.body, inFlight.body)
        assertEquals(idle.stepLabel, inFlight.stepLabel)
        assertEquals(idle.action, inFlight.action)
        assertFalse(inFlight.primaryEnabled)
        assertEquals("Opening settings…", inFlight.primaryLabel)
    }
}
