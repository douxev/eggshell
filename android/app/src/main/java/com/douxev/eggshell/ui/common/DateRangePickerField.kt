package com.douxev.eggshell.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.douxev.eggshell.R
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale

/**
 * Date-range companion to [DateTimePickerField]: a tappable field that opens a
 * Material3 [DateRangePicker] and reports the picked span as [LocalDate]s.
 * Callers decide what time-of-day each day gets (noon for bleeding spans, the
 * chosen intake hour for dose spans) — the field never touches epoch-ms itself,
 * which sidesteps the picker's UTC-midnight vs local-day mismatch entirely.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangePickerField(
    label: String,
    start: LocalDate?,
    end: LocalDate?,
    onChange: (LocalDate, LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** When false, the picker refuses future days — a logged span can only
     *  cover days that already happened. */
    allowFuture: Boolean = true,
) {
    var show by rememberSaveable { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val displayFmt = remember {
        DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
    }
    fun fmt(d: LocalDate): String =
        displayFmt.format(Date(d.atStartOfDay(zone).toInstant().toEpochMilli()))
    val selectableDates = remember(allowFuture) {
        if (allowFuture) {
            DatePickerDefaults.AllDates
        } else {
            object : SelectableDates {
                // The picker reports UTC-midnight millis; reject any day after
                // local today (expressed as the next day's UTC midnight).
                private val maxExclusive = LocalDate.now(zone).plusDays(1)
                    .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis < maxExclusive
                override fun isSelectableYear(year: Int) = year <= LocalDate.now(zone).year
            }
        }
    }

    OutlinedButton(
        onClick = { show = true },
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
    ) {
        Icon(Icons.Filled.DateRange, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(
                if (start != null && end != null) "${fmt(start)} – ${fmt(end)}"
                else stringResource(R.string.daterange_pick),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }

    if (show) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = start?.atStartOfDay(ZoneOffset.UTC)
                ?.toInstant()?.toEpochMilli(),
            initialSelectedEndDateMillis = end?.atStartOfDay(ZoneOffset.UTC)
                ?.toInstant()?.toEpochMilli(),
            selectableDates = selectableDates,
        )
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val s = state.selectedStartDateMillis
                        val e = state.selectedEndDateMillis
                        show = false
                        if (s != null && e != null) {
                            onChange(
                                Instant.ofEpochMilli(s).atZone(ZoneOffset.UTC).toLocalDate(),
                                Instant.ofEpochMilli(e).atZone(ZoneOffset.UTC).toLocalDate(),
                            )
                        }
                    },
                    enabled = state.selectedEndDateMillis != null,
                ) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { show = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) {
            DateRangePicker(state = state, modifier = Modifier.weight(1f))
        }
    }
}
