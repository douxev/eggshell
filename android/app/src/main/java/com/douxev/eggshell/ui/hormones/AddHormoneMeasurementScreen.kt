package com.douxev.eggshell.ui.hormones

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.douxev.eggshell.R
import com.douxev.eggshell.data.HormonesRepository
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.common.clickToDismissKeyboard
import com.douxev.eggshell.ui.components.ActionBand
import com.douxev.eggshell.ui.components.MicroLabel
import com.douxev.eggshell.ui.theme.EggDim
import com.douxev.eggshell.ui.theme.EggShapes
import uniffi.transition.NewHormoneMeasurement

@HiltViewModel
class AddHormoneViewModel @Inject constructor(
    private val repo: HormonesRepository,
) : ViewModel() {
    enum class Status { Idle, Submitting, Done, Error }
    private val _status = MutableStateFlow(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    fun submit(m: NewHormoneMeasurement) {
        _status.value = Status.Submitting
        viewModelScope.launch {
            runCatching { repo.add(m) }
                .onSuccess { _status.value = Status.Done }
                .onFailure { _status.value = Status.Error }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddHormoneMeasurementScreen(
    onDone: () -> Unit,
    vm: AddHormoneViewModel = hiltViewModel(),
) {
    val status by vm.status.collectAsState()
    if (status == AddHormoneViewModel.Status.Done) {
        onDone()
        return
    }

    var hormone by rememberSaveable { mutableStateOf(HormoneCatalog.KINDS.first()) }
    var unit by rememberSaveable { mutableStateOf(HormoneCatalog.UNITS.first()) }
    var value by rememberSaveable { mutableStateOf("") }
    var lab by rememberSaveable { mutableStateOf("") }
    var atMs by rememberSaveable { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    var datePickerOpen by rememberSaveable { mutableStateOf(false) }
    val dateFmt = androidx.compose.runtime.remember {
        java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.getDefault())
    }
    val parsed = value.replace(',', '.').toDoubleOrNull()
    val canSave = parsed != null && parsed > 0.0

    Scaffold(
        bottomBar = {
            ActionBand(alignment = androidx.compose.ui.Alignment.Center) {
                Button(
                    onClick = {
                        val v = parsed ?: return@Button
                        vm.submit(
                            NewHormoneMeasurement(
                                atMs = atMs,
                                hormone = hormone,
                                value = v,
                                unit = unit,
                                labName = lab.ifBlank { null },
                                notes = null,
                            )
                        )
                    },
                    enabled = canSave && status != AddHormoneViewModel.Status.Submitting,
                    shape = EggShapes.Pill,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(stringResource(R.string.hormones_save))
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .clickToDismissKeyboard()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = EggDim.ScreenMargin),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ScreenHeader(
                title = stringResource(R.string.measures_new_title),
                onBack = onDone,
            )
            Text(
                stringResource(R.string.measures_new_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            MicroLabel(stringResource(R.string.hormones_field_hormone))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                HormoneCatalog.KINDS.forEach { id ->
                    FilterChip(
                        selected = id == hormone,
                        onClick = { hormone = id },
                        label = { Text(HormoneCatalog.kindLabel(id)) },
                        shape = MeasureChipShape,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    )
                }
            }

            MicroLabel(stringResource(R.string.hormones_field_unit))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                HormoneCatalog.UNITS.forEach { u ->
                    FilterChip(
                        selected = u == unit,
                        onClick = { unit = u },
                        label = { Text(u) },
                        shape = MeasureChipShape,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    )
                }
            }

            OutlinedTextField(
                value = value,
                onValueChange = { value = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                label = { Text(stringResource(R.string.hormones_field_value)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = EggShapes.Field,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = lab,
                onValueChange = { lab = it },
                label = { Text(stringResource(R.string.hormones_field_lab)) },
                singleLine = true,
                shape = EggShapes.Field,
                modifier = Modifier.fillMaxWidth(),
            )

            // Date of the measurement — defaults to "now" but the user can
            // back-date when entering historical lab results by hand.
            OutlinedButton(
                onClick = { datePickerOpen = true },
                shape = EggShapes.Pill,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        R.string.hormones_date_pick_fmt,
                        dateFmt.format(java.util.Date(atMs)),
                    ),
                )
            }

            Spacer(Modifier.height(12.dp))
        }
    }

    if (datePickerOpen) {
        // Material speaks UTC midnight on both sides of this dialog, so the day
        // is converted in and out of the local zone. Handing it `atMs` raw
        // would preselect the wrong day east of Greenwich, and storing its
        // answer raw would file the reading on the previous day west of it.
        val zone = androidx.compose.runtime.remember { ZoneId.systemDefault() }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = Instant.ofEpochMilli(atMs).atZone(zone)
                .toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { datePickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { picked ->
                        val day = Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate()
                        // Keep the time of day already held, so re-opening the
                        // picker doesn't quietly move the reading to midnight.
                        val previous = Instant.ofEpochMilli(atMs).atZone(zone)
                        atMs = day.atTime(previous.hour, previous.minute)
                            .atZone(zone).toInstant().toEpochMilli()
                    }
                    datePickerOpen = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { datePickerOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) {
            DatePicker(state = state)
        }
    }
}
