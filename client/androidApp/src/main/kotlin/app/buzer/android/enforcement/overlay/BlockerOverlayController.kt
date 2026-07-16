package app.buzer.android.enforcement.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.buzer.android.ui.components.BlockerSurface
import app.buzer.android.ui.theme.BuzerTheme
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.ui.blockers.buildBlockerUiModel
import java.time.ZoneId

/** Action a user can take from the blocker overlay. */
enum class OverlayAction { START, POSTPONE }

internal interface OverlayHostHandle {
    val view: View

    fun setContent(content: @Composable () -> Unit) = Unit

    fun markAttached()

    fun destroy()
}

internal interface OverlayWindow {
    fun add(host: OverlayHostHandle)

    fun remove(host: OverlayHostHandle)
}

private class WindowManagerOverlayWindow(context: Context) : OverlayWindow {
    private val windowManager = context.getSystemService(WindowManager::class.java)

    override fun add(host: OverlayHostHandle) {
        windowManager.addView(host.view, layoutParams())
    }

    override fun remove(host: OverlayHostHandle) {
        windowManager.removeView(host.view)
    }

    private fun layoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
}

/**
 * Hosts the compact blocker overlay via [WindowManager]. The overlay draws above most foreground apps
 * with [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]; it is not a security boundary and
 * cannot cover system UI or the keyguard.
 */
class BlockerOverlayController internal constructor(
    private val window: OverlayWindow,
    private val hostFactory: () -> OverlayHostHandle
) {
    private var view: View? = null
    private var host: OverlayHostHandle? = null

    constructor(context: Context) : this(
        window = WindowManagerOverlayWindow(context),
        hostFactory = { OverlayComposeHost(context) }
    )

    internal val isShowing: Boolean
        get() = host != null

    fun show(request: OverlayBlockerRequest, onAction: (OverlayAction) -> Unit): Boolean {
        if (host != null) return true
        val overlayHost = hostFactory()
        overlayHost.setContent {
            CompactOverlayBlocker(request = request, onAction = onAction)
        }
        return runCatching {
            window.add(overlayHost)
            overlayHost.markAttached()
            host = overlayHost
            view = overlayHost.view
            AppLog.info(
                "overlay shown kind=${request.kind} taskId=${request.taskId} " +
                    "occurrence=${request.occurrenceKey}"
            )
            true
        }.getOrElse { error ->
            runCatching { overlayHost.destroy() }
                .onFailure { destroyError ->
                    AppLog.warn(
                        "overlay host destroy after add failure failed occurrence=${request.occurrenceKey} " +
                            "${destroyError.message}"
                    )
                }
            host = null
            view = null
            AppLog.warn(
                "overlay show failed kind=${request.kind} taskId=${request.taskId} " +
                    "occurrence=${request.occurrenceKey} " +
                    "message=${error.message ?: error::class.simpleName}"
            )
            false
        }
    }

    fun remove() {
        val current = host ?: return
        runCatching { window.remove(current) }
            .onFailure { AppLog.warn("overlay remove failed ${it.message}") }
        current.destroy()
        host = null
        view = null
        AppLog.info("overlay dismissed")
    }
}

@Composable
private fun CompactOverlayBlocker(
    request: OverlayBlockerRequest,
    onAction: (OverlayAction) -> Unit
) {
    val model = buildBlockerUiModel(
        kind = request.blockerKind,
        task = request.toTaskSnapshotItem(),
        timeZoneId = ZoneId.systemDefault().id
    )
    BuzerTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            BlockerSurface(
                model = model,
                warningMessage = null,
                checking = false,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
            ) {
                Button(
                    onClick = { onAction(OverlayAction.START) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "I'm starting",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                OutlinedButton(
                    onClick = { onAction(OverlayAction.POSTPONE) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Postpone 5 min",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}
