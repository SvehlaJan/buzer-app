package app.buzer.android.enforcement.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Helpers for the SYSTEM_ALERT_WINDOW ("Display over other apps") permission used by the
 * blocker overlay. The permission is user-granted via system settings; runtime state is read
 * with [Settings.canDrawOverlays].
 */
object OverlayPermission {
    fun canDraw(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun settingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
