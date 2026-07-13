package app.buzer.android.ui.datetime

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class DateTimeSelection(
    val date: LocalDate,
    val hour: Int,
    val minute: Int
) {
    fun toIsoInstantString(zoneId: ZoneId = ZoneId.systemDefault()): String =
        date.atTime(hour, minute)
            .atZone(zoneId)
            .toInstant()
            .toString()

    fun formatForDisplay(): String =
        date.atTime(hour, minute).format(displayFormatter)
}

data class DateTimeDraftSelection(
    val date: LocalDate?,
    val hour: Int?,
    val minute: Int?
) {
    val dateLabel: String
        get() = date?.format(dateFormatter) ?: "No date"

    val timeLabel: String
        get() = if (hour != null && minute != null) {
            "%02d:%02d".format(hour, minute)
        } else {
            "No time"
        }

    val receiptLabel: String
        get() = when {
            date == null -> "Not set"
            hour == null || minute == null -> "${date.format(dateFormatter)}, no time"
            else -> toSelection()?.formatForDisplay() ?: "Not set"
        }

    fun toSelection(): DateTimeSelection? {
        val selectedDate = date ?: return null
        val selectedHour = hour ?: return null
        val selectedMinute = minute ?: return null
        return DateTimeSelection(selectedDate, selectedHour, selectedMinute)
    }

    companion object {
        val Empty = DateTimeDraftSelection(date = null, hour = null, minute = null)

        fun fromSelection(selection: DateTimeSelection?): DateTimeDraftSelection =
            selection?.let {
                DateTimeDraftSelection(date = it.date, hour = it.hour, minute = it.minute)
            } ?: Empty
    }
}

private val displayFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")

private val dateFormatter: DateTimeFormatter =
    DateTimeFormatter.ISO_LOCAL_DATE
