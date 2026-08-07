package com.douxev.eggshell.ui.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.douxev.eggshell.R
import com.douxev.eggshell.data.MedicationRepository
import com.douxev.eggshell.data.ScheduleRepository
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.components.ActionBand
import com.douxev.eggshell.ui.components.CardRule
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.EggFab
import com.douxev.eggshell.ui.components.EmptyState
import com.douxev.eggshell.ui.components.IconTile
import com.douxev.eggshell.ui.components.StatusPill
import com.douxev.eggshell.ui.medication.MedicationCatalog
import com.douxev.eggshell.ui.theme.EggDim
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.transition.DoseSchedule
import uniffi.transition.Medication

/**
 * Every reminder of one treatment, and everything that can be done to one.
 *
 * Reminder management used to be split in two, and neither half was whole:
 *
 *   - the treatment screen could create, pause and edit a reminder, but not
 *     delete one;
 *   - the settings hub could delete one, but listed only *active* reminders —
 *     it read `listAllActive()`. So pausing a reminder removed it from the only
 *     screen that could delete it, and there was no way back. A user who paused
 *     a treatment they had stopped was left with a row they could neither use
 *     nor get rid of.
 *
 * Splitting a small set of operations across two screens is what made that gap
 * possible and invisible. They live together here, over one list that includes
 * paused reminders precisely because those are the ones that need cleaning up.
 */
@HiltViewModel
class MedicationRemindersViewModel @Inject constructor(
    state: SavedStateHandle,
    private val schedules: ScheduleRepository,
    private val meds: MedicationRepository,
) : ViewModel() {

    private val medicationId: Long = state.get<Long>("id") ?: error("missing medication id")

    data class Item(
        val schedule: DoseSchedule,
        val priority: Boolean,
    )

    private val _medication = MutableStateFlow<Medication?>(null)
    val medication: StateFlow<Medication?> = _medication.asStateFlow()
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _medication.value = runCatching { meds.get(medicationId) }.getOrNull()
            _items.value = runCatching {
                schedules.listForMedication(medicationId, includeInactive = true)
            }.getOrDefault(emptyList())
                // Live reminders first, paused ones after: the paused block is
                // where cleanup happens and it should not be interleaved with
                // the reminders that are actually firing.
                .sortedWith(compareByDescending<DoseSchedule> { it.active }.thenBy { it.nextDueAtMs })
                .map { Item(schedule = it, priority = schedules.isPriority(it.id)) }
            _loading.value = false
        }
    }

    fun setActive(scheduleId: Long, active: Boolean) {
        viewModelScope.launch {
            runCatching { schedules.setActive(scheduleId, active) }
            refresh()
        }
    }

    fun setPriority(scheduleId: Long, priority: Boolean) {
        schedules.setPriority(scheduleId, priority)
        refresh()
    }

    fun delete(scheduleId: Long) {
        viewModelScope.launch {
            runCatching { schedules.deleteSchedule(scheduleId) }
            refresh()
        }
    }
}

