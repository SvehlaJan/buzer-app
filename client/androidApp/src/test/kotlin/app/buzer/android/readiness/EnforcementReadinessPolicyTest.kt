package app.buzer.android.readiness

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Mirrors android.app.NotificationManager importance constants without importing
 * android.* — this policy and its tests stay Android-free by design.
 */
private const val IMPORTANCE_NONE = 0
private const val IMPORTANCE_MIN = 1
private const val IMPORTANCE_LOW = 2
private const val IMPORTANCE_DEFAULT = 3
private const val IMPORTANCE_HIGH = 4

private val BLOCKERS_CHANNEL = RequiredNotificationChannel(
    id = "blockers",
    displayName = "Commitment blockers",
    minimumImportance = IMPORTANCE_HIGH
)
private val PRE_START_CHANNEL = RequiredNotificationChannel(
    id = "pre_start",
    displayName = "Pre-start reminders",
    minimumImportance = IMPORTANCE_MIN
)
private val ERRORS_CHANNEL = RequiredNotificationChannel(
    id = "errors",
    displayName = "Backend action failures",
    minimumImportance = IMPORTANCE_MIN
)

private val REQUIRED_CHANNELS = listOf(BLOCKERS_CHANNEL, PRE_START_CHANNEL, ERRORS_CHANNEL)

class EnforcementReadinessPolicyTest {

    private fun healthySnapshot(sdkInt: Int) = EnforcementCapabilitySnapshot(
        sdkInt = sdkInt,
        notificationRuntimeGranted = true,
        appNotificationsEnabled = true,
        notificationChannelImportance = mapOf(
            "blockers" to IMPORTANCE_HIGH,
            "pre_start" to IMPORTANCE_MIN,
            "errors" to IMPORTANCE_MIN
        ),
        exactAlarmsAllowed = true,
        fullScreenIntentAllowed = true,
        overlayAllowed = true,
        batteryOptimizationExempt = true
    )

    private fun evaluate(snapshot: EnforcementCapabilitySnapshot) =
        EnforcementReadinessPolicy.evaluate(snapshot, REQUIRED_CHANNELS)

    // --- API-boundary tests -------------------------------------------------

    @Test
    fun api26WithEveryApplicableCapabilityTrueIsReadyWithoutConsultingLaterSdkGatedFields() {
        val snapshot = healthySnapshot(sdkInt = 26).copy(
            // These three fields are only consulted at sdkInt 33/31/34 respectively.
            // Leaving them false at API 26 and still getting Ready proves the policy
            // does not consult them below their SDK gate.
            notificationRuntimeGranted = false,
            exactAlarmsAllowed = false,
            fullScreenIntentAllowed = false
        )

        assertEquals(EnforcementReadiness.Ready, evaluate(snapshot))
    }

    @Test
    fun api30DoesNotYetRequireExactAlarms() {
        val snapshot = healthySnapshot(sdkInt = 30).copy(exactAlarmsAllowed = false)

        assertEquals(EnforcementReadiness.Ready, evaluate(snapshot))
    }

