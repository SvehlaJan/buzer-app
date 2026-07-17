package app.buzer.android.enforcement

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

enum class AlarmKind {
    MORNING_REVIEW,
    PRE_START_WARNING,
    START_BLOCKER,
    DEADLINE_BLOCKER
}

object AlarmIntentFactory {
    const val EXTRA_TASK_ID = "app.buzer.extra.TASK_ID"
    const val EXTRA_OCCURRENCE_KEY = "app.buzer.extra.OCCURRENCE_KEY"
    const val EXTRA_ALARM_KIND = "app.buzer.extra.ALARM_KIND"

    fun requestCode(taskId: String, occurrenceKey: String, kind: AlarmKind): Int {
        return "$taskId:$occurrenceKey:${kind.name}".hashCode()
    }

    /** Cancels notification IDs emitted before occurrence-keyed notifications were introduced. */
    fun legacyRequestCode(taskId: String, kind: AlarmKind): Int = "$taskId:${kind.name}".hashCode()

    fun receiverIntent(context: Context, taskId: String, occurrenceKey: String, kind: AlarmKind): Intent {
        return Intent(context, AlarmReceiver::class.java).apply {
            // Intent extras do not participate in PendingIntent identity. Data makes the complete
            // opaque identity distinct even if two request-code hashes ever collide.
            action = "app.buzer.action.ALARM_RECEIVER.${kind.name}"
            data = identityUri(taskId, occurrenceKey, kind)
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_OCCURRENCE_KEY, occurrenceKey)
            putExtra(EXTRA_ALARM_KIND, kind.name)
        }
    }

    /** Pure identity representation so occurrence uniqueness is also verifiable in host JVM tests. */
    fun identityUriString(taskId: String, occurrenceKey: String, kind: AlarmKind): String =
        "buzer://alarm/${pathSegment(taskId)}/${pathSegment(occurrenceKey)}/${pathSegment(kind.name)}"

    fun identityUri(taskId: String, occurrenceKey: String, kind: AlarmKind): Uri =
        Uri.parse(identityUriString(taskId, occurrenceKey, kind))

    private fun pathSegment(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
