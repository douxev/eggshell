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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
    notifContent: com.douxev.eggshell.reminders.NotifContentPrefs,
) : ViewModel() {
    private val medicationId: Long = state.get<Long>("id") ?: error("missing medication id")

    /** Exposed so the label field can warn that GENERIC mode hides its text. */
    val notifMode: StateFlow<com.douxev.eggshell.reminders.NotifContentPrefs.Mode> = notifContent.mode

    /** When > 0, the screen edits this reminder instead of creating one. */
    val editingScheduleId: Long = state.get<Long>("scheduleId") ?: -1L
    val isEditing: Boolean get() = editingScheduleId > 0L

    enum class Status { Idle, Submitting, Done, Error }
    private val _status = MutableStateFlow(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _loaded = MutableStateFlow<uniffi.transition.DoseSchedule?>(null)
    val loaded: StateFlow<uniffi.transition.DoseSchedule?> = _loaded.asStateFlow()

    init {
        if (editingScheduleId > 0L) {
            viewModelScope.launch {
                val schedule = runCatching {
                    repo.listForMedication(medicationId, includeInactive = true)
                        .firstOrNull { it.id == editingScheduleId }
                }.getOrNull()
                _loaded.value = schedule
                // A failed load must not leave a default-valued form with a
                // live Save button — saving it would silently rewrite the
                // real reminder (e.g. a 14-day cycle becoming "every 12 h").
                if (schedule == null) _status.value = Status.Error
            }
        }
    }

    fun submitInterval(hours: Int, label: String?) {
        _status.value = Status.Submitting
        viewModelScope.launch {
            runCatching {
                if (isEditing) {
                    repo.updateSchedule(
                        scheduleId = editingScheduleId,
                        medicationId = medicationId,
                        kind = "interval",
                        intervalMinutes = hours * 60,
                        hour = null,
                        minute = null,
                        intervalDays = null,
                        startDateMs = null,
                        label = label,
                    )
                } else {
                    repo.createInterval(medicationId, hours * 60, label)
                }
            }
                .onSuccess { _status.value = Status.Done }
                .onFailure {
                    _error.value = it.message; _status.value = Status.Error
                }
        }
    }

    fun submitDaily(hour: Int, minute: Int, label: String?) {
        _status.value = Status.Submitting
        viewModelScope.launch {
            runCatching {
                if (isEditing) {
                    repo.updateSchedule(
                        scheduleId = editingScheduleId,
                        medicationId = medicationId,
                        kind = "daily",
                        intervalMinutes = null,
                        hour = hour,
                        minute = minute,
                        intervalDays = null,
                        startDateMs = null,
                        label = label,
                    )
                } else {
                    repo.createDaily(medicationId, hour, minute, label)
                }
            }
                .onSuccess { _status.value = Status.Done }
                .onFailure {
                    _error.value = it.message; _status.value = Status.Error
                }
        }
    }

    fun submitDaysInterval(intervalDays: Int, hour: Int, minute: Int, startDateMs: Long, label: String?) {
        _status.value = Status.Submitting
        viewModelScope.launch {
            runCatching {
                if (isEditing) {
                    repo.updateSchedule(
                        scheduleId = editingScheduleId,
                        medicationId = medicationId,
                        kind = "days_interval",
                        intervalMinutes = null,
                        hour = hour,
                        minute = minute,
                        intervalDays = intervalDays,
                        startDateMs = startDateMs,
                        label = label,
                    )
                } else {
                    repo.createDaysInterval(medicationId, intervalDays, hour, minute, startDateMs, label)
                }
            }
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
    onBack: () -> Unit = {},
    vm: AddScheduleViewModel = hiltViewModel(),
) {
    val status by vm.status.collectAsState()
    val error by vm.error.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val isEditing = vm.isEditing
    val context = LocalContext.current

    if (status == AddScheduleViewModel.Status.Done) {
        onDone()
        return
    }

    var kind by rememberSaveable { mutableStateOf("interval") }
    var hoursStr by rememberSaveable { mutableStateOf("12") }
    var hourStr by rememberSaveable { mutableStateOf("8") }
    var minuteStr by rememberSaveable { mutableStateOf("0") }
    var daysStr by rememberSaveable { mutableStateOf("3") }
    var label by rememberSaveable { mutableStateOf("") }
    var startDateMs by rememberSaveable { mutableStateOf(todayStartMs()) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var seededFromSchedule by rememberSaveable { mutableStateOf(false) }

    // Editing: seed the form from the existing reminder, once.
    LaunchedEffect(loaded) {
        val s = loaded ?: return@LaunchedEffect
        if (seededFromSchedule) return@LaunchedEffect
        kind = s.kind
        s.intervalMinutes?.toInt()?.let { hoursStr = (it / 60).toString() }
        s.dailyHour?.toInt()?.let { hourStr = it.toString() }
        s.dailyMinute?.toInt()?.let { minuteStr = it.toString() }
        s.intervalDays?.toInt()?.let { daysStr = it.toString() }
        label = s.label.orEmpty()
        // Anchor the N-day cycle on the current next-due day, not today — a
        // label-only edit must not shift the phase of a 14-day injection cycle.
        val zone = java.time.ZoneId.systemDefault()
        startDateMs = java.time.Instant.ofEpochMilli(s.nextDueAtMs).atZone(zone)
            .toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        seededFromSchedule = true
    }

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
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isEditing) R.string.schedule_edit_title else R.string.schedule_add_title
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        }
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
                options = listOf("interval", "daily", "days_interval"),
                selected = kind,
                labelOf = {
                    stringResource(
                        when (it) {
                            "interval" -> R.string.schedule_kind_interval
                            "daily" -> R.string.schedule_kind_daily
                            else -> R.string.schedule_kind_days_interval
                        }
                    )
                },
                onSelected = { kind = it },
            )

            when (kind) {
                "interval" -> OutlinedTextField(
                    value = hoursStr,
                    onValueChange = { hoursStr = it.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(R.string.schedule_field_interval_hours)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> {
                    // Shared by "daily" and "days_interval": the time of day.
                    if (kind == "days_interval") {
                        OutlinedTextField(
                            value = daysStr,
                            onValueChange = { daysStr = it.filter(Char::isDigit).take(3) },
                            label = { Text(stringResource(R.string.schedule_field_interval_days)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
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
                    if (kind == "days_interval") {
                        OutlinedButton(
                            onClick = { showDatePicker = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(R.string.schedule_start_date_fmt, formatDate(startDateMs)),
                            )
                        }
                    }
                }
            }

            // Free-text override for what the reminder says ("Aller chercher le
            // traitement"…). Never shown while the content mode is GENERIC —
            // say so instead of letting the user type text that goes nowhere.
            val notifMode by vm.notifMode.collectAsState()
            OutlinedTextField(
                value = label,
                onValueChange = { label = it.take(60) },
                label = { Text(stringResource(R.string.schedule_field_label)) },
                supportingText = {
                    Text(
                        stringResource(
                            if (notifMode == com.douxev.eggshell.reminders.NotifContentPrefs.Mode.GENERIC) {
                                R.string.schedule_field_label_generic_warn
                            } else {
                                R.string.schedule_field_label_hint
                            }
                        )
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    val trimmedLabel = label.trim().ifBlank { null }
                    when (kind) {
                        "interval" -> {
                            val h = hoursStr.toIntOrNull()?.takeIf { it > 0 }
                            if (h != null) vm.submitInterval(h, trimmedLabel)
                        }
                        "days_interval" -> {
                            val d = daysStr.toIntOrNull()?.takeIf { it > 0 }
                            val h = hourStr.toIntOrNull()?.takeIf { it in 0..23 }
                            val m = minuteStr.toIntOrNull()?.takeIf { it in 0..59 }
                            if (d != null && h != null && m != null) {
                                vm.submitDaysInterval(d, h, m, startDateMs, trimmedLabel)
                            }
                        }
                        else -> {
                            val h = hourStr.toIntOrNull()?.takeIf { it in 0..23 }
                            val m = minuteStr.toIntOrNull()?.takeIf { it in 0..59 }
                            if (h != null && m != null) vm.submitDaily(h, m, trimmedLabel)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                // In edit mode, block Save until the reminder actually loaded.
                enabled = status != AddScheduleViewModel.Status.Submitting &&
                    (!isEditing || loaded != null),
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

    if (showDatePicker) {
        StartDatePickerDialog(
            initialMs = startDateMs,
            onDismiss = { showDatePicker = false },
            onPick = { startDateMs = it; showDatePicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartDatePickerDialog(
    initialMs: Long,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = initialMs)
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    val picked = state.selectedDateMillis
                    if (picked == null) {
                        onDismiss()
                    } else {
                        // DatePicker reports UTC midnight; reinterpret those
                        // calendar y/m/d at local midnight so the day can't
                        // shift in negative-offset zones.
                        val date = java.time.Instant.ofEpochMilli(picked)
                            .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        onPick(
                            date.atStartOfDay(java.time.ZoneId.systemDefault())
                                .toInstant().toEpochMilli()
                        )
                    }
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    ) {
        androidx.compose.material3.DatePicker(state = state)
    }
}

/** Local midnight today, in epoch ms — the default start day. */
private fun todayStartMs(): Long =
    java.time.LocalDate.now(java.time.ZoneId.systemDefault())
        .atStartOfDay(java.time.ZoneId.systemDefault())
        .toInstant().toEpochMilli()

private fun formatDate(ms: Long): String =
    java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
        .format(java.util.Date(ms))

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
