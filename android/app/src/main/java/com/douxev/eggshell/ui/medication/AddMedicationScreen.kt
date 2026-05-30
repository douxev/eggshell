package com.douxev.eggshell.ui.medication

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.douxev.eggshell.data.MedicationRepository
import uniffi.transition.NewMedication

@HiltViewModel
class AddMedicationViewModel @Inject constructor(
    private val repo: MedicationRepository,
) : ViewModel() {
    enum class Status { Idle, Submitting, Done, Error }

    private val _status = MutableStateFlow(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun submit(med: NewMedication) {
        _status.value = Status.Submitting
        _error.value = null
        viewModelScope.launch {
            runCatching { repo.add(med) }
                .onSuccess { _status.value = Status.Done }
                .onFailure {
                    _error.value = it.message ?: it::class.simpleName.orEmpty()
                    _status.value = Status.Error
                }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMedicationScreen(
    onDone: () -> Unit,
    vm: AddMedicationViewModel = hiltViewModel(),
) {
    val status by vm.status.collectAsState()
    val error by vm.error.collectAsState()

    if (status == AddMedicationViewModel.Status.Done) {
        onDone()
        return
    }

    var name by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(MedicationCatalog.KINDS.first()) }
    var route by rememberSaveable { mutableStateOf(MedicationCatalog.ROUTES.first()) }
    var dose by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }

    val canSubmit = name.isNotBlank() && status != AddMedicationViewModel.Status.Submitting

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.med_add_title)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.med_field_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(stringResource(R.string.med_field_kind), style = MaterialTheme.typography.labelLarge)
            ChipGroup(
                options = MedicationCatalog.KINDS,
                selected = kind,
                labelOf = { stringResource(MedicationCatalog.kindLabelRes(it)) },
                onSelected = { kind = it },
            )

            Text(stringResource(R.string.med_field_route), style = MaterialTheme.typography.labelLarge)
            ChipGroup(
                options = MedicationCatalog.ROUTES,
                selected = route,
                labelOf = { stringResource(MedicationCatalog.routeLabelRes(it)) },
                onSelected = { route = it },
            )

            OutlinedTextField(
                value = dose,
                onValueChange = { dose = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                label = { Text(stringResource(R.string.med_field_default_dose)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = unit,
                onValueChange = { unit = it },
                label = { Text(stringResource(R.string.med_field_dose_unit)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(stringResource(R.string.med_field_notes)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                enabled = canSubmit,
                onClick = {
                    vm.submit(
                        NewMedication(
                            name = name.trim(),
                            kind = kind,
                            route = route,
                            defaultDose = dose.replace(',', '.').toDoubleOrNull(),
                            defaultDoseUnit = unit.ifBlank { null },
                            color = null,
                            notes = notes.ifBlank { null },
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.med_create))
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ChipGroup(
    options: List<String>,
    selected: String,
    labelOf: @Composable (String) -> String,
    onSelected: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEach { opt ->
            FilterChip(
                selected = opt == selected,
                onClick = { onSelected(opt) },
                label = { Text(labelOf(opt)) },
            )
        }
    }
}
