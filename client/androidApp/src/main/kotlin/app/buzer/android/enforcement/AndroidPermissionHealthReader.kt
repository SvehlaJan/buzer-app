package app.buzer.android.enforcement

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager

object AndroidPermissionHealthReader {
    fun read(context: Context): List<PermissionHealthItem> {
        return PermissionHealth.evaluate(
            exactAlarmGranted = canScheduleExactAlarms(context),
            notificationsGranted = notificationsGranted(context),
            batteryOptimizationIgnored = batteryOptimizationIgnored(context),
            bootReceiverEnabled = receiverEnabled(context, BootReceiver::class.java)
        )
    }

    private fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        return alarmManager.canScheduleExactAlarms()
    }

    private fun notificationsGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun batteryOptimizationIgnored(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java)
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun receiverEnabled(context: Context, receiverClass: Class<*>): Boolean {
        val component = ComponentName(context, receiverClass)
        val state = context.packageManager.getComponentEnabledSetting(component)
        return state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ||
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }
}
