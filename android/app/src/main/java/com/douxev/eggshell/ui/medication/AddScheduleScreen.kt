package com.douxev.eggshell.ui.medication

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.douxev.eggshell.R
import com.douxev.eggshell.data.ScheduleRepository
import com.douxev.eggshell.ui.common.clickToDismissKeyboard

@HiltViewModel
class AddScheduleViewModel @Inject constructor(
    state: SavedStateHandle,
    private val repo: ScheduleRepository,
) : ViewModel() {
    private val medicationId: Long = state.get<Long>("id") ?: error("missing medication id")

    enum class Status { Idle, Submitting, Done, Error }
    private val _status = MutableStateFlow(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun submitInterval(hours: Int) {
        _status.value = Status.Submitting
        viewModelScope.launch {
            runCatching { repo.createInterval(medicationId, hours * 60) }
                .onSuccess { _status.value = Status.Done }
                .onFailure {
                    _error.value = it.message; _status.value = Status.Error
                }
        }
    }

    fun submitDaily(hour: Int, minute: Int) {
        _status.value = Status.Submitting
        viewModelScope.launch {
            runCatching { repo.createDaily(medicationId, hour, minute) }
                .onSuccess { _status.value = Status.Done }
                .onFailure {
                    _error.value = it.message; _status.value = Status.Error
                }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScheduleScreen(
    onDone: () -> Unit,
    vm: AddScheduleViewModel = hiltViewModel(),
) {
    val status by vm.status.collectAsState()
    val error by vm.error.collectAsState()
    val context = LocalContext.current

    if (status == AddScheduleViewModel.Status.Done) {
        onDone()
        return
    }

    var kind by rememberSaveable { mutableStateOf("interval") }
    var hoursStr by rememberSaveable { mutableStateOf("12") }
    var hourStr by rememberSaveable { mutableStateOf("8") }
    var minuteStr by rememberSaveable { mutableStateOf("0") }

    // Ask the user for POST_NOTIFICATIONS if needed.
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result unused: schedule still works even if denied, just no notif */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val needsExactAlarmPermission = needsExactAlarmPermission(context)

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.schedule_add_title)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .clickToDismissKeyboard()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ChipGroup(
                options = listOf("interval", "daily"),
                selected = kind,
                labelOf = {
                    stringResource(
                        if (it == "interval") R.string.schedule_kind_interval
                        else R.string.schedule_kind_daily
                    )
                },
                onSelected = { kind = it },
            )

            if (kind == "interval") {
                OutlinedTextField(
                    value = hoursStr,
                    onValueChange = { hoursStr = it.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(R.string.schedule_field_interval_hours)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = hourStr,
                    onValueChange = { hourStr = it.filter(Char::isDigit).take(2) },
                    label = { Text(stringResource(R.string.schedule_field_hour)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = minuteStr,
                    onValueChange = { minuteStr = it.filter(Char::isDigit).take(2) },
                    label = { Text(stringResource(R.string.schedule_field_minute)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = {
                    if (kind == "interval") {
                        val h = hoursStr.toIntOrNull()?.takeIf { it > 0 }
                        if (h != null) vm.submitInterval(h)
                    } else {
                        val h = hourStr.toIntOrNull()?.takeIf { it in 0..23 }
                        val m = minuteStr.toIntOrNull()?.takeIf { it in 0..59 }
                        if (h != null && m != null) vm.submitDaily(h, m)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = status != AddScheduleViewModel.Status.Submitting,
            ) { Text(stringResource(R.string.schedule_save)) }

            if (needsExactAlarmPermission) {
                Text(
                    stringResource(R.string.permission_exact_alarm_rationale),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = { openExactAlarmSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_open_settings)) }
            }

            error?.let {
                Text(
                    stringResource(R.string.med_error_prefix, it),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun needsExactAlarmPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val mgr = context.getSystemService(AlarmManager::class.java)
    return !mgr.canScheduleExactAlarms()
}

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
