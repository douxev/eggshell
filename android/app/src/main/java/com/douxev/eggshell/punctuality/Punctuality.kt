package com.douxev.eggshell.punctuality

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Punctuality of medication intakes — the measure the refonte adds on top of
 * plain "taken / missed".
 *
 * Pure Kotlin on purpose: the Compose chart, the Médics screens and the doctor
 * PDF all read from here, and it stays unit-testable on the JVM.
 *
 * The offset is never stored: it is derived from the planned time
 * (`DoseEvent.scheduledAtMs`) and the real one (`DoseEvent.takenAtMs`).
 */

/** One intake on the punctuality axis. `deltaMin == null` means "missed". */
data class DosePoint(
    /** When the intake happened — the X axis is proportional to this. */
    val atMs: Long,
    /** Offset from the prescribed time, in minutes. Negative = early. */
    val deltaMin: Int?,
)

/** How a delay is spoken. The caller turns this into a localized string. */
sealed interface DeltaLabel {
    data object OnTime : DeltaLabel
    data object Missed : DeltaLabel
    /** Early by [minutes] (always > 0). */
    data class Early(val minutes: Int) : DeltaLabel
    data class Minutes(val minutes: Int) : DeltaLabel
    data class Hours(val hours: Int) : DeltaLabel
    data class HoursMinutes(val hours: Int, val minutes: Int) : DeltaLabel
}

/**
 * The Y axis of the punctuality chart. `y = 0` sits at the top and the scale
 * clamps to the largest delay **of the period** — never to a constant, so a
 * well-behaved month doesn't look like a bad one.
 */
data class PunctualityAxis(
    /** Largest delay in the period, in minutes. Always ≥ [MIN_SPAN_MIN]. */
    val maxDelayMin: Int,
    /** Largest *early* offset in the period, in minutes (0 when none). */
    val maxEarlyMin: Int,
    /** How many intakes were missed — the band under the dashed separator. */
    val missedCount: Int,
) {
    /** The three gradations: on time, half of the max, the max. */
    val ticks: List<Int> get() = listOf(0, maxDelayMin / 2, maxDelayMin)

    companion object {
        /** Below this, the axis would magnify noise into a wall of late doses. */
        const val MIN_SPAN_MIN = 30
    }
}

fun punctualityAxis(points: List<DosePoint>): PunctualityAxis {
    val deltas = points.mapNotNull { it.deltaMin }
    val maxDelay = deltas.filter { it > 0 }.maxOrNull() ?: 0
    val maxEarly = deltas.filter { it < 0 }.minOrNull()?.let { abs(it) } ?: 0
    return PunctualityAxis(
        maxDelayMin = niceMax(maxOf(maxDelay, PunctualityAxis.MIN_SPAN_MIN)),
        maxEarlyMin = maxEarly,
        missedCount = points.count { it.deltaMin == null },
    )
}

/**
 * Rounds the top of the axis up so that the max **and its half** both land on
 * speakable values — otherwise a 30-minute span would put a "+20 min" label on
 * the 15-minute line and the reader would misjudge every point on the chart.
 *
 * The quantum is twice the rounding step of [axisLabel], which is what makes
 * the middle gradation exact too: 10-minute steps under the hour, 30-minute
 * steps up to three hours, whole hours beyond.
 */
private fun niceMax(minutes: Int): Int {
    val quantum = when {
        minutes <= 60 -> 20
        minutes <= 180 -> 60
        else -> 120
    }
    return ((minutes + quantum - 1) / quantum) * quantum
}

/**
 * Axis gradation label: rounded to the hour above an hour, to the ten minutes
 * below — `+1 h`, `+2 h`, `+40 min`.
 */
fun axisLabel(minutes: Int): DeltaLabel {
    if (minutes == 0) return DeltaLabel.OnTime
    if (minutes < 60) {
        return DeltaLabel.Minutes(((minutes / 10.0).roundToInt() * 10).coerceAtLeast(10))
    }
    // Round the remainder, never the hour: a gradation sitting at 90 minutes
    // must not be labelled "+2 h", or every point on the chart is misread by
    // half an hour.
    val hours = minutes / 60
    val rest = ((minutes % 60) / 10.0).roundToInt() * 10
    return when {
        rest == 0 -> DeltaLabel.Hours(hours)
        rest >= 60 -> DeltaLabel.Hours(hours + 1)
        else -> DeltaLabel.HoursMinutes(hours, rest)
    }
}

/**
 * Exact label of one intake, as shown on its history pill — `à l'heure`,
 * `+1 h 47`, `manquée`.
 *
 * [onTimeToleranceMin] is the window inside which an intake still counts as on
 * time; outside it the pill states the real offset.
 */
fun exactLabel(deltaMin: Int?, onTimeToleranceMin: Int = 15): DeltaLabel = when {
    deltaMin == null -> DeltaLabel.Missed
    abs(deltaMin) <= onTimeToleranceMin -> DeltaLabel.OnTime
    deltaMin < 0 -> DeltaLabel.Early(abs(deltaMin))
    deltaMin >= 60 -> DeltaLabel.HoursMinutes(deltaMin / 60, deltaMin % 60)
    else -> DeltaLabel.Minutes(deltaMin)
}

/** Which of the three punctuality states an intake is in. */
enum class DoseTiming { OnTime, Late, Missed }

fun timingOf(deltaMin: Int?, onTimeToleranceMin: Int = 15): DoseTiming = when {
    deltaMin == null -> DoseTiming.Missed
    deltaMin > onTimeToleranceMin -> DoseTiming.Late
    else -> DoseTiming.OnTime
}

/** Headline figures of the « Régularité » card and of §3 of the PDF. */
data class PunctualityStats(
    val plannedCount: Int,
    val loggedCount: Int,
    val missedCount: Int,
    /** Logged over planned, 0..100. 0 when nothing was planned. */
    val adherencePercent: Int,
    /** Mean offset over the logged intakes that carry one, in minutes. */
    val meanDelayMin: Int,
)

fun punctualityStats(plannedCount: Int, points: List<DosePoint>): PunctualityStats {
    val logged = points.count { it.deltaMin != null }
    val missed = points.count { it.deltaMin == null }
    val deltas = points.mapNotNull { it.deltaMin }
    return PunctualityStats(
        plannedCount = plannedCount,
        loggedCount = logged,
        missedCount = missed,
        adherencePercent = if (plannedCount <= 0) 0 else {
            ((logged.toDouble() / plannedCount) * 100).roundToInt().coerceIn(0, 100)
        },
        meanDelayMin = if (deltas.isEmpty()) 0 else deltas.average().roundToInt(),
    )
}
