package com.douxev.eggshell.ui.hormones

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * The geometry of the analyses curve, with no Compose in it.
 *
 * The old chart computed its scales inline in the `Canvas` block, which is why
 * it could not be reasoned about: the Y axis had no gradations to be wrong
 * about, the X axis was two dates in a `Row` under the plot, and "zoom" had
 * nowhere to live because there was no notion of a visible window at all —
 * every draw read `points.first()` and `points.last()`.
 *
 * Splitting the math out buys three things: the axis choices become nameable
 * (see [niceTicks]), the viewport becomes a value that gestures transform
 * rather than a pile of local floats, and both stay unit-testable on the JVM
 * like [com.douxev.eggshell.punctuality] already is.
 */

/** One reading on the curve. */
data class MeasurePoint(
    val atMs: Long,
    val value: Double,
)

/**
 * Which slice of the time range is on screen, as fractions of the full range.
 *
 * Held as `scale` + `start` rather than as two timestamps so a zoom survives
 * the arrival of a new reading: the window stays "the last tenth of history"
 * instead of pinning itself to instants that the new range no longer bounds.
 */
data class TimeViewport(
    /** 1 = the whole range fits; 4 = a quarter of it is on screen. */
    val scale: Float = 1f,
    /** Left edge, as a fraction of the full range. */
    val start: Float = 0f,
) {
    val span: Float get() = 1f / scale
    val end: Float get() = start + span

    /** True when nothing is zoomed or panned — used to hide the reset affordance. */
    val isIdentity: Boolean get() = scale <= 1.0001f

    /**
     * Zoom by [factor] while keeping the data under [focus] (a 0..1 fraction of
     * the plot's width) pinned to that same spot.
     *
     * Without the focal correction a pinch always zooms towards the left edge,
     * which on a six-month curve means the gesture walks away from whatever the
     * user put their fingers on.
     */
    fun zoomedBy(factor: Float, focus: Float): TimeViewport {
        val newScale = (scale * factor).coerceIn(1f, MAX_SCALE)
        // The data fraction currently sitting under the focal point.
        val anchored = start + focus * span
        return copy(scale = newScale, start = anchored - focus / newScale).clamped()
    }

    /** Pan by [deltaFraction] of the *visible* span (positive = content moves left). */
    fun pannedBy(deltaFraction: Float): TimeViewport =
        copy(start = start + deltaFraction * span).clamped()

    /** Keep the window inside the range, and never narrower than the range. */
    fun clamped(): TimeViewport {
        val s = scale.coerceIn(1f, MAX_SCALE)
        val maxStart = (1f - 1f / s).coerceAtLeast(0f)
        return TimeViewport(scale = s, start = start.coerceIn(0f, maxStart))
    }

    companion object {
        /**
         * Past this the window holds less than a percent of the history, which
         * on any realistic series is fewer than two readings — there is nothing
         * left to resolve, and the curve becomes a line leaving the screen.
         */
        const val MAX_SCALE = 100f
    }
}

/**
 * Gradation values for an axis spanning [min]..[max], at most [target] of them.
 *
 * Steps are the 1 / 2 / 5 × 10ⁿ ladder, so a gradation always lands on a number
 * a reader can hold in their head. This is also what stops the Y axis jittering
 * while a pan rescales it: the step only changes when the span crosses a decade,
 * not on every frame.
 *
 * Returns a single tick when the series is flat — a zero-span axis has no
 * gradations to give, and dividing by it is how the old code produced NaN
 * coordinates for a user with two identical readings.
 */
fun niceTicks(min: Double, max: Double, target: Int = 4): List<Double> {
    if (!min.isFinite() || !max.isFinite()) return emptyList()
    val span = max - min
    if (span <= 0.0 || target < 1) return listOf(min)

    val rawStep = span / target
    val magnitude = 10.0.pow(floor(log10(rawStep)))
    val normalized = rawStep / magnitude
    val step = magnitude * when {
        normalized <= 1.0 -> 1.0
        normalized <= 2.0 -> 2.0
        normalized <= 2.5 -> 2.5
        normalized <= 5.0 -> 5.0
        else -> 10.0
    }
    if (step <= 0.0) return listOf(min)

    val first = ceil(min / step) * step
    val out = ArrayList<Double>(target + 1)
    var tick = first
    var guard = 0
    while (tick <= max + step * EPSILON && guard++ <= target * 4) {
        // Snap the accumulated float error back onto the step so a tick reads
        // "40" rather than "39.999999999999996" once ValueFormat quotes it.
        out.add(if (abs(tick) < step * EPSILON) 0.0 else tick)
        tick += step
    }
    return out
}

/**
 * The value range an axis should cover for [values], padded so the curve does
 * not graze the top and bottom edges of the plot.
 *
 * A flat series gets a synthetic span around its value: without one the curve
 * would be drawn on the baseline with no indication that it is flat *and* the
 * axis would carry a single repeated gradation.
 */
fun valueRange(values: List<Double>, padFraction: Double = 0.08): ClosedFloatingPointRange<Double> {
    val finite = values.filter { it.isFinite() }
    if (finite.isEmpty()) return 0.0..1.0
    val min = finite.min()
    val max = finite.max()
    if (min == max) {
        val pad = if (min == 0.0) 1.0 else abs(min) * 0.1
        return (min - pad)..(max + pad)
    }
    val pad = (max - min) * padFraction
    return (min - pad)..(max + pad)
}

/**
 * The reading nearest [fraction] (a 0..1 position across the *visible* window),
 * or null when the nearest one is further than [toleranceFraction] away.
 *
 * Hit-testing on the time axis alone, not on 2-D distance to the drawn dot: the
 * user is picking a date, and on a curve with a steep segment the dot they mean
 * can sit a long way from their finger vertically.
 */
fun nearestPoint(
    points: List<MeasurePoint>,
    viewport: TimeViewport,
    fraction: Float,
    toleranceFraction: Float = 0.06f,
): MeasurePoint? {
    if (points.isEmpty()) return null
    val first = points.first().atMs
    val last = points.last().atMs
    val range = (last - first).coerceAtLeast(1L)
    val targetFraction = viewport.start + fraction * viewport.span
    return points
        .map { it to abs(((it.atMs - first).toDouble() / range) - targetFraction) }
        .minByOrNull { it.second }
        ?.takeIf { it.second <= toleranceFraction * viewport.span }
        ?.first
}

/** Guards the `<=` on an accumulating float so the last tick isn't dropped. */
private const val EPSILON = 1e-6
