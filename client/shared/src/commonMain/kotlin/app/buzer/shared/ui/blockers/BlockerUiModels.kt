package app.buzer.shared.ui.blockers

import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.ui.commitment.formatDurationLabel
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

enum class BlockerKind {
    Start,
    Deadline,
    OverlayStart
}

enum class BlockerDisplayMode(val showBackAction: Boolean) {
    Production(false),
    Debug(true)
}

data class BlockerUiModel(
    val kind: BlockerKind,
    val eyebrow: String,
    val title: String,
    val blockWindow: String,
    val description: String?,
    val consequenceText: String?
)

fun buildBlockerUiModel(
    kind: BlockerKind,
    task: TaskSnapshotItem?,
    timeZoneId: String = "UTC"
): BlockerUiModel =
    BlockerUiModel(
        kind = kind,
        eyebrow = when (kind) {
            BlockerKind.Start,
            BlockerKind.OverlayStart -> "Start now"
            BlockerKind.Deadline -> "Block ended"
        },
        title = task?.title?.trim()?.takeIf { it.isNotEmpty() } ?: "Committed task",
        blockWindow = formatBlockWindow(task, timeZoneId),
        description = task?.description?.trim()?.takeIf { it.isNotEmpty() },
        consequenceText = when (kind) {
            BlockerKind.Start,
            BlockerKind.OverlayStart -> "Second postpone escalates via WhatsApp."
            BlockerKind.Deadline -> null
        }
    )

private fun formatBlockWindow(
    task: TaskSnapshotItem?,
    timeZoneId: String
): String {
    val dueDatetime = task?.dueDatetime ?: return "Schedule unavailable"
    val durationMinutes = task.durationMinutes ?: return "Schedule unavailable"
    val end = runCatching { Instant.parse(dueDatetime) }.getOrNull() ?: return "Schedule unavailable"
    val start = task.startAt
        ?.let { value -> runCatching { Instant.parse(value) }.getOrNull() }
        ?: end.minus(durationMinutes.minutes)
    val timeZone = runCatching { TimeZone.of(timeZoneId) }.getOrDefault(TimeZone.UTC)
    return "${formatTime(start, timeZone)}-${formatTime(end, timeZone)} - " +
        "${formatDurationLabel(durationMinutes)} block"
}

private fun formatTime(instant: Instant, timeZone: TimeZone): String {
    val local = instant.toLocalDateTime(timeZone)
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    return "$hour:$minute"
}
