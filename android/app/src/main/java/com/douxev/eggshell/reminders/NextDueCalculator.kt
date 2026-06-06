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
        intervalDays: Int? = null,
        // The current occurrence (a valid one, set at creation or last advance).
        // Used to keep the N-day phase for "days_interval"; ignored otherwise.
        currentDueMs: Long? = null,
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
        "days_interval" -> {
            require(intervalDays != null && intervalDays > 0) {
                "days_interval schedule needs positive interval_days"
            }
            require(dailyHour != null && dailyMinute != null) {
                "days_interval schedule needs dailyHour + dailyMinute"
            }
            // Step the N-day cadence forward from the current occurrence so the
            // phase (which day in the cycle) is preserved even if the alarm
            // fired late or the app was off for several cycles. plusDays keeps
            // the wall-clock HH:MM across DST (java.time handles the offset).
            val after = Instant.ofEpochMilli(afterMs).atZone(zone)
            var next = Instant.ofEpochMilli(currentDueMs ?: afterMs).atZone(zone)
                .withHour(dailyHour).withMinute(dailyMinute).withSecond(0).withNano(0)
            while (!next.isAfter(after)) next = next.plusDays(intervalDays.toLong())
            next.toInstant().toEpochMilli()
        }
        else -> error("unknown schedule kind: $kind")
    }
}
