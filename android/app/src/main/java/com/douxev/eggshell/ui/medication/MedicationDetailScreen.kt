package com.douxev.eggshell.ui.medication

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.douxev.eggshell.R
import com.douxev.eggshell.data.MedicationRepository
import com.douxev.eggshell.data.ScheduleRepository
import uniffi.transition.DoseEvent
import uniffi.transition.DoseSchedule
import uniffi.transition.Medication

@HiltViewModel
class MedicationDetailViewModel @Inject constructor(
    state: SavedStateHandle,
    private val repo: MedicationRepository,
    private val schedules: ScheduleRepository,
) : ViewModel() {

    private val medicationId: Long = state.get<Long>("id") ?: error("missing medication id")

    private val _medication = MutableStateFlow<Medication?>(null)
    val medication: StateFlow<Medication?> = _medication.asStateFlow()
    private val _doses = MutableStateFlow<List<DoseEvent>>(emptyList())
    val doses: StateFlow<List<DoseEvent>> = _doses.asStateFlow()
    private val _schedules = MutableStateFlow<List<DoseSchedule>>(emptyList())
    val schedulesState: StateFlow<List<DoseSchedule>> = _schedules.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _medication.value = runCatching { repo.get(medicationId) }.getOrNull()
            _doses.value = runCatching { repo.listDoses(medicationId, 0, 50) }.getOrDefault(emptyList())
            _schedules.value = runCatching { schedules.listForMedication(medicationId, includeInactive = true) }
                .getOrDefault(emptyList())
        }
    }

    fun toggleSchedule(id: Long, active: Boolean) {
        viewModelScope.launch {
            runCatching { schedules.setActive(id, active) }
            refresh()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationDetailScreen(
    onLogDose: () -> Unit,
    onAddSchedule: () -> Unit,
    onBack: () -> Unit,
    vm: MedicationDetailViewModel = hiltViewModel(),
) {
    val med by vm.medication.collectAsState()
    val doses by vm.doses.collectAsState()
    val schedules by vm.schedulesState.collectAsState()

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(med?.name ?: stringResource(R.string.med_detail_loading)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onLogDose) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.med_log_dose),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            med?.let { MedHeader(it) }
            HorizontalDivider()

            SchedulesSection(
                schedules = schedules,
                onAdd = onAddSchedule,
                onToggle = vm::toggleSchedule,
            )

            HorizontalDivider()
            Text(stringResource(R.string.med_history), style = MaterialTheme.typography.titleMedium)
            when {
                doses.isEmpty() -> Text(
                    stringResource(R.string.med_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(doses, key = { it.id }) { dose ->
                        DoseRow(dose)
                    }
                }
            }
        }
    }
}

@Composable
private fun SchedulesSection(
    schedules: List<DoseSchedule>,
    onAdd: () -> Unit,
    onToggle: (Long, Boolean) -> Unit,
) {
    val dateFmt = remember(java.util.Locale.getDefault()) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.schedule_list_title), style = MaterialTheme.typography.titleMedium)
        if (schedules.isEmpty()) {
            Text(stringResource(R.string.schedule_none), style = MaterialTheme.typography.bodyMedium)
        } else {
            schedules.forEach { s ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val descriptor = when (s.kind) {
                        "interval" -> stringResource(
                            R.string.schedule_interval_fmt,
                            (s.intervalMinutes?.toInt() ?: 0) / 60,
                        )
                        "daily" -> stringResource(
                            R.string.schedule_daily_fmt,
                            s.dailyHour?.toInt() ?: 0,
                            s.dailyMinute?.toInt() ?: 0,
                        )
                        else -> s.kind
                    }
                    Text(descriptor, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.schedule_next_due_fmt, dateFmt.format(Date(s.nextDueAtMs))),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    androidx.compose.material3.TextButton(
                        onClick = { onToggle(s.id, !s.active) },
                    ) {
                        Text(stringResource(if (s.active) R.string.schedule_pause else R.string.schedule_resume))
                    }
                }
            }
        }
        androidx.compose.material3.TextButton(onClick = onAdd) {
            Text(stringResource(R.string.schedule_add))
        }
    }
}

@Composable
private fun MedHeader(med: Medication) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val kindLabel = stringResource(MedicationCatalog.kindLabelRes(med.kind))
        val routeLabel = stringResource(MedicationCatalog.routeLabelRes(med.route))
        Text("$kindLabel · $routeLabel", style = MaterialTheme.typography.bodyMedium)
        if (med.defaultDose != null) {
            Text(
                stringResource(
                    R.string.med_default_dose_fmt,
                    formatDose(med.defaultDose!!),
                    med.defaultDoseUnit.orEmpty()
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        med.notes?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DoseRow(dose: DoseEvent) {
    val dateFmt = remember(java.util.Locale.getDefault()) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(dateFmt.format(Date(dose.takenAtMs)), style = MaterialTheme.typography.bodyMedium)
        val parts = buildList {
            dose.dose?.let { add("${formatDose(it)} ${dose.doseUnit.orEmpty()}".trim()) }
            dose.route?.let { add(stringResource(MedicationCatalog.routeLabelRes(it))) }
            dose.injectionSite?.let { add(stringResource(MedicationCatalog.injectionSiteLabelRes(it))) }
        }
        if (parts.isNotEmpty()) {
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
        dose.notes?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatDose(v: Double): String {
    val s = v.toString()
    return if (s.endsWith(".0")) s.dropLast(2) else s
}
