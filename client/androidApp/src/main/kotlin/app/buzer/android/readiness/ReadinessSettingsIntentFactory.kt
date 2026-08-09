package app.buzer.android.readiness

import android.content.Intent
import android.net.Uri
import android.provider.Settings

class ReadinessSettingsIntentFactory(
    private val packageName: String
) {
    private val packageUri: Uri
        get() = Uri.parse("package:$packageName")

    fun create(destination: ReadinessSettingsDestination): Intent = when (destination) {
        ReadinessSettingsDestination.AppNotifications ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        is ReadinessSettingsDestination.NotificationChannel ->
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, destination.channelId)
        ReadinessSettingsDestination.ExactAlarms ->
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
        ReadinessSettingsDestination.FullScreenIntent ->
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri)
        ReadinessSettingsDestination.Overlay ->
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)
        ReadinessSettingsDestination.BatteryExemption ->
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
    }
}
