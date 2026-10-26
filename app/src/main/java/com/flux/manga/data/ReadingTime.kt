package com.flux.manga.data

import java.time.Instant
import java.time.ZoneId

/** Elapsed time measures duration; wall time only assigns it to local calendar dates. */
internal class ReadingClock {
    private var started: Long? = null
    private var wall = 0L
    private var zone = ZoneId.systemDefault()
    private val totals = linkedMapOf<String, Long>()

    fun update(active: Boolean, elapsed: Long, wallTime: Long, timeZone: ZoneId) {
        started?.let { previous ->
            val duration = (elapsed - previous).coerceAtLeast(0)
            splitReadingDays(wall, duration, zone).forEach { (day, millis) ->
                totals[day] = (totals[day] ?: 0L) + millis
            }
        }
        started = if (active) elapsed else null
        wall = wallTime
        zone = timeZone
    }

    fun snapshot(): Map<String, Long> = totals.toMap()
}

internal fun splitReadingDays(start: Long, duration: Long, zone: ZoneId): Map<String, Long> {
    require(duration >= 0)
    val result = linkedMapOf<String, Long>()
    var cursor = Instant.ofEpochMilli(start)
    var remaining = duration
    while (remaining > 0) {
        val date = cursor.atZone(zone).toLocalDate()
        val midnight = date.plusDays(1).atStartOfDay(zone).toInstant()
        val amount = minOf(remaining, midnight.toEpochMilli() - cursor.toEpochMilli())
        result[date.toString()] = (result[date.toString()] ?: 0L) + amount
        cursor = cursor.plusMillis(amount)
        remaining -= amount
    }
    return result
}

internal fun readingDuration(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return when {
        millis in 1..999 -> "不足 1 秒"
        seconds < 60 -> "${seconds} 秒"
        seconds < 3600 -> "${seconds / 60} 分 ${seconds % 60} 秒"
        else -> "${seconds / 3600} 小时 ${seconds % 3600 / 60} 分"
    }
}
