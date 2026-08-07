package com.douxev.eggshell.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.douxev.eggshell.R
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Date

/**
 * The month scaffold both journals share: title row with its two arrows, the
 * weekday header, and six week rows that call [cell] for each real day.
 *
 * Extracted rather than copied because the fiddly parts are the ones a copy
 * gets subtly wrong and nobody notices for months: the first day of the week
 * is locale-dependent (Monday in France, Sunday in the US), the leading-blank
 * count derives from it, and the grid is always six rows so the card does not
 * change height between a 28-day February and a 31-day month that starts on a
 * Sunday. Two implementations of that would eventually disagree, and the one
 * that disagreed would be the one nobody was looking at.
 *
 * What a day *looks* like is deliberately not here — a mood disc, a bleeding
 * run and a dream marker have nothing in common — so [cell] draws it and only
 * has to fill [cellHeight].
 */
@Composable
fun MonthGrid(
    yearMonth: YearMonth,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    modifier: Modifier = Modifier,
    cellHeight: Dp = MonthGridDefaults.CellHeight,
    footer: @Composable (() -> Unit)? = null,
    cell: @Composable (LocalDate) -> Unit,
) {
    val locale = rememberLocale()
    val zone = remember { ZoneId.systemDefault() }
    val monthLabel = remember(yearMonth, locale) {
        SimpleDateFormat("LLLL yyyy", locale)
            .format(Date.from(yearMonth.atDay(1).atStartOfDay(zone).toInstant()))
            .replaceFirstChar { it.titlecase(locale) }
    }
    val firstDayOfWeek = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val weekdayLabels = remember(firstDayOfWeek, locale) {
        (0..6).map { i ->
            DayOfWeek.of(((firstDayOfWeek.value - 1 + i) % 7) + 1)
                .getDisplayName(TextStyle.NARROW, locale)
                .uppercase(locale)
        }
    }
    val firstOfMonth = yearMonth.atDay(1)
    val leadingBlanks = ((firstOfMonth.dayOfWeek.value - firstDayOfWeek.value) + 7) % 7
    val daysInMonth = yearMonth.lengthOfMonth()

    EggCard(variant = CardVariant.Low, padding = PaddingValues(16.dp), modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevMonth, modifier = Modifier.size(MonthGridDefaults.Touch)) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.journal_prev_month),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                monthLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNextMonth, modifier = Modifier.size(MonthGridDefaults.Touch)) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.journal_next_month),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
        ) {
            weekdayLabels.forEach { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Six rows, always: a card that changes height as the user pages
        // through months makes everything below it jump.
        for (row in 0 until 6) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0..6) {
                    val dayNumber = row * 7 + col - leadingBlanks + 1
                    if (dayNumber !in 1..daysInMonth) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(cellHeight),
                        )
                        continue
                    }
                    Box(modifier = Modifier.weight(1f)) { cell(yearMonth.atDay(dayNumber)) }
                }
            }
        }

        footer?.invoke()
    }
}

object MonthGridDefaults {
    val CellHeight = 34.dp
    val Touch = 44.dp
}
