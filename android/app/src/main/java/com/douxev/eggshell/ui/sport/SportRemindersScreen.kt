package com.douxev.eggshell.ui.sport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.douxev.eggshell.R
import com.douxev.eggshell.reminders.LabReminderManager
import com.douxev.eggshell.reminders.LabReminderPrefs
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.EmptyState
import com.douxev.eggshell.ui.components.SectionTitle
import com.douxev.eggshell.ui.reminders.AddLabReminderDialog
import com.douxev.eggshell.ui.reminders.LabReminderCard
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltViewModel
class SportRemindersViewModel @Inject constructor(
    private val labs: LabReminderManager,
) : ViewModel() {

    data class State(
        val reminders: List<LabReminderPrefs.Entry> = emptyList(),
        val priorities: Map<Long, Boolean> = emptyMap(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun refresh() {
        val mine = labs.list().filter { it.category == LabReminderPrefs.CATEGORY_SPORT }
        _state.value = State(
            reminders = mine,
            priorities = mine.associate { it.id to labs.isPriority(it.id) },
        )
    }

    fun addInterval(label: String, days: Int) {
        runCatching { labs.createInterval(label, days, LabReminderPrefs.CATEGORY_SPORT) }
        refresh()
    }

    fun addDaily(label: String, hour: Int, minute: Int) {
        runCatching { labs.createDaily(label, hour, minute, LabReminderPrefs.CATEGORY_SPORT) }
        refresh()
    }

    fun updateInterval(id: Long, label: String, days: Int) {
        runCatching { labs.updateInterval(id, label, days) }
        refresh()
    }

    fun updateDaily(id: Long, label: String, hour: Int, minute: Int) {
        runCatching { labs.updateDaily(id, label, hour, minute) }
        refresh()
    }

    fun setPriority(id: Long, priority: Boolean) {
        labs.setPriority(id, priority)
        refresh()
    }

    fun delete(id: Long) {
        labs.delete(id)
        refresh()
    }
}

/**
 * The sport module's reminders, reached from the module itself — the way a
 * treatment's reminders are reached from the treatment.
 *
 * The same entries the reminders hub in Réglages lists under « Sport »: one
 * store, two doors. They are session reminders in the generic non-medication
 * store ([LabReminderPrefs.CATEGORY_SPORT]), so creating, editing, the
 * priority flag and deleting are exactly what the hub offers, through the same
 * dialog and the same card.
 */
@Composable
fun SportRemindersScreen(
    onBack: () -> Unit,
    vm: SportRemindersViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    var dialog by remember { mutableStateOf<Target?>(null) }

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ScreenHeader(
                    title = stringResource(R.string.sport_reminders_title),
                    onBack = onBack,
                )
            }
            item {
                EggCard(variant = CardVariant.Low) {
                    Text(
                        stringResource(R.string.sport_reminders_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SectionTitle(
                    text = stringResource(R.string.reminders_section_sport),
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                    action = stringResource(R.string.set_action_add),
                    onAction = { dialog = Target.New },
                )
            }
            if (state.reminders.isEmpty()) {
                item {
                    EmptyState(
                        message = stringResource(R.string.reminders_no_sport),
                        actionLabel = stringResource(R.string.reminders_add_sport),
                        onAction = { dialog = Target.New },
                    )
                }
            }
            items(state.reminders, key = { it.id }) { entry ->
                LabReminderCard(
                    entry = entry,
                    icon = Icons.Filled.DirectionsRun,
                    priority = state.priorities[entry.id] == true,
                    onClick = { dialog = Target.Edit(entry) },
                    onTogglePriority = { vm.setPriority(entry.id, it) },
                    onDelete = { vm.delete(entry.id) },
                )
            }
        }
    }

    dialog?.let { target ->
        val editing = (target as? Target.Edit)?.entry
        AddLabReminderDialog(
            titleRes = if (editing == null) R.string.reminders_sport_dialog_title
            else R.string.reminders_sport_edit_dialog_title,
            defaultLabel = editing?.label ?: stringResource(R.string.reminders_sport_default_label),
            initialEntry = editing,
            onDismiss = { dialog = null },
            onSaveInterval = { label, days ->
                if (editing == null) vm.addInterval(label, days)
                else vm.updateInterval(editing.id, label, days)
                dialog = null
            },
            onSaveDaily = { label, h, m ->
                if (editing == null) vm.addDaily(label, h, m)
                else vm.updateDaily(editing.id, label, h, m)
                dialog = null
            },
        )
    }
}

private sealed interface Target {
    data object New : Target
    data class Edit(val entry: LabReminderPrefs.Entry) : Target
}