    @Test
    fun api31RequiresExactAlarms() {
        val snapshot = healthySnapshot(sdkInt = 31).copy(exactAlarmsAllowed = false)

        assertEquals(
            EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.ExactAlarms,
                ReadinessSettingsDestination.ExactAlarms
            ),
            evaluate(snapshot)
        )
    }

    @Test
    fun api32DoesNotYetRequireRuntimeNotifications() {
        val snapshot = healthySnapshot(sdkInt = 32).copy(notificationRuntimeGranted = false)

        assertEquals(EnforcementReadiness.Ready, evaluate(snapshot))
    }

    @Test
    fun api33FirstRequiresRuntimeNotifications() {
        val snapshot = healthySnapshot(sdkInt = 33).copy(notificationRuntimeGranted = false)

        assertEquals(
            EnforcementReadiness.NeedsRuntimePermission(ReadinessRequirement.NotificationRuntime),
            evaluate(snapshot)
        )
    }

    @Test
    fun api33RuntimeNotificationCheckPrecedesEverythingElseEvenWhenOtherFieldsAreAlsoMissing() {
        val snapshot = healthySnapshot(sdkInt = 33).copy(
            notificationRuntimeGranted = false,
            appNotificationsEnabled = false,
            exactAlarmsAllowed = false
        )

        assertEquals(
            EnforcementReadiness.NeedsRuntimePermission(ReadinessRequirement.NotificationRuntime),
            evaluate(snapshot)
        )
    }

    @Test
    fun api33DoesNotYetRequireFullScreenIntent() {
        val snapshot = healthySnapshot(sdkInt = 33).copy(fullScreenIntentAllowed = false)

        assertEquals(EnforcementReadiness.Ready, evaluate(snapshot))
    }

    @Test
    fun api34RequiresFullScreenIntentAfterExactAlarms() {
        val snapshot = healthySnapshot(sdkInt = 34).copy(fullScreenIntentAllowed = false)

        assertEquals(
            EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.FullScreenIntent,
                ReadinessSettingsDestination.FullScreenIntent
            ),
            evaluate(snapshot)
        )
    }

    @Test
    fun api34WithBothExactAlarmsAndFullScreenIntentMissingReportsExactAlarmsFirst() {
        val snapshot = healthySnapshot(sdkInt = 34).copy(
            exactAlarmsAllowed = false,
            fullScreenIntentAllowed = false
        )

        assertEquals(
            EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.ExactAlarms,
                ReadinessSettingsDestination.ExactAlarms
            ),
            evaluate(snapshot)
        )
    }

    @Test
    fun allHealthyApi34ValuesReturnReady() {
        assertEquals(EnforcementReadiness.Ready, evaluate(healthySnapshot(sdkInt = 34)))
    }

    // --- Global notifications beat everything else ---------------------------

    @Test
    fun globalNotificationsDisabledBeatsEveryChannelAndSpecialCapability() {
        val snapshot = healthySnapshot(sdkInt = 34).copy(
            appNotificationsEnabled = false,
            notificationChannelImportance = emptyMap(),
            exactAlarmsAllowed = false,
            fullScreenIntentAllowed = false,
            overlayAllowed = false,
            batteryOptimizationExempt = false
        )

        assertEquals(
            EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.AppNotifications,
                ReadinessSettingsDestination.AppNotifications
            ),
            evaluate(snapshot)
        )
    }

    // --- Channel-specific destinations ----------------------------------------

    @Test
    fun disabledBlockersChannelMapsToItsExactChannelDestination() {
        val snapshot = healthySnapshot(sdkInt = 34).copy(
            notificationChannelImportance = mapOf(
                "blockers" to IMPORTANCE_NONE,
                "pre_start" to IMPORTANCE_MIN,
                "errors" to IMPORTANCE_MIN
            )
        )

        assertEquals(
            EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.NotificationChannel("blockers", "Commitment blockers"),
                ReadinessSettingsDestination.NotificationChannel("blockers")
            ),
            evaluate(snapshot)
        )
    }

    @Test
    fun disabledPreStartChannelMapsToItsExactChannelDestination() {
        val snapshot = healthySnapshot(sdkInt = 34).copy(
            notificationChannelImportance = mapOf(
                "blockers" to IMPORTANCE_HIGH,
                "pre_start" to IMPORTANCE_NONE,
                "errors" to IMPORTANCE_MIN
            )
        )

        assertEquals(
            EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.NotificationChannel("pre_start", "Pre-start reminders"),
                ReadinessSettingsDestination.NotificationChannel("pre_start")
            ),
            evaluate(snapshot)
        )
    }

    @Test
    fun disabledErrorsChannelMapsToItsExactChannelDestination() {
        val snapshot = healthySnapshot(sdkInt = 34).copy(
            notificationChannelImportance = mapOf(
                "blockers" to IMPORTANCE_HIGH,
                "pre_start" to IMPORTANCE_MIN,
                "errors" to IMPORTANCE_NONE
            )
        )

        assertEquals(
            EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.NotificationChannel("errors", "Backend action failures"),
                ReadinessSettingsDestination.NotificationChannel("errors")
            ),
            evaluate(snapshot)
        )
    }

    @Test
    fun channelOrderWithMultipleUnhealthyChannelsReportsFirstRequiredChannelInListOrder() {
        val snapshot = healthySnapshot(sdkInt = 34).copy(
            notificationChannelImportance = mapOf(
                "blockers" to IMPORTANCE_NONE,
                "pre_start" to IMPORTANCE_NONE,
                "errors" to IMPORTANCE_NONE
            )
        )

        assertEquals(
            EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.NotificationChannel("blockers", "Commitment blockers"),
                ReadinessSettingsDestination.NotificationChannel("blockers")
            ),
            evaluate(snapshot)
        )
    }

    // --- Channel importance thresholds ----------------------------------------

    @Test
    fun blockersImportanceBelowHighFails() {
        val snapshot = healthySnapshot(sdkInt = 34).copy(
            notificationChannelImportance = mapOf(
                "blockers" to IMPORTANCE_DEFAULT,
                "pre_start" to IMPORTANCE_MIN,
                "errors" to IMPORTANCE_MIN
            )
        )

        assertEquals(
            EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.NotificationChannel("blockers", "Commitment blockers"),
                ReadinessSettingsDestination.NotificationChannel("blockers")
            ),
            evaluate(snapshot)
        )
    }

    @Test
    fun blockersImportanceAtHighPasses() {
        val snapshot = healthySnapshot(sdkInt = 34).copy(
            notificationChannelImportance = mapOf(
                "blockers" to IMPORTANCE_HIGH,
                "pre_start" to IMPORTANCE_MIN,
                "errors" to IMPORTANCE_MIN
            )
        )

        assertEquals(EnforcementReadiness.Ready, evaluate(snapshot))
    }

    @Test
    fun preStartAndErrorsPassAtAnyImportanceAboveNone() {
        val snapshot = healthySnapshot(sdkInt = 34).copy(
            notificationChannelImportance = mapOf(
                "blockers" to IMPORTANCE_HIGH,
                "pre_start" to IMPORTANCE_LOW,
                "errors" to IMPORTANCE_DEFAULT
            )
        )

        assertEquals(EnforcementReadiness.Ready, evaluate(snapshot))
    }

    @Test
    fun preStartFailsExactlyAtNoneImportance() {
        val snapshot = healthySnapshot(sdkInt = 34).copy(
            notificationChannelImportance = mapOf(
                "blockers" to IMPORTANCE_HIGH,
                "pre_start" to IMPORTANCE_NONE,
                "errors" to IMPORTANCE_MIN
            )
        )

        assertEquals(
            EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.NotificationChannel("pre_start", "Pre-start reminders"),
                ReadinessSettingsDestination.NotificationChannel("pre_start")
            ),
            evaluate(snapshot)
        )
    }

    @Test
    fun missingChannelEntryIsTreatedAsImportanceNone() {
        val snapshot = healthySnapshot(sdkInt = 34).copy(
            notificationChannelImportance = mapOf(
                "pre_start" to IMPORTANCE_MIN,
                "errors" to IMPORTANCE_MIN
                // "blockers" intentionally absent from the map.
            )
        )

        assertEquals(
            EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.NotificationChannel("blockers", "Commitment blockers"),
                ReadinessSettingsDestination.NotificationChannel("blockers")
            ),
            evaluate(snapshot)
        )
    }

    // --- Remaining single-capability boundaries -------------------------------

    @Test
    fun overlayMissingReportsOverlayDestination() {
        val snapshot = healthySnapshot(sdkInt = 34).copy(overlayAllowed = false)

        assertEquals(
            EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.Overlay,
                ReadinessSettingsDestination.Overlay
            ),
            evaluate(snapshot)
        )
    }

    @Test
    fun batteryExemptionMissingReportsBatteryDestination() {
        val snapshot = healthySnapshot(sdkInt = 34).copy(batteryOptimizationExempt = false)

        assertEquals(
            EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.BatteryExemption,
                ReadinessSettingsDestination.BatteryExemption
            ),
            evaluate(snapshot)
        )
    }

    // --- Full ordering cascade with multiple simultaneous failures -----------
    // Proves: multiple missing values always resolve in the order
    // Notifications -> Exact -> Full-screen -> Overlay -> Battery, one fix at a time.

    @Test
    fun cascadeWithEveryCapabilityMissingResolvesInNotificationsExactFullScreenOverlayBatteryOrder() {
        val allMissing = EnforcementCapabilitySnapshot(
            sdkInt = 34,
            notificationRuntimeGranted = false,
            appNotificationsEnabled = false,
            notificationChannelImportance = emptyMap(),
            exactAlarmsAllowed = false,
            fullScreenIntentAllowed = false,
            overlayAllowed = false,
            batteryOptimizationExempt = false
        )

        // Step 1: runtime notification permission blocks first.
        assertEquals(
            EnforcementReadiness.NeedsRuntimePermission(ReadinessRequirement.NotificationRuntime),
            evaluate(allMissing)
        )

        // Step 2: with runtime granted, global app notifications block next.
        val runtimeGranted = allMissing.copy(notificationRuntimeGranted = true)
        assertEquals(
            EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.AppNotifications,
                ReadinessSettingsDestination.AppNotifications
            ),
            evaluate(runtimeGranted)
        )

        // Step 3: with global notifications enabled, the blockers channel blocks next.
        val appNotificationsEnabled = runtimeGranted.copy(appNotificationsEnabled = true)
        assertEquals(
            EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.NotificationChannel("blockers", "Commitment blockers"),
                ReadinessSettingsDestination.NotificationChannel("blockers")
            ),
            evaluate(appNotificationsEnabled)
        )

        // Step 4: with all required channels healthy, exact alarms block next.
        val channelsHealthy = appNotificationsEnabled.copy(
            notificationChannelImportance = mapOf(
                "blockers" to IMPORTANCE_HIGH,
                "pre_start" to IMPORTANCE_MIN,
                "errors" to IMPORTANCE_MIN
            )
        )
        assertEquals(
            EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.ExactAlarms,
                ReadinessSettingsDestination.ExactAlarms
            ),
            evaluate(channelsHealthy)
        )

        // Step 5: with exact alarms allowed, full-screen intent blocks next.
        val exactAlarmsAllowed = channelsHealthy.copy(exactAlarmsAllowed = true)
        assertEquals(
            EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.FullScreenIntent,
                ReadinessSettingsDestination.FullScreenIntent
            ),
            evaluate(exactAlarmsAllowed)
        )

        // Step 6: with full-screen intent allowed, overlay blocks next.
        val fullScreenIntentAllowed = exactAlarmsAllowed.copy(fullScreenIntentAllowed = true)
        assertEquals(
            EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.Overlay,
                ReadinessSettingsDestination.Overlay
            ),
            evaluate(fullScreenIntentAllowed)
        )

        // Step 7: with overlay allowed, battery exemption blocks next.
        val overlayAllowed = fullScreenIntentAllowed.copy(overlayAllowed = true)
        assertEquals(
            EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.BatteryExemption,
                ReadinessSettingsDestination.BatteryExemption
            ),
            evaluate(overlayAllowed)
        )

        // Step 8: with everything healthy, the policy is finally Ready.
        val allHealthy = overlayAllowed.copy(batteryOptimizationExempt = true)
        assertEquals(EnforcementReadiness.Ready, evaluate(allHealthy))
    }

    // --- Capability/step contract sanity --------------------------------------

    @Test
    fun capabilityStepsAreOrderedNotificationsThroughBatteryExemption() {
        assertEquals(
            listOf(
                EnforcementCapability.Notifications,
                EnforcementCapability.ExactAlarms,
                EnforcementCapability.FullScreenIntent,
                EnforcementCapability.Overlay,
                EnforcementCapability.BatteryExemption
            ),
            EnforcementCapability.entries.sortedBy { it.step }
        )
    }
}
