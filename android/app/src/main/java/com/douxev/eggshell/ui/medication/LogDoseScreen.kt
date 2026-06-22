package com.douxev.eggshell.ui.medication

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import com.douxev.eggshell.data.MedicationRepository
import com.douxev.eggshell.ui.common.DateTimePickerField
import com.douxev.eggshell.ui.common.clickToDismissKeyboard
import uniffi.transition.Medication
import uniffi.transition.NewDoseEvent

@HiltViewModel
class LogDoseViewModel @Inject constructor(
    state: SavedStateHandle,
    private val repo: MedicationRepository,
) : ViewModel() {
    private val medicationId: Long = state.get<Long>("id") ?: error("missing medication id")

    enum class Status { Idle, Submitting, Done, Error }

    private val _medication = MutableStateFlow<Medication?>(null)
    val medication: StateFlow<Medication?> = _medication.asStateFlow()
    private val _suggestedSite = MutableStateFlow<String?>(null)
    val suggestedSite: StateFlow<String?> = _suggestedSite.asStateFlow()
    private val _status = MutableStateFlow(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val sites: List<String> = repo.standardInjectionSites

    init {
        viewModelScope.launch {
            _medication.value = runCatching { repo.get(medicationId) }.getOrNull()
            _suggestedSite.value = runCatching {
                repo.suggestNextInjectionSite(medicationId)
            }.getOrNull()
        }
    }

    fun submit(
        takenAtMs: Long,
        dose: Double?,
        doseUnit: String?,
        route: String?,
        site: String?,
        notes: String?,
    ) {
        _status.value = Status.Submitting
        _error.value = null
        viewModelScope.launch {
            runCatching {
                repo.logDose(
                    NewDoseEvent(
                        medicationId = medicationId,
                        takenAtMs = takenAtMs,
                        dose = dose,
                        doseUnit = doseUnit,
                        route = route,
                        injectionSite = site,
                        notes = notes,
                    )
                )
            }
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
fun LogDoseScreen(
    onDone: () -> Unit,
    vm: LogDoseViewModel = hiltViewModel(),
) {
    val med by vm.medication.collectAsState()
    val suggestedSite by vm.suggestedSite.collectAsState()
    val status by vm.status.collectAsState()
    val error by vm.error.collectAsState()

    if (status == LogDoseViewModel.Status.Done) {
        onDone()
        return
    }

    var dose by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("") }
    var site by rememberSaveable { mutableStateOf<String?>(null) }
    var notes by rememberSaveable { mutableStateOf("") }
    // Defaults to now; the user can back-date a dose they forgot to log.
    var takenAtMs by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }

    // Pre-fill defaults from the medication once it loads.
    LaunchedEffect(med) {
        med?.let { m ->
            if (dose.isEmpty()) dose = m.defaultDose?.let { formatDose(it) } ?: ""
            if (unit.isEmpty()) unit = m.defaultDoseUnit.orEmpty()
        }
    }
    // Suggest the next site only the first time we receive a suggestion and
    // the user hasn't already chosen one.
    LaunchedEffect(suggestedSite) {
        if (site == null) site = suggestedSite
    }

    val isInjection = med?.route?.let { MedicationCatalog.isInjection(it) } == true

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.med_log_dose_title)) }) }
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
            med?.let { m ->
                Text(m.name, style = MaterialTheme.typography.titleLarge)
            }

            DateTimePickerField(
                label = stringResource(R.string.med_dose_datetime),
                atMs = takenAtMs,
                onChange = { takenAtMs = it },
                modifier = Modifier.fillMaxWidth(),
                // A dose can only have been taken in the past, never the future.
                allowFuture = false,
            )

            OutlinedTextField(
                value = dose,
                onValueChange = { dose = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                label = { Text(stringResource(R.string.med_field_dose)) },
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

            if (isInjection) {
                Text(stringResource(R.string.med_injection_site), style = MaterialTheme.typography.labelLarge)
                suggestedSite?.let { suggested ->
                    AssistChip(
                        onClick = { site = suggested },
                        label = {
                            Text(
                                stringResource(
                                    R.string.med_injection_suggested,
                                    stringResource(MedicationCatalog.injectionSiteLabelRes(suggested)),
                                )
                            )
                        },
                    )
                }
                ChipGroup(
                    options = vm.sites,
                    selected = site ?: "",
                    labelOf = { stringResource(MedicationCatalog.injectionSiteLabelRes(it)) },
                    onSelected = { site = it },
                )
            }

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(stringResource(R.string.med_field_notes_optional)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                enabled = status != LogDoseViewModel.Status.Submitting,
                onClick = {
                    vm.submit(
                        takenAtMs = takenAtMs,
                        dose = dose.replace(',', '.').toDoubleOrNull(),
                        doseUnit = unit.ifBlank { null },
                        route = med?.route,
                        site = if (isInjection) site else null,
                        notes = notes.ifBlank { null },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.med_save_dose))
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

private fun formatDose(v: Double): String {
    val s = v.toString()
    return if (s.endsWith(".0")) s.dropLast(2) else s
}
