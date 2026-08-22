package app.buzer.android.readiness

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import app.buzer.android.enforcement.NotificationChannels

fun interface EnforcementReadinessChecker {
    fun check(): EnforcementReadiness
}

class AndroidEnforcementReadinessChecker(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : EnforcementReadinessChecker {
    override fun check(): EnforcementReadiness {
        NotificationChannels.ensureCreated(context)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val snapshot = EnforcementCapabilitySnapshot(
            sdkInt = sdkInt,
            notificationRuntimeGranted = sdkInt < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            appNotificationsEnabled = notifications.areNotificationsEnabled(),
            notificationChannelImportance = NotificationChannels.requiredForEnforcement.associate { channel ->
                channel.id to (notifications.getNotificationChannel(channel.id)?.importance ?: 0)
            },
            exactAlarmsAllowed = sdkInt < 31 ||
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
            fullScreenIntentAllowed = sdkInt < 34 || notifications.canUseFullScreenIntent(),
            overlayAllowed = Settings.canDrawOverlays(context),
            batteryOptimizationExempt = context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName)
        )
        return EnforcementReadinessPolicy.evaluate(
            snapshot = snapshot,
            requiredChannels = NotificationChannels.requiredForEnforcement
        )
    }
}
