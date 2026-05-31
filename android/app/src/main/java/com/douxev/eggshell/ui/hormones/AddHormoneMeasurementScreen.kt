package com.douxev.eggshell.ui.hormones

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.douxev.eggshell.R
import com.douxev.eggshell.data.HormonesRepository
import com.douxev.eggshell.ui.common.clickToDismissKeyboard
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
        java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.hormones_add_title)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .clickToDismissKeyboard()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.hormones_field_hormone), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HormoneCatalog.KINDS.forEach { id ->
                    FilterChip(
                        selected = id == hormone,
                        onClick = { hormone = id },
                        label = { Text(HormoneCatalog.kindLabel(id)) },
                    )
                }
            }

            Text(stringResource(R.string.hormones_field_unit), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HormoneCatalog.UNITS.forEach { u ->
                    FilterChip(
                        selected = u == unit,
                        onClick = { unit = u },
                        label = { Text(u) },
                    )
                }
            }

            OutlinedTextField(
                value = value,
                onValueChange = { value = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                label = { Text(stringResource(R.string.hormones_field_value)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = lab,
                onValueChange = { lab = it },
                label = { Text(stringResource(R.string.hormones_field_lab)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Date of the measurement — defaults to "now" but the user can
            // back-date when entering historical lab results by hand.
            OutlinedButton(
                onClick = { datePickerOpen = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        R.string.hormones_date_pick_fmt,
                        dateFmt.format(java.util.Date(atMs)),
                    ),
                )
            }

            Button(
                onClick = {
                    val v = value.replace(',', '.').toDoubleOrNull() ?: return@Button
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
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.hormones_save)) }
        }
    }

    if (datePickerOpen) {
        val state = rememberDatePickerState(initialSelectedDateMillis = atMs)
        DatePickerDialog(
            onDismissRequest = { datePickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { atMs = it }
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
