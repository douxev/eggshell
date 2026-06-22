package com.douxev.eggshell.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
 * A reusable date **and** time picker shown as a tappable field. It displays the
 * selected [atMs] in the device locale, opens a Material3 date picker on tap,
 * then a time picker, and reports the combined local-time epoch-ms via
 * [onChange]. This is the app's only date+time picker — the older screens pick
 * date-only — so prefer it whenever a precise instant is needed (logging a
 * back-dated dose, scheduling an appointment).
 *
 * Timezone handling mirrors the rest of the app: Material's date picker reports
 * UTC midnight, so we reinterpret the picked y/m/d at the *local* zone to avoid
 * day-shift in negative-offset zones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimePickerField(
    label: String,
    atMs: Long,
    onChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** When false, the picker refuses future days and clamps the result to now —
     *  used for back-dating a dose, which can only have happened in the past. */
    allowFuture: Boolean = true,
) {
    var showDate by rememberSaveable { mutableStateOf(false) }
    var showTime by rememberSaveable { mutableStateOf(false) }
    val displayFmt = remember {
        DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT, Locale.getDefault())
    }
    val is24h = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val zone = ZoneId.systemDefault()
    fun clamp(ms: Long): Long = if (allowFuture) ms else ms.coerceAtMost(System.currentTimeMillis())
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
        onClick = { showDate = true },
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
    ) {
        Icon(Icons.Filled.Event, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(displayFmt.format(Date(atMs)), style = MaterialTheme.typography.bodyLarge)
        }
    }

    if (showDate) {
        // Seed the picker with the local calendar day of atMs, re-expressed as
        // UTC midnight (what the picker expects).
        val initialUtcMidnight = Instant.ofEpochMilli(atMs).atZone(zone)
            .toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = initialUtcMidnight,
            selectableDates = selectableDates,
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    val picked = dateState.selectedDateMillis
                    showDate = false
                    if (picked != null) {
                        // Keep the current time-of-day on the newly picked day,
                        // then chain into the time picker.
                        val pickedDate = Instant.ofEpochMilli(picked)
                            .atZone(ZoneOffset.UTC).toLocalDate()
                        val prev = Instant.ofEpochMilli(atMs).atZone(zone)
                        onChange(
                            clamp(
                                pickedDate.atTime(prev.hour, prev.minute)
                                    .atZone(zone).toInstant().toEpochMilli()
                            )
                        )
                        showTime = true
                    }
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) { DatePicker(state = dateState) }
    }

    if (showTime) {
        val cur = Instant.ofEpochMilli(atMs).atZone(zone)
        val timeState = rememberTimePickerState(
            initialHour = cur.hour,
            initialMinute = cur.minute,
            is24Hour = is24h,
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = {
                    val day = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
                    onChange(
                        clamp(
                            day.atTime(timeState.hour, timeState.minute)
                                .atZone(zone).toInstant().toEpochMilli()
                        )
                    )
                    showTime = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showTime = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            title = { Text(stringResource(R.string.datetime_pick_time)) },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TimePicker(state = timeState)
                }
            },
        )
    }
}
