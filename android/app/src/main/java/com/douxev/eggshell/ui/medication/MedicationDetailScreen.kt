package com.douxev.eggshell.ui.medication

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.douxev.eggshell.reminders.MedAliasPrefs
import uniffi.transition.DoseEvent
import uniffi.transition.DoseSchedule
import uniffi.transition.Medication

@HiltViewModel
class MedicationDetailViewModel @Inject constructor(
    state: SavedStateHandle,
    private val repo: MedicationRepository,
    private val schedules: ScheduleRepository,
    private val medAlias: MedAliasPrefs,
) : ViewModel() {

    private val medicationId: Long = state.get<Long>("id") ?: error("missing medication id")

    private val _medication = MutableStateFlow<Medication?>(null)
    val medication: StateFlow<Medication?> = _medication.asStateFlow()
    private val _doses = MutableStateFlow<List<DoseEvent>>(emptyList())
    val doses: StateFlow<List<DoseEvent>> = _doses.asStateFlow()
    private val _schedules = MutableStateFlow<List<DoseSchedule>>(emptyList())
    val schedulesState: StateFlow<List<DoseSchedule>> = _schedules.asStateFlow()
    private val _alias = MutableStateFlow<String?>(null)
    val alias: StateFlow<String?> = _alias.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _medication.value = runCatching { repo.get(medicationId) }.getOrNull()
            _doses.value = runCatching { repo.listDoses(medicationId, 0, 50) }.getOrDefault(emptyList())
            _schedules.value = runCatching { schedules.listForMedication(medicationId, includeInactive = true) }
                .getOrDefault(emptyList())
            _alias.value = medAlias.get(medicationId)
        }
    }

    fun setAlias(alias: String?) {
        medAlias.set(medicationId, alias)
        _alias.value = alias?.takeIf { it.isNotBlank() }
        // Re-resolve the plain-text mirror so the new alias takes effect on
        // already-scheduled reminders (only visible in ALIAS mode).
        viewModelScope.launch { runCatching { schedules.syncFromDb() } }
    }

    fun toggleSchedule(id: Long, active: Boolean) {
        viewModelScope.launch {
            runCatching { schedules.setActive(id, active) }
            refresh()
        }
    }

    /** Remove a single logged dose from the history. */
    fun deleteDose(id: Long) {
        viewModelScope.launch {
            runCatching { repo.deleteDose(id) }
            refresh()
        }
    }

    /** Archive (hide, reversible) the medication, then leave the screen — only
     *  on success, so a failed write doesn't navigate away as if it worked. */
    fun archive(onArchived: () -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { repo.setArchived(medicationId, true) }.isSuccess
            if (ok) onArchived() else refresh()
        }
    }

    /** Permanently delete the medication: tear down off-vault reminder state
     *  first (it reads the schedule ids), then delete the row + cascade. Only
     *  navigates away when the delete actually succeeds — otherwise a half-done
     *  delete would silently look successful. */
    fun deleteMedication(onDeleted: () -> Unit) {
        viewModelScope.launch {
            val ok = runCatching {
                schedules.deleteMedicationCleanup(medicationId)
                repo.delete(medicationId)
            }.isSuccess
            if (ok) onDeleted() else refresh()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationDetailScreen(
    onLogDose: () -> Unit,
    onEditDose: (Long) -> Unit,
    onAddSchedule: () -> Unit,
    onEditSchedule: (Long) -> Unit,
    onEditMedication: () -> Unit,
    onBack: () -> Unit,
    vm: MedicationDetailViewModel = hiltViewModel(),
) {
    val med by vm.medication.collectAsState()
    val doses by vm.doses.collectAsState()
    val schedules by vm.schedulesState.collectAsState()
    val alias by vm.alias.collectAsState()
    var editingAlias by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var doseToDelete by remember { mutableStateOf<DoseEvent?>(null) }

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
                actions = {
                    IconButton(onClick = onEditMedication) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.action_edit),
                        )
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.action_more),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.med_archive)) },
                            leadingIcon = { Icon(Icons.Filled.Archive, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                vm.archive(onArchived = onBack)
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.med_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.DeleteOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                confirmDelete = true
                            },
                        )
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

            AliasRow(alias = alias, onEdit = { editingAlias = true })
            HorizontalDivider()

            SchedulesSection(
                schedules = schedules,
                onAdd = onAddSchedule,
                onEdit = onEditSchedule,
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
                        DoseRow(
                            dose,
                            onEdit = { onEditDose(dose.id) },
                            onDelete = { doseToDelete = dose },
                        )
                    }
                }
            }
        }
    }

    if (editingAlias) {
        AliasDialog(
            initial = alias.orEmpty(),
            onDismiss = { editingAlias = false },
            onSave = {
                vm.setAlias(it.ifBlank { null })
                editingAlias = false
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.med_delete_title)) },
            text = { Text(stringResource(R.string.med_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteMedication(onDeleted = onBack)
                }) {
                    Text(
                        stringResource(R.string.med_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    doseToDelete?.let { dose ->
        AlertDialog(
            onDismissRequest = { doseToDelete = null },
            title = { Text(stringResource(R.string.med_dose_delete_title)) },
            text = { Text(stringResource(R.string.med_dose_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteDose(dose.id)
                    doseToDelete = null
                }) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { doseToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun AliasRow(alias: String?, onEdit: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.med_alias_section), style = MaterialTheme.typography.titleMedium)
            Text(
                alias?.takeIf { it.isNotBlank() } ?: stringResource(R.string.med_alias_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.material3.TextButton(onClick = onEdit) {
            Text(stringResource(R.string.med_alias_edit))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AliasDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.med_alias_dialog_title)) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(40) },
                label = { Text(stringResource(R.string.med_field_notif_alias)) },
                supportingText = { Text(stringResource(R.string.med_field_notif_alias_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onSave(text.trim()) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun SchedulesSection(
    schedules: List<DoseSchedule>,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
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
                        "days_interval" -> stringResource(
                            R.string.schedule_days_interval_fmt,
                            s.intervalDays?.toInt() ?: 0,
                            s.dailyHour?.toInt() ?: 0,
                            s.dailyMinute?.toInt() ?: 0,
                        )
                        else -> s.kind
                    }
                    Text(descriptor, style = MaterialTheme.typography.bodyMedium)
                    s.label?.takeIf { it.isNotBlank() }?.let {
                        Text("« $it »", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        stringResource(R.string.schedule_next_due_fmt, dateFmt.format(Date(s.nextDueAtMs))),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row {
                        androidx.compose.material3.TextButton(
                            onClick = { onToggle(s.id, !s.active) },
                        ) {
                            Text(stringResource(if (s.active) R.string.schedule_pause else R.string.schedule_resume))
                        }
                        androidx.compose.material3.TextButton(onClick = { onEdit(s.id) }) {
                            Text(stringResource(R.string.action_edit))
                        }
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
private fun DoseRow(dose: DoseEvent, onEdit: () -> Unit, onDelete: () -> Unit) {
    val dateFmt = remember(java.util.Locale.getDefault()) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
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
        IconButton(onClick = onEdit) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = stringResource(R.string.med_dose_edit_title),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.DeleteOutline,
                contentDescription = stringResource(R.string.med_dose_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun formatDose(v: Double): String {
    val s = v.toString()
    return if (s.endsWith(".0")) s.dropLast(2) else s
}
