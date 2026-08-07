package com.douxev.eggshell.ui.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.douxev.eggshell.R
import com.douxev.eggshell.data.AppointmentRepository
import com.douxev.eggshell.data.MedicationRepository
import com.douxev.eggshell.data.ScheduleRepository
import com.douxev.eggshell.reminders.LabReminderManager
import com.douxev.eggshell.reminders.LabReminderPrefs
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.EmptyState
import com.douxev.eggshell.ui.components.IconTile
import com.douxev.eggshell.ui.components.StatusPill
import com.douxev.eggshell.ui.components.SectionTitle
import com.douxev.eggshell.ui.medication.MedicationCatalog
import com.douxev.eggshell.ui.theme.EggDim
import com.douxev.eggshell.ui.theme.EggShapes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.transition.Appointment
import uniffi.transition.DoseSchedule
// Aliased: the vault record and the Material glyph share the simple name.
import uniffi.transition.Medication as MedicationRecord

@HiltViewModel
class RemindersViewModel @Inject constructor(
    private val scheduleRepo: ScheduleRepository,
    private val medsRepo: MedicationRepository,
    private val labs: LabReminderManager,
    private val appointments: AppointmentRepository,
) : ViewModel() {

    data class MedReminder(
        val schedule: DoseSchedule,
        val medication: MedicationRecord,
        val priority: Boolean,
    )

    data class State(
        val medReminders: List<MedReminder> = emptyList(),
        val labReminders: List<LabReminderPrefs.Entry> = emptyList(),
        val labPriorities: Map<Long, Boolean> = emptyMap(),
        /** Upcoming one-shot appointment reminders — read-only here, managed
         *  from Rendez-vous. Listed so this screen shows every notification
         *  the app may fire. */
        val appointmentReminders: List<Appointment> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            // Paused reminders included, deliberately. Listing only the active
            // ones is what made a disabled reminder undeletable: pausing it
            // removed it from the only screen that offered a delete, and
            // nothing anywhere would list it again.
            val schedules = runCatching { scheduleRepo.listAll() }.getOrDefault(emptyList())
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
            }.sortedWith(
                compareByDescending<MedReminder> { it.schedule.active }
                    .thenBy { it.medication.name },
            )
            val labList = labs.list()
            val labPriority = labList.associate { it.id to labs.isPriority(it.id) }
            val now = System.currentTimeMillis()
            val upcomingAppointments = runCatching { appointments.list() }
                .getOrDefault(emptyList())
                .filter { (it.reminderAtMs ?: 0L) > now }
                .sortedBy { it.reminderAtMs }
            _state.value = State(
                medReminders = medItems,
                labReminders = labList,
                labPriorities = labPriority,
                appointmentReminders = upcomingAppointments,
            )
        }
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

}

/**
 * The full reminder hub, reached from the « Rappels » section of Réglages
 * (D5): treatments, labs, photo, voice, journal, plus a read-only recap of
 * the appointment alarms.
 *
 * What a reminder *reveals* is set one level up, on the settings hub — this
 * screen is about which reminders exist and when they fire.
 */
