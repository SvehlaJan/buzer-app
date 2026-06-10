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

class BlockerOverlayController(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var overlayView: View? = null

    fun show(request: OverlayBlockerRequest, actions: Actions) {
        remove()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(238, 15, 23, 42))
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        root.addView(titleView(title(request)))
        root.addView(bodyView(body(request)))
        buttons(request, actions).forEach(root::addView)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        windowManager.addView(root, params)
        overlayView = root
    }

    fun remove() {
        overlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        overlayView = null
    }

    private fun buttons(request: OverlayBlockerRequest, actions: Actions): List<Button> {
        return when (request.kind) {
            AlarmKind.START_BLOCKER -> listOf(
                actionButton("I'm starting", actions.onStart),
                actionButton("Postpone 5 min", actions.onPostpone)
            )
            AlarmKind.DEADLINE_BLOCKER -> listOf(
                actionButton("Done", actions.onDone),
                actionButton("Extend 30 min", actions.onExtend),
                actionButton("Open full blocker", actions.onOpenFullBlocker)
            )
            AlarmKind.MORNING_REVIEW -> listOf(
                actionButton("Dismiss", actions.onDismissMorning)
            )
            AlarmKind.PRE_START_WARNING -> emptyList()
        }
    }

    private fun title(request: OverlayBlockerRequest): String {
        return when (request.kind) {
            AlarmKind.START_BLOCKER -> "Start your task"
            AlarmKind.DEADLINE_BLOCKER -> "Task deadline"
            AlarmKind.MORNING_REVIEW -> "Morning review"
            AlarmKind.PRE_START_WARNING -> "Task starts soon"
        }
    }

    private fun body(request: OverlayBlockerRequest): String {
        return when (request.kind) {
            AlarmKind.START_BLOCKER -> "Your committed task should start now."
            AlarmKind.DEADLINE_BLOCKER -> "Your committed task deadline is now."
            AlarmKind.MORNING_REVIEW -> "Review today's commitments."
            AlarmKind.PRE_START_WARNING -> "A committed task starts soon."
        }
    }

    private fun titleView(text: String): TextView =
        TextView(context).apply {
            setText(text)
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
        }

    private fun bodyView(text: String): TextView =
        TextView(context).apply {
            setText(text)
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(24))
        }

    private fun actionButton(label: String, action: () -> Unit): Button =
        Button(context).apply {
            text = label
            minHeight = dp(56)
            textSize = 18f
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
        }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    data class Actions(
        val onStart: () -> Unit,
        val onPostpone: () -> Unit,
        val onDone: () -> Unit,
        val onExtend: () -> Unit,
        val onOpenFullBlocker: () -> Unit,
        val onDismissMorning: () -> Unit
    )
}
