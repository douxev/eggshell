package com.douxev.eggshell.ui.sport

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.douxev.eggshell.ui.common.MonthGrid
import com.douxev.eggshell.ui.common.MonthGridDefaults
import java.time.LocalDate
import java.time.YearMonth

/**
 * A month of step counts, one tappable cell per day.
 *
 * Built on [MonthGrid], the same scaffold Journal and Rêves use, rather than a
 * grid of its own: the fiddly parts — the locale-dependent first day of the
 * week, the leading blanks that follow from it, the fixed six rows so the card
 * does not change height between February and a 31-day month — are exactly the
 * ones a second implementation gets subtly wrong and nobody notices for months.
 *
 * A day is shaded by how close it came to the goal, not by a raw count: 4000
 * steps means something different to someone aiming for 3000 than to someone
 * aiming for 12000, and the point of the calendar is to see the shape of a
 * month at a glance.
 */
@Composable
fun StepCalendar(
    yearMonth: YearMonth,
    stepsByDay: Map<LocalDate, Long>,
    dailyGoal: Int,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onPickDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    MonthGrid(
        yearMonth = yearMonth,
        onPrevMonth = onPrevMonth,
        onNextMonth = onNextMonth,
        modifier = modifier,
    ) { day ->
        val steps = stepsByDay[day]
        // Future days are not "zero steps", they are days that have not
        // happened. Shading them as failures would be a month that always looks
        // like it is going badly.
        val future = day.isAfter(today)
        DayCell(
            day = day,
            steps = steps,
            fill = if (future) 0f else goalFraction(steps, dailyGoal),
            isToday = day == today,
            enabled = !future,
            onClick = { onPickDay(day) },
        )
    }
}

/**
 * How full a day\'s disc is, 0..1.
 *
 * Clamped at 1: a day at triple the goal is a full disc, not a brighter one —
 * there is no colour beyond "done", and pretending otherwise would make an
 * ordinary good day look pale next to one long walk.
 */
internal fun goalFraction(steps: Long?, dailyGoal: Int): Float {
    if (steps == null || steps <= 0L || dailyGoal <= 0) return 0f
    return (steps.toFloat() / dailyGoal).coerceIn(0f, 1f)
}

@Composable
private fun DayCell(
    day: LocalDate,
    steps: Long?,
    fill: Float,
    isToday: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(1.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                // Alpha rather than a colour ramp: it reads correctly in both
                // themes and needs no second palette to keep in step.
                if (fill > 0f) scheme.primary.copy(alpha = 0.15f + 0.55f * fill)
                else Color.Transparent
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                day.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.4f)
                    fill > 0.5f -> scheme.onPrimary
                    else -> scheme.onSurface
                },
            )
            if (steps != null && steps > 0) {
                Text(
                    compactSteps(steps),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (fill > 0.5f) scheme.onPrimary else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * "8,4k" rather than "8412".
 *
 * A month grid gives each day about four characters. A count that does not fit
 * is not truncated by the layout — it is ellipsised into nonsense — so it is
 * shortened here, where the rounding is a decision rather than an accident.
 */
internal fun compactSteps(steps: Long): String = when {
    steps < 1_000 -> steps.toString()
    steps < 10_000 -> "%.1fk".format(steps / 1000.0)
    else -> "${steps / 1000}k"
}
