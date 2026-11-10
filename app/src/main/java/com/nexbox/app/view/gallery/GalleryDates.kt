package com.nexbox.app.view.gallery

import android.text.format.DateFormat
import com.nexbox.core.media.NexBoxMediaEntry
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

internal val NexBoxMediaEntry.displayTimeMillis: Long
    get() = dateTaken.takeIf { it > 0 } ?: (dateAdded.takeIf { it > 0 } ?: dateModified) * 1000L

internal fun NexBoxMediaEntry.galleryDay(zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(displayTimeMillis).atZone(zone).toLocalDate()

internal fun galleryDateLabel(day: LocalDate, today: LocalDate, locale: Locale, zone: ZoneId): String {
    val pattern = DateFormat.getBestDateTimePattern(locale, if (day.year == today.year) "MMMd" else "yMMMd")
    return SimpleDateFormat(pattern, locale).format(Date.from(day.atStartOfDay(zone).toInstant()))
}
