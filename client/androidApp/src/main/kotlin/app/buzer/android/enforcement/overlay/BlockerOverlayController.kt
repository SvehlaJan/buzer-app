package app.buzer.android.enforcement.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.buzer.android.enforcement.AlarmKind
import app.buzer.shared.diagnostics.AppLog

/** Action a user can take from the blocker overlay. */
enum class OverlayAction { START, POSTPONE, COMPLETE, EXTEND, OPEN_FULL, DISMISS }

/**
 * Hosts the full-screen blocker overlay via [WindowManager] using a native view tree
 * (no Compose, to keep service lifecycle simple). The overlay draws above most foreground apps
 * with [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]; it is not a security boundary and
 * cannot cover system UI or the keyguard.
 */
class BlockerOverlayController(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: View? = null

    fun show(request: OverlayBlockerRequest, onAction: (OverlayAction) -> Unit) {
        if (view != null) return
        val root = buildView(request, onAction)
        windowManager.addView(root, layoutParams())
        view = root
        AppLog.info("overlay shown kind=${request.kind} taskId=${request.taskId}")
    }

    fun remove() {
        val current = view ?: return
        runCatching { windowManager.removeView(current) }
            .onFailure { AppLog.warn("overlay remove failed ${it.message}") }
        view = null
        AppLog.info("overlay dismissed")
    }

    private fun layoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE
        ).apply { gravity = Gravity.CENTER }

    private fun buildView(request: OverlayBlockerRequest, onAction: (OverlayAction) -> Unit): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F2202124"))
            setPadding(dp(32), dp(48), dp(32), dp(48))
        }
        root.addView(
            TextView(context).apply {
                text = titleFor(request.kind)
                setTextColor(Color.WHITE)
                textSize = 26f
                gravity = Gravity.CENTER
            }
        )
        root.addView(
            TextView(context).apply {
                text = summaryFor(request.kind)
                setTextColor(Color.parseColor("#CCFFFFFF"))
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(32))
            }
        )
        actionsFor(request.kind).forEach { (label, action) ->
            root.addView(
                Button(context).apply {
                    text = label
                    setOnClickListener { onAction(action) }
                    layoutParams = LinearLayout.LayoutParams(dp(240), dp(56))
                        .apply { topMargin = dp(12) }
                }
            )
        }
        return root
    }

    private fun titleFor(kind: AlarmKind): String = when (kind) {
        AlarmKind.START_BLOCKER -> "Start your task"
        AlarmKind.DEADLINE_BLOCKER -> "Task deadline"
        AlarmKind.MORNING_REVIEW -> "Morning review"
        AlarmKind.PRE_START_WARNING -> "Task starts soon"
    }

    private fun summaryFor(kind: AlarmKind): String = when (kind) {
        AlarmKind.START_BLOCKER -> "A committed task is due to start now."
        AlarmKind.DEADLINE_BLOCKER -> "Your committed task has reached its deadline."
        AlarmKind.MORNING_REVIEW -> "Review your commitments for today."
        AlarmKind.PRE_START_WARNING -> "A committed task starts soon."
    }

    private fun actionsFor(kind: AlarmKind): List<Pair<String, OverlayAction>> = when (kind) {
        AlarmKind.START_BLOCKER -> listOf(
            "Start now" to OverlayAction.START,
            "Postpone 5 min" to OverlayAction.POSTPONE
        )
        AlarmKind.DEADLINE_BLOCKER -> listOf(
            "Done" to OverlayAction.COMPLETE,
            "Extend 30 min" to OverlayAction.EXTEND,
            "Open full blocker" to OverlayAction.OPEN_FULL
        )
        AlarmKind.MORNING_REVIEW -> listOf("Dismiss" to OverlayAction.DISMISS)
        AlarmKind.PRE_START_WARNING -> emptyList()
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
