package com.douxev.eggshell.widget

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The decisions behind a few widget rows, as pure functions.
 *
 * Their own file, and free of Android, for the reason [TakenAtClock] is: each
 * of these is easy to get wrong in a way that still looks perfectly plausible
 * on a home screen — a gauge with no name, a cycle day that is really "days
 * since the last entry", a count that is really the size of a page.
 */
internal object WidgetRowRules {

    /**
     * How many days without an entry a bleed may contain and still be one bleed.
     *
     * One. People skip logging a day in the middle of a period far more often
     * than two periods sit a single day apart, and splitting the bleed there
     * would restart the count at J1 halfway through — the exact misreading
     * this rule exists to stop.
     */
    const val BLEED_GAP_DAYS = 1

    /**
     * The cycle day for [today]: 1 on the first day of the latest bleed.
     *
     * [bleedingDays] are the local days that have a bleeding (not spotting)
     * entry, in any order, duplicates allowed. The latest bleed is the run of
     * those days that ends with the most recent one, where consecutive days may
     * be at most [BLEED_GAP_DAYS] unlogged days apart. Days after [today] — a
     * clock that moved back, an entry typed for tomorrow — are ignored rather
     * than allowed to produce a day zero or a negative one.
     *
     * Null when there is no bleeding day at all: there is nothing to count
     * from, and making a day up would be a prediction.
     */
    fun cycleDay(bleedingDays: Collection<LocalDate>, today: LocalDate): Int? {
        val days = bleedingDays.filter { !it.isAfter(today) }.toSortedSet()
        var start = days.lastOrNull() ?: return null
        while (true) {
            val earlier = days.headSet(start).lastOrNull() ?: break
            val unlogged = ChronoUnit.DAYS.between(earlier, start) - 1
            if (unlogged > BLEED_GAP_DAYS) break
            start = earlier
        }
        return (ChronoUnit.DAYS.between(start, today) + 1).toInt()
    }

    /**
     * The name a journal gauge is shown under.
     *
     * Built-in gauges are stored with an empty label — the app names them, in
     * the user's language — so an empty [label] is resolved through
     * [builtinRes] (the same table the journal screen uses), and falls back to
     * the metric key rather than to nothing: a bar with no name says less than
     * a bar with an internal one.
     */
    fun gaugeLabel(
        label: String,
        metricKey: String,
        builtinRes: Int,
        resolve: (Int) -> String,
    ): String = when {
        label.isNotBlank() -> label
        builtinRes != 0 -> resolve(builtinRes)
        else -> metricKey
    }

    /**
     * Whether a count read through a page of [scan] rows may be short of the
     * truth. A full page means there may be more; the widget then says « 500+ »
     * rather than claim exactly 500.
     */
    fun isCapped(count: Int, scan: Long): Boolean = count.toLong() >= scan
}
