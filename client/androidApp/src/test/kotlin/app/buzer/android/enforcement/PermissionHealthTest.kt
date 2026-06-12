package app.buzer.android.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PermissionHealthTest {
    @Test
    fun reportsMissingExactAlarmAsError() {
        val result = PermissionHealth.evaluate(
            exactAlarmGranted = false,
            notificationsGranted = true,
            batteryOptimizationIgnored = true,
            bootReceiverEnabled = true,
            overlayAllowed = true
        )

        assertTrue(result.any { it.key == "exact_alarm" && it.level == "error" })
    }

    @Test
    fun reportsOkWhenAllEnforcementRequirementsAreReady() {
        val result = PermissionHealth.evaluate(
            exactAlarmGranted = true,
            notificationsGranted = true,
            batteryOptimizationIgnored = true,
            bootReceiverEnabled = true,
            overlayAllowed = true
        )

        assertEquals("ok", result.single().level)
    }

    @Test
    fun reportsMissingOverlayAsError() {
        val result = PermissionHealth.evaluate(
            exactAlarmGranted = true,
            notificationsGranted = true,
            batteryOptimizationIgnored = true,
            bootReceiverEnabled = true,
            overlayAllowed = false
        )

        assertTrue(result.any { it.key == "overlay" && it.level == "error" })
    }
}
