package com.douxev.eggshell.reminders

import java.time.Instant
import java.time.ZoneId

/**
 * Next-due computer for lab reminders.
 *
 * "interval" is in days (analyses are weekly/monthly/quarterly, never hourly).
 * "daily" is the same HH:MM-each-day semantics as medication schedules.
 */
object LabNextDueCalculator {

    fun nextDueAfter(
        kind: String,
        intervalDays: Int?,
        dailyHour: Int?,
        dailyMinute: Int?,
        afterMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long = when (kind) {
        "interval" -> {
            require(intervalDays != null && intervalDays > 0) {
                "lab interval needs positive intervalDays"
            }
            afterMs + intervalDays * 86_400_000L
        }
        "daily" -> {
            require(dailyHour != null && dailyMinute != null) {
                "lab daily reminder needs dailyHour + dailyMinute"
            }
            val local = Instant.ofEpochMilli(afterMs).atZone(zone)
            var next = local.withHour(dailyHour).withMinute(dailyMinute)
                .withSecond(0).withNano(0)
            if (!next.isAfter(local)) next = next.plusDays(1)
            next.toInstant().toEpochMilli()
        }
        else -> error("unknown lab reminder kind: $kind")
    }
}
