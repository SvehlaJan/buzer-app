package app.buzer.android.enforcement

data class PermissionHealthItem(
    val key: String,
    val level: String,
    val message: String
)

object PermissionHealth {
    fun evaluate(
        exactAlarmGranted: Boolean,
        notificationsGranted: Boolean,
        batteryOptimizationIgnored: Boolean,
        bootReceiverEnabled: Boolean,
        overlayAllowed: Boolean
    ): List<PermissionHealthItem> {
        val items = mutableListOf<PermissionHealthItem>()
        if (!exactAlarmGranted) {
            items += PermissionHealthItem("exact_alarm", "error", "Exact alarm permission is required.")
        }
        if (!notificationsGranted) {
            items += PermissionHealthItem("notifications", "error", "Notification permission is required.")
        }
        if (!batteryOptimizationIgnored) {
            items += PermissionHealthItem("battery", "warning", "Battery optimization may delay enforcement.")
        }
        if (!bootReceiverEnabled) {
            items += PermissionHealthItem("boot", "warning", "Boot rescheduling is not active.")
        }
        if (!overlayAllowed) {
            items += PermissionHealthItem(
                "overlay",
                "error",
                "Display over other apps permission is required for blocker overlays."
            )
        }
        return items.ifEmpty {
            listOf(PermissionHealthItem("all", "ok", "Device enforcement permissions look ready."))
        }
    }
}
