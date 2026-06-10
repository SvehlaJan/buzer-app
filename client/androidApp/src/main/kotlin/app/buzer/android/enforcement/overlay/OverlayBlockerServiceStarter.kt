package app.buzer.android.enforcement.overlay

import android.content.Context
import android.os.Build
import app.buzer.shared.diagnostics.AppLog

object OverlayBlockerServiceStarter {
    fun tryStart(context: Context, request: OverlayBlockerRequest): Boolean {
        if (!OverlayPermission.canDraw(context)) {
            AppLog.warn("overlay_start skipped reason=permission_missing kind=${request.kind} taskId=${request.taskId}")
            return false
        }

        return try {
            val intent = request.intent(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (error: RuntimeException) {
            AppLog.error("overlay_start failed kind=${request.kind} taskId=${request.taskId}", error)
            false
        }
    }
}
