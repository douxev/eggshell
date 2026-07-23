package com.douxev.eggshell.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.douxev.eggshell.R
import java.util.Locale

/**
 * Time-only sibling of [DateTimePickerField]: a tappable field showing HH:MM
 * that opens the Material3 time picker. Used where the day(s) are picked
 * separately — e.g. the shared time-of-day of a logged date range.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerField(
    label: String,
    hour: Int,
    minute: Int,
    onChange: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var show by rememberSaveable { mutableStateOf(false) }
    val is24h = android.text.format.DateFormat.is24HourFormat(LocalContext.current)

    OutlinedButton(
        onClick = { show = true },
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
    ) {
        Icon(Icons.Filled.Schedule, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(
                String.format(Locale.getDefault(), "%02d:%02d", hour, minute),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }

    if (show) {
        val timeState = rememberTimePickerState(
            initialHour = hour,
            initialMinute = minute,
            is24Hour = is24h,
        )
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange(timeState.hour, timeState.minute)
                    show = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { show = false }) {
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
