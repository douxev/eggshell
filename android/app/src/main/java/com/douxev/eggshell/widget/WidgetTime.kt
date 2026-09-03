package com.douxev.eggshell.widget

import android.content.Context
import com.douxev.eggshell.R

/**
 * "in 2 h", "now", "3 d ago" — the only time format a widget should use.
 *
 * Relative rather than absolute, because a widget is not repainted on a clock
 * tick. Nothing polls (that would wake the device forever to fix a cosmetic
 * drift), so an absolute time would be exactly as stale as this one while
 * looking authoritative about it. "in 2 h" degrades honestly; "14:30" does not.
 */
object WidgetTime {

    /** Half an hour either side of the mark reads as "now" rather than a count. */
    private const val NOW_WINDOW_MIN = 30L

    fun relative(context: Context, atMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val deltaMin = (atMs - nowMs) / 60_000L
        return when {
            deltaMin in -NOW_WINDOW_MIN..NOW_WINDOW_MIN ->
                context.getString(R.string.widget_due_now)
            deltaMin > 0 -> context.getString(R.string.widget_due_in, spell(context, deltaMin))
            else -> context.getString(R.string.widget_due_ago, spell(context, -deltaMin))
        }
    }

    /** The same scale used the other way round: how long ago something happened. */
    fun since(context: Context, atMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val deltaMin = ((nowMs - atMs) / 60_000L).coerceAtLeast(0)
        return if (deltaMin <= NOW_WINDOW_MIN) context.getString(R.string.widget_due_now)
        else context.getString(R.string.widget_due_ago, spell(context, deltaMin))
    }

    private fun spell(context: Context, minutes: Long): String {
        val (scale, value) = scale(minutes)
        return context.getString(
            when (scale) {
                Scale.MINUTES -> R.string.widget_minutes
                Scale.HOURS -> R.string.widget_hours
                Scale.DAYS -> R.string.widget_days
            },
            value,
        )
    }

    internal enum class Scale { MINUTES, HOURS, DAYS }

    /**
     * Which unit a duration is worth stating in, and the value in that unit.
     *
     * Pure and internal so the boundaries can be tested: 59 vs 60 minutes and
     * 23 h vs 1 d are exactly the kind of off-by-one that reads as perfectly
     * fine on a home screen while being wrong.
     *
     * Truncating, not rounding. "in 1 h" for something 119 minutes away would
     * be a promise the widget cannot keep; the reader can wait longer than the
     * label says, never less.
     */
    internal fun scale(minutes: Long): Pair<Scale, Int> = when {
        minutes < 60 -> Scale.MINUTES to minutes.toInt()
        minutes < 60 * 24 -> Scale.HOURS to (minutes / 60).toInt()
        else -> Scale.DAYS to (minutes / (60 * 24)).toInt()
    }
}
