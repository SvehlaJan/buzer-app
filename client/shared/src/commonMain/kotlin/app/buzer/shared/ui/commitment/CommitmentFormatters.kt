package app.buzer.shared.ui.commitment

data class MinuteOfDay(val hour: Int, val minute: Int)

private const val MINUTES_PER_DAY = 24 * 60

private fun formatTime(totalMinutes: Int): String {
    val wrapped = ((totalMinutes % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
    val hour = (wrapped / 60).toString().padStart(2, '0')
    val minute = (wrapped % 60).toString().padStart(2, '0')
    return "$hour:$minute"
}

fun formatBlockRange(start: MinuteOfDay, durationMinutes: Int): String {
    val startMinutes = start.hour * 60 + start.minute
    return "${formatTime(startMinutes)}-${formatTime(startMinutes + durationMinutes)}"
}

fun formatSaveLabel(start: MinuteOfDay?, durationMinutes: Int): String =
    if (start == null) "Save task" else "Save ${formatBlockRange(start, durationMinutes)}"

fun formatDurationLabel(minutes: Int): String {
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours == 0 -> "$remainder min"
        remainder == 0 -> "${hours}h"
        else -> "${hours}h ${remainder}m"
    }
}
