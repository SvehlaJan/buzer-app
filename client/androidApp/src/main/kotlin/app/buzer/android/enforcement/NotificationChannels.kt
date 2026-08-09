package app.buzer.android.enforcement

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import app.buzer.android.readiness.RequiredNotificationChannel

object NotificationChannels {
    const val PRE_START = "pre_start"
    const val BLOCKERS = "blockers"
    const val ERRORS = "errors"

    val requiredForEnforcement: List<RequiredNotificationChannel>
        get() = listOf(
            RequiredNotificationChannel(BLOCKERS, "Commitment blockers", NotificationManager.IMPORTANCE_HIGH),
            RequiredNotificationChannel(PRE_START, "Pre-start reminders", NotificationManager.IMPORTANCE_MIN),
            RequiredNotificationChannel(ERRORS, "Backend action failures", NotificationManager.IMPORTANCE_MIN)
        )

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                PRE_START,
                "Pre-start reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                BLOCKERS,
                "Commitment blockers",
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ERRORS,
                "Backend action failures",
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }
}
