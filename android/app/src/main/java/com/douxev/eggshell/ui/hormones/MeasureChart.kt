package com.douxev.eggshell.ui.hormones

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.douxev.eggshell.R
import com.douxev.eggshell.ui.common.ValueFormat
import com.douxev.eggshell.ui.components.MicroLabel
import com.douxev.eggshell.ui.theme.EggColors
import com.douxev.eggshell.ui.theme.EggShapes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The analyses curve (handoff §5.1), rebuilt around [MeasureChartModel].
 *
 * What the previous version could not do, and why each is here:
 *
 * - **The Y axis carried no numbers.** Three evenly-spaced grey lines were
 *   drawn at fixed quarters of the plot and left unlabelled, so the curve
 *   showed a *shape* and never a level — the one thing a blood result is
 *   consulted for. Gradations now come from [niceTicks] and are written out.
 * - **The X axis was two dates in a Row underneath.** With no gradations
 *   between them, a reading in the middle of a two-year history could not be
 *   dated at all. The axis is now drawn and labelled at the same cadence as the
 *   visible window.
 * - **There was no viewport.** Every draw spanned `points.first()` to
 *   `points.last()`, so a decade of history was permanently squeezed into one
 *   card width and the recent weeks — the part anyone actually reads — were a
 *   few pixels wide. Pinch and drag now move a [TimeViewport].
 * - **Nothing could be interrogated.** Tapping the plot now pins the nearest
 *   reading and states its date and value, which is also what makes the chart
 *   answerable for a screen-reader user via [selectionSummary].
 *
 * The Y axis re-fits the *visible* window rather than the whole series: zoomed
 * into a stable stretch, a curve scaled to a two-year outlier would be a flat
 * line pinned to the bottom of the plot, which is exactly the reading the zoom
 * was performed to avoid.
 */
@Composable
fun MeasureChart(
    points: List<MeasurePoint>,
    unit: String,
    doseMarkers: List<DoseMarker>,
    treatmentChanges: List<Long>,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val curveColor = scheme.primary
    val doseColor = scheme.tertiary
    val changeColor = scheme.secondary
    val gridColor = EggColors.chartGrid
    val axisTextColor = scheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall

    var viewport by remember(points) { mutableStateOf(TimeViewport()) }
    var selected by remember(points) { mutableStateOf<MeasurePoint?>(null) }

    // A reading added or removed while zoomed leaves the pinned point dangling.
    LaunchedEffect(points) { selected = null }

    val dayFmt = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }
    val yearFmt = remember { SimpleDateFormat("MMM yy", Locale.getDefault()) }
    val fullFmt = remember { SimpleDateFormat("d MMMM yyyy", Locale.getDefault()) }

    Column(modifier = modifier) {
        Box {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ChartHeight)
                    .semantics { contentDescription?.let { this.contentDescription = it } }
                    // Two separate pointer handlers: transform gestures consume
                    // multi-touch and drags, taps are their own detector. Folding
                    // the tap into detectTransformGestures would fire a selection
                    // at the end of every pan.
                    .pointerInput(points) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val width = size.width.toFloat().coerceAtLeast(1f)
                            if (zoom != 1f) {
                                val focus = (centroid.x / width).coerceIn(0f, 1f)
                                viewport = viewport.zoomedBy(zoom, focus)
                            }
                            if (pan.x != 0f) {
                                viewport = viewport.pannedBy(-pan.x / width)
                            }
                        }
                    }
                    .pointerInput(points) {
                        detectTapGestures { offset ->
                            // Same plot rectangle the draw uses. Deriving it a
                            // second time by eye would put the hit-test a few
                            // pixels off the dots it is meant to pick.
                            val plotLeft = gutterPx(this@pointerInput.density)
                            val plotRight = size.width - plotRightInsetPx(this@pointerInput.density)
                            val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
                            val fraction = ((offset.x - plotLeft) / plotWidth).coerceIn(0f, 1f)
                            val hit = nearestPoint(points, viewport, fraction)
                            // Tapping the pinned reading again unpins it, so the
                            // readout is dismissable without hunting for empty space.
                            selected = if (hit == selected) null else hit
                        }
                    },
            ) {
                drawMeasureChart(
                    points = points,
                    viewport = viewport,
                    selected = selected,
                    doseMarkers = doseMarkers,
                    treatmentChanges = treatmentChanges,
                    curveColor = curveColor,
                    doseColor = doseColor,
                    changeColor = changeColor,
                    gridColor = gridColor,
                    axisTextColor = axisTextColor,
                    measurer = measurer,
                    axisStyle = axisStyle,
                    labelForTime = { ms ->
                        // The visible window decides the cadence: "12 mars" is
                        // noise across four years, "mars 24" is useless across
                        // three weeks.
                        val visibleMs = totalSpanMs(points) * viewport.span.toDouble()
                        if (visibleMs > YEAR_MS) yearFmt.format(Date(ms)) else dayFmt.format(Date(ms))
                    },
                )
            }

            if (!viewport.isIdentity) {
                TextButton(
                    onClick = { viewport = TimeViewport() },
                    modifier = Modifier.align(Alignment.TopEnd),
                ) { Text(stringResource(R.string.measures_chart_reset_zoom)) }
            }
        }

        SelectionReadout(
            selected = selected,
            unit = unit,
            formatDate = { fullFmt.format(Date(it)) },
        )

        ChartLegend(
            curveColor = curveColor,
            doseColor = doseColor,
            changeColor = changeColor,
            hasDoses = doseMarkers.isNotEmpty(),
            hasChanges = treatmentChanges.isNotEmpty(),
            unit = unit,
        )
    }
}

