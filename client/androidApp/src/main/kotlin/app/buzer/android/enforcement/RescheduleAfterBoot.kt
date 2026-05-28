package app.buzer.android.enforcement

import android.content.Context
import android.content.Intent
import app.buzer.android.MainActivity

object RescheduleAfterBoot {
    const val EXTRA_BOOT_RESCHEDULE_REQUIRED = "app.buzer.extra.BOOT_RESCHEDULE_REQUIRED"

    fun enqueue(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_BOOT_RESCHEDULE_REQUIRED, true)
        }
        context.startActivity(intent)
    }
}
