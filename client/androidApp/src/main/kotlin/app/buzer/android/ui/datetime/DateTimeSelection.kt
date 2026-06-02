package app.buzer.android.ui.datetime

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
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

fun datePickerMillisToLocalDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()

fun localDateToDatePickerMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()

private val displayFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