@Composable
fun RemindersScreen(
    onBack: () -> Unit,
    onManageMedReminders: (medicationId: Long) -> Unit = {},
    vm: RemindersViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    // Re-read on every return to this screen — a med reminder edited via the
    // row tap lands back here and must show its new cadence/label.
    LaunchedEffect(Unit) { vm.refresh() }
    // Null = no dialog open. Set to a LabDialogTarget to open the lab-reminder
    // dialog in either "create new" or "edit existing" mode.
    var dialogTarget by remember { mutableStateOf<LabDialogTarget?>(null) }

    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = EggDim.ScreenMargin,
                end = EggDim.ScreenMargin,
                bottom = 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(EggDim.RowGap),
        ) {
            item {
                ScreenHeader(title = stringResource(R.string.reminders_title), onBack = onBack)
            }
            item {
                EggCard(variant = CardVariant.Low) {
                    Text(
                        stringResource(R.string.set_reminders_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // -- Médics : every treatment reminder, managed in one place -----
            //
            // These rows are a directory, not a second set of controls. Pause,
            // edit and delete all live on the treatment's own reminder screen,
            // because two partial control surfaces over the same reminders is
            // exactly the arrangement that let a paused one become undeletable.
            item { SectionHeader(stringResource(R.string.reminders_section_meds)) }
            item { Hint(stringResource(R.string.reminders_section_meds_hint)) }
            if (state.medReminders.isEmpty()) {
                item { EmptyState(message = stringResource(R.string.reminders_no_meds)) }
            } else {
                items(state.medReminders.size) { index ->
                    val item = state.medReminders[index]
                    MedReminderRow(
                        item = item,
                        onClick = { onManageMedReminders(item.medication.id) },
                    )
                }
            }

            categorySection(
                titleRes = R.string.reminders_section_labs,
                hintRes = R.string.reminders_section_labs_hint,
                emptyRes = R.string.reminders_no_labs,
                addDescriptionRes = R.string.reminders_add_lab,
                category = LabReminderPrefs.CATEGORY_LAB,
                state = state,
                onAdd = { dialogTarget = LabDialogTarget.New(LabReminderPrefs.CATEGORY_LAB) },
                onEdit = { entry -> dialogTarget = LabDialogTarget.Edit(entry) },
                onTogglePriority = vm::setLabPriority,
                onDelete = vm::deleteLab,
            )
            categorySection(
                titleRes = R.string.reminders_section_photos,
                hintRes = R.string.reminders_section_photos_hint,
                emptyRes = R.string.reminders_no_photos,
                addDescriptionRes = R.string.reminders_add_photo,
                category = LabReminderPrefs.CATEGORY_PHOTO,
                state = state,
                onAdd = { dialogTarget = LabDialogTarget.New(LabReminderPrefs.CATEGORY_PHOTO) },
                onEdit = { entry -> dialogTarget = LabDialogTarget.Edit(entry) },
                onTogglePriority = vm::setLabPriority,
                onDelete = vm::deleteLab,
            )
            categorySection(
                titleRes = R.string.reminders_section_voice,
                hintRes = R.string.reminders_section_voice_hint,
                emptyRes = R.string.reminders_no_voice,
                addDescriptionRes = R.string.reminders_add_voice,
                category = LabReminderPrefs.CATEGORY_VOICE,
                state = state,
                onAdd = { dialogTarget = LabDialogTarget.New(LabReminderPrefs.CATEGORY_VOICE) },
                onEdit = { entry -> dialogTarget = LabDialogTarget.Edit(entry) },
                onTogglePriority = vm::setLabPriority,
                onDelete = vm::deleteLab,
            )
            categorySection(
                titleRes = R.string.reminders_section_journal,
                hintRes = R.string.reminders_section_journal_hint,
                emptyRes = R.string.reminders_no_journal,
                addDescriptionRes = R.string.reminders_add_journal,
                category = LabReminderPrefs.CATEGORY_JOURNAL,
                state = state,
                onAdd = { dialogTarget = LabDialogTarget.New(LabReminderPrefs.CATEGORY_JOURNAL) },
                onEdit = { entry -> dialogTarget = LabDialogTarget.Edit(entry) },
                onTogglePriority = vm::setLabPriority,
                onDelete = vm::deleteLab,
            )

            // -- Rendez-vous : read-only recap --------------------------------
            item { SectionHeader(stringResource(R.string.reminders_section_appointments)) }
            item { Hint(stringResource(R.string.reminders_section_appointments_hint)) }
            if (state.appointmentReminders.isEmpty()) {
                item { EmptyState(message = stringResource(R.string.reminders_no_appointments)) }
            } else {
                items(state.appointmentReminders.size) { index ->
                    AppointmentReminderRow(state.appointmentReminders[index])
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
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
                LabReminderPrefs.CATEGORY_JOURNAL -> R.string.reminders_journal_edit_dialog_title
                else -> R.string.reminders_lab_edit_dialog_title
            }
            is LabDialogTarget.New -> when (category) {
                LabReminderPrefs.CATEGORY_PHOTO -> R.string.reminders_photo_dialog_title
                LabReminderPrefs.CATEGORY_VOICE -> R.string.reminders_voice_dialog_title
                LabReminderPrefs.CATEGORY_JOURNAL -> R.string.reminders_journal_dialog_title
                else -> R.string.reminders_lab_dialog_title
            }
        }
        val defaultLabel = when (target) {
            is LabDialogTarget.Edit -> target.entry.label
            is LabDialogTarget.New -> stringResource(
                when (category) {
                    LabReminderPrefs.CATEGORY_PHOTO -> R.string.reminders_photo_default_label
                    LabReminderPrefs.CATEGORY_VOICE -> R.string.reminders_voice_default_label
                    LabReminderPrefs.CATEGORY_JOURNAL -> R.string.reminders_journal_default_label
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

/**
 * One reminder family: its header carries the « Ajouter » action, so the add
 * affordance sits with the thing it adds to instead of floating at the bottom
 * of a long page.
 */
private fun LazyListScope.categorySection(
    titleRes: Int,
    hintRes: Int,
    emptyRes: Int,
    addDescriptionRes: Int,
    category: String,
    state: RemindersViewModel.State,
    onAdd: () -> Unit,
    onEdit: (LabReminderPrefs.Entry) -> Unit,
    onTogglePriority: (Long, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val entries = state.labReminders.filter { it.category == category }
    item {
        SectionHeader(
            text = stringResource(titleRes),
            action = stringResource(R.string.set_action_add),
            onAction = onAdd,
        )
    }
    item { Hint(stringResource(hintRes)) }
    if (entries.isEmpty()) {
        item {
            EmptyState(
                message = stringResource(emptyRes),
                actionLabel = stringResource(addDescriptionRes),
                onAction = onAdd,
            )
        }
    } else {
        items(entries.size) { index ->
            val entry = entries[index]
            LabReminderCard(
                entry = entry,
                icon = labIconFor(entry.category),
                priority = state.labPriorities[entry.id] == true,
                onClick = { onEdit(entry) },
                onTogglePriority = { onTogglePriority(entry.id, it) },
                onDelete = { onDelete(entry.id) },
            )
        }
    }
}

private fun labIconFor(category: String): ImageVector = when (category) {
    LabReminderPrefs.CATEGORY_PHOTO -> Icons.Filled.PhotoCamera
    LabReminderPrefs.CATEGORY_VOICE -> Icons.Filled.GraphicEq
    LabReminderPrefs.CATEGORY_JOURNAL -> Icons.Filled.EditNote
    else -> Icons.Filled.Science
}

@Composable
private fun AppointmentReminderRow(appt: Appointment) {
    val dateFmt = remember(java.util.Locale.getDefault()) {
        java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.MEDIUM,
            java.text.DateFormat.SHORT,
        )
    }
    EggCard(
        variant = CardVariant.Low,
        padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(container = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    Icons.Filled.CalendarMonth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .weight(1f),
            ) {
                Text(
                    appt.place?.takeIf { it.isNotBlank() }
                        ?: appt.professionalName?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.reminders_appointment_generic),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                appt.reminderAtMs?.let {
                    Text(
                        dateFmt.format(java.util.Date(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    text: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column {
        Spacer(Modifier.height(8.dp))
        SectionTitle(
            text = text,
            modifier = Modifier.padding(horizontal = 4.dp),
            action = action,
            onAction = onAction,
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/**
 * One treatment reminder as a directory entry: what it is, whether it is
 * running, and a tap through to the screen that manages it.
 *
 * A paused reminder is listed with the rest and says so. That is the whole
 * point of the section — the reminders that need attention are the ones that
 * stopped firing, and the previous listing filtered exactly those out.
 */
@Composable
private fun MedReminderRow(
    item: RemindersViewModel.MedReminder,
    onClick: () -> Unit,
) {
    val routeIcon: ImageVector = when {
        MedicationCatalog.isInjection(item.medication.route) -> Icons.Filled.Vaccines
        else -> Icons.Filled.Medication
    }
    val scheduleText = cadenceTextFor(item.schedule)
    val label = item.schedule.label?.takeIf { it.isNotBlank() }
    val subtitle = if (label != null) "$scheduleText · « $label »" else scheduleText

    EggCard(
        variant = CardVariant.Low,
        padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(container = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    routeIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .weight(1f),
            ) {
                Text(
                    item.medication.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!item.schedule.active) {
                StatusPill(
                    label = stringResource(R.string.reminders_paused_pill),
                    container = MaterialTheme.colorScheme.surfaceContainerHighest,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (item.priority) {
                StatusPill(
                    label = stringResource(R.string.reminders_priority_pill),
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
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
    val priorityLabel = stringResource(R.string.reminders_priority_label)
    EggCard(
        variant = CardVariant.Low,
        padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(container = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .weight(1f),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(EggDim.TouchTarget),
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.reminders_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(priorityLabel, style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.reminders_priority_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = priority,
                onCheckedChange = onTogglePriority,
                modifier = Modifier.semantics { contentDescription = "$priorityLabel · $title" },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
                    shape = EggShapes.Field,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == "interval",
                        onClick = { kind = "interval" },
                        label = { Text(stringResource(R.string.reminders_lab_kind_interval)) },
                        shape = RoundedCornerShape(10.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    )
                    FilterChip(
                        selected = kind == "daily",
                        onClick = { kind = "daily" },
                        label = { Text(stringResource(R.string.reminders_lab_kind_daily)) },
                        shape = RoundedCornerShape(10.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    )
                }
                if (kind == "interval") {
                    OutlinedTextField(
                        value = daysStr,
                        onValueChange = { daysStr = it.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.reminders_lab_interval_days)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = EggShapes.Field,
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
                            shape = EggShapes.Field,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = minuteStr,
                            onValueChange = { minuteStr = it.filter(Char::isDigit).take(2) },
                            label = { Text(stringResource(R.string.schedule_field_minute)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = EggShapes.Field,
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
