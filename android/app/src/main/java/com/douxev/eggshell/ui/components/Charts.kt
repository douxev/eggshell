package com.douxev.eggshell.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.douxev.eggshell.punctuality.DeltaLabel
import com.douxev.eggshell.punctuality.DosePoint
import com.douxev.eggshell.punctuality.DoseTiming
import com.douxev.eggshell.punctuality.axisLabel
import com.douxev.eggshell.punctuality.punctualityAxis
import com.douxev.eggshell.punctuality.timingOf
import com.douxev.eggshell.ui.theme.EggColors

/**
 * Progress ring of the dose card. The 600 ms `cubic-bezier(.2,0,0,1)` curve is
 * the one the handoff specifies for the ring, not the generic M3 easing.
 */
@Composable
fun ProgressRing(
    value: Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 64.dp,
    stroke: Dp = 6.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    track: Color = color.copy(alpha = 0.20f),
    content: @Composable () -> Unit,
) {
    val animated by animateFloatAsState(
        targetValue = value.coerceIn(0f, 1f),
        animationSpec = tween(
            durationMillis = 600,
            easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
        ),
        label = "progress-ring",
    )
    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(diameter)) {
            val w = stroke.toPx()
            val topLeft = Offset(w / 2, w / 2)
            val arcSize = Size(size.width - w, size.height - w)
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = w),
                topLeft = topLeft,
                size = arcSize,
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                style = Stroke(width = w, cap = StrokeCap.Round),
                topLeft = topLeft,
                size = arcSize,
            )
        }
        content()
    }
}

/**
 * The punctuality chart (handoff §5.2) — new to this refonte, shared by Médics
 * and the doctor PDF.
 *
 * One dot per intake, X proportional to time. Y is the offset from the
 * prescribed time with `y = 0` at the top; the scale clamps to the largest
 * delay **of the period**. The legend is carried by the axis gradations, never
 * by a separate row under the plot.
 *
 * Strings arrive already localized so the component stays translation-agnostic:
 * [labelFor] renders an axis gradation and [missedLabel] the "oubliées · N" band.
 */
@Composable
fun PunctualityChart(
    points: List<DosePoint>,
    labelFor: (DeltaLabel) -> String,
    missedLabel: (Int) -> String,
    modifier: Modifier = Modifier,
    onTimeToleranceMin: Int = 15,
) {
    val axis = punctualityAxis(points)
    val scheme = MaterialTheme.colorScheme
    val gridColor = EggColors.chartGrid
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall

    val tickLabels = axis.ticks.map { labelFor(axisLabel(it)) }
    val missedText = missedLabel(axis.missedCount)
    val tickColors = listOf(scheme.tertiary, scheme.secondary, scheme.secondary)

    Canvas(modifier = modifier.fillMaxWidth()) {
        val gutter = 66.dp.toPx()
        val plotLeft = gutter + 8.dp.toPx()
        val plotRight = size.width
        val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)

        // Reserve a band for the missed doses under a dashed separator, and a
        // sliver of headroom above the zero line for the early intakes.
        val missedBandHeight = 18.dp.toPx()
        val separatorY = size.height - missedBandHeight
        val missedCenterY = size.height - missedBandHeight / 2
        val earlyHeadroom = if (axis.maxEarlyMin > 0) 10.dp.toPx() else 4.dp.toPx()
        val zeroY = earlyHeadroom
        val maxY = (separatorY - 6.dp.toPx()).coerceAtLeast(zeroY + 1f)

        fun yFor(deltaMin: Int): Float = when {
            deltaMin >= 0 ->
                zeroY + (deltaMin.toFloat() / axis.maxDelayMin) * (maxY - zeroY)
            axis.maxEarlyMin > 0 ->
                zeroY - (-deltaMin.toFloat() / axis.maxEarlyMin) * earlyHeadroom
            else -> zeroY
        }.coerceIn(0f, maxY)

        val dashed = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))

        // Gradations. Zero is the dashed tertiary line; the other two are grid.
        axis.ticks.forEachIndexed { index, tick ->
            val y = yFor(tick)
            drawLine(
                color = if (index == 0) scheme.tertiary else gridColor,
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = if (index == 0) dashed else null,
            )
            val text = measurer.measure(
                tickLabels[index],
                style = labelStyle.merge(TextStyle(color = tickColors[index])),
            )
            drawText(
                textLayoutResult = text,
                topLeft = Offset(
                    x = gutter - text.size.width,
                    y = y - text.size.height / 2f,
                ),
            )
        }

        // Separator + the missed band's own axis label.
        drawLine(
            color = gridColor,
            start = Offset(plotLeft, separatorY),
            end = Offset(plotRight, separatorY),
            strokeWidth = 1.dp.toPx(),
            pathEffect = dashed,
        )
        val missedLayout = measurer.measure(
            missedText,
            style = labelStyle.merge(TextStyle(color = scheme.error)),
        )
        drawText(
            textLayoutResult = missedLayout,
            topLeft = Offset(
                x = gutter - missedLayout.size.width,
                y = missedCenterY - missedLayout.size.height / 2f,
            ),
        )

        if (points.isEmpty()) return@Canvas

        // X is proportional to time, never to the index.
        val firstMs = points.minOf { it.atMs }
        val lastMs = points.maxOf { it.atMs }
        val spanMs = (lastMs - firstMs).coerceAtLeast(1L)
        val radius = 2.6.dp.toPx()

        points.forEach { point ->
            val x = plotLeft + ((point.atMs - firstMs).toFloat() / spanMs) * plotWidth
            when (timingOf(point.deltaMin, onTimeToleranceMin)) {
                DoseTiming.Missed -> drawCircle(
                    color = scheme.error,
                    radius = radius,
                    center = Offset(x, missedCenterY),
                )
                DoseTiming.OnTime -> drawCircle(
                    color = scheme.tertiary,
                    radius = radius,
                    center = Offset(x, yFor(point.deltaMin ?: 0)),
                )
                DoseTiming.Late -> drawCircle(
                    color = scheme.secondary,
                    radius = radius,
                    center = Offset(x, yFor(point.deltaMin ?: 0)),
                )
            }
        }
    }
}
