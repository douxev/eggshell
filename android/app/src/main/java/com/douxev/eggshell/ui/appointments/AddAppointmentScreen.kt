package com.douxev.eggshell.ui.appointments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
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
import com.douxev.eggshell.data.AppointmentRepository
import com.douxev.eggshell.ui.common.DateTimePickerField
import com.douxev.eggshell.ui.common.clickToDismissKeyboard
import uniffi.transition.Appointment
import uniffi.transition.NewAppointment

private const val ONE_DAY_MS = 24L * 60L * 60L * 1000L
private const val ONE_HOUR_MS = 60L * 60L * 1000L

/** A sensible, always-in-the-future default reminder for an appointment at
 *  [apptMs]: a day before if that's still ahead, otherwise an hour before,
 *  never in the past. */
private fun defaultReminderFor(apptMs: Long): Long {
    val now = System.currentTimeMillis()
    val dayBefore = apptMs - ONE_DAY_MS
    if (dayBefore > now) return dayBefore
    val hourBefore = apptMs - ONE_HOUR_MS
    return if (hourBefore > now) hourBefore else now + 5L * 60L * 1000L
}

@HiltViewModel
class AddAppointmentViewModel @Inject constructor(
    private val repo: AppointmentRepository,
    state: SavedStateHandle,
) : ViewModel() {
    enum class Status { Idle, Submitting, Done, Error }

    val editingId: Long = state.get<Long>("id") ?: -1L

    private val _status = MutableStateFlow(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _loaded = MutableStateFlow<Appointment?>(null)
    val loaded: StateFlow<Appointment?> = _loaded.asStateFlow()

    init {
        if (editingId > 0L) {
            viewModelScope.launch {
                runCatching { repo.get(editingId) }.onSuccess { _loaded.value = it }
            }
        }
    }

    fun submit(entry: NewAppointment) {
        _status.value = Status.Submitting
        viewModelScope.launch {
            runCatching {
                if (editingId > 0L) repo.update(editingId, entry) else repo.add(entry)
            }
                .onSuccess { _status.value = Status.Done }
                .onFailure { _status.value = Status.Error }
        }
    }

    fun delete() {
        if (editingId <= 0L) return
        _status.value = Status.Submitting
        viewModelScope.launch {
            runCatching { repo.delete(editingId) }
                .onSuccess { _status.value = Status.Done }
                .onFailure { _status.value = Status.Error }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAppointmentScreen(
    onDone: () -> Unit,
    vm: AddAppointmentViewModel = hiltViewModel(),
) {
    val status by vm.status.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val isEditing = vm.editingId > 0L

    if (status == AddAppointmentViewModel.Status.Done) {
        onDone()
        return
    }

    var atMs by remember { mutableStateOf(System.currentTimeMillis() + ONE_DAY_MS) }
    var place by remember { mutableStateOf("") }
    var proName by remember { mutableStateOf("") }
    var proRole by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var todo by remember { mutableStateOf("") }
    var reminderOn by remember { mutableStateOf(false) }
    // Seeded below; placeholder until the seed effect runs.
    var reminderAtMs by remember { mutableStateOf(System.currentTimeMillis()) }
    // Once the user picks a reminder time we stop auto-deriving it from the
    // appointment time, so their choice isn't overwritten when they move the RDV.
    var reminderEdited by remember { mutableStateOf(false) }
    var seeded by remember { mutableStateOf(false) }

    LaunchedEffect(loaded) {
        if (seeded) return@LaunchedEffect
        if (isEditing && loaded == null) return@LaunchedEffect
        loaded?.let { a ->
            atMs = a.atMs
            place = a.place.orEmpty()
            proName = a.professionalName.orEmpty()
            proRole = a.professionalRole.orEmpty()
            notes = a.notes.orEmpty()
            todo = a.todo.orEmpty()
            reminderOn = a.reminderAtMs != null
            // Only treat a stored reminder as a fixed user choice if it's still
            // in the future; a past one is re-defaulted below so we never re-save
            // a dead reminder the UI shows as "on".
            reminderEdited = a.reminderAtMs?.let { it > System.currentTimeMillis() } == true
        }
        val storedFuture = loaded?.reminderAtMs?.takeIf { it > System.currentTimeMillis() }
        reminderAtMs = storedFuture ?: defaultReminderFor(atMs)
        seeded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isEditing) R.string.appointment_edit_title else R.string.appointment_add_title
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (isEditing) {
                        IconButton(onClick = vm::delete) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .clickToDismissKeyboard()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DateTimePickerField(
                label = stringResource(R.string.appointment_field_datetime),
                atMs = atMs,
                onChange = {
                    atMs = it
                    // Keep the reminder a sensible offset before the RDV until
                    // the user sets it themselves.
                    if (!reminderEdited) reminderAtMs = defaultReminderFor(it)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = place,
                onValueChange = { place = it },
                label = { Text(stringResource(R.string.appointment_field_place)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = proName,
                onValueChange = { proName = it },
                label = { Text(stringResource(R.string.appointment_field_pro_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = proRole,
                onValueChange = { proRole = it },
                label = { Text(stringResource(R.string.appointment_field_pro_role)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = todo,
                onValueChange = { todo = it },
                label = { Text(stringResource(R.string.appointment_field_todo)) },
                supportingText = { Text(stringResource(R.string.appointment_field_todo_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(stringResource(R.string.appointment_field_notes)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.appointment_reminder_switch),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        stringResource(R.string.appointment_reminder_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = reminderOn, onCheckedChange = { reminderOn = it })
            }
            if (reminderOn) {
                DateTimePickerField(
                    label = stringResource(R.string.appointment_reminder_at),
                    atMs = reminderAtMs,
                    onChange = {
                        reminderAtMs = it
                        reminderEdited = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = {
                    vm.submit(
                        NewAppointment(
                            atMs = atMs,
                            place = place.ifBlank { null },
                            professionalName = proName.ifBlank { null },
                            professionalRole = proRole.ifBlank { null },
                            notes = notes.ifBlank { null },
                            todo = todo.ifBlank { null },
                            reminderAtMs = if (reminderOn) reminderAtMs else null,
                        )
                    )
                },
                enabled = status != AddAppointmentViewModel.Status.Submitting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.appointment_save)) }

            if (status == AddAppointmentViewModel.Status.Error) {
                Text(stringResource(R.string.appointment_error), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
