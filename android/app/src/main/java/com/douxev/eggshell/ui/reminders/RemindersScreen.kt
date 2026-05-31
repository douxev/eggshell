package com.douxev.eggshell.ui.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.douxev.eggshell.data.ScheduleRepository
import com.douxev.eggshell.reminders.LabReminderManager
import com.douxev.eggshell.reminders.LabReminderPrefs
import com.douxev.eggshell.ui.medication.MedicationCatalog
import uniffi.transition.DoseSchedule
import uniffi.transition.Medication

@HiltViewModel
class RemindersViewModel @Inject constructor(
    private val scheduleRepo: ScheduleRepository,
    private val medsRepo: MedicationRepository,
    private val labs: LabReminderManager,
) : ViewModel() {

    data class MedReminder(
        val schedule: DoseSchedule,
        val medication: Medication,
        val priority: Boolean,
    )

    data class State(
        val medReminders: List<MedReminder> = emptyList(),
        val labReminders: List<LabReminderPrefs.Entry> = emptyList(),
        val labPriorities: Map<Long, Boolean> = emptyMap(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val schedules = runCatching { scheduleRepo.listAllActive() }.getOrDefault(emptyList())
            val meds = runCatching { medsRepo.list(includeArchived = true) }.getOrDefault(emptyList())
            val medById = meds.associateBy { it.id }
            val medItems = schedules.mapNotNull { s ->
                medById[s.medicationId]?.let { m ->
                    MedReminder(
                        schedule = s,
                        medication = m,
                        priority = scheduleRepo.isPriority(s.id),
                    )
                }
            }
            val labList = labs.list()
            val labPriority = labList.associate { it.id to labs.isPriority(it.id) }
            _state.value = State(
                medReminders = medItems,
                labReminders = labList,
                labPriorities = labPriority,
            )
        }
    }

    fun setMedPriority(scheduleId: Long, priority: Boolean) {
        scheduleRepo.setPriority(scheduleId, priority)
        refresh()
    }

    fun setLabPriority(labId: Long, priority: Boolean) {
        labs.setPriority(labId, priority)
        refresh()
    }

    fun addLabInterval(label: String, intervalDays: Int, category: String = LabReminderPrefs.CATEGORY_LAB) {
        runCatching { labs.createInterval(label, intervalDays, category) }
        refresh()
    }

    fun addLabDaily(label: String, hour: Int, minute: Int, category: String = LabReminderPrefs.CATEGORY_LAB) {
        runCatching { labs.createDaily(label, hour, minute, category) }
        refresh()
    }

    fun updateLabInterval(id: Long, label: String, intervalDays: Int) {
        runCatching { labs.updateInterval(id, label, intervalDays) }
        refresh()
    }

    fun updateLabDaily(id: Long, label: String, hour: Int, minute: Int) {
        runCatching { labs.updateDaily(id, label, hour, minute) }
        refresh()
    }

    fun deleteLab(labId: Long) {
        labs.delete(labId)
        refresh()
    }

    fun deleteMedSchedule(scheduleId: Long) {
        viewModelScope.launch {
            runCatching { scheduleRepo.setActive(scheduleId, false) }
            refresh()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RemindersScreen(
    onBack: () -> Unit,
    vm: RemindersViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    // Null = no dialog open. Set to a LabDialogTarget to open the lab-reminder
    // dialog in either "create new" or "edit existing" mode.
    var dialogTarget by remember { mutableStateOf<LabDialogTarget?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reminders_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader(stringResource(R.string.reminders_section_meds))
            Text(
                stringResource(R.string.reminders_section_meds_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.medReminders.isEmpty()) {
                EmptyHint(stringResource(R.string.reminders_no_meds))
            } else {
                state.medReminders.forEach { item ->
                    MedReminderCard(
                        item = item,
                        onTogglePriority = { vm.setMedPriority(item.schedule.id, it) },
                        onDelete = { vm.deleteMedSchedule(item.schedule.id) },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Lab tests
            CategorySection(
                title = stringResource(R.string.reminders_section_labs),
                hint = stringResource(R.string.reminders_section_labs_hint),
                addLabel = stringResource(R.string.reminders_add_lab),
                emptyLabel = stringResource(R.string.reminders_no_labs),
                items = state.labReminders.filter { it.category == LabReminderPrefs.CATEGORY_LAB },
                priorities = state.labPriorities,
                iconForCategory = LabIconFor(LabReminderPrefs.CATEGORY_LAB),
                onAdd = { dialogTarget = LabDialogTarget.New(LabReminderPrefs.CATEGORY_LAB) },
                onEdit = { entry -> dialogTarget = LabDialogTarget.Edit(entry) },
                onTogglePriority = vm::setLabPriority,
                onDelete = vm::deleteLab,
            )

            // Photo timeline
            CategorySection(
                title = stringResource(R.string.reminders_section_photos),
                hint = stringResource(R.string.reminders_section_photos_hint),
                addLabel = stringResource(R.string.reminders_add_photo),
                emptyLabel = stringResource(R.string.reminders_no_photos),
                items = state.labReminders.filter { it.category == LabReminderPrefs.CATEGORY_PHOTO },
                priorities = state.labPriorities,
                iconForCategory = LabIconFor(LabReminderPrefs.CATEGORY_PHOTO),
                onAdd = { dialogTarget = LabDialogTarget.New(LabReminderPrefs.CATEGORY_PHOTO) },
                onEdit = { entry -> dialogTarget = LabDialogTarget.Edit(entry) },
                onTogglePriority = vm::setLabPriority,
                onDelete = vm::deleteLab,
            )

            // Voice tracking
            CategorySection(
                title = stringResource(R.string.reminders_section_voice),
                hint = stringResource(R.string.reminders_section_voice_hint),
                addLabel = stringResource(R.string.reminders_add_voice),
                emptyLabel = stringResource(R.string.reminders_no_voice),
                items = state.labReminders.filter { it.category == LabReminderPrefs.CATEGORY_VOICE },
                priorities = state.labPriorities,
                iconForCategory = LabIconFor(LabReminderPrefs.CATEGORY_VOICE),
                onAdd = { dialogTarget = LabDialogTarget.New(LabReminderPrefs.CATEGORY_VOICE) },
                onEdit = { entry -> dialogTarget = LabDialogTarget.Edit(entry) },
                onTogglePriority = vm::setLabPriority,
                onDelete = vm::deleteLab,
            )

            Box(modifier = Modifier.height(80.dp))
        }
    }

    dialogTarget?.let { target ->
        val category = when (target) {
            is LabDialogTarget.New -> target.category
            is LabDialogTarget.Edit -> target.entry.category
        }
        val titleRes = when (target) {
            is LabDialogTarget.Edit -> when (category) {
                LabReminderPrefs.CATEGORY_PHOTO -> R.string.reminders_photo_edit_dialog_title
                LabReminderPrefs.CATEGORY_VOICE -> R.string.reminders_voice_edit_dialog_title
                else -> R.string.reminders_lab_edit_dialog_title
            }
            is LabDialogTarget.New -> when (category) {
                LabReminderPrefs.CATEGORY_PHOTO -> R.string.reminders_photo_dialog_title
                LabReminderPrefs.CATEGORY_VOICE -> R.string.reminders_voice_dialog_title
                else -> R.string.reminders_lab_dialog_title
            }
        }
        val defaultLabel = when (target) {
            is LabDialogTarget.Edit -> target.entry.label
            is LabDialogTarget.New -> stringResource(
                when (category) {
                    LabReminderPrefs.CATEGORY_PHOTO -> R.string.reminders_photo_default_label
                    LabReminderPrefs.CATEGORY_VOICE -> R.string.reminders_voice_default_label
                    else -> R.string.reminders_lab_default_label
                }
            )
        }
        AddLabReminderDialog(
            titleRes = titleRes,
            defaultLabel = defaultLabel,
            initialEntry = (target as? LabDialogTarget.Edit)?.entry,
            onDismiss = { dialogTarget = null },
            onSaveInterval = { label, days ->
                when (target) {
                    is LabDialogTarget.New -> vm.addLabInterval(label, days, category)
                    is LabDialogTarget.Edit -> vm.updateLabInterval(target.entry.id, label, days)
                }
                dialogTarget = null
            },
            onSaveDaily = { label, h, m ->
                when (target) {
                    is LabDialogTarget.New -> vm.addLabDaily(label, h, m, category)
                    is LabDialogTarget.Edit -> vm.updateLabDaily(target.entry.id, label, h, m)
                }
                dialogTarget = null
            },
        )
    }
}

private sealed interface LabDialogTarget {
    data class New(val category: String) : LabDialogTarget
    data class Edit(val entry: LabReminderPrefs.Entry) : LabDialogTarget
}

@Composable
private fun CategorySection(
    title: String,
    hint: String,
    addLabel: String,
    emptyLabel: String,
    items: List<LabReminderPrefs.Entry>,
    priorities: Map<Long, Boolean>,
    iconForCategory: ImageVector,
    onAdd: () -> Unit,
    onEdit: (LabReminderPrefs.Entry) -> Unit,
    onTogglePriority: (Long, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
) {
    SectionHeader(title)
    Text(
        hint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (items.isEmpty()) {
        EmptyHint(emptyLabel)
    } else {
        items.forEach { entry ->
            LabReminderCard(
                entry = entry,
                icon = iconForCategory,
                priority = priorities[entry.id] == true,
                onClick = { onEdit(entry) },
                onTogglePriority = { onTogglePriority(entry.id, it) },
                onDelete = { onDelete(entry.id) },
            )
        }
    }
    androidx.compose.material3.OutlinedButton(
        onClick = onAdd,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(addLabel)
    }
    Box(modifier = Modifier.height(4.dp))
}

@Composable
private fun LabIconFor(category: String): ImageVector = when (category) {
    LabReminderPrefs.CATEGORY_PHOTO -> Icons.Filled.PhotoCamera
    LabReminderPrefs.CATEGORY_VOICE -> Icons.Filled.GraphicEq
    else -> Icons.Filled.Science
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun MedReminderCard(
    item: RemindersViewModel.MedReminder,
    onTogglePriority: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val routeIcon: ImageVector = when {
        MedicationCatalog.isInjection(item.medication.route) -> Icons.Filled.Vaccines
        item.medication.route == "transdermal" || item.medication.route == "topical" ->
            Icons.Filled.Medication
        else -> Icons.Filled.LocalPharmacy
    }
    val scheduleText = when (item.schedule.kind) {
        "interval" -> {
            val hours = (item.schedule.intervalMinutes?.toInt() ?: 0) / 60
            stringResource(R.string.schedule_interval_fmt, hours)
        }
        "daily" -> stringResource(
            R.string.schedule_daily_fmt,
            item.schedule.dailyHour?.toInt() ?: 0,
            item.schedule.dailyMinute?.toInt() ?: 0,
        )
        else -> ""
    }
    ReminderCard(
        leadingIcon = routeIcon,
        title = item.medication.name,
        subtitle = scheduleText,
        priority = item.priority,
        onTogglePriority = onTogglePriority,
        onDelete = onDelete,
    )
}

@Composable
private fun LabReminderCard(
    entry: LabReminderPrefs.Entry,
    icon: ImageVector,
    priority: Boolean,
    onClick: () -> Unit,
    onTogglePriority: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val scheduleText = when (entry.kind) {
        "interval" -> stringResource(R.string.reminders_lab_interval_fmt, entry.intervalDays ?: 0)
        "daily" -> stringResource(
            R.string.schedule_daily_fmt,
            entry.dailyHour ?: 0,
            entry.dailyMinute ?: 0,
        )
        else -> ""
    }
    ReminderCard(
        leadingIcon = icon,
        title = entry.label,
        subtitle = scheduleText,
        priority = priority,
        onClick = onClick,
        onTogglePriority = onTogglePriority,
        onDelete = onDelete,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderCard(
    leadingIcon: ImageVector,
    title: String,
    subtitle: String,
    priority: Boolean,
    onClick: (() -> Unit)? = null,
    onTogglePriority: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val cardModifier = Modifier.fillMaxWidth()
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val shape = RoundedCornerShape(20.dp)
    val content: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier
                    .padding(start = 12.dp)
                    .weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.reminders_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.reminders_priority_label),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.reminders_priority_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = priority, onCheckedChange = onTogglePriority)
            }
        }
    }
    if (onClick != null) {
        Card(onClick = onClick, modifier = cardModifier, colors = colors, shape = shape) { content() }
    } else {
        Card(modifier = cardModifier, colors = colors, shape = shape) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddLabReminderDialog(
    titleRes: Int,
    defaultLabel: String,
    initialEntry: LabReminderPrefs.Entry? = null,
    onDismiss: () -> Unit,
    onSaveInterval: (label: String, days: Int) -> Unit,
    onSaveDaily: (label: String, hour: Int, minute: Int) -> Unit,
) {
    // initialEntry seeds edit-mode defaults; if absent we keep the create-mode
    // defaults (90-day interval, 9 AM daily).
    val seedKind = initialEntry?.kind ?: "interval"
    val seedDays = initialEntry?.intervalDays?.toString() ?: "90"
    val seedHour = initialEntry?.dailyHour?.toString() ?: "9"
    val seedMinute = initialEntry?.dailyMinute?.toString() ?: "0"
    var label by rememberSaveable(defaultLabel) { mutableStateOf(defaultLabel) }
    var kind by rememberSaveable(seedKind) { mutableStateOf(seedKind) }
    var daysStr by rememberSaveable(seedDays) { mutableStateOf(seedDays) }
    var hourStr by rememberSaveable(seedHour) { mutableStateOf(seedHour) }
    var minuteStr by rememberSaveable(seedMinute) { mutableStateOf(seedMinute) }

    val canSave: Boolean = label.isNotBlank() && when (kind) {
        "interval" -> daysStr.toIntOrNull()?.let { it > 0 } == true
        "daily" -> (hourStr.toIntOrNull()?.let { it in 0..23 } == true) &&
            (minuteStr.toIntOrNull()?.let { it in 0..59 } == true)
        else -> false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(60) },
                    label = { Text(stringResource(R.string.reminders_lab_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == "interval",
                        onClick = { kind = "interval" },
                        label = { Text(stringResource(R.string.reminders_lab_kind_interval)) },
                    )
                    FilterChip(
                        selected = kind == "daily",
                        onClick = { kind = "daily" },
                        label = { Text(stringResource(R.string.reminders_lab_kind_daily)) },
                    )
                }
                if (kind == "interval") {
                    OutlinedTextField(
                        value = daysStr,
                        onValueChange = { daysStr = it.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.reminders_lab_interval_days)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = hourStr,
                            onValueChange = { hourStr = it.filter(Char::isDigit).take(2) },
                            label = { Text(stringResource(R.string.schedule_field_hour)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = minuteStr,
                            onValueChange = { minuteStr = it.filter(Char::isDigit).take(2) },
                            label = { Text(stringResource(R.string.schedule_field_minute)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (kind) {
                        "interval" -> onSaveInterval(label, daysStr.toInt())
                        "daily" -> onSaveDaily(label, hourStr.toInt(), minuteStr.toInt())
                    }
                },
                enabled = canSave,
            ) { Text(stringResource(R.string.reminders_lab_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
