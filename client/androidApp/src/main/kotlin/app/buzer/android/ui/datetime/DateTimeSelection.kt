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

private val displayFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
