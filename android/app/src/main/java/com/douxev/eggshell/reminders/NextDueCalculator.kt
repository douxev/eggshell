package com.douxev.eggshell.reminders

import java.time.Instant
import java.time.ZoneId

/**
 * Pure functions that compute when a schedule's next reminder should fire.
 *
 * Local-time semantics for "daily" schedules live here (and not in Rust) so
 * DST and timezone transitions are handled by java.time.
 */
object NextDueCalculator {

    fun nextDueAfter(
        kind: String,
        intervalMinutes: Int?,
        dailyHour: Int?,
        dailyMinute: Int?,
        afterMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long = when (kind) {
        "interval" -> {
            require(intervalMinutes != null && intervalMinutes > 0) {
                "interval schedule needs positive interval_minutes"
            }
            afterMs + intervalMinutes * 60_000L
        }
        "daily" -> {
            require(dailyHour != null && dailyMinute != null) {
                "daily schedule needs dailyHour + dailyMinute"
            }
            val local = Instant.ofEpochMilli(afterMs).atZone(zone)
            var next = local.withHour(dailyHour).withMinute(dailyMinute)
                .withSecond(0).withNano(0)
            if (!next.isAfter(local)) next = next.plusDays(1)
            next.toInstant().toEpochMilli()
        }
        else -> error("unknown schedule kind: $kind")
    }
}