/**
 * The readout under the plot. It keeps its height whether or not a reading is
 * pinned — a row that appears and disappears would shove the legend and every
 * card below it up and down on each tap.
 */
@Composable
private fun SelectionReadout(
    selected: MeasurePoint?,
    unit: String,
    formatDate: (Long) -> String,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ReadoutHeight)
            .padding(top = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (selected == null) {
            MicroLabel(stringResource(R.string.measures_chart_hint))
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        EggShapes.Pill,
                    )
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(
                    formatDate(selected.atMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${ValueFormat.significant(selected.value)} $unit",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * The key. Every series is named in words as well as coloured, so nothing on
 * this chart is told by hue alone (§5.1) — including the curve itself, which
 * the previous legend left unlabelled while naming its two overlays.
 */
@Composable
private fun ChartLegend(
    curveColor: Color,
    doseColor: Color,
    changeColor: Color,
    hasDoses: Boolean,
    hasChanges: Boolean,
    unit: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendKey(
            label = stringResource(R.string.measures_legend_level_fmt, unit),
            color = curveColor,
            dashed = false,
        )
        if (hasDoses) {
            LegendKey(
                label = stringResource(R.string.measures_legend_doses),
                color = doseColor,
                dashed = false,
            )
        }
        if (hasChanges) {
            LegendKey(
                label = stringResource(R.string.measures_legend_change),
                color = changeColor,
                dashed = true,
            )
        }
    }
}

@Composable
private fun LegendKey(label: String, color: Color, dashed: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.douxev.eggshell.ui.components.Decorative {
            if (dashed) {
                Box(
                    modifier = Modifier
                        .width(14.dp)
                        .height(2.dp)
                        .background(color),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(color, EggShapes.Pill),
                )
            }
        }
        MicroLabel(label, color = color)
    }
}

// ---------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------

/**
 * One draw of the plot. Kept as a `DrawScope` extension rather than inlined in
 * the composable so the Canvas lambda reads as a call and the drawing order —
 * grid, area, curve, overlays, selection — is visible in one screen.
 */
private fun DrawScope.drawMeasureChart(
    points: List<MeasurePoint>,
    viewport: TimeViewport,
    selected: MeasurePoint?,
    doseMarkers: List<DoseMarker>,
    treatmentChanges: List<Long>,
    curveColor: Color,
    doseColor: Color,
    changeColor: Color,
    gridColor: Color,
    axisTextColor: Color,
    measurer: TextMeasurer,
    axisStyle: TextStyle,
    labelForTime: (Long) -> String,
) {
    if (points.size < 2) return

    val gutter = gutterPx(density)
    val bottomAxis = bottomAxisPx(density)
    val plotLeft = gutter
    val plotRight = size.width - plotRightInsetPx(density)
    val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
    val plotTop = 8.dp.toPx()
    val plotBottom = (size.height - bottomAxis).coerceAtLeast(plotTop + 1f)
    val plotHeight = plotBottom - plotTop

    val firstMs = points.first().atMs
    val lastMs = points.last().atMs
    val totalMs = (lastMs - firstMs).coerceAtLeast(1L)

    // The visible time window, in real instants.
    val windowStartMs = firstMs + (totalMs * viewport.start.toDouble()).toLong()
    val windowEndMs = firstMs + (totalMs * viewport.end.toDouble()).toLong()
    val windowMs = (windowEndMs - windowStartMs).coerceAtLeast(1L)

    fun xFor(ms: Long): Float =
        plotLeft + plotWidth * ((ms - windowStartMs).toDouble() / windowMs).toFloat()

    // Y fits the readings that are visible, plus the segment endpoints just
    // outside the window — otherwise a curve entering from off-screen would be
    // scaled against values it does not reach and would leave the plot.
    val visible = points.filter { it.atMs in windowStartMs..windowEndMs }
    val spanning = buildList {
        addAll(visible.map { it.value })
        points.lastOrNull { it.atMs < windowStartMs }?.let { add(interpolate(points, windowStartMs)) }
        points.firstOrNull { it.atMs > windowEndMs }?.let { add(interpolate(points, windowEndMs)) }
    }
    val range = valueRange(spanning.ifEmpty { points.map { it.value } })
    val vMin = range.start
    val vMax = range.endInclusive
    val vSpan = (vMax - vMin).takeIf { it > 0.0 } ?: 1.0

    fun yFor(v: Double): Float =
        plotBottom - (plotHeight * ((v - vMin) / vSpan)).toFloat()

    // -- Y gradations: a line across the plot, its value in the gutter --------
    niceTicks(vMin, vMax, target = 4).forEach { tick ->
        val y = yFor(tick)
        if (y < plotTop - 1f || y > plotBottom + 1f) return@forEach
        drawLine(
            color = gridColor,
            start = Offset(plotLeft, y),
            end = Offset(plotRight, y),
            strokeWidth = 1.dp.toPx(),
        )
        val layout = measurer.measure(
            ValueFormat.significant(tick),
            style = axisStyle.merge(TextStyle(color = axisTextColor)),
        )
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                x = (gutter - 6.dp.toPx() - layout.size.width).coerceAtLeast(0f),
                y = y - layout.size.height / 2f,
            ),
        )
    }

    // -- The curve, clipped to the plot so a pan can't paint over the gutter --
    clipRect(left = plotLeft, top = 0f, right = plotRight, bottom = size.height) {
        val line = Path()
        val area = Path()
        points.forEachIndexed { i, p ->
            val x = xFor(p.atMs)
            val y = yFor(p.value)
            if (i == 0) {
                line.moveTo(x, y)
                area.moveTo(x, plotBottom)
                area.lineTo(x, y)
            } else {
                line.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo(xFor(points.last().atMs), plotBottom)
        area.close()

        drawPath(
            path = area,
            brush = Brush.verticalGradient(
                colors = listOf(curveColor.copy(alpha = 0.30f), curveColor.copy(alpha = 0f)),
                startY = plotTop,
                endY = plotBottom,
            ),
        )
        drawPath(
            path = line,
            color = curveColor,
            style = Stroke(
                width = 2.4.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )

        // Treatment changes: a dashed vertical to line up against the bend.
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        treatmentChanges.filter { it in windowStartMs..windowEndMs }.forEach { at ->
            drawLine(
                color = changeColor.copy(alpha = 0.8f),
                start = Offset(xFor(at), plotTop),
                end = Offset(xFor(at), plotBottom),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = dash,
            )
        }

        // Doses ride the interpolated curve.
        doseMarkers.filter { it.atMs in windowStartMs..windowEndMs }.forEach { m ->
            drawCircle(
                color = doseColor,
                radius = 3.2.dp.toPx(),
                center = Offset(xFor(m.atMs), yFor(interpolate(points, m.atMs))),
            )
        }

        // Each reading gets a dot once zoomed in enough that they don't merge
        // into a bead chain — at full range a two-year weekly series would be
        // a solid stripe.
        if (visible.size <= MAX_DOTS) {
            visible.forEach { p ->
                drawCircle(
                    color = curveColor,
                    radius = 3.dp.toPx(),
                    center = Offset(xFor(p.atMs), yFor(p.value)),
                )
            }
        }

        // The pinned reading: haloed, and dropped to the axis so the date under
        // it is unambiguous.
        selected?.let { p ->
            val x = xFor(p.atMs)
            val y = yFor(p.value)
            drawLine(
                color = curveColor.copy(alpha = 0.45f),
                start = Offset(x, plotTop),
                end = Offset(x, plotBottom),
                strokeWidth = 1.dp.toPx(),
            )
            drawCircle(curveColor.copy(alpha = 0.22f), radius = 9.dp.toPx(), center = Offset(x, y))
            drawCircle(curveColor, radius = 5.dp.toPx(), center = Offset(x, y))
        }
    }

    // -- X gradations, under the plot ----------------------------------------
    drawLine(
        color = gridColor,
        start = Offset(plotLeft, plotBottom),
        end = Offset(plotRight, plotBottom),
        strokeWidth = 1.dp.toPx(),
    )
    val timeTicks = timeTicksFor(windowStartMs, windowEndMs, TIME_TICK_TARGET)
    var lastRight = -Float.MAX_VALUE
    timeTicks.forEach { ms ->
        val x = xFor(ms)
        if (x < plotLeft - 1f || x > plotRight + 1f) return@forEach
        val layout: TextLayoutResult = measurer.measure(
            labelForTime(ms),
            style = axisStyle.merge(TextStyle(color = axisTextColor)),
        )
        val left = (x - layout.size.width / 2f).coerceIn(plotLeft, plotRight - layout.size.width)
        // Drop a label that would collide with the previous one rather than
        // overprinting: an unreadable date is worse than a missing gradation.
        if (left < lastRight + 6.dp.toPx()) return@forEach
        lastRight = left + layout.size.width
        drawLine(
            color = gridColor,
            start = Offset(x, plotBottom),
            end = Offset(x, plotBottom + 3.dp.toPx()),
            strokeWidth = 1.dp.toPx(),
        )
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(x = left, y = plotBottom + 6.dp.toPx()),
        )
    }
}

/** The curve's value at [ms], linearly interpolated between its readings. */
private fun interpolate(points: List<MeasurePoint>, ms: Long): Double {
    if (points.isEmpty()) return 0.0
    val i = points.indexOfLast { it.atMs <= ms }
    if (i < 0) return points.first().value
    if (i >= points.lastIndex) return points.last().value
    val a = points[i]
    val b = points[i + 1]
    if (b.atMs == a.atMs) return a.value
    val f = (ms - a.atMs).toDouble() / (b.atMs - a.atMs).toDouble()
    return a.value + (b.value - a.value) * f
}

/**
 * Instants to gradate the time axis at, on calendar-friendly steps.
 *
 * Round *durations* (every 2 500 000 ms) would put gradations mid-afternoon on
 * arbitrary days; the ladder below keeps them on day, week, month and year
 * boundaries, which is how the dates under them are read.
 */
private fun timeTicksFor(fromMs: Long, toMs: Long, target: Int): List<Long> {
    val span = (toMs - fromMs).coerceAtLeast(1L)
    val step = TIME_STEPS.firstOrNull { span / it <= target } ?: TIME_STEPS.last()
    val first = (fromMs / step) * step
    val out = ArrayList<Long>()
    var t = first
    var guard = 0
    while (t <= toMs && guard++ <= target * 6) {
        if (t >= fromMs) out.add(t)
        t += step
    }
    return out
}

private const val DAY_MS = 86_400_000L
private const val YEAR_MS = 365L * DAY_MS

/** Day, 2 days, week, fortnight, month, quarter, half-year, year, 2 / 5 years. */
private val TIME_STEPS = longArrayOf(
    DAY_MS,
    2 * DAY_MS,
    7 * DAY_MS,
    14 * DAY_MS,
    30 * DAY_MS,
    91 * DAY_MS,
    182 * DAY_MS,
    YEAR_MS,
    2 * YEAR_MS,
    5 * YEAR_MS,
)

private fun totalSpanMs(points: List<MeasurePoint>): Long =
    if (points.size < 2) 1L else (points.last().atMs - points.first().atMs).coerceAtLeast(1L)

private val ChartHeight = 180.dp
private val ReadoutHeight = 30.dp

// The plot rectangle, in px. Shared by the draw and the tap hit-test so the two
// cannot drift apart — the dots a user aims at are placed by the first and
// picked by the second.

/** Room for the widest Y label ValueFormat can produce at a sane magnitude. */
private fun gutterPx(density: Float): Float = 52f * density

/** Room for the X gradations and their labels. */
private fun bottomAxisPx(density: Float): Float = 22f * density

/** Breathing room so the last reading's halo isn't clipped by the card edge. */
private fun plotRightInsetPx(density: Float): Float = 4f * density

/** Above this many visible readings the per-point dots merge into a stripe. */
private const val MAX_DOTS = 40

private const val TIME_TICK_TARGET = 4