@Composable
fun MedicationRemindersScreen(
    onBack: () -> Unit,
    onAddReminder: () -> Unit,
    onEditReminder: (scheduleId: Long) -> Unit,
    vm: MedicationRemindersViewModel = hiltViewModel(),
) {
    val med by vm.medication.collectAsState()
    val items by vm.items.collectAsState()
    val loading by vm.loading.collectAsState()
    // Returning from the add/edit screen must re-read: the reminder that was
    // just created or retimed is the whole reason the user came back.
    LaunchedEffect(Unit) { vm.refresh() }

    var confirmDelete by remember { mutableStateOf<DoseSchedule?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            ActionBand {
                EggFab(
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.reminders_med_add),
                    label = stringResource(R.string.reminders_med_add_label),
                    onClick = onAddReminder,
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = EggDim.ScreenMargin,
                end = EggDim.ScreenMargin,
                bottom = EggDim.BlockGap,
            ),
            verticalArrangement = Arrangement.spacedBy(EggDim.RowGap),
        ) {
            item {
                ScreenHeader(
                    title = med?.name
                        ?.let { stringResource(R.string.reminders_med_title_fmt, it) }
                        ?: stringResource(R.string.reminders_med_title),
                    onBack = onBack,
                )
            }
            item {
                EggCard(variant = CardVariant.Low) {
                    Text(
                        stringResource(R.string.reminders_med_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (items.isEmpty()) {
                if (!loading) {
                    item {
                        EmptyState(
                            message = stringResource(R.string.schedule_none),
                            actionLabel = stringResource(R.string.schedule_add),
                            onAction = onAddReminder,
                        )
                    }
                }
            } else {
                items(items, key = { it.schedule.id }) { item ->
                    ReminderManagerCard(
                        item = item,
                        routeIcon = routeIconFor(med),
                        onToggleActive = { vm.setActive(item.schedule.id, it) },
                        onTogglePriority = { vm.setPriority(item.schedule.id, it) },
                        onEdit = { onEditReminder(item.schedule.id) },
                        onDelete = { confirmDelete = item.schedule },
                    )
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    confirmDelete?.let { schedule ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.reminders_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.reminders_delete_confirm_body,
                        med?.name ?: stringResource(R.string.reminder_title),
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(schedule.id)
                    confirmDelete = null
                }) {
                    Text(
                        stringResource(R.string.reminders_delete_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * One reminder, with every operation on it in reach: pause, prioritise, edit,
 * delete.
 *
 * Delete is a button on the card rather than a long-press, and it is present
 * whether or not the reminder is running — a paused reminder is the single most
 * likely thing on this screen to want deleting, and hiding the action behind an
 * undiscoverable gesture is how it went missing in the first place.
 */
@Composable
private fun ReminderManagerCard(
    item: MedicationRemindersViewModel.Item,
    routeIcon: ImageVector,
    onToggleActive: (Boolean) -> Unit,
    onTogglePriority: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val schedule = item.schedule
    val activeLabel = stringResource(R.string.meds_schedule_toggle)
    val priorityLabel = stringResource(R.string.reminders_priority_label)
    val cadence = cadenceTextFor(schedule)
    val dateFmt = remember {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    }

    EggCard(
        variant = CardVariant.Low,
        padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
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
                    cadence,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    // A paused reminder's next-due is a leftover, not a
                    // promise: stating a date next to a switch that is off
                    // reads as "it will still fire then".
                    if (schedule.active) {
                        stringResource(
                            R.string.schedule_next_due_fmt,
                            dateFmt.format(Date(schedule.nextDueAtMs)),
                        )
                    } else {
                        stringResource(R.string.reminders_paused)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!schedule.active) {
                StatusPill(
                    label = stringResource(R.string.reminders_paused_pill),
                    container = MaterialTheme.colorScheme.surfaceContainerHighest,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        schedule.label?.takeIf { it.isNotBlank() }?.let { label ->
            Text(
                stringResource(R.string.meds_quoted_fmt, label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        CardRule(modifier = Modifier.padding(top = 12.dp))

        SwitchRow(
            title = activeLabel,
            subtitle = stringResource(R.string.reminders_active_hint),
            checked = schedule.active,
            onCheckedChange = onToggleActive,
            semanticsLabel = "$activeLabel · $cadence",
        )
        SwitchRow(
            title = priorityLabel,
            subtitle = stringResource(R.string.reminders_priority_hint),
            checked = item.priority,
            // Priority only means anything for a reminder that fires. Leaving
            // it live on a paused one offers a setting with no effect.
            enabled = schedule.active,
            onCheckedChange = onTogglePriority,
            semanticsLabel = "$priorityLabel · $cadence",
        )

        CardRule(modifier = Modifier.padding(top = 4.dp))

        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onEdit) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(R.string.action_edit),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(EggDim.TouchTarget),
            ) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = stringResource(R.string.reminders_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    semanticsLabel: String,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = semanticsLabel },
        )
    }
}

private fun routeIconFor(med: Medication?): ImageVector = when {
    med != null && MedicationCatalog.isInjection(med.route) -> Icons.Filled.Vaccines
    else -> Icons.Filled.Medication
}

/** « Chaque jour à 08:00 » and its two siblings — the three kinds the core has. */
@Composable
internal fun cadenceTextFor(schedule: DoseSchedule): String = when (schedule.kind) {
    "interval" -> stringResource(
        R.string.schedule_interval_fmt,
        (schedule.intervalMinutes?.toInt() ?: 0) / 60,
    )
    "daily" -> stringResource(
        R.string.schedule_daily_fmt,
        schedule.dailyHour?.toInt() ?: 0,
        schedule.dailyMinute?.toInt() ?: 0,
    )
    "days_interval" -> stringResource(
        R.string.schedule_days_interval_fmt,
        schedule.intervalDays?.toInt() ?: 0,
        schedule.dailyHour?.toInt() ?: 0,
        schedule.dailyMinute?.toInt() ?: 0,
    )
    else -> schedule.kind
}
